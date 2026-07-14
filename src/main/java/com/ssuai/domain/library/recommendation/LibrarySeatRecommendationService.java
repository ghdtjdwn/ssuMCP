package com.ssuai.domain.library.recommendation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibraryRoomAvailableSeatsResponse;
import com.ssuai.domain.library.dto.PyxisSeatInfo;
import com.ssuai.domain.library.service.LibraryAvailableSeatsService;

@Service
public class LibrarySeatRecommendationService {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;
    private static final int BASE_AVAILABLE_SCORE = 50;
    public static final String GRADUATE_ONLY_AUDIENCE = "graduate_only";

    private static final Map<LibraryFloor, List<Integer>> FLOOR_ROOMS;
    private static final Map<Integer, String> ROOM_CODES = Map.of(
            53, "soongsil-square-on",
            54, "open-reading-2f",
            57, "maru-reading",
            58, "graduate-reading",
            59, "recliner-5f",
            60, "multi-lounge-5f");

    static {
        Map<LibraryFloor, List<Integer>> m = new LinkedHashMap<>();
        m.put(LibraryFloor.F2, List.of(53, 54));
        m.put(LibraryFloor.F5, List.of(59, 60));
        m.put(LibraryFloor.F6, List.of(57, 58));
        FLOOR_ROOMS = Collections.unmodifiableMap(m);
    }

    private final LibraryAvailableSeatsService availableSeatsService;
    private final LibrarySeatCatalogService catalogService;
    private final LibrarySeatRecommendationMetrics metrics;

    public LibrarySeatRecommendationService(
            LibraryAvailableSeatsService availableSeatsService,
            LibrarySeatCatalogService catalogService,
            LibrarySeatRecommendationMetrics metrics) {
        this.availableSeatsService = availableSeatsService;
        this.catalogService = catalogService;
        this.metrics = metrics;
    }

    public LibrarySeatRecommendationResponse recommend(
            LibraryFloor floor,
            String sessionKey,
            LibrarySeatPreference preference,
            Integer requestedLimit
    ) {
        return recommend(floor, sessionKey, preference, requestedLimit, null);
    }

    public LibrarySeatRecommendationResponse recommend(
            LibraryFloor floor,
            String sessionKey,
            LibrarySeatPreference preference,
            Integer requestedLimit,
            Boolean includeGraduateOnly
    ) {
        int limit = normalizeLimit(requestedLimit);
        boolean withGraduateOnly = Boolean.TRUE.equals(includeGraduateOnly);
        LibrarySeatPreference effectivePreference = preference == null
                ? new LibrarySeatPreference(null, null, null, null, null, null)
                : preference;

        List<Integer> roomIds = FLOOR_ROOMS.getOrDefault(floor, List.of());
        List<LiveSeat> liveSeats = new ArrayList<>();
        int liveSeatItemsSeen = 0;

        for (int roomId : roomIds) {
            LibraryRoomAvailableSeatsResponse roomData;
            try {
                roomData = availableSeatsService.getRoomAvailableSeats(roomId, sessionKey);
            } catch (RuntimeException e) {
                // Tag upstream failures on the same metric so one panel covers
                // ok/empty/error for the whole recommendation path.
                metrics.countError();
                throw e;
            }
            for (PyxisSeatInfo seat : roomData.seats()) {
                liveSeats.add(new LiveSeat(ROOM_CODES.get(roomId), seat.label(), seat.status()));
                liveSeatItemsSeen++;
            }
        }

        int liveAvailable = (int) liveSeats.stream()
                .map(LiveSeat::status)
                .filter("available"::equals)
                .count();

        Set<String> excludedRooms = new LinkedHashSet<>();
        List<LibrarySeatRecommendation> allRecommendations = liveSeats.stream()
                .filter(seat -> "available".equals(seat.status()))
                .flatMap(seat -> catalogService.find(floor, seat.roomCode(), seat.label()).stream()
                        .filter(entry -> {
                            if (!withGraduateOnly && GRADUATE_ONLY_AUDIENCE.equals(entry.audience())) {
                                excludedRooms.add(entry.roomName());
                                return false;
                            }
                            return true;
                        })
                        .map(entry -> buildRecommendation(
                                entry, seat.status(), effectivePreference)))
                .sorted(Comparator
                        .comparingInt(LibrarySeatRecommendation::score).reversed()
                        .thenComparing(LibrarySeatRecommendation::seatId, LibrarySeatCatalogService::compareSeatIds))
                .toList();

        List<LibrarySeatRecommendation> limited = diversify(allRecommendations, limit);

        List<String> warnings = new ArrayList<>();
        if (withGraduateOnly && limited.stream()
                .anyMatch(item -> GRADUATE_ONLY_AUDIENCE.equals(item.audience()))) {
            warnings.add("대학원열람실은 대학원생 전용일 수 있습니다. 이용 자격을 확인하세요.");
        }
        if (effectivePreference.hasAnyPreference()) {
            warnings.add("좌석 선호 조건은 hard filter가 아닌 soft preference입니다.");
            warnings.add("현재 정적 속성 카탈로그의 false 값은 검증된 부재가 아니라 unknown으로 처리됩니다.");
            boolean anyFullMatch = limited.stream().anyMatch(item ->
                    item.matchedPreferenceCount() == item.requestedPreferenceCount());
            if (!anyFullMatch) {
                warnings.add("요청한 모든 중요 속성을 확인할 수 있는 이용 가능 좌석이 없습니다.");
            }
        }

        String source = liveSeatItemsSeen > 0 ? "live_per_seat" : "no_seats_found";
        // Empty-vs-error observability (TROUBLESHOOTING 2026-07-02): a sustained
        // empty rate on source=no_seats_found is the upstream-drift signature.
        metrics.countResult(source, limited.isEmpty());
        return new LibrarySeatRecommendationResponse(
                floor.code(),
                floor.displayLabel(),
                limit,
                source,
                messageFor(liveSeatItemsSeen, liveAvailable, allRecommendations, effectivePreference, excludedRooms),
                List.copyOf(excludedRooms),
                warnings,
                limited,
                true,
                effectivePreference.requestedCount(),
                "POSITIVE_ONLY");
    }

