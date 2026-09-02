package com.ssuai.domain.library.reservation;

/** Stable action-audit type names shared by prepare adapters and crash reconciliation. */
public final class LibraryActionTypes {

    public static final String RESERVATION = "LIBRARY_SEAT_RESERVATION";
    public static final String CANCEL = "LIBRARY_SEAT_CANCEL";
    public static final String SWAP = "LIBRARY_SEAT_SWAP";

    private LibraryActionTypes() {
    }
}
