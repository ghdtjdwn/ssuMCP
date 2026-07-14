package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.library.dto.LibraryBookSearchResponse;
import com.ssuai.domain.library.service.LibraryBookService;

class LibraryBookMcpToolTests {

    private final LibraryBookService service = mock(LibraryBookService.class);
    private final LibraryBookMcpTool tool = new LibraryBookMcpTool(service);

    @Test
    void delegatesValidatedSearchArgumentsWithoutChangingPagination() {
        LibraryBookSearchResponse expected = new LibraryBookSearchResponse(0, 2, 5, List.of());
        when(service.search("파이썬", 2, 5)).thenReturn(expected);

        assertThat(tool.searchLibraryBook("파이썬", 2, 5)).isSameAs(expected);
        verify(service).search("파이썬", 2, 5);
    }

    @Test
    void leavesIndependentValidationToTheSharedService() {
        when(service.search("파이썬", -1, 10))
                .thenThrow(new IllegalArgumentException("page는 0 이상이어야 해요."));

        assertThatThrownBy(() -> tool.searchLibraryBook("파이썬", -1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page");
        verify(service).search("파이썬", -1, 10);
    }
}
