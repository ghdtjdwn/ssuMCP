package com.ssuai.domain.library.dto;

import java.time.Instant;
import java.util.List;

public record LibraryRoomAvailableSeatsResponse(
        int roomId,
        String roomName,
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
        List<PyxisSeatInfo> seats,
        int returnedSeats,
        int offset,
        Integer limit,
        boolean compact,
        boolean truncated,
        boolean hasMore
) {
    public LibraryRoomAvailableSeatsResponse {
        seats = seats == null ? List.of() : List.copyOf(seats);
    }

    public LibraryRoomAvailableSeatsResponse(
            int roomId,
            String roomName,
            int totalSeats,
            int availableSeats,
            int occupiedSeats,
            int awaySeats,
            int inactiveSeats,
            Instant fetchedAt,
            List<PyxisSeatInfo> seats) {
        this(
                roomId,
                roomName,
                totalSeats,
                totalSeats,
                Math.max(0, totalSeats - inactiveSeats),
                availableSeats,
                occupiedSeats,
                awaySeats,
                0,
                inactiveSeats,
                inactiveSeats,
                Math.max(0, totalSeats - availableSeats - occupiedSeats - awaySeats - inactiveSeats),
                fetchedAt,
                seats,
                seats == null ? 0 : seats.size(),
                0,
                null,
                false,
                false,
                false);
    }
}
