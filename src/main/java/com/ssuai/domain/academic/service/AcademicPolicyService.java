package com.ssuai.domain.academic.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.ssuai.domain.academic.dto.AcademicPolicyBriefResponse;
import com.ssuai.domain.academic.dto.AcademicPolicyCitation;
import com.ssuai.domain.academic.dto.AcademicPolicyCorpusSnapshot;
import com.ssuai.domain.academic.dto.AcademicPolicyDocument;
import com.ssuai.domain.academic.dto.AcademicPolicyEvidence;
import com.ssuai.domain.academic.dto.AcademicPolicySearchResponse;
import com.ssuai.domain.academic.dto.AcademicPolicySource;
import com.ssuai.domain.academic.dto.ScholarshipPolicyCheckResponse;
import com.ssuai.domain.academic.dto.ScholarshipTierEvaluation;
import com.ssuai.domain.academic.embedding.AcademicEmbeddingClient;
import com.ssuai.domain.academic.embedding.AcademicTextChunker;
import com.ssuai.domain.academic.embedding.EmbeddedChunk;
import com.ssuai.domain.academic.embedding.EmbeddedCorpus;
import com.ssuai.domain.academic.service.AcademicPolicyCorpusCache.CorpusAccess;

@Service
public class AcademicPolicyService {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;
    /** RRF smoothing constant. 60 is the industry default (Elastic/Azure/Mongo/Weaviate). */
    private static final int RRF_K = 60;
    /** Vector hits considered before fusion — bounded so a large corpus stays cheap. */
    private static final int VECTOR_CANDIDATE_LIMIT = 50;
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s,.;:!?()\\[\\]{}<>\"'`/|]+");
    private static final Pattern ARTICLE_HEADING = Pattern.compile(
            "제\\s*\\d+\\s*조(?:의\\s*\\d+)?(?:\\s*\\([^)]{1,80}\\))?");
    private static final Pattern KOREAN_TERM = Pattern.compile("[가-힣]{2,}");
    private static final Pattern NUMERIC_TERM = Pattern.compile("\\d+(?:\\.\\d+)?");
    private final AcademicPolicyCorpusCache corpusCache;
    private final AcademicEmbeddingClient embeddingClient;
    private final AcademicQuestionClassifier classifier;
    private final ScholarshipPolicyEvaluator scholarshipPolicyEvaluator;
    private final ScholarshipTierEvaluator scholarshipTierEvaluator;

    @org.springframework.beans.factory.annotation.Autowired
    public AcademicPolicyService(
            AcademicPolicyCorpusCache corpusCache,
            AcademicEmbeddingClient embeddingClient,
            AcademicQuestionClassifier classifier,
            ScholarshipPolicyEvaluator scholarshipPolicyEvaluator,
            ScholarshipTierEvaluator scholarshipTierEvaluator) {
        this.corpusCache = corpusCache;
        this.embeddingClient = embeddingClient;
        this.classifier = classifier;
        this.scholarshipPolicyEvaluator = scholarshipPolicyEvaluator;
        this.scholarshipTierEvaluator = scholarshipTierEvaluator;
    }

    /** Compatibility constructor retained for focused tests and embedded callers. */
    public AcademicPolicyService(
            AcademicPolicyCorpusCache corpusCache,
            AcademicEmbeddingClient embeddingClient,
            AcademicQuestionClassifier classifier,
            ScholarshipPolicyEvaluator scholarshipPolicyEvaluator) {
        this(corpusCache, embeddingClient, classifier, scholarshipPolicyEvaluator, new ScholarshipTierEvaluator());
    }

    public List<AcademicPolicySource> listSources(String category, Boolean live) {
        AcademicPolicyCorpusSnapshot snapshot = corpusCache.snapshot(Boolean.TRUE.equals(live));
        String normalizedCategory = normalizeCategory(category);
        return snapshot.sources().stream()
                .filter(source -> matchesCategory(source, normalizedCategory))
                .toList();
    }

    public AcademicPolicySearchResponse search(String query, String category, Integer limit, Boolean live) {
        String safeQuery = query == null ? "" : query.trim();
        String normalizedCategory = normalizeCategory(category);
        int safeLimit = safeLimit(limit);
        boolean callerRequestedLive = Boolean.TRUE.equals(live);

        CorpusAccess access = corpusAccess(callerRequestedLive);
        EmbeddedCorpus corpus = access.corpus();
        AcademicPolicyCorpusSnapshot snapshot = corpus.snapshot();

        List<String> rawTokens = tokens(safeQuery);
        List<String> searchTokens = rawTokens.isEmpty() && normalizedCategory != null
                ? List.of(normalizedCategory)
                : rawTokens;

        // Lexical ranking over chunks shared with the embedding path (same chunk indices).
        List<Candidate> lexicalRanked = lexicalCandidates(snapshot, normalizedCategory, searchTokens, safeQuery);

        float[] queryVector = (corpus.embeddingActive() && !safeQuery.isBlank())
                ? embeddingClient.embedQuery(safeQuery)
                : new float[0];
        boolean embeddingUsed = queryVector.length > 0;
        List<Candidate> ranked = embeddingUsed
                ? fuseWithRrf(lexicalRanked, vectorCandidates(corpus, normalizedCategory, queryVector))
                : lexicalRanked;

        Map<String, AcademicPolicyDocument> documentsBySourceId = documentsBySourceId(snapshot);
        List<AcademicPolicyEvidence> evidence = deduplicateNearDuplicates(ranked).stream()
                .limit(safeLimit)
                .map(candidate -> toEvidence(candidate, documentsBySourceId))
                .toList();
        Instant searchExecutedAt = Instant.now();

        return AcademicPolicySearchResponse.of(
                safeQuery,
                normalizedCategory,
                callerRequestedLive,
                access.liveFetchAttempted(),
                snapshot.fallbackUsed(),
                corpusType(snapshot),
                embeddingUsed,
                embeddingUsed ? "rrf" : "lexical",
                searchExecutedAt,
                (int) snapshot.sources().stream()
                        .filter(source -> matchesCategory(source, normalizedCategory))
                        .count(),
                evidence.size(),
                evidence,
                snapshot.sources().stream()
                        .filter(source -> matchesCategory(source, normalizedCategory))
                        .toList(),
                access.liveFetchRequested(),
                access.liveFetchAttempted(),
                access.liveFetchSucceeded(),
                access.servedFromCache(),
                access.sourceOrigin(),
                snapshot.fetchedAt(),
                searchExecutedAt);
    }

    public AcademicPolicyBriefResponse brief(String query, String category, Integer limit, Boolean live) {
        AcademicPolicySearchResponse search = search(query, category, limit, live);
        String answer;
        List<String> facts = search.evidence().stream()
                .map(evidence -> evidence.snippet().trim())
                .filter(fact -> !fact.isBlank())
                .distinct()
                .toList();
        List<AcademicPolicyCitation> citations = search.evidence().stream()
                .map(AcademicPolicyService::citation)
                .toList();
        List<String> unresolved = new ArrayList<>();
        if (search.evidence().isEmpty()) {
            answer = "반환된 공식 근거만으로는 질문에 답할 수 없습니다.";
            unresolved.add("질문과 직접 일치하는 공식 정책 근거");
        } else {
            AcademicPolicyEvidence top = search.evidence().getFirst();
            answer = "공식 근거상 " + top.snippet();
            if (!top.revisionVerified()) {
                unresolved.add("상위 근거의 개정본 식별 또는 실시간 개정 이력 검증");
            }
        }
        List<String> cautions = new ArrayList<>();
        cautions.add("facts는 evidence에서 직접 추출한 문장입니다. 학생별 최종 판단에는 u-SAINT와 학과별 교육과정 확인이 필요합니다.");
        if (search.fallbackUsed()) {
            cautions.add("live fetch에 실패한 일부 출처는 seed corpus로 대체되었습니다.");
            unresolved.add("fallback 출처의 최신 공식 원문");
        } else if ("SEED".equals(search.sourceOrigin())) {
            cautions.add("live fetch를 수행하지 않아 seed corpus에서 답했습니다.");
            unresolved.add("공식 원문의 현재 개정 상태");
        } else if (search.servedFromCache()) {
            cautions.add("캐시된 공식 원문을 사용했습니다.");
            unresolved.add("sourceFetchedAt 이후 공식 원문 변경 여부");
        }
        String summary = answer;
        return new AcademicPolicyBriefResponse(
                search.query(),
                search.category(),
                summary,
                cautions,
                search.evidence(),
                answer,
                List.copyOf(facts),
                unresolved.stream().distinct().toList(),
                citations,
                search.liveFetchRequested(),
                search.liveFetchAttempted(),
                search.liveFetchSucceeded(),
                search.servedFromCache(),
                search.sourceOrigin(),
                search.fallbackUsed(),
                search.sourceFetchedAt(),
                search.searchExecutedAt());
    }

    public ScholarshipPolicyCheckResponse checkScholarshipPolicy(
            String query,
            Double gpa,
            Integer earnedCredits,
            Integer admissionYear,
            Integer topikLevel,
            Boolean internationalStudent,
            Boolean live,
            Integer limit) {
        List<String> facts = new ArrayList<>();
        if (gpa != null) facts.add("gpa=" + gpa);
        if (earnedCredits != null) facts.add("earnedCredits=" + earnedCredits);
        if (admissionYear != null) facts.add("admissionYear=" + admissionYear);
        if (topikLevel != null) facts.add("topikLevel=" + topikLevel);
        if (internationalStudent != null) facts.add("internationalStudent=" + internationalStudent);

        String combinedQuery = scholarshipPolicyEvaluator.buildScholarshipQuery(query, facts);
        AcademicPolicyBriefResponse brief = brief(combinedQuery, "scholarship", limit, live);
        ScholarshipTierEvaluation tierEvaluation = scholarshipTierEvaluator.evaluate(
                combinedQuery,
                brief.evidence(),
                gpa,
                earnedCredits,
                admissionYear,
                topikLevel,
                internationalStudent);
        return scholarshipPolicyEvaluator.evaluate(
                combinedQuery,
                facts,
                brief.evidence(),
                brief.cautions(),
                gpa,
                earnedCredits,
                admissionYear,
                topikLevel,
                internationalStudent,
                tierEvaluation,
                brief);
    }

    public AcademicQuestionClassifier classifier() {
        return classifier;
    }

    private CorpusAccess corpusAccess(boolean callerRequestedLive) {
        CorpusAccess access = corpusCache.access(callerRequestedLive);
        if (access != null) {
            return access;
        }
        // Compatibility for embedded/mock AcademicPolicyCorpusCache implementations
        // written before request-level provenance was introduced.
        EmbeddedCorpus corpus = corpusCache.embeddedCorpus(callerRequestedLive);
        AcademicPolicyCorpusSnapshot snapshot = corpus.snapshot();
        long liveDocuments = snapshot.documents().stream().filter(AcademicPolicyDocument::live).count();
        String origin = liveDocuments == 0
                ? "SEED"
                : liveDocuments == snapshot.documents().size() ? "LIVE" : "MIXED";
        boolean attempted = callerRequestedLive && snapshot.liveRequested();
        return new CorpusAccess(
                corpus,
                callerRequestedLive,
                attempted,
                attempted && liveDocuments > 0,
                !attempted,
                origin);
    }

    // --- ranking ---------------------------------------------------------

    private List<Candidate> lexicalCandidates(
            AcademicPolicyCorpusSnapshot snapshot, String category, List<String> tokens, String query) {
        if (tokens.isEmpty()) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (AcademicPolicyDocument document : snapshot.documents()) {
            if (!matchesCategory(document.source(), category)) {
                continue;
            }
            List<String> chunks = AcademicTextChunker.chunk(document.text());
            for (int index = 0; index < chunks.size(); index++) {
                String heading = heading(chunks.get(index), document.source(), tokens);
                Score score = score(chunks.get(index), document.source(), heading, tokens, query);
                if (score.value() > 0) {
                    candidates.add(new Candidate(
                            document.source(), index, chunks.get(index), heading,
                            score.value(), score.matchedTerms()));
                }
            }
        }
        // List.sort is stable: equal-scoring candidates retain official corpus order,
        // so a later near-duplicate cannot displace its canonical predecessor.
        candidates.sort(Comparator.comparingInt(Candidate::lexScore).reversed());
        return candidates;
    }

    private List<Candidate> vectorCandidates(EmbeddedCorpus corpus, String category, float[] queryVector) {
        return corpus.chunks().stream()
                .filter(chunk -> matchesCategory(chunk.source(), category))
                .map(chunk -> Map.entry(chunk, chunk.cosineSimilarity(queryVector)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<EmbeddedChunk, Double>comparingByValue().reversed())
                .limit(VECTOR_CANDIDATE_LIMIT)
                .map(entry -> {
                    EmbeddedChunk chunk = entry.getKey();
                    return new Candidate(
                            chunk.source(), chunk.chunkIndex(), chunk.text(),
                            heading(chunk.text(), chunk.source(), List.of()), 0, List.of());
                })
                .toList();
    }

    /**
     * Reciprocal Rank Fusion: each candidate scores Σ 1/(k + rank) across the lexical
     * and vector lists. Works on rank positions, not raw scores, so the two
     * incomparable score scales need no normalization.
     */
    private List<Candidate> fuseWithRrf(List<Candidate> lexicalRanked, List<Candidate> vectorRanked) {
        Map<String, Candidate> byKey = new LinkedHashMap<>();
        Map<String, Double> rrfScore = new LinkedHashMap<>();

        accumulate(lexicalRanked, byKey, rrfScore);
        accumulate(vectorRanked, byKey, rrfScore);

        return rrfScore.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(entry -> byKey.get(entry.getKey()).source().title()))
                .map(entry -> byKey.get(entry.getKey()))
                .toList();
    }

    private static void accumulate(
            List<Candidate> ranked, Map<String, Candidate> byKey, Map<String, Double> rrfScore) {
        for (int i = 0; i < ranked.size(); i++) {
            Candidate candidate = ranked.get(i);
            String key = candidate.source().id() + "#" + candidate.chunkIndex();
            // Prefer the candidate that carries matched lexical terms for richer evidence.
            byKey.merge(key, candidate, (existing, incoming) ->
                    existing.matchedTerms().isEmpty() ? incoming : existing);
            rrfScore.merge(key, 1.0 / (RRF_K + i + 1), Double::sum);
        }
    }

    private static Map<String, AcademicPolicyDocument> documentsBySourceId(AcademicPolicyCorpusSnapshot snapshot) {
        Map<String, AcademicPolicyDocument> map = new LinkedHashMap<>();
        for (AcademicPolicyDocument document : snapshot.documents()) {
            map.putIfAbsent(document.source().id(), document);
        }
        return map;
    }

    private static AcademicPolicyEvidence toEvidence(
            Candidate candidate, Map<String, AcademicPolicyDocument> documentsBySourceId) {
        AcademicPolicySource source = candidate.source();
        AcademicPolicyDocument document = documentsBySourceId.get(source.id());
        boolean live = document != null && document.live();
        boolean fallbackUsed = document != null && document.fallbackUsed();
        return new AcademicPolicyEvidence(
                source.id(),
                source.title(),
                source.category(),
                source.sourceType(),
                source.url(),
                source.revision(),
                source.effectiveDate(),
                live,
                fallbackUsed,
                document != null ? document.fetchedAt() : null,
                candidate.lexScore(),
                candidate.heading(),
                snippet(candidate.chunkText(), candidate.matchedTerms()),
                candidate.matchedTerms(),
                source.lastVerifiedDate(),
                revisionVerified(source, document),
                live ? "LIVE" : "SEED",
                document != null ? document.fetchedAt() : null);
    }

    // --- scoring helpers (lexical) --------------------------------------

    private static Score score(
            String chunk, AcademicPolicySource source, String heading, List<String> tokens, String query) {
        String normalizedChunk = normalize(chunk);
        String normalizedTitle = normalize(source.title());
        String normalizedHeading = normalize(heading);
        String haystack = normalize(chunk + " " + source.title() + " " + source.category() + " " + source.note());
        Set<String> matched = new LinkedHashSet<>();
        int score = 0;
        for (String token : tokens) {
            if (token.length() < 2) {
                continue;
            }
            int count = countOccurrences(haystack, token);
            if (count > 0) {
                matched.add(token);
                score += count;
            }
            if (normalizedTitle.contains(token)) {
                score += 4;
            }
            if (normalizedHeading.contains(token)) {
                score += 7;
            }
        }
        if (matched.isEmpty()) {
            return new Score(0, List.of());
        }
        String normalizedQuery = normalize(query).replaceAll("\\s+", " ");
        if (normalizedQuery.length() >= 2 && normalizedChunk.contains(normalizedQuery)) {
            score += 18;
        }
        score += exactKoreanPhraseBoost(normalizedChunk, normalizedQuery);
        if (ARTICLE_HEADING.matcher(normalizedQuery).find()
                && normalizedHeading.replaceAll("\\s+", "").contains(normalizedQuery.replaceAll("\\s+", ""))) {
            score += 12;
        }
        return new Score(score, List.copyOf(matched));
    }

    private static int exactKoreanPhraseBoost(String chunk, String query) {
        List<String> koreanTerms = KOREAN_TERM.matcher(query).results()
                .map(result -> result.group())
                .toList();
        int boost = 0;
        for (int index = 0; index + 1 < koreanTerms.size(); index++) {
            String spaced = koreanTerms.get(index) + " " + koreanTerms.get(index + 1);
            String joined = koreanTerms.get(index) + koreanTerms.get(index + 1);
            if (chunk.contains(spaced) || chunk.replace(" ", "").contains(joined)) {
                boost += 8;
            }
        }
        return boost;
    }

    private static String heading(String chunk, AcademicPolicySource source, List<String> tokens) {
        List<String> headings = ARTICLE_HEADING.matcher(chunk == null ? "" : chunk).results()
                .map(result -> result.group().replaceAll("\\s+", " ").trim())
                .toList();
        for (String article : headings) {
            String normalized = normalize(article);
            if (tokens.stream().anyMatch(normalized::contains)) {
                return article;
            }
        }
        return headings.isEmpty() ? source.title() : headings.getFirst();
    }

    private static List<Candidate> deduplicateNearDuplicates(List<Candidate> ranked) {
        List<Candidate> unique = new ArrayList<>();
        for (Candidate candidate : ranked) {
            boolean duplicate = unique.stream().anyMatch(existing -> nearDuplicate(
                    existing.chunkText(), candidate.chunkText()));
            if (!duplicate) {
                unique.add(candidate);
            }
        }
        return unique;
    }

    private static boolean nearDuplicate(String left, String right) {
        String normalizedLeft = normalizeForDedup(left);
        String normalizedRight = normalizeForDedup(right);
        if (!numericTerms(left).equals(numericTerms(right))) {
            return false;
        }
        if (normalizedLeft.equals(normalizedRight)) {
            return true;
        }
        int shorter = Math.min(normalizedLeft.length(), normalizedRight.length());
        if (shorter >= 80
                && (normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft))
                && shorter * 1.0d / Math.max(normalizedLeft.length(), normalizedRight.length()) >= 0.9d) {
            return true;
        }
        Set<String> leftShingles = shingles(normalizedLeft);
        Set<String> rightShingles = shingles(normalizedRight);
        if (leftShingles.isEmpty() || rightShingles.isEmpty()) {
            return false;
        }
        Set<String> intersection = new LinkedHashSet<>(leftShingles);
        intersection.retainAll(rightShingles);
        Set<String> union = new LinkedHashSet<>(leftShingles);
        union.addAll(rightShingles);
        return intersection.size() * 1.0d / union.size() >= 0.82d;
    }

    private static Set<String> shingles(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index + 3 <= value.length(); index++) {
            result.add(value.substring(index, index + 3));
        }
        return result;
    }

    private static String normalizeForDedup(String value) {
        return normalize(value).replaceAll("[^0-9a-z가-힣]", "");
    }

    private static List<String> numericTerms(String value) {
        return NUMERIC_TERM.matcher(value == null ? "" : value).results()
                .map(result -> result.group())
                .toList();
    }

    private static boolean revisionVerified(AcademicPolicySource source, AcademicPolicyDocument document) {
        if (document == null || !document.live() || source.lastVerifiedDate() == null) {
            return false;
        }
        String revision = normalize(source.revision());
        return !revision.isBlank()
                && !"dynamic-current".equals(revision)
                && !"official-page".equals(revision);
    }

    private static AcademicPolicyCitation citation(AcademicPolicyEvidence evidence) {
        return new AcademicPolicyCitation(
                evidence.sourceId(),
                evidence.title(),
                evidence.url(),
                evidence.revision(),
                evidence.effectiveDate(),
                evidence.lastVerifiedDate(),
                evidence.revisionVerified(),
                evidence.heading());
    }

    private static String snippet(String chunk, List<String> matchedTerms) {
        if (chunk.length() <= 360) {
            return chunk;
        }
        int start = 0;
        for (String term : matchedTerms) {
            int index = normalize(chunk).indexOf(term);
            if (index >= 0) {
                start = Math.max(0, index - 120);
                break;
            }
        }
        int end = Math.min(chunk.length(), start + 360);
        String prefix = start > 0 ? "..." : "";
        String suffix = end < chunk.length() ? "..." : "";
        return prefix + chunk.substring(start, end).trim() + suffix;
    }

    private static List<String> tokens(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return TOKEN_SPLIT.splitAsStream(normalize(query))
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .distinct()
                .limit(12)
                .toList();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String normalized = normalize(category);
        if (normalized.contains("졸업") || normalized.contains("학점") || normalized.contains("graduation")) {
            return "graduation";
        }
        if (normalized.contains("장학") || normalized.contains("scholar")) {
            return "scholarship";
        }
        if (normalized.contains("일정") || normalized.contains("calendar")) {
            return "calendar";
        }
        return normalized;
    }

    private static boolean matchesCategory(AcademicPolicySource source, String category) {
        return category == null
                || "academic".equals(category)
                || source.category().equalsIgnoreCase(category)
                || source.note().toLowerCase(Locale.ROOT).contains(category);
    }

    /**
     * Corpus provenance: "live" only when fetched from official sources without any
     * per-source fallback, "mixed" when live fetch partially fell back to seed,
     * "seed" when the snapshot never touched the official sources.
     */
    private static String corpusType(AcademicPolicyCorpusSnapshot snapshot) {
        if (!snapshot.liveRequested()) {
            return "seed";
        }
        return snapshot.fallbackUsed() ? "mixed" : "live";
    }

    private static int safeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private record Score(int value, List<String> matchedTerms) {
    }

    private record Candidate(
            AcademicPolicySource source,
            int chunkIndex,
            String chunkText,
            String heading,
            int lexScore,
            List<String> matchedTerms) {
    }
}
