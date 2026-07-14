package com.ssuai.domain.saint.dto;

/**
 * One weekly meeting slot for a course.
 */
public record MeetingSlot(
        int dayOfWeek,
        String dayLabel,
        Integer period,
        String timeRange,
        String room
) {
}
