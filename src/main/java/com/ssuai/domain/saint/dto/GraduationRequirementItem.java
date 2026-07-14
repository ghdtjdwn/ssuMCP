package com.ssuai.domain.saint.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GraduationRequirementItem(
        String name,
        String category,
        Float required,
        Float completed,
        Float remaining,
        boolean satisfied
) {

    public GraduationRequirementItem {
        if (required != null && completed != null
                && required == 0.0f && completed == 0.0f) {
            required = null;
        }
        if (required == null) {
            completed = null;
            remaining = null;
        } else {
            completed = completed == null ? 0.0f : completed;
            remaining = Math.max(0.0f, required - completed);
        }
    }

    /**
     * Completed minus required. Negative means deficient, positive means
     * over-completed. Use {@code remaining} for a user-facing deficit value.
     */
    @JsonProperty("difference")
    public Float difference() {
        return required == null || completed == null ? null : completed - required;
    }

    @JsonProperty("creditBased")
    public boolean creditBased() {
        return required != null;
    }

    @JsonProperty("requirementType")
    public String requirementType() {
        return creditBased() ? "CREDIT" : "GATE";
    }

    @JsonProperty("gateStatus")
    public String gateStatus() {
        return creditBased() ? null : (satisfied ? "SATISFIED" : "UNSATISFIED");
    }

    @JsonProperty("requiredCredits")
    public Float requiredCredits() {
        return required;
    }

    @JsonProperty("completedCredits")
    public Float completedCredits() {
        return completed;
    }

    @JsonProperty("remainingCredits")
    public Float remainingCredits() {
        return remaining;
    }
}