    private static LibrarySeatRecommendation buildRecommendation(
            LibrarySeatCatalogEntry entry,
            String status,
            LibrarySeatPreference preference) {
        Score score = score(entry.attributes(), preference);
        return new LibrarySeatRecommendation(
                entry.seatId(),
                entry.externalSeatId(),
                entry.label(),
                entry.roomCode(),
                entry.roomName(),
                entry.zone(),
                entry.seatType(),
                entry.audience(),
                status,
                score.value(),
                score.matchedPreferences(),
                score.missingPreferences(),
                entry.attributes(),
                entry.note(),
                preference.requestedCount(),
                score.matchedPreferences().size(),
                score.unknownPreferences(),
                attributeStates(entry.attributes()),
                score.confidence(),
                score.attributeCoverage());
    }

    private static Score score(LibrarySeatAttributes attributes, LibrarySeatPreference preference) {
        int value = BASE_AVAILABLE_SCORE;
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> unknown = new ArrayList<>();

        ScoringResult window = scoreBoolean("window", preference.window(), attributes.window());
        value += window.delta();
        matched.addAll(window.matched());
        missing.addAll(window.missing());
        unknown.addAll(window.unknown());

        ScoringResult outlet = scoreBoolean("outlet", preference.outlet(), attributes.outlet());
        value += outlet.delta();
        matched.addAll(outlet.matched());
        missing.addAll(outlet.missing());
        unknown.addAll(outlet.unknown());

        ScoringResult standing = scoreBoolean("standing", preference.standing(), attributes.standing());
        value += standing.delta();
        matched.addAll(standing.matched());
        missing.addAll(standing.missing());
        unknown.addAll(standing.unknown());

        ScoringResult edge = scoreBoolean("edge", preference.edge(), attributes.edge());
        value += edge.delta();
        matched.addAll(edge.matched());
        missing.addAll(edge.missing());
        unknown.addAll(edge.unknown());

        ScoringResult quiet = scoreBoolean("quiet", preference.quiet(), attributes.quiet());
        value += quiet.delta();
        matched.addAll(quiet.matched());
        missing.addAll(quiet.missing());
        unknown.addAll(quiet.unknown());

        ScoringResult nearEntrance =
                scoreBoolean("nearEntrance", preference.nearEntrance(), attributes.nearEntrance());
        value += nearEntrance.delta();
        matched.addAll(nearEntrance.matched());
        missing.addAll(nearEntrance.missing());
        unknown.addAll(nearEntrance.unknown());

        int requested = preference.requestedCount();
        double coverage = requested == 0 ? 1.0
                : (double) (matched.size() + missing.size()) / requested;
        String confidence = requested == 0 ? "NOT_APPLICABLE"
                : coverage >= 0.75 ? "HIGH" : coverage >= 0.4 ? "MEDIUM" : "LOW";
        return new Score(value, matched, missing, unknown, confidence, coverage);
    }

