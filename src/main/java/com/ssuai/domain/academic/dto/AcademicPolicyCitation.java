package com.ssuai.domain.academic.dto;

import java.time.LocalDate;

/** A stable, revision-aware citation derived one-to-one from returned evidence. */
public record AcademicPolicyCitation(
        String sourceId,
        String title,
        String url,
        String revision,
        String effectiveDate,
        LocalDate lastVerifiedDate,
        boolean revisionVerified,
        String heading) {
}
