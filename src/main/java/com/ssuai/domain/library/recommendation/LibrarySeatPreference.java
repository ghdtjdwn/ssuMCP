package com.ssuai.domain.library.recommendation;

public record LibrarySeatPreference(
        Boolean window,
        Boolean outlet,
        Boolean standing,
        Boolean edge,
        Boolean quiet,
        Boolean nearEntrance
) {

    public boolean hasAnyPreference() {
        return window != null
                || outlet != null
                || standing != null
                || edge != null
                || quiet != null
                || nearEntrance != null;
    }

    public int requestedCount() {
        int count = 0;
        count += window == null ? 0 : 1;
        count += outlet == null ? 0 : 1;
        count += standing == null ? 0 : 1;
        count += edge == null ? 0 : 1;
        count += quiet == null ? 0 : 1;
        count += nearEntrance == null ? 0 : 1;
        return count;
    }
}
