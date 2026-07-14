package com.ssuai.domain.academic.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.ssuai.domain.academic.dto.AcademicQuestionClassificationResponse;

@Component
public class AcademicQuestionClassifier {

    public AcademicQuestionClassificationResponse classify(String query) {
        String normalized = normalize(query);
        Set<String> categories = new LinkedHashSet<>();
        List<String> tools = new ArrayList<>();

        int graduationScore = score(
                normalized,
                "졸업", "졸업요건", "졸업 조건", "전공", "교양", "다전공", "복수전공", "부전공", "채플", "학점",
                "graduation", "graduate", "degree requirement", "major requirement", "major credits",
                "general education", "double major", "minor", "chapel", "credits required");
        int scholarshipScore = score(
                normalized,
                "장학", "장학금", "백마", "성적우수", "국가장학", "등록금", "토픽", "한국어능력시험", "소득분위",
                "scholarship", "financial aid", "baekma", "merit award", "merit scholarship",
                "tuition award", "topik", "international student scholarship");
        int calendarScore = score(
                normalized,
                "일정", "언제", "기간", "수강신청", "개강", "종강", "시험", "학사일정",
                "academic calendar", "schedule", "when", "registration period", "course registration",
                "semester starts", "semester ends", "midterm", "final exam");

        String intent = "GENERAL_ACADEMIC_POLICY";
        int max = Math.max(graduationScore, Math.max(scholarshipScore, calendarScore));
        if (max == graduationScore && graduationScore > 0) {
            intent = "GRADUATION_POLICY";
            categories.add("graduation");
            tools.add("evaluate_graduation_with_policy");
            tools.add("check_graduation_requirements");
        } else if (max == scholarshipScore && scholarshipScore > 0) {
            intent = "SCHOLARSHIP_POLICY";
            categories.add("scholarship");
            tools.add("check_scholarship_policy");
            tools.add("get_my_scholarships");
        } else if (max == calendarScore && calendarScore > 0) {
            intent = "ACADEMIC_CALENDAR";
            categories.add("calendar");
            tools.add("find_academic_calendar_events");
            tools.add("get_academic_calendar");
        }
        if (categories.isEmpty()) {
            categories.add("academic");
            tools.add("get_academic_policy_brief");
            tools.add("search_academic_policy_sources");
        }
        tools.add("search_academic_policy_sources");

        double confidence = max == 0 ? 0.35d : Math.min(0.95d, 0.55d + (max * 0.1d));
        return new AcademicQuestionClassificationResponse(
                query,
                intent,
                confidence,
                List.copyOf(categories),
                tools.stream().distinct().toList(),
                "키워드 기반 라우팅입니다. 최종 답변은 policy evidence와 기존 개인 데이터 도구를 함께 사용해야 합니다.");
    }

    private static int score(String text, String... terms) {
        int score = 0;
        for (String term : terms) {
            if (text.contains(term.toLowerCase(Locale.ROOT))) {
                score++;
            }
        }
        return score;
    }

    private static String normalize(String query) {
        return query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
    }
}
