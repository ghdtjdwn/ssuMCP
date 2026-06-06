package com.ssuai.domain.academic.dto;

import java.time.LocalDate;

public record AcademicPolicySource(
        String id,
        String title,
        String category,
        String sourceType,
        String url,
        String contentUrl,
        String revision,
        String effectiveDate,
        LocalDate lastVerifiedDate,
        boolean liveFetchSupported,
        String freshnessStatus,
        String note) {
}
