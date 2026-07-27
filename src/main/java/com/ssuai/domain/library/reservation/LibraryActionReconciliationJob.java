package com.ssuai.domain.library.reservation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ssuai.domain.action.ActionAudit;
import com.ssuai.domain.action.ActionService;
import com.ssuai.domain.auth.mcp.McpAuthService;
import com.ssuai.domain.auth.mcp.McpProviderCredentialRevokedException;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.events.LibrarySeatEventPublisher;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentRepository;
import com.ssuai.global.exception.LibraryAuthRequiredException;
import com.ssuai.global.exception.LibrarySeatNotAvailableException;

/**
 * Reconciles MCP library actions left EXECUTING when a process stops between an upstream write
 * and the durable terminal audit update.
 *
 * <p>Every pass first revalidates the exact provider generation under the same session-row fence
 * used by normal writes. It then reads the authoritative current charge before deciding whether
 * an effect happened. Retried cancel and compensating reserve operations are only attempted from
 * a state that makes them safe; a second reconciler serializes on the provider fence and observes
 * the already-terminal action before doing any work.</p>
 */
@Component
public class LibraryActionReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(LibraryActionReconciliationJob.class);

    private final ActionService actionService;
    private final LibraryReservationIntentRepository intentRepository;
    private final LibrarySessionStore sessionStore;
    private final LibraryReservationConnector reservationConnector;
    private final LibrarySeatEventPublisher seatEventPublisher;
    private final McpAuthService mcpAuthService;
    private final LibraryReservationProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public LibraryActionReconciliationJob(
            ActionService actionService,
            LibraryReservationIntentRepository intentRepository,
            LibrarySessionStore sessionStore,
            LibraryReservationConnector reservationConnector,
            LibrarySeatEventPublisher seatEventPublisher,
            McpAuthService mcpAuthService,
            LibraryReservationProperties properties,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.actionService = actionService;
        this.intentRepository = intentRepository;
        this.sessionStore = sessionStore;
        this.reservationConnector = reservationConnector;
        this.seatEventPublisher = seatEventPublisher;
        this.mcpAuthService = mcpAuthService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${ssuai.library.reservation.action-reconciliation-interval-ms:60000}",
            initialDelayString = "${ssuai.library.reservation.action-reconciliation-interval-ms:60000}")
    public void reconcileStaleActions() {
        Instant cutoff = clock.instant().minus(properties.getActionReconciliationDelay());
        List<ActionAudit> stale = actionService.findStaleExecutingMcpActions(
                cutoff, properties.getActionReconciliationBatchSize());
        stale.forEach(this::reconcileOneSafely);
    }

    void reconcileOneSafely(ActionAudit action) {
        if (action.getId() == null || action.getOwnerMcpSessionId() == null) {
            return;
        }
        try {
            mcpAuthService.executeWhileProviderCredentialCurrent(
                    action.getOwnerMcpSessionId(),
                    McpProviderType.LIBRARY,
                    action.getStudentId(),
                    () -> {
                        if (!actionService.isExecuting(action.getId())) {
                            return null;
                        }
                        reconcileCurrentGeneration(action);
                        return null;
                    });
        } catch (McpProviderCredentialRevokedException | LibraryAuthRequiredException exception) {
            complete(action, ActionService.OUTCOME_FAILURE_AUTH,
                    "도서관 세션이 만료되거나 철회되어 중단된 작업을 복구할 수 없습니다.", "auth");
        } catch (RuntimeException exception) {
            // An unknown read/write outcome must remain EXECUTING so the next pass can inspect
            // authoritative upstream state again. Never guess success or repeat blindly.
            meterRegistry.counter("library.action.reconciliation", "result", "retry").increment();
            log.warn("library action reconciliation deferred: actionId={} type={}",
                    action.getId(), action.getActionType(), exception);
        }
    }

    private void reconcileCurrentGeneration(ActionAudit action) {
        if (LibraryActionTypes.RESERVATION.equals(action.getActionType())) {
            reconcileReservation(action);
            return;
        }

        String token = sessionStore.token(action.getStudentId())
                .orElseThrow(LibraryAuthRequiredException::new);
        Optional<LibraryReservationResult> current = reservationConnector.getCurrentCharge(token);
        if (LibraryActionTypes.CANCEL.equals(action.getActionType())) {
            reconcileCancel(action, token, current);
            return;
        }
        if (LibraryActionTypes.SWAP.equals(action.getActionType())) {
            reconcileSwap(action, token, current);
            return;
        }
        complete(action, ActionService.OUTCOME_FAILURE_UPSTREAM,
                "지원하지 않는 실행 중 액션을 복구 작업이 종료했습니다.", "unsupported");
    }

    private void reconcileReservation(ActionAudit action) {
        if (intentRepository.existsByActionAuditId(action.getId())) {
            meterRegistry.counter("library.action.reconciliation", "result", "intent_owned").increment();
            return;
        }
        complete(action, ActionService.OUTCOME_FAILURE_UPSTREAM,
                "프로세스 중단 전에 예약 intent가 커밋되지 않았습니다. 다시 prepare/confirm 해주세요.",
                "intent_missing");
    }

    private void reconcileCancel(
            ActionAudit action,
            String token,
            Optional<LibraryReservationResult> current) {
        LibraryCancelRequest request = actionService.payload(action, LibraryCancelRequest.class);
        if (current.isEmpty() || current.get().chargeId() != request.chargeId()) {
            if (complete(action, ActionService.OUTCOME_SUCCESS,
                    "중단 후 확인 결과 기존 예약이 이미 반납됐습니다.", "cancel_observed")) {
                seatEventPublisher.cancel(request.roomId(), request.seatId());
            }
            return;
        }

        // The exact old charge is still authoritative, so the interrupted discharge did not
        // take effect. Retrying it is safe; another reconciler cannot enter this provider fence.
        try {
            reservationConnector.discharge(token, request.chargeId());
            if (complete(action, ActionService.OUTCOME_SUCCESS,
                    "중단된 좌석 반납을 안전하게 재실행했습니다.", "cancel_retried")) {
                seatEventPublisher.cancel(request.roomId(), request.seatId());
            }
        } catch (LibrarySeatNotAvailableException exception) {
            complete(action, ActionService.OUTCOME_FAILURE_UPSTREAM,
                    "현재 좌석 상태에서는 반납을 재실행할 수 없습니다.", "cancel_not_available");
        }
    }

    private void reconcileSwap(
            ActionAudit action,
            String token,
            Optional<LibraryReservationResult> current) {
        LibrarySwapRequest request = actionService.payload(action, LibrarySwapRequest.class);
        if (current.isPresent() && sameSeat(current.get(), request.newSeatId())) {
            if (complete(action, ActionService.OUTCOME_SUCCESS,
                    "중단 후 확인 결과 새 좌석 예약이 완료돼 있었습니다.", "swap_observed_new")) {
                seatEventPublisher.swapDischarge(request.oldRoomId(), request.oldSeatId());
                seatEventPublisher.swapReserve(current.get().roomId(), current.get().seatId());
            }
            return;
        }
        if (current.isPresent() && sameOldReservation(current.get(), request)) {
            if (complete(action, ActionService.OUTCOME_FAILURE_RACE,
                    "중단 후 확인 결과 기존 좌석이 유지돼 자리 변경을 종료했습니다.", "swap_old_retained")) {
                seatEventPublisher.swapReserve(
                        current.get().roomId() == null ? request.oldRoomId() : current.get().roomId(),
                        current.get().seatId() == null ? request.oldSeatId() : current.get().seatId());
            }
            return;
        }
        if (current.isPresent()) {
            complete(action, ActionService.OUTCOME_FAILURE_UPSTREAM,
                    "중단 후 다른 예약이 확인돼 자동 변경을 중지했습니다.", "swap_other_charge");
            return;
        }
        if (request.oldSeatId() == null) {
            complete(action, ActionService.OUTCOME_PARTIAL_FAILURE,
                    "기존 좌석 반납 후 예약이 없고 원래 좌석 식별자가 없어 자동 복구하지 못했습니다.",
                    "swap_missing_old_seat");
            return;
        }

        try {
            LibraryReservationResult restored = reservationConnector.reserve(
                    token, new LibraryReservationRequest(request.oldSeatId()));
            if (complete(action, ActionService.OUTCOME_FAILURE_RACE,
                    "프로세스 중단 후 기존 좌석을 재예약해 복구했습니다.", "swap_compensated")) {
                seatEventPublisher.swapReserve(
                        restored.roomId() == null ? request.oldRoomId() : restored.roomId(),
                        restored.seatId() == null ? request.oldSeatId() : restored.seatId());
            }
        } catch (LibrarySeatNotAvailableException exception) {
            complete(action, ActionService.OUTCOME_PARTIAL_FAILURE,
                    "기존 좌석 반납 후 새 좌석과 기존 좌석 모두 확보하지 못했습니다.",
                    "swap_compensation_failed");
        }
    }

    private boolean complete(ActionAudit action, String outcome, String message, String metricResult) {
        boolean completed = actionService.completeMcpActionDurably(action.getId(), outcome, message);
        if (completed) {
            meterRegistry.counter("library.action.reconciliation", "result", metricResult).increment();
        }
        return completed;
    }

    private static boolean sameSeat(LibraryReservationResult current, long seatId) {
        return current.seatId() != null && current.seatId() == seatId;
    }

    private static boolean sameOldReservation(
            LibraryReservationResult current, LibrarySwapRequest request) {
        return current.chargeId() == request.oldChargeId()
                || (request.oldSeatId() != null && request.oldSeatId().equals(current.seatId()));
    }
}
