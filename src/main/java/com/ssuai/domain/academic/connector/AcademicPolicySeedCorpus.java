package com.ssuai.domain.academic.connector;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.ssuai.domain.academic.dto.AcademicPolicyCorpusSnapshot;
import com.ssuai.domain.academic.dto.AcademicPolicyDocument;
import com.ssuai.domain.academic.dto.AcademicPolicySource;

final class AcademicPolicySeedCorpus {

    private static final LocalDate VERIFIED_AT = LocalDate.of(2026, 6, 6);

    private AcademicPolicySeedCorpus() {
    }

    static List<AcademicPolicySource> sources() {
        return List.of(
                new AcademicPolicySource(
                        "undergraduate-bylaw",
                        "학칙시행세칙(학사과정)",
                        "graduation",
                        "rule",
                        "https://rule.ssu.ac.kr/lmxsrv/law/lawDetail.do?SEQ=99",
                        "https://rule.ssu.ac.kr/lmxsrv/law/lawFullContent.do?SEQ=99",
                        "dynamic-current",
                        null,
                        VERIFIED_AT,
                        true,
                        "LIVE_SOURCE",
                        "학사과정 졸업, 이수, 다전공, 학적 규정 원문"),
                new AcademicPolicySource(
                        "scholarship-regulation",
                        "장학규정",
                        "scholarship",
                        "rule",
                        "https://rule.ssu.ac.kr/lmxsrv/law/lawDetail.do?SEQ=233",
                        "https://rule.ssu.ac.kr/lmxsrv/law/lawFullContent.do?SEQ=233",
                        "dynamic-current",
                        null,
                        VERIFIED_AT,
                        true,
                        "LIVE_SOURCE",
                        "교내 장학 제도의 상위 규정"),
                new AcademicPolicySource(
                        "scholarship-payment-rule",
                        "장학금지급내규",
                        "scholarship",
                        "rule",
                        "https://rule.ssu.ac.kr/lmxsrv/law/lawDetail.do?SEQ=234",
                        "https://rule.ssu.ac.kr/lmxsrv/law/lawFullContent.do?SEQ=234",
                        "dynamic-current",
                        null,
                        VERIFIED_AT,
                        true,
                        "LIVE_SOURCE",
                        "장학금별 지급 기준과 제한 사항"),
                new AcademicPolicySource(
                        "undergraduate-scholarship-guide",
                        "교내장학금 안내",
                        "scholarship",
                        "official-page",
                        "https://ssu.ac.kr/%ED%95%99%EC%82%AC/%ED%95%99%EC%82%AC%EC%A0%95%EB%B3%B4/%EC%9E%A5%ED%95%99/%EA%B5%90%EB%82%B4%EC%9E%A5%ED%95%99%EA%B8%88/",
                        "https://ssu.ac.kr/%ED%95%99%EC%82%AC/%ED%95%99%EC%82%AC%EC%A0%95%EB%B3%B4/%EC%9E%A5%ED%95%99/%EA%B5%90%EB%82%B4%EC%9E%A5%ED%95%99%EA%B8%88/",
                        "official-page",
                        null,
                        VERIFIED_AT,
                        true,
                        "LIVE_SOURCE",
                        "교내 장학금 종류, 신청 절차, 성적 산정, 중복 수혜 안내"),
                new AcademicPolicySource(
                        "international-scholarship-guide",
                        "외국인 유학생 장학금 안내",
                        "scholarship",
                        "official-page",
                        "https://ssu.ac.kr/%ED%95%99%EC%82%AC/%ED%95%99%EC%82%AC%EC%A0%95%EB%B3%B4/%EC%9E%A5%ED%95%99/%EC%99%B8%EA%B5%AD%EC%9D%B8-%EC%9C%A0%ED%95%99%EC%83%9D-%EC%9E%A5%ED%95%99%EA%B8%88/",
                        "https://ssu.ac.kr/%ED%95%99%EC%82%AC/%ED%95%99%EC%82%AC%EC%A0%95%EB%B3%B4/%EC%9E%A5%ED%95%99/%EC%99%B8%EA%B5%AD%EC%9D%B8-%EC%9C%A0%ED%95%99%EC%83%9D-%EC%9E%A5%ED%95%99%EA%B8%88/",
                        "official-page",
                        "2024-12-27",
                        VERIFIED_AT,
                        true,
                        "LIVE_SOURCE",
                        "TOPIK, 입학연도, 직전학기 성적별 글로벌 장학금 기준"),
                new AcademicPolicySource(
                        "graduation-guide",
                        "졸업사정 안내",
                        "graduation",
                        "official-page",
                        "https://ssu.ac.kr/%ED%95%99%EC%82%AC/%ED%95%99%EC%82%AC%EC%A0%95%EB%B3%B4/%EC%A1%B8%EC%97%85/%EC%A1%B8%EC%97%85%EC%82%AC%EC%A0%95/",
                        "https://ssu.ac.kr/%ED%95%99%EC%82%AC/%ED%95%99%EC%82%AC%EC%A0%95%EB%B3%B4/%EC%A1%B8%EC%97%85/%EC%A1%B8%EC%97%85%EC%82%AC%EC%A0%95/",
                        "official-page",
                        null,
                        VERIFIED_AT,
                        true,
                        "LIVE_SOURCE",
                        "졸업사정, 졸업요건 확인, 졸업 관련 학생 안내"),
                new AcademicPolicySource(
                        "credit-completion-guide",
                        "학점이수 안내",
                        "graduation",
                        "official-page",
                        "https://ssu.ac.kr/%ED%95%99%EC%82%AC/%ED%95%99%EC%82%AC%EC%A0%95%EB%B3%B4/%ED%95%99%EC%A0%90%EC%9D%B4%EC%88%98/",
                        "https://ssu.ac.kr/%ED%95%99%EC%82%AC/%ED%95%99%EC%82%AC%EC%A0%95%EB%B3%B4/%ED%95%99%EC%A0%90%EC%9D%B4%EC%88%98/",
                        "official-page",
                        null,
                        VERIFIED_AT,
                        true,
                        "LIVE_SOURCE",
                        "학점 이수, 인정, 수강 및 졸업 관련 기본 안내"));
    }

