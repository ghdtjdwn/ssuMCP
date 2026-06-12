package com.ssuai.domain.notice.dto;

import java.util.List;

public record NoticeCompactListResponse(
        List<NoticeCompactItem> items,
        int currentPage,
        int totalPages
) {

    public NoticeCompactListResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static NoticeCompactListResponse from(NoticeListResponse response) {
        return new NoticeCompactListResponse(
                response.items() == null
                        ? List.of()
                        : response.items().stream()
                                .map(NoticeCompactItem::from)
                                .toList(),
                response.currentPage(),
                response.totalPages()
        );
    }

    public record NoticeCompactItem(String title, String url) {

        private static NoticeCompactItem from(Notice notice) {
            return new NoticeCompactItem(notice.title(), notice.link());
        }
    }
}
