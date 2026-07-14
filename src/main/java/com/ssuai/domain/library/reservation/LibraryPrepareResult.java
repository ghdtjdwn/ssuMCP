package com.ssuai.domain.library.reservation;

/**
 * Structured payload returned by prepare_reserve/cancel/swap_library_seat.
 * actionId uniquely identifies the pending action created in ActionService;
 * ssuAgent uses it to detect and trigger the HITL interrupt gate.
 */
public record LibraryPrepareResult(Long actionId, String code, String message) {

    public LibraryPrepareResult(long actionId, String message) {
        this(actionId, "OK", message);
    }

    public static LibraryPrepareResult noCurrentSeat(String message) {
        return new LibraryPrepareResult(null, "NO_CURRENT_SEAT", message);
    }

    public static LibraryPrepareResult conflict(String message) {
        return new LibraryPrepareResult(null, "ACTION_CONFLICT", message);
    }
}
