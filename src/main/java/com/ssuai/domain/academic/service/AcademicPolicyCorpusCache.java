package com.ssuai.domain.academic.service;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.ssuai.domain.academic.connector.AcademicPolicyConnector;
import com.ssuai.domain.academic.dto.AcademicPolicyCorpusSnapshot;

@Service
public class AcademicPolicyCorpusCache {

    private static final Logger log = LoggerFactory.getLogger(AcademicPolicyCorpusCache.class);

    private final AcademicPolicyConnector connector;
    private final AtomicReference<AcademicPolicyCorpusSnapshot> current = new AtomicReference<>();
    private final boolean refreshEnabled;

    public AcademicPolicyCorpusCache(
            AcademicPolicyConnector connector,
            @Value("${ssuai.academic-policy.refresh-enabled:true}") boolean refreshEnabled) {
        this.connector = connector;
        this.refreshEnabled = refreshEnabled;
    }

    @PostConstruct
    void loadFastFallbackCorpus() {
        current.set(connector.loadCorpus(false));
    }

    @EventListener(ApplicationReadyEvent.class)
    void refreshAfterStartup() {
        refreshFromOfficialSources();
    }

    @Scheduled(
            initialDelayString = "${ssuai.academic-policy.initial-delay-ms:30000}",
            fixedDelayString = "${ssuai.academic-policy.refresh-interval-ms:21600000}")
    void scheduledRefresh() {
        refreshFromOfficialSources();
    }

    public AcademicPolicyCorpusSnapshot snapshot(boolean forceOfficialRefresh) {
        if (forceOfficialRefresh) {
            return refreshFromOfficialSources();
        }
        AcademicPolicyCorpusSnapshot snapshot = current.get();
        if (snapshot != null) {
            return snapshot;
        }
        return refreshFromOfficialSources();
    }

    public AcademicPolicyCorpusSnapshot refreshFromOfficialSources() {
        AcademicPolicyCorpusSnapshot existing = current.get();
        if (!refreshEnabled) {
            return existing != null ? existing : connector.loadCorpus(false);
        }
        try {
            AcademicPolicyCorpusSnapshot refreshed = connector.loadCorpus(true);
            current.set(refreshed);
            log.debug(
                    "academic-policy corpus refreshed sources={} documents={} fallbackUsed={}",
                    refreshed.sources().size(),
                    refreshed.documents().size(),
                    refreshed.fallbackUsed());
            return refreshed;
        } catch (RuntimeException exception) {
            log.warn("academic-policy corpus refresh failed; using previous snapshot", exception);
            if (existing != null) {
                return existing;
            }
            AcademicPolicyCorpusSnapshot fallback = connector.loadCorpus(false);
            current.set(fallback);
            return fallback;
        }
    }
}
