package com.ssuai.domain.library.dto;

import java.time.Instant;
import java.util.List;

public record LibrarySeatStatusResponse(
        int floor,
        String floorLabel,
        /** Legacy total alias; use {@link #physicalTotalSeats()} for explicit semantics. */
        int totalSeats,
        int physicalTotalSeats,
        int activeSeats,
        int availableSeats,
        int occupiedSeats,
        int awaySeats,
        int reservedSeats,
        int inactiveSeats,
        int outOfServiceSeats,
        int otherSeats,
        Instant fetchedAt,
        List<LibrarySeatZone> zones
) {

    public LibrarySeatStatusResponse {
        zones = zones == null ? List.of() : List.copyOf(zones);
    }

    /** Backward-compatible constructor for older fixtures whose total represented physical seats. */
    public LibrarySeatStatusResponse(
            int floor,
            String floorLabel,
            int totalSeats,
            int availableSeats,
            int occupiedOrReservedSeats,
            int outOfServiceSeats,
            Instant fetchedAt,
            List<LibrarySeatZone> zones) {
        this(
                floor,
                floorLabel,
                totalSeats,
                totalSeats,
                Math.max(0, totalSeats - outOfServiceSeats),
                availableSeats,
                occupiedOrReservedSeats,
                0,
                0,
                outOfServiceSeats,
                outOfServiceSeats,
                Math.max(0, totalSeats - outOfServiceSeats
                        - availableSeats - occupiedOrReservedSeats),
                fetchedAt,
                zones);
    }
}
