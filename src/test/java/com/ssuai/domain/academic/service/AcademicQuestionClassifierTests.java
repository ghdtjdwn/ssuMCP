package com.ssuai.domain.academic.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AcademicQuestionClassifierTests {

    private final AcademicQuestionClassifier classifier = new AcademicQuestionClassifier();

    @Test
    void classifiesGraduationQuestion() {
        var response = classifier.classify("복수전공 졸업 학점 조건 알려줘");

        assertThat(response.intent()).isEqualTo("GRADUATION_POLICY");
        assertThat(response.categories()).contains("graduation");
        assertThat(response.recommendedTools()).contains("evaluate_graduation_with_policy");
    }

    @Test
    void classifiesScholarshipQuestion() {
        var response = classifier.classify("백마성적우수장학금 취득학점 기준이 뭐야");

        assertThat(response.intent()).isEqualTo("SCHOLARSHIP_POLICY");
        assertThat(response.categories()).contains("scholarship");
        assertThat(response.recommendedTools()).contains("check_scholarship_policy");
    }

    @Test
    void koreanAndEnglishGraduationIntentsHaveParity() {
        var korean = classifier.classify("복수전공 졸업 요건과 전공 학점 알려줘");
        var english = classifier.classify("What are the double major graduation requirements and major credits?");

        assertThat(english.intent()).isEqualTo(korean.intent()).isEqualTo("GRADUATION_POLICY");
        assertThat(english.categories()).isEqualTo(korean.categories());
        assertThat(english.recommendedTools()).contains("evaluate_graduation_with_policy");
    }

    @Test
    void koreanAndEnglishScholarshipIntentsHaveParity() {
        var korean = classifier.classify("외국인 유학생 TOPIK 장학금 기준");
        var english = classifier.classify("International student TOPIK scholarship eligibility");

        assertThat(english.intent()).isEqualTo(korean.intent()).isEqualTo("SCHOLARSHIP_POLICY");
        assertThat(english.categories()).isEqualTo(korean.categories());
        assertThat(english.recommendedTools()).contains("check_scholarship_policy");
    }

    @Test
    void koreanAndEnglishCalendarIntentsHaveParity() {
        var korean = classifier.classify("수강신청 기간이 언제야");
        var english = classifier.classify("When is the course registration period?");

        assertThat(english.intent()).isEqualTo(korean.intent()).isEqualTo("ACADEMIC_CALENDAR");
        assertThat(english.categories()).isEqualTo(korean.categories());
    }
}
