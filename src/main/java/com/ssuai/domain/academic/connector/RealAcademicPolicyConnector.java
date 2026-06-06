package com.ssuai.domain.academic.connector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.academic.dto.AcademicPolicyCorpusSnapshot;
import com.ssuai.domain.academic.dto.AcademicPolicyDocument;
import com.ssuai.domain.academic.dto.AcademicPolicySource;
import com.ssuai.global.exception.ConnectorUnavailableException;

@Component
@ConditionalOnProperty(name = "ssuai.connector.academic-policy", havingValue = "real")
class RealAcademicPolicyConnector implements AcademicPolicyConnector {

    private static final Logger log = LoggerFactory.getLogger(RealAcademicPolicyConnector.class);

    private static final String USER_AGENT = "ssuAI/0.1 (+akftjdwn@gmail.com)";
    private static final String ACCEPT_LANGUAGE = "ko-KR,ko;q=0.9";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MIN_TEXT_LENGTH = 80;
    private static final Pattern SEQ_PATTERN = Pattern.compile("SEQ=(\\d+)");
    private static final Pattern DATE_PATTERN = Pattern.compile("showDate\\('(?<date>\\d{8})'");

    @Override
    public AcademicPolicyCorpusSnapshot loadCorpus(boolean live) {
        Instant fetchedAt = Instant.now();
        if (!live) {
            return AcademicPolicySeedCorpus.fallbackSnapshot(false, fetchedAt);
        }

        boolean fallbackUsed = false;
        List<AcademicPolicyDocument> documents = AcademicPolicySeedCorpus.sources().stream()
                .map(source -> fetchDocument(source, fetchedAt))
                .toList();

        fallbackUsed = documents.stream().anyMatch(AcademicPolicyDocument::fallbackUsed);
        return new AcademicPolicyCorpusSnapshot(
                documents.stream().map(AcademicPolicyDocument::source).toList(), documents, true, fallbackUsed, fetchedAt);
    }

    private AcademicPolicyDocument fetchDocument(AcademicPolicySource source, Instant fetchedAt) {
        AcademicPolicySource resolvedSource = resolveSource(source);
        try {
            Document doc = Jsoup.connect(resolvedSource.contentUrl())
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .get();
            String text = parseText(doc);
            if (text.length() < MIN_TEXT_LENGTH) {
                log.warn("connector=academic-policy source={} status=parse-empty", resolvedSource.id());
                return fallbackDocument(resolvedSource, fetchedAt);
            }
            return new AcademicPolicyDocument(resolvedSource, text, true, false, fetchedAt, sha256(text));
        } catch (java.net.SocketTimeoutException e) {
            log.warn("connector=academic-policy source={} status=fallback reason=timeout", resolvedSource.id());
            return fallbackDocument(resolvedSource, fetchedAt);
        } catch (IOException e) {
            log.warn("connector=academic-policy source={} status=fallback reason={}", resolvedSource.id(), e.getClass().getSimpleName());
            return fallbackDocument(resolvedSource, fetchedAt);
        }
    }

    private AcademicPolicySource resolveSource(AcademicPolicySource source) {
        if (!"rule".equals(source.sourceType())) {
            return source;
        }
        Matcher seqMatcher = SEQ_PATTERN.matcher(source.contentUrl());
        if (!seqMatcher.find()) {
            return source;
        }
        String seq = seqMatcher.group(1);
        String detailUrl = "https://rule.ssu.ac.kr/lmxsrv/law/lawDetail.do?SEQ=" + seq;
        try {
            Document detail = Jsoup.connect(detailUrl)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .get();
            Element selected = detail.selectFirst("#histroySeq option[selected], #histroySeq option:first-child");
            if (selected == null) {
                return source;
            }
            String history = selected.attr("value");
            if (history == null || history.isBlank()) {
                return source;
            }
            String effectiveDate = parseHistoryDate(selected.html());
            String contentUrl = "https://rule.ssu.ac.kr/lmxsrv/law/lawFullContent.do?SEQ=" + seq
                    + "&SEQ_HISTORY=" + history;
            return new AcademicPolicySource(
                    source.id(),
                    source.title(),
                    source.category(),
                    source.sourceType(),
                    detailUrl + "&SEQ_HISTORY=" + history,
                    contentUrl,
                    "SEQ_HISTORY=" + history,
                    effectiveDate,
                    source.lastVerifiedDate(),
                    source.liveFetchSupported(),
                    "LIVE_SOURCE",
                    source.note());
        } catch (IOException e) {
            log.warn("connector=academic-policy source={} status=history-fallback reason={}", source.id(), e.getClass().getSimpleName());
            return source;
        }
    }

    private static String parseHistoryDate(String html) {
        Matcher matcher = DATE_PATTERN.matcher(html == null ? "" : html);
        if (!matcher.find()) {
            return null;
        }
        String raw = matcher.group("date");
        return raw.substring(0, 4) + "-" + raw.substring(4, 6) + "-" + raw.substring(6, 8);
    }

    static String parseText(Document doc) {
        doc.select("script, style, nav, footer, header, iframe").remove();
        String text = doc.select("#lawcontent, main, .contents, .entry-content, body").text();
        return text.replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static AcademicPolicyDocument fallbackDocument(AcademicPolicySource source, Instant fetchedAt) {
        return new AcademicPolicyDocument(
                source,
                AcademicPolicySeedCorpus.fallbackText(source.id()),
                false,
                true,
                fetchedAt,
                "seed-" + source.id());
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new ConnectorUnavailableException(e);
        }
    }
}
