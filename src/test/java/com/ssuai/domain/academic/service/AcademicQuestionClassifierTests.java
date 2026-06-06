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
}
