package com.ssuai.domain.lms.connector;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.auth.lms.LmsSsoProperties;
import com.ssuai.domain.lms.dto.ContentDownloadInfo;
import com.ssuai.domain.lms.dto.LmsCourse;
import com.ssuai.domain.lms.dto.LmsMaterial;
import com.ssuai.domain.lms.util.CommonsXmlParser;

/** LearningX material connector backed by the canonical session cookie jar. */
@Component
@ConditionalOnProperty(name = "ssuai.connector.lms-materials", havingValue = "real")
public class RealLmsMaterialsConnector implements LmsMaterialsConnector {

    private static final Logger log = LoggerFactory.getLogger(RealLmsMaterialsConnector.class);

    private final LmsSsoProperties properties;
    private final ObjectMapper objectMapper;
    private final LmsMaterialSizeResolver sizeResolver;
    private final LmsSessionStore sessionStore;

    @Autowired
    public RealLmsMaterialsConnector(
            LmsSsoProperties properties,
            ObjectMapper objectMapper,
            LmsMaterialSizeResolver sizeResolver,
            LmsSessionStore sessionStore) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.sizeResolver = sizeResolver;
        this.sessionStore = sessionStore;
    }

    /** Compatibility constructor used by isolated connector tests. */
    public RealLmsMaterialsConnector(
            LmsSsoProperties properties,
            ObjectMapper objectMapper,
            LmsMaterialSizeResolver sizeResolver) {
        this(properties, objectMapper, sizeResolver, null);
    }

    @Override
    public List<LmsCourse> fetchCourses(String studentId, LmsCookies cookies, long termId) {
        String url = properties.getCanvasBaseUrl()
                + "/learningx/api/v1/learn_activities/courses?term_ids[]=" + termId;
        JsonNode root = http(cookies, url).getJson(url, true);
        List<LmsCourse> result = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode node : root) {
                long id = node.path("id").asLong();
                if (id > 0) {
                    result.add(new LmsCourse(
                            id,
                            node.path("name").asText(""),
                            node.path("course_code").asText(""),
                            termId));
                }
            }
        }
        return List.copyOf(result);
    }

    @Override
    public List<LmsMaterial> fetchMaterials(
            String studentId, LmsCookies cookies, LmsCourse course) {
        String url = properties.getCanvasBaseUrl()
                + "/learningx/api/v1/courses/" + course.id()
                + "/modules?include_detail=true";
        LmsHttpSession http = http(cookies, url);
        JsonNode root = http.getJson(url, true);
        List<LmsMaterial> result = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode moduleNode : root) {
                appendModuleMaterials(result, moduleNode, course);
            }
        }
        return correctUnreliableSizes(cookies, http, result);
    }

    private static void appendModuleMaterials(
            List<LmsMaterial> destination, JsonNode moduleNode, LmsCourse course) {
        String weekTitle = moduleNode.path("title").asText("");
        JsonNode items = moduleNode.path("module_items");
        if (!items.isArray()) {
            return;
        }
        for (JsonNode itemNode : items) {
            JsonNode item = itemNode.path("content_data").path("item_content_data");
            if (item.isMissingNode() || item.isNull()) {
                continue;
            }
            String contentId = item.path("content_id").asText("");
            if (contentId.isBlank()) {
                continue;
            }
            String fileName = item.path("file_name").isTextual()
                    ? item.path("file_name").asText() : null;
            String extension = extension(fileName);
            JsonNode sizeNode = item.path("total_file_size");
            Long sizeBytes = sizeNode.isNumber() ? sizeNode.asLong() : null;
            destination.add(new LmsMaterial(
                    contentId,
                    course.id(),
                    course.name(),
                    fileName,
                    extension,
                    sizeBytes,
                    weekTitle,
                    item.path("title").asText(itemNode.path("title").asText("")),
                    item.path("content_type").asText(null)));
        }
    }

    @Override
    public Optional<ContentDownloadInfo> resolveDownload(
            LmsCookies cookies, String contentId) {
        String url = contentUrl(contentId);
        return resolveDownload(cookies, contentId, http(cookies, url));
    }

    private Optional<ContentDownloadInfo> resolveDownload(
            LmsCookies cookies,
            String contentId,
            LmsHttpSession http) {
        String body = http.getText(contentUrl(contentId), false);
        CommonsXmlParser.ParsedContent parsed = CommonsXmlParser.parse(body);
        if (parsed == null || parsed.downloadUri() == null || parsed.downloadUri().isBlank()) {
            return Optional.empty();
        }
        String absoluteUrl = properties.getCommonsBaseUrl() + parsed.downloadUri();
        return Optional.of(new ContentDownloadInfo(contentId, parsed.title(), absoluteUrl));
    }

    private List<LmsMaterial> correctUnreliableSizes(
            LmsCookies cookies,
            LmsHttpSession http,
            List<LmsMaterial> materials) {
        List<LmsMaterial> corrected = new ArrayList<>(materials.size());
        int attempted = 0;
        int resolved = 0;
        for (LmsMaterial material : materials) {
            if (hasReliableReportedSize(material)) {
                corrected.add(material);
                continue;
            }
            attempted++;
            Long resolvedSize = null;
            Optional<ContentDownloadInfo> download = resolveDownload(
                    cookies, material.contentId(), http);
            if (download.isPresent()) {
                OptionalLong contentLength = sizeResolver.resolve(
                        http.client(), http.cookies(), download.get().absoluteDownloadUrl(), properties.getTimeout());
                if (contentLength.isPresent()) {
                    resolvedSize = contentLength.getAsLong();
                    resolved++;
                }
            }
            corrected.add(new LmsMaterial(
                    material.contentId(), material.courseId(), material.courseName(),
                    material.fileName(), material.extension(), resolvedSize,
                    material.weekTitle(), material.title(), material.contentType()));
        }
        if (attempted > 0) {
            log.debug("LMS material size enrichment attempted={} resolved={} unknown={}",
                    attempted, resolved, attempted - resolved);
        }
        return List.copyOf(corrected);
    }

    @Override
    public void download(
            LmsCookies cookies, String absoluteDownloadUrl, OutputStream destination) {
        http(cookies, absoluteDownloadUrl).download(absoluteDownloadUrl, destination);
    }

    private LmsHttpSession http(LmsCookies cookies, String initialUrl) {
        LmsCookies latest = latest(cookies);
        return new LmsHttpSession(
                objectMapper, sessionStore, latest, properties.getTimeout(), initialUrl);
    }

    private LmsCookies latest(LmsCookies cookies) {
        if (sessionStore == null || cookies.sessionKey() == null || cookies.sessionKey().isBlank()) {
            return cookies;
        }
        return sessionStore.cookies(cookies.sessionKey())
                .orElseThrow(com.ssuai.global.exception.LmsSessionExpiredException::new);
    }

    private String contentUrl(String contentId) {
        return properties.getCommonsBaseUrl()
                + "/viewer/ssplayer/uniplayer_support/content.php?content_id="
                + URLEncoder.encode(contentId, StandardCharsets.UTF_8);
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int lastDot = fileName.lastIndexOf('.');
        return lastDot >= 0 && lastDot < fileName.length() - 1
                ? fileName.substring(lastDot + 1).toLowerCase().trim()
                : null;
    }

    private static boolean hasReliableReportedSize(LmsMaterial material) {
        return "pdf".equalsIgnoreCase(material.contentType())
                && material.sizeBytes() != null
                && material.sizeBytes() > 0;
    }
}
