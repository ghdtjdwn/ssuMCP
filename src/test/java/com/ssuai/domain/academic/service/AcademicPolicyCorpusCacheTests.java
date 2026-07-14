package com.ssuai.domain.academic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.academic.connector.AcademicPolicyConnector;
import com.ssuai.domain.academic.dto.AcademicPolicyCorpusSnapshot;
import com.ssuai.domain.academic.dto.AcademicPolicyDocument;
import com.ssuai.domain.academic.dto.AcademicPolicySource;
import com.ssuai.domain.academic.embedding.AcademicEmbeddingStore;
import com.ssuai.domain.academic.embedding.EmbeddedCorpus;

/**
 * Regression for the 2026-06-11 prod crash loop: embedding enrichment runs on
 * the @PostConstruct startup path, so no enrichment failure of any exception
 * type may escape — it must degrade to a lexical-only corpus instead.
 */
class AcademicPolicyCorpusCacheTests {

    private static final AcademicPolicySource SOURCE = new AcademicPolicySource(
            "undergraduate-bylaw", "학칙시행세칙", "graduation", "rule",
            "https://rule.example", "https://rule.example/full", "SEQ_HISTORY=1",
            "2026-01-01", null, true, "LIVE_SOURCE", "test");

    private static AcademicPolicyCorpusSnapshot snapshot() {
        AcademicPolicyDocument document = new AcademicPolicyDocument(
                SOURCE, "졸업 학점은 133학점이다.", false, false, Instant.now(), "hash");
        return new AcademicPolicyCorpusSnapshot(
                List.of(SOURCE), List.of(document), false, false, Instant.now());
    }

    private static AcademicPolicyConnector connector() {
        return live -> snapshot();
    }

    /** Usable-looking store whose embed call blows up with a non-RestClientException. */
    private static AcademicEmbeddingStore throwingStore() {
        return new AcademicEmbeddingStore() {
            @Override
            public boolean isUsable() {
                return true;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                throw new IllegalArgumentException("invalid header value: \"Bearer key\n\"");
            }
        };
    }

    @Test
    void startupLoadSurvivesEmbeddingFailureAndDegradesToLexical() {
        AcademicPolicyCorpusCache cache =
                new AcademicPolicyCorpusCache(connector(), throwingStore(), false);

        assertThatCode(cache::loadFastFallbackCorpus).doesNotThrowAnyException();

        EmbeddedCorpus corpus = cache.embeddedCorpus(false);
        assertThat(corpus.embeddingActive()).isFalse();
        assertThat(corpus.snapshot().documents()).hasSize(1);
    }

    @Test
    void refreshSurvivesEmbeddingFailureAndDegradesToLexical() {
        AcademicPolicyCorpusCache cache =
                new AcademicPolicyCorpusCache(connector(), throwingStore(), true);

        assertThatCode(cache::refreshFromOfficialSources).doesNotThrowAnyException();
        assertThat(cache.embeddedCorpus(false).embeddingActive()).isFalse();
    }

    @Test
    void accessProvenanceDistinguishesCachedSeedFromAttemptedLiveRefresh() {
        AtomicInteger liveCalls = new AtomicInteger();
        AcademicPolicyConnector connector = live -> {
            if (live) {
                liveCalls.incrementAndGet();
            }
            Instant fetchedAt = live
                    ? Instant.parse("2026-07-14T01:00:00Z")
                    : Instant.parse("2026-07-14T00:00:00Z");
            AcademicPolicyDocument document = new AcademicPolicyDocument(
                    SOURCE, "졸업 학점은 공식 근거를 확인한다.", live, false, fetchedAt, "hash");
            return new AcademicPolicyCorpusSnapshot(
                    List.of(SOURCE), List.of(document), live, false, fetchedAt);
        };
        AcademicEmbeddingStore disabledStore = new AcademicEmbeddingStore() {
            @Override
            public boolean isUsable() {
                return false;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                return List.of();
            }
        };
        AcademicPolicyCorpusCache cache = new AcademicPolicyCorpusCache(connector, disabledStore, true);
        cache.loadFastFallbackCorpus();

        var cached = cache.access(false);
        var refreshed = cache.access(true);

        assertThat(cached.liveFetchRequested()).isFalse();
        assertThat(cached.liveFetchAttempted()).isFalse();
        assertThat(cached.liveFetchSucceeded()).isFalse();
        assertThat(cached.servedFromCache()).isTrue();
        assertThat(cached.sourceOrigin()).isEqualTo("SEED");
        assertThat(refreshed.liveFetchRequested()).isTrue();
        assertThat(refreshed.liveFetchAttempted()).isTrue();
        assertThat(refreshed.liveFetchSucceeded()).isTrue();
        assertThat(refreshed.servedFromCache()).isFalse();
        assertThat(refreshed.sourceOrigin()).isEqualTo("LIVE");
        assertThat(liveCalls).hasValue(1);
    }
}
