package com.ssuai.domain.academic.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.ssuai.domain.academic.connector.AcademicPolicyConnector;
import com.ssuai.domain.academic.dto.AcademicPolicyCorpusSnapshot;
import com.ssuai.domain.academic.dto.AcademicPolicyDocument;
import com.ssuai.domain.academic.embedding.AcademicEmbeddingStore;
import com.ssuai.domain.academic.embedding.AcademicTextChunker;
import com.ssuai.domain.academic.embedding.EmbeddedChunk;
import com.ssuai.domain.academic.embedding.EmbeddedCorpus;

@Service
public class AcademicPolicyCorpusCache {

    private static final Logger log = LoggerFactory.getLogger(AcademicPolicyCorpusCache.class);

    private final AcademicPolicyConnector connector;
    private final AcademicEmbeddingStore embeddingStore;
    private final AtomicReference<EmbeddedCorpus> current = new AtomicReference<>();
    private final AtomicBoolean backgroundRefreshRunning = new AtomicBoolean(false);
    private final boolean refreshEnabled;

    public AcademicPolicyCorpusCache(
            AcademicPolicyConnector connector,
            AcademicEmbeddingStore embeddingStore,
            @org.springframework.beans.factory.annotation.Value("${ssuai.academic-policy.refresh-enabled:true}")
            boolean refreshEnabled) {
        this.connector = connector;
        this.embeddingStore = embeddingStore;
        this.refreshEnabled = refreshEnabled;
    }

    @PostConstruct
    void loadFastFallbackCorpus() {
        // Lexical-only on the bean-init path: no embedding network calls during
        // startup, so a slow or rate-limited embedding API can never delay or
        // crash boot. The post-startup refresh below embeds with pacing.
        current.set(EmbeddedCorpus.lexicalOnly(connector.loadCorpus(false)));
    }

    @EventListener(ApplicationReadyEvent.class)
    void refreshAfterStartup() {
        refreshInBackground();
    }

    @Scheduled(
            initialDelayString = "${ssuai.academic-policy.initial-delay-ms:30000}",
            fixedDelayString = "${ssuai.academic-policy.refresh-interval-ms:21600000}")
    void scheduledRefresh() {
        refreshInBackground();
    }

    /**
     * Runs the corpus refresh on a background virtual thread, at most one at a
     * time. Two reasons: (1) a paced embedding refresh can take minutes and must
     * not block the shared scheduling-1 thread (library session cleanup runs
     * there every minute), and (2) the startup refresh and the first scheduled
     * refresh (initial delay 30s) used to overlap, doubling request rate against
     * the free-tier embedding quota and 429-ing each other (prod 2026-06-11).
     */
    private void refreshInBackground() {
        if (!backgroundRefreshRunning.compareAndSet(false, true)) {
            log.debug("academic-policy refresh already running; skipping");
            return;
        }
        Thread.ofVirtual().name("academic-corpus-refresh").start(() -> {
            try {
                refreshFromOfficialSources();
            } finally {
                backgroundRefreshRunning.set(false);
            }
        });
    }

    /** Backward-compatible accessor: the lexical search path only needs the snapshot. */
    public AcademicPolicyCorpusSnapshot snapshot(boolean forceOfficialRefresh) {
        return access(forceOfficialRefresh).corpus().snapshot();
    }

    /** Snapshot + per-chunk embeddings, read atomically so a live refresh cannot split them. */
    public EmbeddedCorpus embeddedCorpus(boolean forceOfficialRefresh) {
        return access(forceOfficialRefresh).corpus();
    }

    /**
     * Returns the corpus together with request-level provenance. This deliberately
     * distinguishes an attempted refresh from a cached corpus whose snapshot may
     * itself have been live-fetched by an earlier request.
     */
    public CorpusAccess access(boolean forceOfficialRefresh) {
        if (forceOfficialRefresh) {
            return refreshAccess();
        }
        EmbeddedCorpus corpus = current.get();
        if (corpus != null) {
            return CorpusAccess.cached(corpus, false);
        }
        EmbeddedCorpus loaded = enrich(connector.loadCorpus(false));
        current.compareAndSet(null, loaded);
        return CorpusAccess.loaded(loaded, false, false);
    }

    public EmbeddedCorpus refreshFromOfficialSources() {
        return refreshAccess().corpus();
    }

