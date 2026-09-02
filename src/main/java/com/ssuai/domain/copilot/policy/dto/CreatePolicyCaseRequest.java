package com.ssuai.domain.copilot.policy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePolicyCaseRequest(
        @NotBlank
        @Size(min = 10, max = 1000)
        String question,
        @Size(max = 32)
        String category) {
}
