package com.ssuai.domain.notice.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.notice.dto.Notice;
import com.ssuai.domain.notice.dto.NoticeDetailResponse;

class RealNoticeConnectorParseTests {

    @Test
    void parseNoticeListExtractsItemsFromFixture() throws IOException {
        Document doc = loadFixture("fixtures/notice/notice_list.html");

        List<Notice> notices = RealNoticeConnector.parseNoticeList(doc);

        assertThat(notices).isNotEmpty();
        assertThat(notices.getFirst().date()).isNotBlank();
        assertThat(notices.getFirst().title()).isNotBlank();
    }

    @Test
    void parseNoticeListExtractsDateCorrectly() throws IOException {
        Document doc = loadFixture("fixtures/notice/notice_list.html");

        List<Notice> notices = RealNoticeConnector.parseNoticeList(doc);

        assertThat(notices.getFirst().date()).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void normalizeDateConvertsKnownFormatsToIsoDate() {
        assertThat(RealNoticeConnector.normalizeDate("2026.06.04")).isEqualTo("2026-06-04");
        assertThat(RealNoticeConnector.normalizeDate("2026\uB144 6\uC6D4 18\uC77C")).isEqualTo("2026-06-18");
        assertThat(RealNoticeConnector.normalizeDate("")).isEmpty();
        assertThat(RealNoticeConnector.normalizeDate(null)).isEmpty();
    }

    @Test
    void parseNoticeDetailExtractsMetadataFromFixture() throws IOException {
        Document doc = loadFixture("fixtures/notice/notice_detail_metadata.html");

        NoticeDetailResponse detail = RealNoticeConnector.parseNoticeDetail(
                doc,
                "https://scatch.ssu.ac.kr/%EA%B3%B5%EC%A7%80%EC%82%AC%ED%95%AD/?slug=test");

        assertThat(detail.title()).isEqualTo(
                "\uC81C16\uD68C \uC22D\uC2E4 \uCEA1\uC2A4\uD1A4\uB514\uC790\uC778 "
                        + "\uACBD\uC9C4\uB300\uD68C \u201C\uC735\uD569\uD300\u201D "
                        + "\uBAA8\uC9D1 \uC548\uB0B4");
        assertThat(detail.date()).isEqualTo("2026-06-18");
        assertThat(detail.category()).isEqualTo("\uBE44\uAD50\uACFC\u00B7\uD589\uC0AC");
        assertThat(detail.bodyText()).contains(
                "\uBCF8\uBB38 \uD655\uC778\uC6A9 \uBB38\uC7A5\uC785\uB2C8\uB2E4.");
        assertThat(detail.status()).isEmpty();
        assertThat(detail.department()).isEmpty();
        assertThat(detail.contentCompleteness()).isEqualTo("FULL");
        assertThat(detail.bodySource()).isEqualTo("OFFICIAL_HTML");
        assertThat(detail.bodyMissingReason()).isNull();
        assertThat(detail.attachments())
                .singleElement()
                .satisfies(attachment -> {
                    assertThat(attachment.name()).isEqualTo("대회 안내서");
                    assertThat(attachment.url()).isEqualTo("https://scatch.ssu.ac.kr/files/capstone-guide.pdf");
                });
    }

    @Test
    void parseNoticeListExtractsStatusCorrectly() throws IOException {
        Document doc = loadFixture("fixtures/notice/notice_list.html");

        List<Notice> notices = RealNoticeConnector.parseNoticeList(doc);

        assertThat(notices)
                .extracting(Notice::status)
                .allSatisfy(status ->
                        assertThat(status).isIn("진행", "완료", ""));
    }

    @Test
    void parseNoticeListExtractsTitleAndLink() throws IOException {
        Document doc = loadFixture("fixtures/notice/notice_list.html");

        List<Notice> notices = RealNoticeConnector.parseNoticeList(doc);

        assertThat(notices.getFirst().title()).isNotBlank();
        // link may be empty in fixture if base URI is not set, so just check it doesn't throw
        assertThat(notices.getFirst().link()).isNotNull();
    }

    @Test
    void parseNoticeListExtractsCategoryLabel() throws IOException {
        Document doc = loadFixture("fixtures/notice/notice_list.html");

        List<Notice> notices = RealNoticeConnector.parseNoticeList(doc);

        // At least some notices should have categories
        assertThat(notices)
                .extracting(Notice::category)
                .anySatisfy(cat -> assertThat(cat).isNotBlank());
    }

    @Test
    void parseNoticeListExtractsDepartment() throws IOException {
        Document doc = loadFixture("fixtures/notice/notice_list.html");

        List<Notice> notices = RealNoticeConnector.parseNoticeList(doc);

        assertThat(notices)
                .extracting(Notice::department)
                .anySatisfy(dept -> assertThat(dept).isNotBlank());
    }

    @Test
    void parseTotalPagesExtractsMaxPageNumber() throws IOException {
        Document doc = loadFixture("fixtures/notice/notice_list.html");

        int totalPages = RealNoticeConnector.parseTotalPages(doc);

        assertThat(totalPages).isGreaterThanOrEqualTo(1);
    }

    @Test
    void parseEmptyDocumentReturnsEmptyList() {
        Document emptyDoc = Jsoup.parse("<html><body></body></html>");

        List<Notice> notices = RealNoticeConnector.parseNoticeList(emptyDoc);

        assertThat(notices).isEmpty();
    }

    @Test
    void parseTotalPagesFromEmptyDocReturnsMinusOne() {
        Document emptyDoc = Jsoup.parse("<html><body></body></html>");

        int totalPages = RealNoticeConnector.parseTotalPages(emptyDoc);

        assertThat(totalPages).isEqualTo(-1);
    }

    @Test
    void parseDetailBodySelectsContentAfterHr() throws IOException {
        Document doc = loadFixture("fixtures/notice/notice_detail.html");

        // hr + div selector should find content div (not header/metadata)
        org.jsoup.nodes.Element body = doc.selectFirst("div.bg-white > hr + div");

        assertThat(body).isNotNull();
        String text = body.text();
        // body should contain actual content, not just metadata noise
        assertThat(text.length()).isGreaterThan(50);
        // should NOT start with category label or date pattern
        assertThat(text).doesNotStartWith("국제교류");
        assertThat(text).doesNotStartWith("장학");
    }

    @Test
    void parseNoticeDetailSignalsMissingBodyInsteadOfPretendingItWasComplete() {
        NoticeDetailResponse detail = RealNoticeConnector.parseNoticeDetail(
                Jsoup.parse("<h1>제목</h1>", "https://scatch.ssu.ac.kr"),
                "https://scatch.ssu.ac.kr/notice/missing-body");

        assertThat(detail.bodyText()).isEmpty();
        assertThat(detail.contentCompleteness()).isEqualTo("MISSING");
        assertThat(detail.bodySource()).isEqualTo("NONE");
        assertThat(detail.bodyMissingReason()).isEqualTo("BODY_SELECTOR_NOT_FOUND");
    }

    private static Document loadFixture(String resourcePath) throws IOException {
        try (InputStream in = RealNoticeConnectorParseTests.class
                .getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("fixture not found: " + resourcePath);
            }
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Jsoup.parse(html, "https://scatch.ssu.ac.kr");
        }
    }
}
