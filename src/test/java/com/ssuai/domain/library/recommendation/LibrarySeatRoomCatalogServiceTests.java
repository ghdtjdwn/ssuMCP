package com.ssuai.domain.library.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LibrarySeatRoomCatalogServiceTests {

    private final LibrarySeatRoomCatalogService catalogService = new LibrarySeatRoomCatalogService();

    @Test
    void loadsEveryCapturedRoom() {
        LibrarySeatRoomCatalogResponse response = catalogService.catalog(null, null, false);

        assertThat(response.roomCount()).isEqualTo(7);
        assertThat(response.rooms())
                .extracting(LibrarySeatRoomCatalogEntry::roomCode)
                .contains(
                        "soongsil-square-on",
                        "open-reading-2f",
                        "multi-lounge-5f",
                        "recliner-5f",
                        "maru-reading",
                        "graduate-reading",
                        "basement-reading-b1");
        assertThat(response.rooms()).allSatisfy(room -> assertThat(room.textLayout()).isEmpty());
    }

    @Test
    void filtersByFloorAndIncludesTextLayoutWhenRequested() {
        LibrarySeatRoomCatalogResponse response = catalogService.catalog("2", null, true);

        assertThat(response.roomCount()).isEqualTo(2);
        assertThat(response.includesLayout()).isTrue();
        assertThat(response.rooms())
                .extracting(LibrarySeatRoomCatalogEntry::floorCode)
                .containsOnly("2F");
        assertThat(response.rooms())
                .allSatisfy(room -> assertThat(room.textLayout()).isNotEmpty());
    }

    @Test
    void marksGraduateRoomRestriction() {
        LibrarySeatRoomCatalogResponse response = catalogService.catalog(null, "graduate-reading", false);

        assertThat(response.roomCount()).isEqualTo(1);
        LibrarySeatRoomCatalogEntry room = response.rooms().getFirst();
        assertThat(room.graduateOnly()).isTrue();
        assertThat(room.audience()).isEqualTo("graduate_only");
        // captureNotes are internal data-collection notes — hidden unless debug=true
        assertThat(room.captureNotes()).isEmpty();

        LibrarySeatRoomCatalogResponse debugResponse =
                catalogService.catalog(null, "graduate-reading", false, true);
        assertThat(debugResponse.rooms().getFirst().captureNotes()).anySatisfy(note ->
                assertThat(note).contains("upstream eligibility rule"));
    }

    @Test
    void b1CatalogUsesTheVerifiedLiveRoomIdAndDocumentsRecommendationLimit() {
        LibrarySeatRoomCatalogResponse response = catalogService.catalog("B1", null, true);

        assertThat(response.roomCount()).isEqualTo(1);
        assertThat(response.rooms().getFirst().roomCode()).isEqualTo("basement-reading-b1");
        assertThat(response.rooms().getFirst().floor()).isEqualTo(-1);
        assertThat(response.rooms().getFirst().roomId()).isEqualTo(15);
        assertThat(response.message()).contains("roomId=15").contains("exclude B1");
    }

    @Test
    void legacyMultiZoneAliasResolvesToTheCanonicalLiveRoom() {
        LibrarySeatRoomCatalogResponse response =
                catalogService.catalog(null, "pc-multi-zone-5f", false);

        assertThat(response.rooms()).singleElement().satisfies(room -> {
            assertThat(room.roomCode()).isEqualTo("multi-lounge-5f");
            assertThat(room.roomId()).isEqualTo(60);
            assertThat(room.aliases()).contains("PC존/멀티존");
        });
    }

    @Test
    void catalogMessageDoesNotReferencePrivateCaptureProvenance() {
        assertThat(catalogService.catalog(null, null, false).message())
                .doesNotContainIgnoringCase("screenshot")
                .doesNotContain("user's");
    }
}
