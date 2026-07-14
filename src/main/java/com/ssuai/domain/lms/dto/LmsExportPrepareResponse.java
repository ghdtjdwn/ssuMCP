package com.ssuai.domain.lms.dto;

import java.util.List;

public record LmsExportPrepareResponse(
    int courseCount, int fileCount, long totalBytes,
    List<LmsCourseMaterials> selected, List<LmsExportExclusion> excluded,
    String message,
    Long actionId,
    String previewVersion,
    Long selectedTermId,
    String selectedTermName,
    LmsTermType selectedTermType,
    String selectionReason,
    List<LmsTermType> availableTermTypes) {

    public LmsExportPrepareResponse(
            int courseCount,
            int fileCount,
            long totalBytes,
            List<LmsCourseMaterials> selected,
            List<LmsExportExclusion> excluded,
            String message) {
        this(courseCount, fileCount, totalBytes, selected, excluded, message,
                null, null, null, null, null, null, List.of());
    }

    public LmsExportPrepareResponse(
            int courseCount,
            int fileCount,
            long totalBytes,
            List<LmsCourseMaterials> selected,
            List<LmsExportExclusion> excluded,
            String message,
            Long actionId,
            String previewVersion) {
        this(courseCount, fileCount, totalBytes, selected, excluded, message,
                actionId, previewVersion, null, null, null, null, List.of());
    }
}