    private static ScoringResult scoreBoolean(String key, Boolean preferred, boolean actual) {
        if (preferred == null) {
            return new ScoringResult(0, List.of(), List.of(), List.of());
        }
        String label = preferred ? key : "not_" + key;
        if (actual) {
            return preferred
                    ? new ScoringResult(25, List.of(label), List.of(), List.of())
                    : new ScoringResult(-25, List.of(), List.of(label), List.of());
        }
        // The current catalog records verified positive attributes; false is not evidence of
        // verified absence. Penalize uncertainty without presenting it as a definite mismatch.
        return new ScoringResult(preferred ? -25 : -10, List.of(), List.of(), List.of(label));
    }

    private static Map<String, String> attributeStates(LibrarySeatAttributes attributes) {
        Map<String, String> states = new LinkedHashMap<>();
        states.put("window", state(attributes.window()));
        states.put("outlet", state(attributes.outlet()));
        states.put("standing", state(attributes.standing()));
        states.put("edge", state(attributes.edge()));
        states.put("quiet", state(attributes.quiet()));
        states.put("nearEntrance", state(attributes.nearEntrance()));
        return Map.copyOf(states);
    }

    private static String state(boolean verifiedPositive) {
        return verifiedPositive ? "TRUE" : "UNKNOWN";
    }

    private static List<LibrarySeatRecommendation> diversify(
            List<LibrarySeatRecommendation> ranked, int limit) {
        List<LibrarySeatRecommendation> remaining = new ArrayList<>(ranked);
        List<LibrarySeatRecommendation> selected = new ArrayList<>();
        while (!remaining.isEmpty() && selected.size() < limit) {
            if (selected.isEmpty()) {
                selected.add(remaining.removeFirst());
                continue;
            }
            int highestScore = remaining.stream()
                    .mapToInt(LibrarySeatRecommendation::score)
                    .max()
                    .orElse(Integer.MIN_VALUE);
            LibrarySeatRecommendation next = remaining.stream()
                    .filter(candidate -> candidate.score() == highestScore)
                    .max(Comparator
                            .comparingInt((LibrarySeatRecommendation candidate) ->
                                    diversityDistance(candidate, selected))
                            .thenComparing(LibrarySeatRecommendation::seatId,
                                    LibrarySeatCatalogService::compareSeatIds))
                    .orElseThrow();
            selected.add(next);
            remaining.remove(next);
        }
        return List.copyOf(selected);
    }

    private static int diversityDistance(
            LibrarySeatRecommendation candidate,
            List<LibrarySeatRecommendation> selected) {
        if (selected.isEmpty()) {
            return 0;
        }
        return selected.stream().mapToInt(existing -> {
            if (!existing.roomCode().equals(candidate.roomCode())) {
                return 10_000;
            }
            if (!existing.zone().equals(candidate.zone())) {
                return 5_000;
            }
            try {
                return Math.abs(Integer.parseInt(existing.seatId())
                        - Integer.parseInt(candidate.seatId()));
            } catch (NumberFormatException ignored) {
                return existing.seatId().equals(candidate.seatId()) ? 0 : 1;
            }
        }).min().orElse(0);
    }

    private static String messageFor(
            int liveSeatItemsSeen,
            int liveAvailable,
            List<LibrarySeatRecommendation> recommendations,
            LibrarySeatPreference preference,
            Set<String> excludedRooms) {
        String exclusionSuffix = excludedRooms.isEmpty()
                ? ""
                : " 대학원 전용 열람실(" + String.join(", ", excludedRooms)
                        + ")은 기본 제외했습니다. 포함하려면 include_graduate_only=true.";
        if (liveSeatItemsSeen == 0) {
            return "No per-seat data was returned for the requested floor. The library may be closed.";
        }
        if (liveAvailable == 0) {
            return "All seats on this floor are currently occupied.";
        }
        if (recommendations.isEmpty()) {
            if (!excludedRooms.isEmpty()) {
                return "사용 가능한 좌석이 대학원 전용 열람실에만 있습니다." + exclusionSuffix;
            }
            return "No available live seats matched the static catalog. "
                    + "Add the floor's seat IDs to library/seat-catalog.json.";
        }
        if (!preference.hasAnyPreference()) {
            return "No preferences were provided, so available catalog seats are sorted deterministically."
                    + exclusionSuffix;
        }
        return "Recommendations are ranked by live availability and the requested seat preferences."
                + exclusionSuffix;
    }

    private static int normalizeLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, requestedLimit));
    }

    private record Score(
            int value,
            List<String> matchedPreferences,
            List<String> missingPreferences,
            List<String> unknownPreferences,
            String confidence,
            double attributeCoverage
    ) {
    }

    private record ScoringResult(
            int delta,
            List<String> matched,
            List<String> missing,
            List<String> unknown
    ) {
    }

    private record LiveSeat(String roomCode, String label, String status) {
    }
}
