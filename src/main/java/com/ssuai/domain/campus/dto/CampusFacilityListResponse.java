package com.ssuai.domain.campus.dto;

import java.util.List;

public record CampusFacilityListResponse(
        List<CampusFacilityResponse> facilities,
        boolean empty,
        String note,
        int currentPage,
        int pageSize,
        int total,
        int totalPages,
        boolean hasNext
) {
    private static final String EMPTY_NOTE = "검색 조건에 맞는 시설이 없어요.";

    public static CampusFacilityListResponse of(List<CampusFacilityResponse> facilities) {
        boolean empty = facilities == null || facilities.isEmpty();
        int total = facilities == null ? 0 : facilities.size();
        return new CampusFacilityListResponse(
                facilities,
                empty,
                empty ? EMPTY_NOTE : null,
                0, total, total, total == 0 ? 0 : 1, false);
    }

    public static CampusFacilityListResponse of(
            List<CampusFacilityResponse> facilities, int currentPage, int pageSize, int total) {
        boolean empty = facilities == null || facilities.isEmpty();
        int totalPages = pageSize == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        boolean hasNext = pageSize > 0 && (long) (currentPage + 1) * pageSize < total;
        return new CampusFacilityListResponse(facilities, empty, empty ? EMPTY_NOTE : null,
                currentPage, pageSize, total, totalPages, hasNext);
    }
}
