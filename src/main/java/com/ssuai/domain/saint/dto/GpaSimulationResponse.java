package com.ssuai.domain.saint.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GpaSimulationResponse(
        double currentGpa,
        double currentGpaCredits,
        double currentGpaSum,
        double plannedCredits,
        Double plannedGradePointAverage,
        Double projectedGpa,
        Double targetGpa,
        Double requiredGradePointAverage,
        Boolean achievable,
        double maxGradePoint,
        double maxAchievableGpa
) {
    @JsonProperty("gpaBasis")
    public String gpaBasis() {
        return "ACADEMIC_RECORD_GPA";
    }

    @JsonProperty("pfCreditsExcludedFromDenominator")
    public boolean pfCreditsExcludedFromDenominator() {
        return true;
    }
}
