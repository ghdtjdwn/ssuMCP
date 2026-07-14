package com.ssuai.domain.saint.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GraduationStatus(
        boolean isGraduatable,
        String studentName,
        String department,
        int grade,
        float completedPoints,
        float graduationPoints,
        List<GraduationRequirementItem> requirements
) {

    public GraduationStatus {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    /** Compatibility field {@code department} may contain a college-level label upstream. */
    @JsonProperty("college")
    public String college() {
        return looksLikeCollege(department) ? department : null;
    }

    @JsonProperty("actualDepartment")
    public String actualDepartment() {
        return looksLikeCollege(department) ? null : department;
    }

    /** The graduation screen does not expose the transcript's independent earned-credit sum. */
    @JsonProperty("academicEarnedCredits")
    public Float academicEarnedCredits() {
        return null;
    }

    @JsonProperty("graduationRecognizedCredits")
    public Float graduationRecognizedCredits() {
        return completedPoints;
    }

    @JsonProperty("creditBasis")
    public String creditBasis() {
        return "GRADUATION_REQUIREMENTS_SCREEN";
    }

    private static boolean looksLikeCollege(String value) {
        return value != null && (value.endsWith("대학") || value.endsWith("학부대학"));
    }
}