    private CorpusAccess refreshAccess() {
        EmbeddedCorpus existing = current.get();
        if (!refreshEnabled) {
            if (existing != null) {
                return CorpusAccess.cached(existing, true);
            }
            EmbeddedCorpus loaded = enrich(connector.loadCorpus(false));
            current.set(loaded);
            return CorpusAccess.loaded(loaded, true, false);
        }
        try {
            EmbeddedCorpus refreshed = enrich(connector.loadCorpus(true));
            current.set(refreshed);
            log.info(
                    "academic-policy corpus refreshed sources={} documents={} fallbackUsed={} embeddingActive={} chunks={}",
                    refreshed.snapshot().sources().size(),
                    refreshed.snapshot().documents().size(),
                    refreshed.snapshot().fallbackUsed(),
                    refreshed.embeddingActive(),
                    refreshed.chunks().size());
            return CorpusAccess.loaded(refreshed, true, true);
        } catch (RuntimeException exception) {
            log.warn("academic-policy corpus refresh failed; using previous snapshot", exception);
            if (existing != null) {
                return CorpusAccess.failedRefresh(existing);
            }
            EmbeddedCorpus fallback = enrich(connector.loadCorpus(false));
            current.set(fallback);
            return CorpusAccess.failedRefresh(fallback, false);
        }
    }

    /**
     * Chunks each document and embeds the chunks. On any embedding failure (disabled,
     * no key, upstream error) returns a lexical-only corpus so search still works.
     */
    private EmbeddedCorpus enrich(AcademicPolicyCorpusSnapshot snapshot) {
        if (!embeddingStore.isUsable()) {
            return EmbeddedCorpus.lexicalOnly(snapshot);
        }
        // Enrichment runs inside @PostConstruct on the startup path: any escaping
        // exception fails the whole context and crash-loops the pod (observed in
        // prod 2026-06-11, TROUBLESHOOTING). Embeddings are an optional layer —
        // every failure here must degrade to the lexical-only corpus.
        try {
            List<String> chunkTexts = new ArrayList<>();
            List<EmbeddedChunk> pending = new ArrayList<>();
            for (AcademicPolicyDocument document : snapshot.documents()) {
                List<String> chunks = AcademicTextChunker.chunk(document.text());
                for (int index = 0; index < chunks.size(); index++) {
                    chunkTexts.add(chunks.get(index));
                    pending.add(new EmbeddedChunk(document.source(), index, chunks.get(index), null));
                }
            }
            if (chunkTexts.isEmpty()) {
                return EmbeddedCorpus.lexicalOnly(snapshot);
            }
            List<float[]> vectors = embeddingStore.embed(chunkTexts);
            if (vectors.size() != chunkTexts.size()) {
                log.warn("academic-policy embedding incomplete ({}/{}); using lexical-only",
                        vectors.size(), chunkTexts.size());
                return EmbeddedCorpus.lexicalOnly(snapshot);
            }
            List<EmbeddedChunk> embedded = new ArrayList<>(pending.size());
            for (int i = 0; i < pending.size(); i++) {
                EmbeddedChunk base = pending.get(i);
                embedded.add(new EmbeddedChunk(base.source(), base.chunkIndex(), base.text(), vectors.get(i)));
            }
            return new EmbeddedCorpus(snapshot, embedded, true);
        } catch (RuntimeException exception) {
            log.warn("academic-policy embedding enrichment failed ({}); using lexical-only",
                    exception.getClass().getSimpleName());
            return EmbeddedCorpus.lexicalOnly(snapshot);
        }
    }

    public record CorpusAccess(
            EmbeddedCorpus corpus,
            boolean liveFetchRequested,
            boolean liveFetchAttempted,
            boolean liveFetchSucceeded,
            boolean servedFromCache,
            String sourceOrigin) {

        private static CorpusAccess cached(EmbeddedCorpus corpus, boolean requested) {
            return new CorpusAccess(corpus, requested, false, false, true, sourceOrigin(corpus));
        }

        private static CorpusAccess loaded(EmbeddedCorpus corpus, boolean requested, boolean attempted) {
            return new CorpusAccess(
                    corpus,
                    requested,
                    attempted,
                    attempted && hasLiveDocument(corpus),
                    false,
                    sourceOrigin(corpus));
        }

        private static CorpusAccess failedRefresh(EmbeddedCorpus corpus) {
            return failedRefresh(corpus, true);
        }

        private static CorpusAccess failedRefresh(EmbeddedCorpus corpus, boolean servedFromCache) {
            return new CorpusAccess(corpus, true, true, false, servedFromCache, sourceOrigin(corpus));
        }

        private static boolean hasLiveDocument(EmbeddedCorpus corpus) {
            return corpus.snapshot().documents().stream().anyMatch(AcademicPolicyDocument::live);
        }

        private static String sourceOrigin(EmbeddedCorpus corpus) {
            long liveDocuments = corpus.snapshot().documents().stream()
                    .filter(AcademicPolicyDocument::live)
                    .count();
            if (liveDocuments == 0) {
                return "SEED";
            }
            return liveDocuments == corpus.snapshot().documents().size() ? "LIVE" : "MIXED";
        }
    }
}
