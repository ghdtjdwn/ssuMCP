package com.ssuai.domain.notice.dto;

import java.util.List;

public record NoticeListResponse(
        List<Notice> items,
        int currentPage,
        int totalPages,
        boolean empty,
        String note
) {
    private static final String EMPTY_NOTE = "조건에 맞는 공지가 없어요.";

    public static NoticeListResponse of(List<Notice> items, int currentPage, int totalPages) {
        boolean empty = items == null || items.isEmpty();
        return new NoticeListResponse(
                items,
                currentPage,
                totalPages,
                empty,
                empty ? EMPTY_NOTE : null);
    }
}
