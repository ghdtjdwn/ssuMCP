package com.ssuai.domain.campus.dto;

import java.util.List;

public record CampusFacilityListResponse(
        List<CampusFacilityResponse> facilities,
        boolean empty,
        String note
) {
    private static final String EMPTY_NOTE = "검색 조건에 맞는 시설이 없어요.";

    public static CampusFacilityListResponse of(List<CampusFacilityResponse> facilities) {
        boolean empty = facilities == null || facilities.isEmpty();
        return new CampusFacilityListResponse(
                facilities,
                empty,
                empty ? EMPTY_NOTE : null);
    }
}
