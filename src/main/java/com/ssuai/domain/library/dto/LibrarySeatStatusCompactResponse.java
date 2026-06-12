package com.ssuai.domain.library.dto;

public record LibrarySeatStatusCompactResponse(
        int floor,
        int totalSeats,
        int availableSeats,
        int occupiedSeats
) {

    public static LibrarySeatStatusCompactResponse from(LibrarySeatStatusResponse response) {
        return new LibrarySeatStatusCompactResponse(
                response.floor(),
                response.totalSeats(),
                response.availableSeats(),
                Math.max(0, response.totalSeats() - response.availableSeats() - response.outOfServiceSeats())
        );
    }
}
