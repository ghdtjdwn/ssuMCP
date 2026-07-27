package com.ssuai.domain.library.reservation;

import java.util.Optional;

public interface LibraryReservationConnector {

    /**
     * Returns empty only when the upstream authoritatively reports no current charge.
     * Transport, HTTP, authentication, and parse failures must be propagated so callers never
     * mistake an unknown read result for proof that a write completed.
     */
    Optional<LibraryReservationResult> getCurrentCharge(String pyxisAuthToken);

    LibraryReservationResult reserve(String pyxisAuthToken, LibraryReservationRequest request);

    void discharge(String pyxisAuthToken, long chargeId);
}
