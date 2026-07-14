package com.ssuai.domain.academic.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.ssuai.domain.academic.dto.AcademicPolicyEvidence;
import com.ssuai.domain.academic.dto.ScholarshipTierEvaluation;

/**
 * Deterministic evaluator for the two rule families whose inputs are explicitly
 * represented by the MCP contract. It never invents a threshold: every boundary
 * used below must be extracted from returned policy evidence.
 */
@Component
public class ScholarshipTierEvaluator {

    private static final Pattern ADMISSION_BOUNDARY = Pattern.compile(
            "(20\\d{2})\\s*(?:학년도)?\\s*(?:이후|이상|부터)[^\\n.]{0,30}(?:입학|입학자|입학생)"
                    + "|(?:입학|입학자|입학생)[^0-9\\n.]{0,30}(20\\d{2})\\s*(?:학년도)?\\s*(?:이후|이상|부터)");
    private static final Pattern TOPIK_THRESHOLD = Pattern.compile(
            "(?i)(?:topik|토픽|한국어능력시험)[^0-9\\n.]{0,24}([1-6])\\s*급"
                    + "|([1-6])\\s*급[^\\n.]{0,24}(?:topik|토픽|한국어능력시험)");
    private static final Pattern GPA_THRESHOLD = Pattern.compile(
            "(?i)(?:gpa|평점평균|평균평점|평점)[^0-9\\n.]{0,24}(\\d(?:\\.\\d+)?)\\s*(?:점)?\\s*(?:이상|>=)");
    private static final Pattern CREDIT_THRESHOLD = Pattern.compile(
            "(?:취득\\s*학점|취득학점|이수\\s*학점|이수학점)[^0-9\\n.]{0,24}(\\d{1,3})\\s*(?:학점)?\\s*(?:이상|>=)");

    public ScholarshipTierEvaluation evaluate(
            String query,
            List<AcademicPolicyEvidence> evidence,
            Double gpa,
            Integer earnedCredits,
            Integer admissionYear,
            Integer topikLevel,
            Boolean internationalStudent) {
        String normalizedQuery = normalize(query);
        List<AcademicPolicyEvidence> safeEvidence = evidence == null ? List.of() : evidence;
        if (mentionsBaekmaQuery(normalizedQuery)) {
            return evaluateBaekma(safeEvidence, gpa, earnedCredits);
        }
        if (Boolean.TRUE.equals(internationalStudent) || mentionsInternationalOrTopikQuery(normalizedQuery)) {
            return evaluateInternational(safeEvidence, admissionYear, topikLevel, internationalStudent);
        }
        if (containsEvidence(safeEvidence, "백마", "baekma")) {
            return evaluateBaekma(safeEvidence, gpa, earnedCredits);
        }
        if (containsEvidence(safeEvidence, "외국인", "유학생", "topik", "토픽")) {
            return evaluateInternational(safeEvidence, admissionYear, topikLevel, internationalStudent);
        }
        return new ScholarshipTierEvaluation(
                "UNSUPPORTED",
                "UNDETERMINED",
                List.of(),
                List.of(),
                List.of("지원되는 외국인/TOPIK 또는 백마성적우수 규칙 식별"),
                safeEvidence,
                safeEvidence.isEmpty() ? 0.0d : 0.2d,
                List.of("구조화 판정은 명시적으로 지원되는 장학 규칙에만 적용됩니다."));
    }

