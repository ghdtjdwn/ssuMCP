package com.ssuai.domain.library.dto;

import java.util.List;

public record LibrarySeatZone(
        String label,
        /** Legacy total alias; use {@link #physicalTotalSeats()}. */
        int total,
        int physicalTotalSeats,
        int activeSeats,
        int available,
        int occupiedSeats,
        int awaySeats,
        int reservedSeats,
        int inactiveSeats,
        int outOfServiceSeats,
        int otherSeats,
        List<String> seatIds,
        List<LibrarySeatItem> seats
) {

    public LibrarySeatZone {
        seatIds = seatIds == null ? List.of() : List.copyOf(seatIds);
        seats = seats == null ? List.of() : List.copyOf(seats);
    }

    public LibrarySeatZone(
            String label,
            int total,
            int available,
            List<String> seatIds,
            List<LibrarySeatItem> seats) {
        this(
                label,
                total,
                total,
                total,
                available,
                Math.max(0, total - available),
                0,
                0,
                0,
                0,
                0,
                seatIds,
                seats);
    }
}
