package com.ssuai.domain.saint.dto;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One row of the 학기별 세부 성적 table (ZCMB3W0017 하단 표). Returned
 * by the parser for each term reached via the 이전학기 (WD01F0)
 * button-press iterate.
 *
 * <p>{@code score} is the 0-100 numeric score the page renders in
 * "성적" column. For P/F-graded courses both {@code score} and
 * {@code grade} read "P" or "F"; for letter-graded courses
 * {@code score} is the numeric and {@code grade} is the letter
 * ("A+", "A0", "A-", "B+", …). Kept as strings because of that
 * pass/fail collision.
 *
 * <p>{@code courseCode} = 학수번호 (8-digit numeric, e.g. "21503227")
 * — non-PII reference data from the curriculum catalog.
 *
 * <p>{@code remark} is most often an empty cell on the page; the parser
 * surfaces an empty string in that case rather than {@code null}.
 */
public record CourseGrade(
        String score,
        String grade,
        String courseName,
        String courseCode,
        double credits,
        String professor,
        String remark
) {

    public CourseGrade {
        if (courseName == null) {
            courseName = "";
        }
        if (professor == null) {
            professor = "";
        }
        if (remark == null) {
            remark = "";
        }
    }

    /**
     * Letter-grade value on the common SSU 4.5 scale. P/F and blank grades
     * are not GPA-bearing, so they return null.
     */
    @JsonProperty("gradePoint")
    public Double gradePoint() {
        Double byLetter = gradePointForLetter(grade);
        return byLetter != null ? byLetter : gradePointForScore(score);
    }

    private static Double gradePointForLetter(String rawGrade) {
        if (rawGrade == null || rawGrade.isBlank()) {
            return null;
        }
        String value = rawGrade.trim().toUpperCase(Locale.ROOT).replace('O', '0');
        return switch (value) {
            case "A+" -> 4.5d;
            case "A0" -> 4.3d;
            case "A" -> 4.0d;
            case "B+" -> 3.5d;
            case "B0" -> 3.3d;
            case "B" -> 3.0d;
            case "C+" -> 2.5d;
            case "C0" -> 2.3d;
            case "C" -> 2.0d;
            case "D+" -> 1.5d;
            case "D0" -> 1.3d;
            case "D" -> 1.0d;
            case "F", "F0" -> 0.0d;
            default -> null;
        };
    }

    private static Double gradePointForScore(String rawScore) {
        if (rawScore == null || rawScore.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(rawScore.trim());
            if (value >= 97.0d) return 4.5d;
            if (value >= 94.0d) return 4.3d;
            if (value >= 90.0d) return 4.0d;
            if (value >= 87.0d) return 3.5d;
            if (value >= 84.0d) return 3.3d;
            if (value >= 80.0d) return 3.0d;
            if (value >= 77.0d) return 2.5d;
            if (value >= 74.0d) return 2.3d;
            if (value >= 70.0d) return 2.0d;
            if (value >= 67.0d) return 1.5d;
            if (value >= 64.0d) return 1.3d;
            if (value >= 60.0d) return 1.0d;
            if (value >= 0.0d) return 0.0d;
            return null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
