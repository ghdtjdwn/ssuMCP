package com.ssuai.domain.lms.connector;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Request-scoped availability budget for optional LMS material metadata enrichment.
 *
 * <p>Course and export listing services fetch several courses in one user request. Once the
 * Commons metadata provider fails, exact file sizes are no longer worth retrying for every
 * remaining course. Core material listing continues with unknown sizes.
 */
public final class LmsMaterialEnrichmentBudget {

    private final AtomicBoolean available = new AtomicBoolean(true);

    public boolean isAvailable() {
        return available.get();
    }

    public void disable() {
        available.set(false);
    }
}
