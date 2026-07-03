package com.ssuai.domain.library.recommendation;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Observability for the seat-recommendation path (TROUBLESHOOTING 2026-07-02
 * follow-up): the contract-drift incident produced a UI that silently rendered
 * "no seats" forever, and no signal separated <em>legitimately empty</em> from
 * <em>parsing/contract drift produced empty</em>. This counter makes the empty
 * rate visible per availability source:
 *
 * <ul>
 *   <li>{@code result=ok|empty} + {@code source=live_per_seat} — live scan
 *       worked; an occasional {@code empty} here is a genuinely full floor.</li>
 *   <li>{@code result=empty} + {@code source=no_seats_found} — the Pyxis room
 *       scan yielded zero seat items; a sustained rate is the upstream/scraper
 *       drift signature and should alert.</li>
 *   <li>{@code result=error} — the upstream call threw; already visible via
 *       logs/traces but tagged here so one panel covers the whole path.</li>
 * </ul>
 */
@Component
public class LibrarySeatRecommendationMetrics {

    private static final String METRIC = "library.recommendation";

    private final MeterRegistry meterRegistry;

    public LibrarySeatRecommendationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void countResult(String availabilitySource, boolean empty) {
        meterRegistry.counter(METRIC,
                        "result", empty ? "empty" : "ok",
                        "source", availabilitySource)
                .increment();
    }

    public void countError() {
        meterRegistry.counter(METRIC, "result", "error", "source", "none").increment();
    }
}
