package com.ssuai.domain.saint.connector;

import java.util.List;

import com.ssuai.domain.saint.dto.ChapelInfo;
import com.ssuai.domain.saint.dto.GraduationStatus;
import com.ssuai.domain.saint.dto.GradesResponse;
import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.domain.saint.dto.ScholarshipEntry;

public interface RusaintClient {

    /**
     * Eagerly loads and verifies the native rusaint FFI so the first real login
     * doesn't pay the one-time native-load cost. Default is a no-op (test doubles
     * and mock connectors have no native layer to warm). See
     * {@code RusaintUniFfiClient#warmUp()} for why this matters to login latency.
     */
    default void warmUp() {
        // no-op by default
    }

    RusaintAuthenticatedSession authenticateWithToken(String studentId, String ssoToken);

    ScheduleResponse fetchSchedule(String studentId, String sessionJson);

    default ScheduleResponse fetchSchedule(String studentId, String sessionJson, Integer year, Integer term) {
        if (year == null && term == null) {
            return fetchSchedule(studentId, sessionJson);
        }
        throw new UnsupportedOperationException("term-specific schedule fetch is not supported");
    }

    default RusaintSessionResult<ScheduleResponse> fetchScheduleWithSession(
            String studentId, String sessionJson, Integer year, Integer term) {
        return new RusaintSessionResult<>(
                fetchSchedule(studentId, sessionJson, year, term), sessionJson);
    }

    GradesResponse fetchGrades(String studentId, String sessionJson);

    default RusaintSessionResult<GradesResponse> fetchGradesWithSession(
            String studentId, String sessionJson) {
        return new RusaintSessionResult<>(fetchGrades(studentId, sessionJson), sessionJson);
    }

    ChapelInfo fetchChapelInfo(String studentId, String sessionJson);

    ChapelInfo fetchChapelInfo(String studentId, String sessionJson, Integer year, String semester);

    default RusaintSessionResult<ChapelInfo> fetchChapelInfoWithSession(
            String studentId, String sessionJson, Integer year, String semester) {
        return new RusaintSessionResult<>(
                fetchChapelInfo(studentId, sessionJson, year, semester), sessionJson);
    }

    int countChapelPassedSemesters(String studentId, String sessionJson, int entryYear);

    default RusaintSessionResult<Integer> countChapelPassedSemestersWithSession(
            String studentId, String sessionJson, int entryYear) {
        return new RusaintSessionResult<>(
                countChapelPassedSemesters(studentId, sessionJson, entryYear), sessionJson);
    }

    GraduationStatus fetchGraduationRequirements(String studentId, String sessionJson);

    default RusaintSessionResult<GraduationStatus> fetchGraduationRequirementsWithSession(
            String studentId, String sessionJson) {
        return new RusaintSessionResult<>(
                fetchGraduationRequirements(studentId, sessionJson), sessionJson);
    }

    List<ScholarshipEntry> fetchScholarships(String studentId, String sessionJson);

    default RusaintSessionResult<List<ScholarshipEntry>> fetchScholarshipsWithSession(
            String studentId, String sessionJson) {
        return new RusaintSessionResult<>(fetchScholarships(studentId, sessionJson), sessionJson);
    }
}