    static AcademicPolicyCorpusSnapshot fallbackSnapshot(boolean liveRequested, Instant fetchedAt) {
        List<AcademicPolicyDocument> documents = sources().stream()
                .map(source -> new AcademicPolicyDocument(
                        source,
                        fallbackText(source.id()),
                        false,
                        liveRequested,
                        fetchedAt,
                        "seed-" + source.id()))
                .toList();
        return new AcademicPolicyCorpusSnapshot(
                sources(), documents, liveRequested, liveRequested, fetchedAt);
    }

    static String fallbackText(String sourceId) {
        return switch (sourceId) {
            case "undergraduate-bylaw" -> """
                    학칙시행세칙(학사과정)은 학사과정의 수업, 학점, 이수, 다전공, 졸업 등에 관한 기준을 둔다.
                    졸업 사정은 입학연도, 소속, 전공, 복수전공 또는 부전공, 교양 및 전공 이수 기준을 함께 확인해야 한다.
                    학점 인정과 졸업 요건은 단과대학과 학과별 교육과정, 적용 연도, 경과조치에 따라 달라질 수 있다.
                    """;
            case "scholarship-regulation" -> """
                    장학규정은 장학금의 종류, 선발, 지급, 제한 및 중복 수혜 원칙을 둔다.
                    장학금은 성적, 경제 사정, 봉사, 근로, 외부 장학 등 목적에 따라 구분되며 세부 기준은 지급 내규와 공지로 확인해야 한다.
                    """;
            case "scholarship-payment-rule" -> """
                    장학금지급내규는 장학금별 지급 대상, 성적 기준, 취득 학점, 지급 제한, 중복 수혜 제한을 세부적으로 정한다.
                    장학금 수혜 가능 여부는 직전 학기 성적, 취득 학점, 등록 상태, 장학금별 신청 요건을 함께 확인해야 한다.
                    """;
            case "undergraduate-scholarship-guide" -> """
                    교내장학금 안내는 백마성적우수장학금, 베어드입학우수장학금, 봉사장학금, 근로장학금 등 교내 장학금 종류를 안내한다.
                    교내장학금 신청은 u-SAINT에서 진행하며 장학금별 제출 서류와 일정은 학기별 공지사항을 확인해야 한다.
                    성적 및 취득학점 산정 시 F학점은 취득학점 산정에서 제외될 수 있으며, 국가장학금 신청 여부가 교내장학금 선발에 영향을 줄 수 있다.
                    """;
            case "international-scholarship-guide" -> """
                    외국인 유학생 장학금은 입학연도, TOPIK 급수, 직전학기 성적, 취득 학점에 따라 지급 비율이 달라진다.
                    2025학년도 이후 입학자의 숭실 글로벌 장학금은 TOPIK 4급 이상 취득자와 미취득자 기준이 다르며, 일부 구간은 TOPIK 6급 요건이 붙는다.
                    장학 기준은 교내 사정으로 변동될 수 있고 정규학기를 초과하면 지급되지 않을 수 있다.
                    """;
            case "graduation-guide" -> """
                    졸업사정 안내는 졸업 예정자의 졸업 가능 여부, 졸업요건 확인, 졸업 관련 신청과 유의사항을 안내한다.
                    학생은 u-SAINT 졸업사정표와 학과별 교육과정, 교양 및 전공 이수 기준을 함께 확인해야 한다.
                    """;
            case "credit-completion-guide" -> """
                    학점이수 안내는 수강, 이수, 학점 인정, 재수강, 전공 및 교양 이수와 관련된 기본 원칙을 안내한다.
                    졸업 가능 여부는 총 취득학점만으로 판단하지 않고 전공, 교양, 필수 과목, 인증, 채플 등 별도 요건을 함께 본다.
                    """;
            default -> "";
        };
    }
}
