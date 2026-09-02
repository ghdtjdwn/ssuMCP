package com.ssuai.domain.library.reservation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ssuai.domain.action.ActionAudit;
import com.ssuai.domain.action.ActionService;
import com.ssuai.domain.auth.mcp.McpAuthService;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.events.LibrarySeatEventPublisher;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentRepository;
import com.ssuai.global.exception.ConnectorTimeoutException;

class LibraryActionReconciliationJobTests {

    private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");
    private static final String OWNER = "mcp-owner";
    private static final String CREDENTIAL = "credential-key";
    private static final String TOKEN = "token";

    private ActionService actionService;
    private LibraryReservationIntentRepository intentRepository;
    private LibrarySessionStore sessionStore;
    private LibraryReservationConnector connector;
    private LibrarySeatEventPublisher eventPublisher;
    private LibraryActionReconciliationJob job;

    @BeforeEach
    void setUp() {
        actionService = mock(ActionService.class);
        intentRepository = mock(LibraryReservationIntentRepository.class);
        sessionStore = mock(LibrarySessionStore.class);
        connector = mock(LibraryReservationConnector.class);
        eventPublisher = mock(LibrarySeatEventPublisher.class);
        McpAuthService mcpAuthService = mock(McpAuthService.class);
        doAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(3).get())
                .when(mcpAuthService)
                .executeWhileProviderCredentialCurrent(any(), any(), any(), any());
        job = new LibraryActionReconciliationJob(
                actionService,
                intentRepository,
                sessionStore,
                connector,
                eventPublisher,
                mcpAuthService,
                new LibraryReservationProperties(),
                new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void cancellationObservedMissingUpstreamCompletesSuccessWithoutSecondWrite() {
        ActionAudit action = executing(10L, LibraryActionTypes.CANCEL);
        LibraryCancelRequest request = new LibraryCancelRequest(77L, 3, 99L);
        when(actionService.isExecuting(10L)).thenReturn(true);
        when(actionService.payload(action, LibraryCancelRequest.class)).thenReturn(request);
        when(sessionStore.token(CREDENTIAL)).thenReturn(Optional.of(TOKEN));
        when(connector.getCurrentCharge(TOKEN)).thenReturn(Optional.empty());
        when(actionService.completeMcpActionDurably(
                eq(10L), eq(ActionService.OUTCOME_SUCCESS), any())).thenReturn(true);

        job.reconcileOneSafely(action);

        verify(connector, never()).discharge(any(), anyLong());
        verify(actionService).completeMcpActionDurably(
                eq(10L), eq(ActionService.OUTCOME_SUCCESS), any());
        verify(eventPublisher).cancel(3, 99L);
    }

    @Test
    void swapWithNoCurrentChargeCompensatesOriginalSeat() {
        ActionAudit action = executing(11L, LibraryActionTypes.SWAP);
        LibrarySwapRequest request = new LibrarySwapRequest(77L, 200L, 3, 100L);
        LibraryReservationResult restored = new LibraryReservationResult(
                88L, "room", "100", "10:00", "14:00", 3, 100L);
        when(actionService.isExecuting(11L)).thenReturn(true);
        when(actionService.payload(action, LibrarySwapRequest.class)).thenReturn(request);
        when(sessionStore.token(CREDENTIAL)).thenReturn(Optional.of(TOKEN));
        when(connector.getCurrentCharge(TOKEN)).thenReturn(Optional.empty());
        when(connector.reserve(TOKEN, new LibraryReservationRequest(100L))).thenReturn(restored);
        when(actionService.completeMcpActionDurably(
                eq(11L), eq(ActionService.OUTCOME_FAILURE_RACE), any())).thenReturn(true);

        job.reconcileOneSafely(action);

        verify(connector).reserve(TOKEN, new LibraryReservationRequest(100L));
        verify(actionService).completeMcpActionDurably(
                eq(11L), eq(ActionService.OUTCOME_FAILURE_RACE), any());
        verify(eventPublisher).swapReserve(3, 100L);
    }

    @Test
    void reservationWithoutCommittedIntentFailsForSafeUserRetry() {
        ActionAudit action = executing(12L, LibraryActionTypes.RESERVATION);
        when(actionService.isExecuting(12L)).thenReturn(true);
        when(intentRepository.existsByActionAuditId(12L)).thenReturn(false);
        when(actionService.completeMcpActionDurably(
                eq(12L), eq(ActionService.OUTCOME_FAILURE_UPSTREAM), any())).thenReturn(true);

        job.reconcileOneSafely(action);

        verify(actionService).completeMcpActionDurably(
                eq(12L), eq(ActionService.OUTCOME_FAILURE_UPSTREAM), any());
        verify(connector, never()).getCurrentCharge(any());
    }

    @Test
    void unknownCurrentChargeReadKeepsCancelExecutingWithoutWrite() {
        ActionAudit action = executing(13L, LibraryActionTypes.CANCEL);
        when(actionService.isExecuting(13L)).thenReturn(true);
        when(sessionStore.token(CREDENTIAL)).thenReturn(Optional.of(TOKEN));
        when(connector.getCurrentCharge(TOKEN)).thenThrow(new ConnectorTimeoutException());

        job.reconcileOneSafely(action);

        verify(actionService, never()).completeMcpActionDurably(anyLong(), any(), any());
        verify(connector, never()).discharge(any(), anyLong());
        verify(connector, never()).reserve(any(), any());
    }

    @Test
    void unknownCurrentChargeReadKeepsSwapExecutingWithoutCompensation() {
        ActionAudit action = executing(14L, LibraryActionTypes.SWAP);
        when(actionService.isExecuting(14L)).thenReturn(true);
        when(sessionStore.token(CREDENTIAL)).thenReturn(Optional.of(TOKEN));
        when(connector.getCurrentCharge(TOKEN)).thenThrow(new ConnectorTimeoutException());

        job.reconcileOneSafely(action);

        verify(actionService, never()).completeMcpActionDurably(anyLong(), any(), any());
        verify(connector, never()).discharge(any(), anyLong());
        verify(connector, never()).reserve(any(), any());
    }

    private static ActionAudit executing(long id, String type) {
        ActionAudit action = ActionAudit.pendingForMcp(
                OWNER, CREDENTIAL, type, type, "{}", NOW.minusSeconds(120));
        ReflectionTestUtils.setField(action, "id", id);
        action.markExecuting(NOW.minusSeconds(90));
        return action;
    }
}