    private ScholarshipTierEvaluation evaluateInternational(
            List<AcademicPolicyEvidence> evidence,
            Integer admissionYear,
            Integer topikLevel,
            Boolean internationalStudent) {
        List<AcademicPolicyEvidence> relevant = relevantEvidence(
                evidence, "외국인", "유학생", "topik", "토픽", "한국어능력시험");
        String text = evidenceText(relevant);
        List<Integer> boundaries = integerValues(ADMISSION_BOUNDARY, text);
        List<Integer> topikThresholds = integerValues(TOPIK_THRESHOLD, text).stream()
                .filter(value -> value >= 1 && value <= 6)
                .sorted()
                .toList();
        List<String> matched = new ArrayList<>();
        List<String> unmet = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<String> caveats = new ArrayList<>();

        if (internationalStudent == null) {
            unknown.add("internationalStudent");
        } else if (internationalStudent) {
            matched.add("internationalStudent=true");
        } else {
            unmet.add("internationalStudent=true");
        }

        String selectedRule = "INTERNATIONAL_TOPIK";
        boolean supportedAdmissionBoundary = true;
        if (boundaries.size() == 1) {
            int boundary = boundaries.getFirst();
            if (admissionYear == null) {
                unknown.add("admissionYear boundary=" + boundary);
            } else if (admissionYear >= boundary) {
                matched.add("admissionYear >= " + boundary);
                selectedRule += "_" + boundary + "_ONWARD";
            } else {
                selectedRule += "_BEFORE_" + boundary;
                unknown.add("admissionYear=" + admissionYear + " 적용 규칙");
                supportedAdmissionBoundary = false;
                caveats.add("근거는 " + boundary + "학년도 이후 입학자 기준만 명확히 식별합니다.");
            }
        } else if (boundaries.isEmpty()) {
            unknown.add("admissionYear boundary");
        } else {
            unknown.add("복수 admissionYear boundaries=" + boundaries);
        }

        String tier = "UNRESOLVED";
        if (!supportedAdmissionBoundary) {
            // A pre-boundary rule is a different policy branch; do not reuse the later rule.
            unknown.add("TOPIK tier for selected admission year");
        } else if (topikThresholds.isEmpty()) {
            unknown.add("TOPIK tier thresholds");
        } else if (topikLevel == null) {
            unknown.add("topikLevel");
        } else {
            int achievedIndex = -1;
            for (int index = 0; index < topikThresholds.size(); index++) {
                int threshold = topikThresholds.get(index);
                if (topikLevel >= threshold) {
                    achievedIndex = index;
                    matched.add("topikLevel >= " + threshold);
                } else {
                    unmet.add("topikLevel >= " + threshold);
                }
            }
            if (achievedIndex < 0) {
                tier = "BELOW_TOPIK_" + topikThresholds.getFirst();
            } else if (achievedIndex == topikThresholds.size() - 1) {
                tier = "TOPIK_" + topikThresholds.get(achievedIndex) + "_PLUS";
            } else {
                tier = "TOPIK_" + topikThresholds.get(achievedIndex)
                        + "_TO_" + (topikThresholds.get(achievedIndex + 1) - 1);
            }
        }

        caveats.add("장학 비율과 추가 성적·등록 요건은 반환 evidence에 명시된 내용만 별도로 확인해야 합니다.");
        return result(selectedRule, tier, matched, unmet, unknown, relevant, caveats);
    }

    private ScholarshipTierEvaluation evaluateBaekma(
            List<AcademicPolicyEvidence> evidence, Double gpa, Integer earnedCredits) {
        List<AcademicPolicyEvidence> relevant = relevantEvidence(evidence, "백마", "성적우수");
        String text = evidenceText(relevant);
        List<Double> gpaThresholds = doubleValues(GPA_THRESHOLD, text);
        List<Integer> creditThresholds = integerValues(CREDIT_THRESHOLD, text);
        List<String> matched = new ArrayList<>();
        List<String> unmet = new ArrayList<>();
        List<String> unknown = new ArrayList<>();

        evaluateMinimum("gpa", gpa, gpaThresholds, matched, unmet, unknown);
        evaluateMinimum("earnedCredits", earnedCredits, creditThresholds, matched, unmet, unknown);

        String tier;
        if (!unmet.isEmpty()) {
            tier = "NOT_QUALIFIED";
        } else if (!unknown.isEmpty()) {
            tier = "UNRESOLVED";
        } else {
            tier = "QUALIFIED";
        }
        return result(
                "BAEKMA_ACADEMIC_EXCELLENCE",
                tier,
                matched,
                unmet,
                unknown,
                relevant,
                List.of("선발 순위, 예산, 신청·등록 상태는 정량 최소요건 충족 여부와 별도입니다."));
    }

