package com.ssuai.domain.saint.dto;

import java.util.List;

/**
 * Timetable response for {@code GET /api/saint/schedule} and the
 * {@code get_my_schedule} MCP tool. Without a requested term this describes
 * the u-SAINT selected semester. With {@code year}/{@code term}, it describes
 * that specific semester. Term values: 1=spring, 2=summer, 3=fall, 4=winter.
 */
public record ScheduleResponse(
        int enrollmentYear,
        int currentYear,
        int currentTerm,
        List<TermSchedule> terms
) {

    public ScheduleResponse {
        terms = terms == null ? List.of() : List.copyOf(terms);
    }
}
