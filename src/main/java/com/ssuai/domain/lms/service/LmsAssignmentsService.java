package com.ssuai.domain.lms.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.lms.connector.LmsAssignmentsConnector;
import com.ssuai.domain.lms.dto.AssignmentsResponse;
import com.ssuai.domain.lms.dto.LmsTermItem;
import com.ssuai.domain.lms.dto.LmsTermSelection;
import com.ssuai.global.exception.UnauthorizedException;

/**
 * Façade for the LMS assignments fetch.
 *
 * <p>Mirrors {@link com.ssuai.domain.saint.service.SaintGradesService}:
 * look up the caller's canvas session cookies from {@link LmsSessionStore},
 * hand them to the active {@link LmsAssignmentsConnector}, and let
 * {@link LmsSessionExpiredException} propagate to the global handler
 * (→ HTTP 401 {@code LMS_SESSION_EXPIRED}).
 */
@Service
public class LmsAssignmentsService {

    private final LmsAssignmentsConnector connector;
    private final LmsSessionStore sessionStore;

    public LmsAssignmentsService(LmsAssignmentsConnector connector, LmsSessionStore sessionStore) {
        this.connector = connector;
        this.sessionStore = sessionStore;
    }

    public List<LmsTermItem> fetchTerms(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            throw new UnauthorizedException();
        }
        return sessionStore.withSession(studentId,
                session -> connector.fetchTerms(session.studentId(), session.cookies()));
    }

    public AssignmentsResponse fetchAssignments(String studentId, Long termId) {
        if (studentId == null || studentId.isBlank()) {
            throw new UnauthorizedException();
        }
        List<LmsTermItem> terms = fetchTerms(studentId);
        LmsTermSelection selection = LmsTermResolver.select(terms, termId);
        return sessionStore.withSession(studentId,
                session -> connector.fetchAssignments(
                                session.studentId(), session.cookies(), selection.selectedTermId())
                        .withSelection(selection));
    }
}
