package com.ssuai.domain.lms.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ssuai.domain.lms.dto.LmsTermItem;
import com.ssuai.domain.lms.dto.LmsTermSelection;
import com.ssuai.domain.lms.dto.LmsTermType;

/** Deterministic academic-term selector shared by every LMS operation. */
public final class LmsTermResolver {

    private static final Duration REGULAR_TERM_GRACE = Duration.ofDays(45);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Pattern REGULAR_KO = Pattern.compile("(20\\d{2})\\s*년?\\s*([12])\\s*학기");
    private static final Pattern REGULAR_EN = Pattern.compile(
            "(20\\d{2}).*(spring|fall|autumn)|(?:spring|fall|autumn).*(20\\d{2})",
            Pattern.CASE_INSENSITIVE);

    private LmsTermResolver() {
    }

    public static long resolveCurrentTermId(List<LmsTermItem> terms) {
        return select(terms, null, Instant.now()).selectedTermId();
    }

    static long resolveCurrentTermId(List<LmsTermItem> terms, Instant now) {
        return select(terms, null, now).selectedTermId();
    }

    public static LmsTermSelection select(List<LmsTermItem> terms, Long explicitTermId) {
        return select(terms, explicitTermId, Instant.now());
    }

    static LmsTermSelection select(
            List<LmsTermItem> terms, Long explicitTermId, Instant now) {
        if (terms == null || terms.isEmpty()) {
            throw new IllegalArgumentException("terms must not be empty");
        }
        if (explicitTermId != null) {
            LmsTermItem explicit = terms.stream()
                    .filter(term -> term.id() == explicitTermId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown term_id: " + explicitTermId));
            return selection(explicit, "EXPLICIT_TERM_ID", terms);
        }

        List<LmsTermItem> regular = terms.stream()
                .filter(term -> classify(term.name()) == LmsTermType.REGULAR)
                .toList();
        LmsTermItem active = regular.stream()
                .filter(term -> isActiveRegular(term, now))
                .max(Comparator.comparing(term -> parseQuietly(term.startAt()),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        if (active != null) {
            return selection(active, "ACTIVE_REGULAR_TERM", terms);
        }

        LmsTermItem recent = regular.stream()
                .filter(term -> endedWithinGrace(term, now))
                .max(Comparator.comparing(term -> parseQuietly(term.endAt()),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        if (recent != null) {
            return selection(recent, "RECENT_REGULAR_TERM_GRACE_45_DAYS", terms);
        }

        LmsTermItem lmsDefault = terms.stream()
                .filter(LmsTermItem::defaultTerm)
                .findFirst()
                .orElse(null);
        if (lmsDefault != null) {
            String reason = classify(lmsDefault.name()) == LmsTermType.REGULAR
                    ? "LMS_DEFAULT_REGULAR_FALLBACK"
                    : "LMS_DEFAULT_NONREGULAR_FALLBACK";
            return selection(lmsDefault, reason, terms);
        }
        return selection(terms.get(0), "FIRST_AVAILABLE_FALLBACK", terms);
    }

    public static LmsTermType classify(String name) {
        if (name == null || name.isBlank()) {
            return LmsTermType.UNKNOWN;
        }
        String normalized = name.toLowerCase(Locale.ROOT)
                .replace("_", " ")
                .replace("-", " ");
        if (normalized.contains("ssu path") || normalized.contains("ssu-path")
                || normalized.contains("슈패스")) {
            return LmsTermType.SSU_PATH;
        }
        if (normalized.contains("비정규") || normalized.contains("nonregular")
                || normalized.contains("non regular") || normalized.contains("비 정규")) {
            return LmsTermType.NONREGULAR;
        }
        if (normalized.contains("하계") || normalized.contains("여름")
                || normalized.contains("summer")) {
            return LmsTermType.SUMMER;
        }
        if (normalized.contains("동계") || normalized.contains("겨울")
                || normalized.contains("winter")) {
            return LmsTermType.WINTER;
        }
        if (REGULAR_KO.matcher(normalized).find() || REGULAR_EN.matcher(normalized).find()) {
            return LmsTermType.REGULAR;
        }
        return LmsTermType.UNKNOWN;
    }

    public static List<LmsTermItem> withResolvedDefault(List<LmsTermItem> terms) {
        return withResolvedDefault(terms, Instant.now());
    }

    static List<LmsTermItem> withResolvedDefault(List<LmsTermItem> terms, Instant now) {
        if (terms == null || terms.isEmpty()) {
            return terms;
        }
        long selected = select(terms, null, now).selectedTermId();
        List<LmsTermItem> normalized = new ArrayList<>(terms.size());
        for (LmsTermItem term : terms) {
            boolean isSelected = term.id() == selected;
            normalized.add(isSelected == term.defaultTerm()
                    ? term
                    : new LmsTermItem(
                            term.id(), term.name(), term.startAt(), term.endAt(), isSelected));
        }
        return List.copyOf(normalized);
    }

    private static LmsTermSelection selection(
            LmsTermItem selected, String reason, List<LmsTermItem> terms) {
        Set<LmsTermType> types = new LinkedHashSet<>();
        terms.stream().map(term -> classify(term.name())).forEach(types::add);
        return new LmsTermSelection(
                selected.id(), selected.name(), classify(selected.name()),
                reason, List.copyOf(types));
    }

    private static boolean isActiveRegular(LmsTermItem term, Instant now) {
        Instant start = parseQuietly(term.startAt());
        Instant end = parseQuietly(term.endAt());
        if (start != null && end != null) {
            return !now.isBefore(start) && !now.isAfter(end);
        }
        return matchesAcademicCalendar(term.name(), LocalDate.ofInstant(now, SEOUL));
    }

    private static boolean matchesAcademicCalendar(String name, LocalDate date) {
        Matcher matcher = REGULAR_KO.matcher(name == null ? "" : name);
        if (!matcher.find()) {
            return false;
        }
        int year = Integer.parseInt(matcher.group(1));
        int semester = Integer.parseInt(matcher.group(2));
        int academicYear = date.getMonthValue() <= 2 ? date.getYear() - 1 : date.getYear();
        int currentSemester = date.getMonthValue() >= 3 && date.getMonthValue() <= 8 ? 1 : 2;
        return year == academicYear && semester == currentSemester;
    }

    private static boolean endedWithinGrace(LmsTermItem term, Instant now) {
        Instant end = parseQuietly(term.endAt());
        return end != null && end.isBefore(now)
                && !end.isBefore(now.minus(REGULAR_TERM_GRACE));
    }

    private static Instant parseQuietly(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
