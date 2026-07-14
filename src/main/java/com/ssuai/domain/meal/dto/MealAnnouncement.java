package com.ssuai.domain.meal.dto;

/** A source-wide notice published alongside a meal timetable. */
public record MealAnnouncement(
        MealAnnouncementType type,
        String message
) {
}
