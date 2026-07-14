package com.ssuai.domain.library.dto;

public record LibrarySeatStatusCompactResponse(
        int floor,
        int totalSeats,
        int physicalTotalSeats,
        int activeSeats,
        int availableSeats,
        int occupiedSeats,
        int awaySeats,
        int reservedSeats,
        int inactiveSeats,
        int outOfServiceSeats,
        int otherSeats
) {

    public static LibrarySeatStatusCompactResponse from(LibrarySeatStatusResponse response) {
        return new LibrarySeatStatusCompactResponse(
                response.floor(),
                response.totalSeats(),
                response.physicalTotalSeats(),
                response.activeSeats(),
                response.availableSeats(),
                response.occupiedSeats(),
                response.awaySeats(),
                response.reservedSeats(),
                response.inactiveSeats(),
                response.outOfServiceSeats(),
                response.otherSeats()
        );
    }
}
