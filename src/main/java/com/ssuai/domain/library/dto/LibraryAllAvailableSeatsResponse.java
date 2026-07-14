package com.ssuai.domain.library.dto;

import java.time.Instant;
import java.util.List;

public record LibraryAllAvailableSeatsResponse(
        int totalAvailableSeats,
        int totalAwaySeats,
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
        List<LibraryAllAvailableSeatsRoomSummary> rooms,
        boolean compact,
        int offset,
        Integer limit,
        boolean truncated
) {
    public LibraryAllAvailableSeatsResponse {
        rooms = rooms == null ? List.of() : List.copyOf(rooms);
    }

    public LibraryAllAvailableSeatsResponse(
            int totalAvailableSeats,
            int totalAwaySeats,
            Instant fetchedAt,
            List<LibraryAllAvailableSeatsRoomSummary> rooms) {
        this(
                totalAvailableSeats,
                totalAwaySeats,
                0,
                0,
                totalAvailableSeats,
                0,
                totalAwaySeats,
                0,
                0,
                0,
                0,
                fetchedAt,
                rooms,
                false,
                0,
                null,
                false);
    }
}
