package com.ssuai.domain.library.dto;

import java.util.List;

public record LibraryAllAvailableSeatsRoomSummary(
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
        List<Integer> availableExternalSeatIds,
        List<String> availableLabels,
        int returnedAvailableSeats,
        int offset,
        Integer limit,
        boolean truncated,
        boolean hasMore
) {
    public LibraryAllAvailableSeatsRoomSummary {
        availableExternalSeatIds = availableExternalSeatIds == null
                ? List.of() : List.copyOf(availableExternalSeatIds);
        availableLabels = availableLabels == null ? List.of() : List.copyOf(availableLabels);
    }

    public LibraryAllAvailableSeatsRoomSummary(
            int roomId,
            String roomName,
            int totalSeats,
            int availableSeats,
            int awaySeats,
            List<Integer> availableExternalSeatIds,
            List<String> availableLabels) {
        this(
                roomId,
                roomName,
                totalSeats,
                totalSeats,
                Math.max(0, totalSeats),
                availableSeats,
                Math.max(0, totalSeats - availableSeats - awaySeats),
                awaySeats,
                0,
                0,
                0,
                0,
                availableExternalSeatIds,
                availableLabels,
                availableExternalSeatIds == null ? 0 : availableExternalSeatIds.size(),
                0,
                null,
                false,
                false);
    }
}
