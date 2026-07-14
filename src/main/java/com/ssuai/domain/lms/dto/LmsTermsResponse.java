package com.ssuai.domain.lms.dto;

import java.util.List;

public record LmsTermsResponse(
        List<LmsTermItem> terms,
        long selectedTermId,
        String selectedTermName,
        LmsTermType selectedTermType,
        String selectionReason,
        List<LmsTermType> availableTermTypes) {

    public static LmsTermsResponse from(List<LmsTermItem> terms, LmsTermSelection selection) {
        return new LmsTermsResponse(
                terms, selection.selectedTermId(), selection.selectedTermName(),
                selection.selectedTermType(), selection.selectionReason(),
                selection.availableTermTypes());
    }
}
