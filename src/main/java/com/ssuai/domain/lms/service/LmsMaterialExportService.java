package com.ssuai.domain.lms.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssuai.domain.action.ActionAudit;
import com.ssuai.domain.action.ActionService;
import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.lms.connector.LmsMaterialEnrichmentBudget;
import com.ssuai.domain.lms.connector.LmsMaterialsConnector;
import com.ssuai.domain.lms.dto.LmsCourse;
import com.ssuai.domain.lms.dto.LmsCourseMaterials;
import com.ssuai.domain.lms.dto.LmsExportConfirmResponse;
import com.ssuai.domain.lms.dto.LmsExportExclusion;
import com.ssuai.domain.lms.dto.LmsExportPrepareResponse;
import com.ssuai.domain.lms.dto.LmsExportSelectionItem;
import com.ssuai.domain.lms.dto.LmsMaterial;
import com.ssuai.domain.lms.dto.LmsMaterialGroup;
import com.ssuai.domain.lms.dto.LmsTermItem;
import com.ssuai.domain.lms.dto.LmsTermSelection;
import com.ssuai.domain.lms.dto.SelectionPayload;
import com.ssuai.domain.lms.export.LmsExportJob;
import com.ssuai.domain.lms.export.LmsExportJobRepository;
import com.ssuai.domain.lms.export.LmsExportProperties;
import com.ssuai.domain.lms.util.MaterialFileFilter;
import com.ssuai.global.exception.LmsSessionExpiredException;
import com.ssuai.global.exception.UnauthorizedException;

@Service
public class LmsMaterialExportService {

    private static final Logger log = LoggerFactory.getLogger(LmsMaterialExportService.class);

