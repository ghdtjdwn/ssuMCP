package com.ssuai.domain.meal.dto;

import java.time.LocalDate;
import java.util.List;

public record WeeklyMealResponse(
        LocalDate startDate,
        LocalDate endDate,
        List<MealResponse> days,
        List<MealAnnouncement> operatingHours,
        List<MealAnnouncement> announcements
) {

    public WeeklyMealResponse {
        days = days == null ? List.of() : List.copyOf(days);
        operatingHours = operatingHours == null ? List.of() : List.copyOf(operatingHours);
        announcements = announcements == null ? List.of() : List.copyOf(announcements);
    }

    /** Preserves the original weekly-menu contract for existing connectors and clients. */
    public WeeklyMealResponse(LocalDate startDate, LocalDate endDate, List<MealResponse> days) {
        this(startDate, endDate, days, List.of(), List.of());
    }
}
