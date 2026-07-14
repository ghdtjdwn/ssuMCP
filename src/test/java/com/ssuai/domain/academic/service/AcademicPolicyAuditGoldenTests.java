package com.ssuai.domain.academic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssuai.domain.academic.dto.AcademicPolicyCorpusSnapshot;
import com.ssuai.domain.academic.dto.AcademicPolicyDocument;
import com.ssuai.domain.academic.dto.AcademicPolicySource;
import com.ssuai.domain.academic.embedding.AcademicEmbeddingClient;
import com.ssuai.domain.academic.embedding.EmbeddedCorpus;

class AcademicPolicyAuditGoldenTests {

    private static final Instant FETCHED_AT = Instant.parse("2026-07-14T00:00:00Z");

    private final AcademicPolicyCorpusCache corpusCache = mock(AcademicPolicyCorpusCache.class);
    private final AcademicPolicyService service = new AcademicPolicyService(
            corpusCache,
            mock(AcademicEmbeddingClient.class),
            new AcademicQuestionClassifier(),
            new ScholarshipPolicyEvaluator(),
            new ScholarshipTierEvaluator());
    private Fixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new ObjectMapper().readValue(
                getClass().getResourceAsStream("/fixtures/academic-policy/fixed-corpus.json"), Fixture.class);
        AcademicPolicyCorpusSnapshot snapshot = snapshot(fixture.sources());
        when(corpusCache.embeddedCorpus(false)).thenReturn(EmbeddedCorpus.lexicalOnly(snapshot));
        when(corpusCache.embeddedCorpus(true)).thenReturn(EmbeddedCorpus.lexicalOnly(snapshot));
    }

    @Test
    void fixedCorpusGoldenRankingBoostsExactHeadingAndDeduplicatesNearCopy() {
        var response = service.search("백마성적우수장학금 취득학점", "scholarship", 3, false);

        assertThat(response.evidence().getFirst().sourceId()).isEqualTo(fixture.golden().baekmaTopSource());
        assertThat(response.evidence()).extracting(item -> item.sourceId())
                .doesNotContain(fixture.golden().deduplicatedSource());
        assertThat(response.evidence().getFirst().heading()).isEqualTo("제12조(백마성적우수장학금)");
    }

    @Test
    void briefGoldenSeparatesAnswerFactsUnresolvedAndRevisionConsistentCitations() {
        var response = service.brief("백마성적우수장학금 취득학점", "scholarship", 2, false);

        assertThat(response.answer()).startsWith("공식 근거상 ");
        assertThat(response.facts()).isNotEmpty().allMatch(fact -> !fact.isBlank());
        assertThat(response.unresolved()).contains("sourceFetchedAt 이후 공식 원문 변경 여부");
        assertThat(response.citations()).hasSameSizeAs(response.evidence());
        assertThat(response.citations().getFirst().revision())
                .isEqualTo(response.evidence().getFirst().revision());
        assertThat(response.citations().getFirst().revisionVerified()).isTrue();
        assertThat(response.liveFetchRequested()).isFalse();
        assertThat(response.liveFetchAttempted()).isFalse();
        assertThat(response.servedFromCache()).isTrue();
        assertThat(response.sourceOrigin()).isEqualTo("LIVE");
        assertThat(response.sourceFetchedAt()).isEqualTo(FETCHED_AT);
        assertThat(response.searchExecutedAt()).isAfterOrEqualTo(FETCHED_AT);
    }

    @Test
    void baekmaGoldenUsesSeparateDeterministicRuleEvaluation() {
        var response = service.checkScholarshipPolicy(
                "백마성적우수장학금", 3.8d, 15, null, null, false, false, 3);

        assertThat(response.tierEvaluation().selectedRule()).isEqualTo(fixture.golden().baekmaRule());
        assertThat(response.tierEvaluation().tier()).isEqualTo(fixture.golden().baekmaTier());
        assertThat(response.tierEvaluation().matched()).contains("gpa >= 3.5", "earnedCredits >= 15");
        assertThat(response.tierEvaluation().unmet()).isEmpty();
        assertThat(response.tierEvaluation().evidence()).isNotEmpty();
        assertThat(response.tierEvaluation().confidence()).isGreaterThan(0.0d);
        assertThat(response.sourceFetchedAt()).isEqualTo(FETCHED_AT);
    }

    @Test
    void internationalGoldenHonorsAdmissionBoundaryAndTopikTier() {
        var supported = service.checkScholarshipPolicy(
                "외국인 유학생 TOPIK 장학금", null, null, 2025, 5, true, false, 3);
        var beforeBoundary = service.checkScholarshipPolicy(
                "international student TOPIK scholarship", null, null, 2024, 6, true, false, 3);

        assertThat(supported.tierEvaluation().selectedRule())
                .isEqualTo(fixture.golden().internationalRule());
        assertThat(supported.tierEvaluation().tier()).isEqualTo(fixture.golden().internationalTier());
        assertThat(supported.tierEvaluation().matched())
                .contains("admissionYear >= 2025", "topikLevel >= 4");
        assertThat(beforeBoundary.tierEvaluation().selectedRule())
                .isEqualTo("INTERNATIONAL_TOPIK_BEFORE_2025");
        assertThat(beforeBoundary.tierEvaluation().tier()).isEqualTo("UNRESOLVED");
        assertThat(beforeBoundary.tierEvaluation().unknown())
                .contains("admissionYear=2024 적용 규칙", "TOPIK tier for selected admission year");
    }

    private static AcademicPolicyCorpusSnapshot snapshot(List<FixtureSource> fixtureSources) {
        List<AcademicPolicyDocument> documents = fixtureSources.stream()
                .map(item -> {
                    AcademicPolicySource source = new AcademicPolicySource(
                            item.id(), item.title(), item.category(), "rule",
                            "https://rule.ssu.ac.kr/" + item.id(),
                            "https://rule.ssu.ac.kr/" + item.id() + "/content",
                            item.revision(), item.effectiveDate(), LocalDate.of(2026, 7, 14),
                            true, "LIVE_SOURCE", item.title());
                    return new AcademicPolicyDocument(source, item.text(), true, false, FETCHED_AT, "hash-" + item.id());
                })
                .toList();
        return new AcademicPolicyCorpusSnapshot(
                documents.stream().map(AcademicPolicyDocument::source).toList(),
                documents,
                true,
                false,
                FETCHED_AT);
    }

    private record Fixture(List<FixtureSource> sources, Golden golden) {
    }

    private record FixtureSource(
            String id,
            String title,
            String category,
            String revision,
            String effectiveDate,
            String text) {
    }

    private record Golden(
            String baekmaTopSource,
            String deduplicatedSource,
            String baekmaRule,
            String baekmaTier,
            String internationalRule,
            String internationalTier) {
    }
}
