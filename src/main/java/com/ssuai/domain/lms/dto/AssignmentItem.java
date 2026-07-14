package com.ssuai.domain.lms.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Single pending assignment or quiz returned by the canvas to_dos API.
 *
 * @param courseName name of the enrolling course (joined from courses response)
 * @param title      assignment / quiz title
 * @param type       component type string from canvas ("assignment", "quiz", etc.)
 * @param dueDate    ISO-8601 due datetime, or {@code null} if no deadline set
 */
public record AssignmentItem(
        String courseName,
        String title,
        String type,
        String dueDate
) {
    @JsonProperty("deadlineStatus")
    public String deadlineStatus() {
        return dueDate == null || dueDate.isBlank() ? "NO_DUE_DATE" : "DUE_DATE_SET";
    }

    @JsonProperty("inclusionReason")
    public String inclusionReason() {
        return dueDate == null || dueDate.isBlank()
                ? "UNSUBMITTED_WITHOUT_DEADLINE"
                : "UNSUBMITTED_WITH_DEADLINE";
    }
}