    private final LmsMaterialsConnector connector;
    private final LmsSessionStore sessionStore;
    private final LmsAssignmentsService assignmentsService;
    private final ActionService actionService;
    private final LmsExportJobRepository jobRepository;
    private final LmsExportProperties properties;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public LmsMaterialExportService(LmsMaterialsConnector connector, LmsSessionStore sessionStore,
                                    LmsAssignmentsService assignmentsService, ActionService actionService,
                                    LmsExportJobRepository jobRepository, LmsExportProperties properties,
                                    ObjectMapper objectMapper) {
        this.connector = connector;
        this.sessionStore = sessionStore;
        this.assignmentsService = assignmentsService;
        this.actionService = actionService;
        this.jobRepository = jobRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public LmsExportPrepareResponse prepare(String studentId, Long termId, List<String> contentIds) {
        return prepareForMcp(studentId, studentId, termId, contentIds);
    }

    public LmsExportPrepareResponse prepareForMcp(
            String ownerMcpSessionId,
            String credentialKey,
            Long termId,
            List<String> contentIds) {
        if (ownerMcpSessionId == null || ownerMcpSessionId.isBlank()
                || credentialKey == null || credentialKey.isBlank()) {
            throw new UnauthorizedException();
        }
        return sessionStore.withSession(credentialKey, providerSession -> prepareWithSession(
                ownerMcpSessionId,
                credentialKey,
                providerSession.studentId(),
                providerSession.cookies(),
                termId,
                contentIds));
    }

    private LmsExportPrepareResponse prepareWithSession(
            String ownerMcpSessionId,
            String credentialKey,
            String upstreamStudentId,
            LmsCookies cookies,
            Long termId,
            List<String> contentIds) {

        List<LmsTermItem> terms = assignmentsService.fetchTerms(credentialKey);
        LmsTermSelection selection = LmsTermResolver.select(terms, termId);
        long resolvedTermId = selection.selectedTermId();

        List<LmsCourse> courses = connector.fetchCourses(upstreamStudentId, cookies, resolvedTermId);
        Map<String, LmsMaterial> allMaterialsMap = new HashMap<>();
        Map<Long, LmsCourse> courseMap = new HashMap<>();

        LmsMaterialEnrichmentBudget enrichmentBudget = new LmsMaterialEnrichmentBudget();
        for (LmsCourse course : courses) {
            courseMap.put(course.id(), course);
            List<LmsMaterial> materials = connector.fetchMaterials(
                    upstreamStudentId, cookies, course, enrichmentBudget);
            for (LmsMaterial material : materials) {
                if (material.contentId() != null) {
                    allMaterialsMap.put(material.contentId(), material);
                }
            }
        }

        List<LmsMaterial> whitelistedSelections = new ArrayList<>();
        List<LmsExportExclusion> excluded = new ArrayList<>();

        if (contentIds != null) {
            for (String contentId : contentIds) {
                LmsMaterial material = allMaterialsMap.get(contentId);
                if (material == null) {
                    excluded.add(new LmsExportExclusion(contentId, "", "파일을 찾을 수 없습니다."));
                } else if (!MaterialFileFilter.isIncluded(material)) {
                    excluded.add(new LmsExportExclusion(contentId, material.fileName(), "지원하지 않는 파일 형식 또는 비디오입니다."));
                } else {
                    whitelistedSelections.add(material);
                }
            }
        }

        return finalizeExport(
                ownerMcpSessionId, credentialKey, selection,
                courseMap, whitelistedSelections, excluded);
    }

    public LmsExportPrepareResponse exportAll(String studentId, Long termId) {
        return exportAllForMcp(studentId, studentId, termId);
    }

    public LmsExportPrepareResponse exportAllForMcp(
            String ownerMcpSessionId, String credentialKey, Long termId) {
        if (ownerMcpSessionId == null || ownerMcpSessionId.isBlank()
                || credentialKey == null || credentialKey.isBlank()) {
            throw new UnauthorizedException();
        }
        return sessionStore.withSession(credentialKey, providerSession -> exportAllWithSession(
                ownerMcpSessionId,
                credentialKey,
                providerSession.studentId(),
                providerSession.cookies(),
                termId));
    }

    private LmsExportPrepareResponse exportAllWithSession(
            String ownerMcpSessionId,
            String credentialKey,
            String upstreamStudentId,
            LmsCookies cookies,
            Long termId) {

        // Resolve term — fetch terms to get term name for the response message
        List<LmsTermItem> terms = assignmentsService.fetchTerms(credentialKey);
        LmsTermSelection selection = LmsTermResolver.select(terms, termId);
        long resolvedTermId = selection.selectedTermId();
        String termLabel = selection.selectedTermName();

        // Gather all courses and all eligible materials
        List<LmsCourse> courses = connector.fetchCourses(upstreamStudentId, cookies, resolvedTermId);
        Map<Long, LmsCourse> courseMap = new HashMap<>();
        List<LmsMaterial> whitelistedSelections = new ArrayList<>();

        LmsMaterialEnrichmentBudget enrichmentBudget = new LmsMaterialEnrichmentBudget();
        for (LmsCourse course : courses) {
            courseMap.put(course.id(), course);
            List<LmsMaterial> materials = connector.fetchMaterials(
                    upstreamStudentId, cookies, course, enrichmentBudget);
            for (LmsMaterial material : materials) {
                if (material.contentId() != null && MaterialFileFilter.isIncluded(material)) {
                    whitelistedSelections.add(material);
                }
            }
        }

        // Delegate limit/grouping/pending-action creation to shared helper
        LmsExportPrepareResponse response = finalizeExport(
                ownerMcpSessionId, credentialKey,
                selection,
                courseMap, whitelistedSelections, new ArrayList<>());

        // Prepend term label to the response message (reuse message field — no DTO schema change)
        String newMessage = "[" + termLabel + "] " + response.message();
        return new LmsExportPrepareResponse(
                response.courseCount(),
                response.fileCount(),
                response.totalBytes(),
                response.selected(),
                response.excluded(),
                newMessage,
                response.actionId(),
                response.previewVersion(),
                response.selectedTermId(),
                response.selectedTermName(),
                response.selectedTermType(),
                response.selectionReason(),
                response.availableTermTypes());
    }

    private LmsExportPrepareResponse finalizeExport(
            String ownerMcpSessionId,
            String credentialKey,
            LmsTermSelection selection,
            Map<Long, LmsCourse> courseMap,
            List<LmsMaterial> whitelistedSelections,
            List<LmsExportExclusion> seededExclusions) {
        List<LmsExportExclusion> excluded = new ArrayList<>(seededExclusions);

        // Sort whitelisted selections by size descending to prioritize largest files for limit check
        List<LmsMaterial> sortedSelections = whitelistedSelections.stream()
                .sorted(Comparator.comparing(LmsMaterial::sizeBytes, Comparator.nullsLast(Long::compareTo)).reversed())
                .toList();

        List<LmsMaterial> acceptedSelections = new ArrayList<>();
        int maxFiles = properties.getMaxFilesPerExport();
        long maxBytes = properties.getMaxBytesPerExport();

        int accumulatedCount = 0;
        long accumulatedBytes = 0;

        for (LmsMaterial material : sortedSelections) {
            long size = material.sizeBytes() != null ? material.sizeBytes() : 0;
            if (accumulatedCount + 1 <= maxFiles && accumulatedBytes + size <= maxBytes) {
                acceptedSelections.add(material);
                accumulatedCount++;
                accumulatedBytes += size;
            } else {
                excluded.add(new LmsExportExclusion(material.contentId(), material.fileName(), "한도 초과"));
            }
        }

        // An all-invalid / all-excluded selection must NOT create a pending action; otherwise
        // confirm() would mint a 0-file ZIP job and a live capability download URL (external
        // review — 0-file export). Returning here leaves nothing for confirm() to claim, so it
        // reports "대기 중인 내보내기 요청이 없습니다" instead of a bogus download link.
        if (acceptedSelections.isEmpty()) {
            String message = excluded.isEmpty()
                    ? "내보낼 수 있는 파일이 없습니다. 다운로드할 과목/파일을 선택한 뒤 다시 시도해주세요."
                    : String.format(
                            "내보낼 수 있는 파일이 없습니다. 선택한 항목 %d개가 모두 제외되었습니다(미지원 형식·비디오 또는 한도 초과). "
                                    + "다른 과목/파일을 선택해주세요.",
                            excluded.size());
            return new LmsExportPrepareResponse(
                    0, 0, 0L, List.of(), List.copyOf(excluded), message,
                    null, null,
                    selection.selectedTermId(), selection.selectedTermName(),
                    selection.selectedTermType(), selection.selectionReason(),
                    selection.availableTermTypes());
        }

        // Group accepted selections by course for response structure
        Map<Long, List<LmsMaterial>> groupedByCourse = acceptedSelections.stream()
                .collect(Collectors.groupingBy(LmsMaterial::courseId));

        List<LmsCourseMaterials> selectedCourseMaterials = new ArrayList<>();
        for (Map.Entry<Long, List<LmsMaterial>> entry : groupedByCourse.entrySet()) {
            LmsCourse course = courseMap.get(entry.getKey());
            if (course == null) continue;

            List<LmsMaterial> courseSelected = entry.getValue();
            Map<String, List<LmsMaterial>> groupedByExt = courseSelected.stream()
                    .collect(Collectors.groupingBy(m -> {
                        String ext = m.extension();
                        return ext == null ? "" : ext.toLowerCase().trim();
                    }));

            List<LmsMaterialGroup> groups = new ArrayList<>();
            long courseBytes = 0;

            for (Map.Entry<String, List<LmsMaterial>> extEntry : groupedByExt.entrySet()) {
                String ext = extEntry.getKey();
                List<LmsMaterial> extMaterials = extEntry.getValue().stream()
                        .sorted(Comparator.comparing(LmsMaterial::weekTitle, Comparator.nullsFirst(String::compareTo))
                                .thenComparing(LmsMaterial::title, Comparator.nullsFirst(String::compareTo)))
                        .toList();

                groups.add(new LmsMaterialGroup(ext, extMaterials.size(), extMaterials));
                for (LmsMaterial m : extMaterials) {
                    if (m.sizeBytes() != null) {
                        courseBytes += m.sizeBytes();
                    }
                }
            }

            groups.sort(Comparator.comparing(LmsMaterialGroup::extension));
            selectedCourseMaterials.add(new LmsCourseMaterials(course, groups, courseSelected.size(), courseBytes));
        }

        // Sort course materials by course ID
        selectedCourseMaterials.sort(Comparator.comparing(c -> c.course().id()));

        // Create pending action payload
        List<LmsExportSelectionItem> payloadItems = acceptedSelections.stream()
                .map(m -> new LmsExportSelectionItem(m.contentId(), m.courseId(), m.courseName(), m.fileName()))
                .toList();

        SelectionPayload payload = new SelectionPayload(payloadItems, accumulatedBytes);
        ActionAudit previewAction = actionService.createPendingMcpAction(
                ownerMcpSessionId,
                credentialKey,
                "LMS_MATERIAL_EXPORT",
                "LMS_MATERIAL_EXPORT",
                payload);

        String message = String.format("내보내기 준비 완료. %d개 파일 (%,d bytes)이 내보내기 목록에 추가되었습니다.",
                accumulatedCount, accumulatedBytes);
        if (!excluded.isEmpty()) {
            message += String.format(" (한도 초과 또는 미지원 파일 %d개 제외)", excluded.size());
        }

        return new LmsExportPrepareResponse(
                selectedCourseMaterials.size(), accumulatedCount, accumulatedBytes,
                selectedCourseMaterials, excluded, message,
                previewAction.getId(), "action-" + previewAction.getId(),
                selection.selectedTermId(), selection.selectedTermName(),
                selection.selectedTermType(), selection.selectionReason(),
                selection.availableTermTypes());
    }

    @Transactional
    public LmsExportConfirmResponse confirm(String studentId) {
        return confirmForMcp(studentId, studentId, null);
    }

    @Transactional
    public LmsExportConfirmResponse confirmForMcp(
            String ownerMcpSessionId, String credentialKey, Long requestedActionId) {
        if (ownerMcpSessionId == null || ownerMcpSessionId.isBlank()
                || credentialKey == null || credentialKey.isBlank()) {
            throw new UnauthorizedException();
        }

        if (requestedActionId != null) {
            Optional<LmsExportJob> existing = jobRepository
                    .findByOwnerMcpSessionIdAndSourceActionId(
                            ownerMcpSessionId, requestedActionId);
            if (existing.isPresent()) {
                return idempotentConfirmation(existing.get());
            }
        }

        List<ActionAudit> pendingExports = actionService
                .findActivePendingMcpActions(ownerMcpSessionId, credentialKey)
                .stream()
                .filter(action -> "LMS_MATERIAL_EXPORT".equals(action.getActionType()))
                .filter(action -> requestedActionId == null
                        || requestedActionId.equals(action.getId()))
                .toList();
        if (pendingExports.size() != 1) {
            throw new ActionService.NoPendingActionException();
        }
        Long actionId = pendingExports.get(0).getId();
        ActionAudit claimed = actionService.claimPendingMcpActionById(
                ownerMcpSessionId, credentialKey, actionId);
        SelectionPayload payload = actionService.payload(claimed, SelectionPayload.class);

        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        String tokenHash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            tokenHash = HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize selection payload", e);
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getDownloadTtl());

        LmsExportJob job = LmsExportJob.createQueuedForMcp(
                ownerMcpSessionId,
                claimed.getId(),
                credentialKey,
                tokenHash,
                payloadJson,
                now,
                expiresAt);
        LmsExportJob savedJob = jobRepository.save(job);

        actionService.completeAction(claimed, ActionService.OUTCOME_SUCCESS, "export job " + savedJob.getId() + " queued");

        String downloadUrl = properties.getPublicBaseUrl() + "/api/lms/exports/" + savedJob.getId() + "/download?token=" + rawToken;

        return new LmsExportConfirmResponse(
                savedJob.getId(),
                payload.selections().size(),
                payload.totalBytes(), // estimate captured at prepare time (sum of known material sizes)
                expiresAt.toString(),
                downloadUrl,
                ""
        );
    }

    private LmsExportConfirmResponse idempotentConfirmation(LmsExportJob job) {
        SelectionPayload payload;
        try {
            payload = objectMapper.readValue(job.getPayload(), SelectionPayload.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored export payload cannot be parsed", exception);
        }
        return new LmsExportConfirmResponse(
                job.getId(),
                payload.selections().size(),
                payload.totalBytes(),
                job.getExpiresAt().toString(),
                null,
                "ALREADY_CONFIRMED: 기존 작업을 반환합니다. capability URL은 최초 확인 응답에서만 제공됩니다.");
    }
}
