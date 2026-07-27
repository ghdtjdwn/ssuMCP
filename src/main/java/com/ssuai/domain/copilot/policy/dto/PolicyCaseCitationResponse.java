package com.ssuai.domain.copilot.policy.dto;

import java.time.LocalDate;

import com.ssuai.domain.academic.dto.AcademicPolicyCitation;

public record PolicyCaseCitationResponse(
        String sourceId,
        String title,
        String url,
        String revision,
        String effectiveDate,
        LocalDate lastVerifiedDate,
        boolean revisionVerified,
        String heading) {

    public static PolicyCaseCitationResponse from(AcademicPolicyCitation citation) {
        return new PolicyCaseCitationResponse(
                citation.sourceId(),
                citation.title(),
                citation.url(),
                citation.revision(),
                citation.effectiveDate(),
                citation.lastVerifiedDate(),
                citation.revisionVerified(),
                citation.heading());
    }
}
