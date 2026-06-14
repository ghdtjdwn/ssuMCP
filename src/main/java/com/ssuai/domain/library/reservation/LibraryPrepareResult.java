package com.ssuai.domain.library.reservation;

/**
 * Structured payload returned by prepare_reserve/cancel/swap_library_seat.
 * actionId uniquely identifies the pending action created in ActionService;
 * ssuAgent uses it to detect and trigger the HITL interrupt gate.
 */
public record LibraryPrepareResult(long actionId, String message) {}
