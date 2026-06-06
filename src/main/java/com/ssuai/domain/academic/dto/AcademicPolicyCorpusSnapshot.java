package com.ssuai.domain.academic.dto;

import java.time.Instant;
import java.util.List;

public record AcademicPolicyCorpusSnapshot(
        List<AcademicPolicySource> sources,
        List<AcademicPolicyDocument> documents,
        boolean liveRequested,
        boolean fallbackUsed,
        Instant fetchedAt) {
}
