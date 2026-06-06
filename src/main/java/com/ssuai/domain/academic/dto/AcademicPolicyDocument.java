package com.ssuai.domain.academic.dto;

import java.time.Instant;

public record AcademicPolicyDocument(
        AcademicPolicySource source,
        String text,
        boolean live,
        boolean fallbackUsed,
        Instant fetchedAt,
        String contentHash) {
}
