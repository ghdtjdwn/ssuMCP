package com.ssuai.domain.library.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.library.dto.BookStatus;
import com.ssuai.domain.library.dto.LibraryBook;
import com.ssuai.domain.library.dto.LibraryBookSearchResponse;

class LibraryBookServiceTests {

    private final LibraryBookCache cache = mock(LibraryBookCache.class);
    private final LibraryBookService service = new LibraryBookService(cache);

    @Test
    void blankQueryIsRejected() {
        assertThatThrownBy(() -> service.search("   ", 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("검색어");

        assertThatThrownBy(() -> service.search(null, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(cache);
    }

    @Test
    void queryLongerThan64CharsIsRejected() {
        String tooLong = "가".repeat(65);

        assertThatThrownBy(() -> service.search(tooLong, 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");

        verifyNoInteractions(cache);
    }

    @Test
    void negativePageIsRejected() {
        assertThatThrownBy(() -> service.search("파이썬", -1, 10))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(cache);
    }

    @Test
    void zeroOrNegativeSizeIsRejected() {
        assertThatThrownBy(() -> service.search("파이썬", 0, 0))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(cache);
    }

    @Test
    void sizeAbove20IsRejectedWithoutCallingConnector() {
        assertThatThrownBy(() -> service.search("파이썬", 0, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20");
        verifyNoInteractions(cache);
    }

    @Test
    void nullPageAndSizeUseDefaults() {
        when(cache.get(anyString(), anyInt(), anyInt())).thenReturn(stubResponse());

        service.search("파이썬", null, null);

        org.mockito.Mockito.verify(cache).get(eq("파이썬"), eq(0), eq(10));
    }

    @Test
    void queryIsTrimmedBeforeForwarding() {
        when(cache.get(anyString(), anyInt(), anyInt())).thenReturn(stubResponse());

        service.search("  파이썬  ", 0, 10);

        org.mockito.Mockito.verify(cache).get(eq("파이썬"), eq(0), eq(10));
    }

    @Test
    void validRequestReturnsCacheResult() {
        LibraryBookSearchResponse stub = stubResponse();
        when(cache.get(eq("파이썬"), eq(0), eq(10))).thenReturn(stub);

        LibraryBookSearchResponse response = service.search("파이썬", 0, 10);

        assertThat(response)
                .isNotSameAs(stub)
                .extracting(LibraryBookSearchResponse::page, LibraryBookSearchResponse::size,
                        LibraryBookSearchResponse::total, LibraryBookSearchResponse::hasNext)
                .containsExactly(0, 10, 1, false);
    }

    @Test
    void searchResponseDerivedPaginationHandlesEdgeCases() {
        assertThat(new LibraryBookSearchResponse(10, 0, 0, List.of()).totalPages()).isZero();
        assertThat(new LibraryBookSearchResponse(10, 0, 0, List.of()).hasNext()).isFalse();
        assertThat(new LibraryBookSearchResponse(0, 0, 10, List.of()).hasNext()).isFalse();
        assertThat(new LibraryBookSearchResponse(10, 1, 5, List.of()).hasNext()).isFalse();
    }

    @Test
    void bookAvailabilityIsAnExplicitProjectionOfStatus() {
        assertThat(new LibraryBook(1L, "available", null, null, null, null, null, null,
                BookStatus.AVAILABLE).isAvailable()).isTrue();
        assertThat(new LibraryBook(2L, "loaned", null, null, null, null, null, null,
                BookStatus.CHECKED_OUT).isAvailable()).isFalse();
    }

    private static LibraryBookSearchResponse stubResponse() {
        return new LibraryBookSearchResponse(1, 0, 10, List.of(
                new LibraryBook(1L, "stub", "stub", "pub", null, null,
                        "000.0", "중앙도서관", BookStatus.AVAILABLE)
        ));
    }
}
