package com.ssuai.domain.notice.dto;

public record Notice(
        String title,
        String link,
        String date,
        String status,
        String department,
        String category,
        String author,
        String sourceDepartment,
        String publishedAt,
        String deadlineAt,
        String timezone,
        String statusReason,
        String deadlineConfidence
) {
    /** Original public fields remain available to REST and MCP clients. */
    public Notice(String title, String link, String date, String status,
                  String department, String category) {
        this(title, link, date, status, department, category,
                null, department, null, null, null, null, null);
    }
}
