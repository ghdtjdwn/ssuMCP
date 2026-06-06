package com.ssuai.domain.academic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.academic.dto.AcademicPolicyCorpusSnapshot;
import com.ssuai.domain.academic.dto.AcademicPolicyDocument;
import com.ssuai.domain.academic.dto.AcademicPolicySource;

class AcademicPolicyServiceTests {

    private final AcademicPolicyCorpusCache corpusCache = mock(AcademicPolicyCorpusCache.class);
    private final AcademicQuestionClassifier classifier = new AcademicQuestionClassifier();
    private final AcademicPolicyService service = new AcademicPolicyService(corpusCache, classifier);

    @Test
    void searchReturnsScoredEvidenceFromOfficialSnapshot() {
        AcademicPolicySource source = source("graduation", "졸업사정 안내");
        AcademicPolicyCorpusSnapshot snapshot = snapshot(source, "졸업요건은 전공 학점, 교양 학점, 채플을 함께 확인한다.");
        when(corpusCache.snapshot(false)).thenReturn(snapshot);

        var response = service.search("졸업 전공 학점", "graduation", 3, false);

        assertThat(response.evidence()).hasSize(1);
        assertThat(response.evidence().getFirst().title()).isEqualTo("졸업사정 안내");
        assertThat(response.evidence().getFirst().matchedTerms()).contains("졸업", "전공", "학점");
    }

    @Test
    void scholarshipCheckBuildsFactsIntoQuery() {
        AcademicPolicySource source = source("scholarship", "교내장학금 안내");
        AcademicPolicyCorpusSnapshot snapshot = snapshot(source, "백마성적우수장학금은 성적과 취득학점 기준을 확인한다.");
        when(corpusCache.snapshot(true)).thenReturn(snapshot);

        var response = service.checkScholarshipPolicy(
                "백마성적우수장학금",
                4.1d,
                15,
                null,
                null,
                false,
                true,
                5);

        assertThat(response.inputFacts()).contains("gpa=4.1", "earnedCredits=15", "internationalStudent=false");
        assertThat(response.evidence()).isNotEmpty();
    }

    private static AcademicPolicyCorpusSnapshot snapshot(AcademicPolicySource source, String text) {
        AcademicPolicyDocument document = new AcademicPolicyDocument(
                source,
                text,
                true,
                false,
                Instant.parse("2026-06-06T00:00:00Z"),
                "hash");
        return new AcademicPolicyCorpusSnapshot(
                List.of(source),
                List.of(document),
                true,
                false,
                Instant.parse("2026-06-06T00:00:00Z"));
    }

    private static AcademicPolicySource source(String category, String title) {
        return new AcademicPolicySource(
                "source-1",
                title,
                category,
                "official-page",
                "https://ssu.ac.kr",
                "https://ssu.ac.kr",
                "official-page",
                null,
                LocalDate.of(2026, 6, 6),
                true,
                "LIVE_SOURCE",
                title);
    }
}
