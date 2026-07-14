package com.ssuai.domain.lms.dto;

import java.util.List;

public record LmsTermSelection(
        long selectedTermId,
        String selectedTermName,
        LmsTermType selectedTermType,
        String selectionReason,
        List<LmsTermType> availableTermTypes) {

    public LmsTermSelection {
        availableTermTypes = availableTermTypes == null
                ? List.of() : List.copyOf(availableTermTypes);
    }
}
