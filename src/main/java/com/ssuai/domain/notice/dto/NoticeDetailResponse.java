package com.ssuai.domain.notice.dto;

import java.util.List;

public record NoticeDetailResponse(
        String title,
        String link,
        String date,
        String status,
        String department,
        String category,
        String bodyText,
        List<NoticeAttachment> attachments,
        String contentCompleteness,
        String bodySource,
        String bodyMissingReason
) {
    public NoticeDetailResponse {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** Preserves the detail shape exposed before attachment/content provenance was added. */
    public NoticeDetailResponse(String title, String link, String date, String status,
                                String department, String category, String bodyText) {
        this(title, link, date, status, department, category, bodyText,
                List.of(), bodyText == null || bodyText.isBlank() ? "MISSING" : "FULL",
                bodyText == null || bodyText.isBlank() ? "NONE" : "OFFICIAL_HTML",
                bodyText == null || bodyText.isBlank() ? "BODY_NOT_FOUND" : null);
    }
}
