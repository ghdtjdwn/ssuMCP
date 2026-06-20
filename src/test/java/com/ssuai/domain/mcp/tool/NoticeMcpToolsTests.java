package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssuai.domain.notice.dto.Notice;
import com.ssuai.domain.notice.dto.NoticeCompactListResponse;
import com.ssuai.domain.notice.dto.NoticeListResponse;
import com.ssuai.domain.notice.service.NoticeService;

class NoticeMcpToolsTests {

    private NoticeService noticeService;
    private NoticeMcpTools tools;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        noticeService = mock(NoticeService.class);
        tools = new NoticeMcpTools(noticeService);
    }

    @Test
    void getRecentNotices_compact_false_returnsFullFields() {
        NoticeListResponse stub = notices();
        when(noticeService.getRecentNotices("scholarship", 2)).thenReturn(stub);

        Object response = tools.getRecentNotices("scholarship", 2, false);

        assertThat(response).isSameAs(stub);
        NoticeListResponse full = (NoticeListResponse) response;
        assertThat(full.currentPage()).isEqualTo(2);
        assertThat(full.totalPages()).isEqualTo(7);
        assertThat(full.empty()).isFalse();
        assertThat(full.note()).isNull();
        Notice item = full.items().get(0);
        assertThat(item.title()).isEqualTo("Scholarship notice");
        assertThat(item.link()).isEqualTo("https://example.edu/notices/1");
        assertThat(item.date()).isEqualTo("2026-06-13");
        assertThat(item.status()).isEqualTo("active");
        assertThat(item.department()).isEqualTo("Student Affairs");
        assertThat(item.category()).isEqualTo("scholarship");
        verify(noticeService).getRecentNotices("scholarship", 2);
    }

    @Test
    void getRecentNotices_compact_true_returnsOnlySummaryFields() throws Exception {
        NoticeListResponse stub = notices();
        when(noticeService.getRecentNotices(null, 1)).thenReturn(stub);

        Object response = tools.getRecentNotices(null, 1, true);

        assertThat(response).isInstanceOf(NoticeCompactListResponse.class);
        NoticeCompactListResponse compact = (NoticeCompactListResponse) response;
        assertThat(compact.currentPage()).isEqualTo(2);
        assertThat(compact.totalPages()).isEqualTo(7);
        assertThat(compact.items()).hasSize(1);
        assertThat(compact.items().get(0).title()).isEqualTo("Scholarship notice");
        assertThat(compact.items().get(0).url()).isEqualTo("https://example.edu/notices/1");

        String json = objectMapper.writeValueAsString(compact);
        assertThat(json)
                .contains("\"title\":\"Scholarship notice\"")
                .contains("\"url\":\"https://example.edu/notices/1\"")
                .doesNotContain("\"link\"")
                .doesNotContain("\"date\"")
                .doesNotContain("\"status\"")
                .doesNotContain("\"department\"")
                .doesNotContain("\"category\"");
        verify(noticeService).getRecentNotices(null, 1);
    }

    @Test
    void searchNotices_compact_false_returnsFullFields() {
        NoticeListResponse stub = notices();
        when(noticeService.searchNotices("tuition", "scholarship", 3)).thenReturn(stub);

        Object response = tools.searchNotices("tuition", "scholarship", 3, false);

        assertThat(response).isSameAs(stub);
        NoticeListResponse full = (NoticeListResponse) response;
        assertThat(full.currentPage()).isEqualTo(2);
        assertThat(full.totalPages()).isEqualTo(7);
        Notice item = full.items().get(0);
        assertThat(item.title()).isEqualTo("Scholarship notice");
        assertThat(item.link()).isEqualTo("https://example.edu/notices/1");
        assertThat(item.date()).isEqualTo("2026-06-13");
        assertThat(item.category()).isEqualTo("scholarship");
        verify(noticeService).searchNotices("tuition", "scholarship", 3);
    }

    @Test
    void searchNotices_compact_true_returnsOnlySummaryFields() throws Exception {
        NoticeListResponse stub = notices();
        when(noticeService.searchNotices("tuition", null, 1)).thenReturn(stub);

        Object response = tools.searchNotices("tuition", null, 1, true);

        assertThat(response).isInstanceOf(NoticeCompactListResponse.class);
        NoticeCompactListResponse compact = (NoticeCompactListResponse) response;
        assertThat(compact.items()).hasSize(1);
        assertThat(compact.items().get(0).title()).isEqualTo("Scholarship notice");
        assertThat(compact.items().get(0).url()).isEqualTo("https://example.edu/notices/1");

        String json = objectMapper.writeValueAsString(compact);
        assertThat(json)
                .contains("\"title\":\"Scholarship notice\"")
                .contains("\"url\":\"https://example.edu/notices/1\"")
                .doesNotContain("\"link\"")
                .doesNotContain("\"date\"")
                .doesNotContain("\"status\"")
                .doesNotContain("\"department\"")
                .doesNotContain("\"category\"");
        verify(noticeService).searchNotices("tuition", null, 1);
    }

    private static NoticeListResponse notices() {
        return NoticeListResponse.of(List.of(new Notice(
                "Scholarship notice",
                "https://example.edu/notices/1",
                "2026-06-13",
                "active",
                "Student Affairs",
                "scholarship"
        )), 2, 7);
    }
}