    private static void evaluateMinimum(
            String field,
            Number input,
            List<? extends Number> thresholds,
            List<String> matched,
            List<String> unmet,
            List<String> unknown) {
        if (thresholds.size() != 1) {
            unknown.add(thresholds.isEmpty() ? field + " threshold" : "multiple " + field + " thresholds=" + thresholds);
            return;
        }
        Number threshold = thresholds.getFirst();
        if (input == null) {
            unknown.add(field);
        } else if (input.doubleValue() >= threshold.doubleValue()) {
            matched.add(field + " >= " + render(threshold));
        } else {
            unmet.add(field + " >= " + render(threshold));
        }
    }

    private static ScholarshipTierEvaluation result(
            String selectedRule,
            String tier,
            List<String> matched,
            List<String> unmet,
            List<String> unknown,
            List<AcademicPolicyEvidence> evidence,
            List<String> caveats) {
        double confidence = evidence.isEmpty() ? 0.1d : unknown.isEmpty() ? 0.9d : 0.65d;
        if (evidence.stream().anyMatch(AcademicPolicyEvidence::fallbackUsed)) {
            confidence = Math.max(0.1d, confidence - 0.2d);
        }
        if (!evidence.isEmpty() && evidence.stream().noneMatch(AcademicPolicyEvidence::revisionVerified)) {
            confidence = Math.max(0.1d, confidence - 0.1d);
        }
        return new ScholarshipTierEvaluation(
                selectedRule,
                tier,
                List.copyOf(matched),
                List.copyOf(unmet),
                List.copyOf(unknown),
                evidence,
                confidence,
                caveats);
    }

    private static List<AcademicPolicyEvidence> relevantEvidence(
            List<AcademicPolicyEvidence> evidence, String... markers) {
        List<AcademicPolicyEvidence> relevant = evidence.stream()
                .filter(item -> {
                    String text = normalize(item.title() + " " + item.heading() + " " + item.snippet());
                    for (String marker : markers) {
                        if (text.contains(normalize(marker))) {
                            return true;
                        }
                    }
                    return false;
                })
                .toList();
        return relevant.isEmpty() ? evidence : relevant;
    }

    private static boolean mentionsBaekmaQuery(String query) {
        return query.contains("백마") || query.contains("baekma");
    }

    private static boolean mentionsInternationalOrTopikQuery(String query) {
        return query.contains("외국인") || query.contains("유학생") || query.contains("topik") || query.contains("토픽")
                || query.contains("international student");
    }

    private static boolean containsEvidence(List<AcademicPolicyEvidence> evidence, String... terms) {
        return evidence.stream().anyMatch(item -> {
            String text = normalize(item.title() + " " + item.heading() + " " + item.snippet());
            for (String term : terms) {
                if (text.contains(normalize(term))) {
                    return true;
                }
            }
            return false;
        });
    }

    private static String evidenceText(List<AcademicPolicyEvidence> evidence) {
        return String.join("\n", evidence.stream()
                .map(item -> item.title() + " " + item.heading() + " " + item.snippet())
                .toList());
    }

    private static List<Integer> integerValues(Pattern pattern, String text) {
        Set<Integer> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            for (int group = 1; group <= matcher.groupCount(); group++) {
                if (matcher.group(group) != null) {
                    values.add(Integer.parseInt(matcher.group(group)));
                }
            }
        }
        return values.stream().sorted().toList();
    }

    private static List<Double> doubleValues(Pattern pattern, String text) {
        Set<Double> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(Double.parseDouble(matcher.group(1)));
        }
        return values.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private static String render(Number value) {
        return value.doubleValue() == Math.rint(value.doubleValue())
                ? Integer.toString(value.intValue())
                : value.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
