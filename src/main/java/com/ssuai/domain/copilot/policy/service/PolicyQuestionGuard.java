package com.ssuai.domain.copilot.policy.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class PolicyQuestionGuard {

    private static final int MIN_LENGTH = 10;
    private static final int MAX_LENGTH = 1000;
    private static final Set<String> ALLOWED_CATEGORIES = Set.of("academic", "graduation", "scholarship");
    private static final Pattern STUDENT_ID_CONTEXT = Pattern.compile(
            "학번|student\\s*(?:id|number)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EIGHT_DIGIT_IDENTIFIER = Pattern.compile("(?<!\\d)(\\d{8})(?!\\d)");
    private static final Pattern SEPARATED_EIGHT_DIGIT_IDENTIFIER = Pattern.compile(
            "(?<!\\d)\\d{4}(?:\\s*[-/.]\\s*|\\s+)\\d{4}(?!\\d)");
    private static final Pattern OFFICIAL_DATE_CONTEXT = Pattern.compile(
            "시행|적용|개정|공포|기준(?:일)?|날짜|일자",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PERSONAL_IDENTITY_CONTEXT = Pattern.compile(
            "(?:이름|성명|생년월일|출생일|태어난\\s*날|주소|거주지|사는\\s*곳)"
                    + "|(?:full\\s*name|birth\\s*date|date\\s*of\\s*birth|address)",
            Pattern.CASE_INSENSITIVE);
    private static final String KOREAN_SURNAME =
            "(?:김|이|박|최|정|강|조|윤|장|임|한|오|서|신|권|황|안|송|류|홍|전|고|문|양|손|배|백|허|유|남|심|노|하|곽|성|차|주|우|구|민|진|지|엄|채|원|천|방|공|현|함|변|염|여|추|도|소|석|선|설|마|길|연|위|표|명|기|반|왕|금|옥|육|인|맹|제|모|탁|국|어|은|편|용)";
    private static final String POLICY_CONTEXT =
            "(?:학사|학칙|졸업|학점|평점|성적|전공|교양|장학|등록금|수강|휴학|복학|제적|채플|학적|이수|재수강)";
    private static final Pattern KOREAN_NAME_STUDENT_CONTEXT = Pattern.compile(
            "(?<![가-힣])" + KOREAN_SURNAME
                    + "[가-힣]{1,2}\\s*(?:학생|학우|님|씨)(?:의|은|는|이|가|에게)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern KOREAN_NAME_POSSESSIVE_POLICY_CONTEXT = Pattern.compile(
            "(?<![가-힣])(" + KOREAN_SURNAME + "[가-힣]{2})의(?=\\s*" + POLICY_CONTEXT + ")",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern KOREAN_NAME_SUBJECT_POLICY_CONTEXT = Pattern.compile(
            "(?<![가-힣])(" + KOREAN_SURNAME + "[가-힣]{2})"
                    + "(?:은|는|이|가|에게)(?=\\s*" + POLICY_CONTEXT + ")",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> PUBLIC_POLICY_SUBJECTS = Set.of(
            "장학금", "이수자", "신청자", "전공자", "편입생", "유학생", "지원자", "신입생", "한학기");
    private static final Pattern PERSONAL_PRONOUN = Pattern.compile(
            "(?:나의|내가|나는|저의|제가|저는)|(?:내|제)(?=\\s|gpa|평점|성적|학점|topik|토픽)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ACADEMIC_VALUE_WITH_NUMBER = Pattern.compile(
            "(?:성적|학점|평점|gpa|topik|토픽).{0,24}\\d"
                    + "|\\d(?:[.,]\\d+)?(?:\\s*)(?:학점|점|급)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ELIGIBILITY_JUDGMENT = Pattern.compile(
            "(?:장학|졸업|수혜|자격).{0,32}(?:받을\\s*수|가능|자격|해당|되나요|될까요)"
                    + "|(?:가능|해당|자격).{0,24}(?:장학|졸업|수혜)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![\\p{Alnum}._%+-])[\\p{Alnum}._%+-]+@[\\p{Alnum}.-]+\\.[A-Z]{2,}(?![\\p{Alnum}._%+-])");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)01[016789][ .-]?\\d{3,4}[ .-]?\\d{4}(?!\\d)");
    private static final Pattern RESIDENT_NUMBER = Pattern.compile(
            "(?<!\\d)\\d{6}[ -]?[1-4]\\d{6}(?!\\d)");
    private static final Pattern SECRET_OR_PROMPT_THEFT = Pattern.compile(
            "비밀번호|패스워드|password|api[ _-]?key|토큰|token|쿠키|cookie|secret|"
                    + "시스템\\s*프롬프트|system\\s*prompt|개발자\\s*메시지|developer\\s*message|"
                    + "이전\\s*(?:지시|명령).{0,12}무시|ignore.{0,20}(?:previous|prior).{0,20}instruction|"
                    + "(?:모든|어떤)\\s*(?:지시|명령|프롬프트).{0,12}무시|"
                    + "(?:지시|명령|프롬프트)(?:를|을)?\\s*(?:모두|전부|다)\\s*무시|"
                    + "ignore.{0,12}(?:all|any).{0,12}(?:instruction|direction|prompt)s?|"
                    + "(?:disregard|forget|bypass).{0,24}(?:instruction|direction|prompt)s?|"
                    + "내부\\s*(?:지시|설정).{0,20}(?:보여|공개|출력)|reveal.{0,20}(?:prompt|secret)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ACADEMIC_SCOPE = Pattern.compile(
            "학사|학칙|졸업|학점|평점|gpa|전공|교양|다전공|복수전공|부전공|장학|등록금|수강|"
                    + "휴학|복학|제적|채플|학적|이수|재수강|성적\\s*기준|academic|graduation|"
                    + "토픽|topik|scholarship|credit|major|minor|tuition",
            Pattern.CASE_INSENSITIVE);

    public String validateQuestion(String question) {
        String normalized = question == null ? "" : question.trim();
        if (normalized.length() < MIN_LENGTH || normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("question은 10자 이상 1000자 이하여야 합니다.");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!ACADEMIC_SCOPE.matcher(lower).find()) {
            throw new IllegalArgumentException("공개 학사정책 질문만 검토 요청할 수 있습니다.");
        }
        if (STUDENT_ID_CONTEXT.matcher(lower).find()
                || containsUnsafeEightDigitIdentifier(lower)
                || SEPARATED_EIGHT_DIGIT_IDENTIFIER.matcher(lower).find()
                || PERSONAL_IDENTITY_CONTEXT.matcher(lower).find()
                || KOREAN_NAME_STUDENT_CONTEXT.matcher(lower).find()
                || containsLikelyKoreanName(lower, KOREAN_NAME_POSSESSIVE_POLICY_CONTEXT)
                || containsLikelyKoreanName(lower, KOREAN_NAME_SUBJECT_POLICY_CONTEXT)
                || EMAIL.matcher(lower).find()
                || PHONE.matcher(lower).find()
                || RESIDENT_NUMBER.matcher(lower).find()) {
            throw new IllegalArgumentException("학번·연락처 등 개인 식별정보가 포함된 질문은 처리할 수 없습니다.");
        }
        boolean personallyContextualized = PERSONAL_PRONOUN.matcher(lower).find()
                && (ACADEMIC_VALUE_WITH_NUMBER.matcher(lower).find()
                        || ELIGIBILITY_JUDGMENT.matcher(lower).find());
        if (personallyContextualized) {
            throw new IllegalArgumentException("개인 성적·자격 판단은 처리할 수 없습니다. 일반 정책 기준만 질문해 주세요.");
        }
        if (SECRET_OR_PROMPT_THEFT.matcher(lower).find()) {
            throw new IllegalArgumentException("비밀정보 또는 내부 프롬프트 요청은 처리할 수 없습니다.");
        }
        return normalized;
    }

    private static boolean containsUnsafeEightDigitIdentifier(String value) {
        var matcher = EIGHT_DIGIT_IDENTIFIER.matcher(value);
        while (matcher.find()) {
            int contextStart = Math.max(0, matcher.start() - 16);
            int contextEnd = Math.min(value.length(), matcher.end() + 16);
            String context = value.substring(contextStart, contextEnd);
            if (!isOfficialDate(matcher.group(1)) || !OFFICIAL_DATE_CONTEXT.matcher(context).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsLikelyKoreanName(String value, Pattern pattern) {
        var matcher = pattern.matcher(value);
        while (matcher.find()) {
            if (!PUBLIC_POLICY_SUBJECTS.contains(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOfficialDate(String value) {
        try {
            LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    public String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String normalized = category.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("졸업") || normalized.contains("학점")) {
            normalized = "graduation";
        } else if (normalized.contains("장학")) {
            normalized = "scholarship";
        } else if (normalized.contains("학사") || normalized.contains("정책")) {
            normalized = "academic";
        }
        if (!ALLOWED_CATEGORIES.contains(normalized)) {
            throw new IllegalArgumentException("category는 academic, graduation, scholarship 중 하나여야 합니다.");
        }
        return normalized;
    }
}
