package com.ssuai.domain.lms.dto;

import java.util.List;

public record LmsMaterialsResponse(
        List<LmsCourseMaterials> courses,
        long selectedTermId,
        String selectedTermName,
        LmsTermType selectedTermType,
        String selectionReason,
        List<LmsTermType> availableTermTypes) {

    public static LmsMaterialsResponse of(
            List<LmsCourseMaterials> courses, LmsTermSelection selection) {
        return new LmsMaterialsResponse(
                courses, selection.selectedTermId(), selection.selectedTermName(),
                selection.selectedTermType(), selection.selectionReason(),
                selection.availableTermTypes());
    }
}
