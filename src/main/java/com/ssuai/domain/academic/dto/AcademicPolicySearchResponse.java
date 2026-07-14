package com.ssuai.domain.academic.dto;

import java.time.Instant;
import java.util.List;

/**
 * @param liveRequested what the caller actually asked for (the {@code live} tool parameter)
 * @param liveExecuted  whether this request was served by a corpus that was live-fetched
 *                      because of this call (false when serving the cached/seed corpus)
 * @param corpusType    provenance of the corpus snapshot used: "live", "mixed" (live with
 *                      partial fallback), or "seed"
 * @param embeddingUsed whether semantic (embedding) ranking contributed; false means the
 *                      result is lexical-only (embeddings disabled or upstream unavailable)
 * @param fusionMethod  "rrf" when lexical + vector rankings were fused, else "lexical"
 * @param liveFetchRequested exact caller intent for this request
 * @param liveFetchAttempted whether this request invoked the connector's live path
 * @param liveFetchSucceeded whether that attempt yielded at least one live official document
 * @param servedFromCache whether the corpus was already cached before this request (including
 *                        a cached corpus used after a failed refresh)
 * @param sourceOrigin actual document origin: {@code LIVE}, {@code MIXED}, or {@code SEED}
 * @param sourceFetchedAt when the returned corpus snapshot was loaded
 * @param searchExecutedAt when ranking for this response completed
 */
public record AcademicPolicySearchResponse(
        String query,
        String category,
        boolean liveRequested,
        boolean liveExecuted,
        boolean fallbackUsed,
        String corpusType,
        boolean embeddingUsed,
        String fusionMethod,
        Instant searchedAt,
        int totalSources,
        int totalMatches,
        List<AcademicPolicyEvidence> evidence,
        List<AcademicPolicySource> sources,
        boolean empty,
        String note,
        boolean liveFetchRequested,
        boolean liveFetchAttempted,
        boolean liveFetchSucceeded,
        boolean servedFromCache,
        String sourceOrigin,
        Instant sourceFetchedAt,
        Instant searchExecutedAt) {

    private static final String EMPTY_NOTE = "관련 공식 출처를 찾지 못했어요.";

    public static AcademicPolicySearchResponse of(
            String query,
            String category,
            boolean liveRequested,
            boolean liveExecuted,
            boolean fallbackUsed,
            String corpusType,
            boolean embeddingUsed,
            String fusionMethod,
            Instant searchedAt,
            int totalSources,
            int totalMatches,
            List<AcademicPolicyEvidence> evidence,
            List<AcademicPolicySource> sources) {
        return of(
                query, category, liveRequested, liveExecuted, fallbackUsed, corpusType,
                embeddingUsed, fusionMethod, searchedAt, totalSources, totalMatches, evidence, sources,
                liveRequested, liveExecuted, liveExecuted && !"seed".equalsIgnoreCase(corpusType),
                !liveExecuted, normalizeOrigin(corpusType), searchedAt, searchedAt);
    }

    public static AcademicPolicySearchResponse of(
            String query,
            String category,
            boolean liveRequested,
            boolean liveExecuted,
            boolean fallbackUsed,
            String corpusType,
            boolean embeddingUsed,
            String fusionMethod,
            Instant searchedAt,
            int totalSources,
            int totalMatches,
            List<AcademicPolicyEvidence> evidence,
            List<AcademicPolicySource> sources,
            boolean liveFetchRequested,
            boolean liveFetchAttempted,
            boolean liveFetchSucceeded,
            boolean servedFromCache,
            String sourceOrigin,
            Instant sourceFetchedAt,
            Instant searchExecutedAt) {
        boolean empty = evidence == null || evidence.isEmpty();
        return new AcademicPolicySearchResponse(
                query,
                category,
                liveRequested,
                liveExecuted,
                fallbackUsed,
                corpusType,
                embeddingUsed,
                fusionMethod,
                searchedAt,
                totalSources,
                totalMatches,
                evidence,
                sources,
                empty,
                empty ? EMPTY_NOTE : null,
                liveFetchRequested,
                liveFetchAttempted,
                liveFetchSucceeded,
                servedFromCache,
                sourceOrigin,
                sourceFetchedAt,
                searchExecutedAt);
    }

    private static String normalizeOrigin(String corpusType) {
        return corpusType == null ? "UNKNOWN" : corpusType.toUpperCase(java.util.Locale.ROOT);
    }
}
