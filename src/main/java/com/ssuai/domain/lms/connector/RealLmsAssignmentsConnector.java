package com.ssuai.domain.lms.connector;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.auth.lms.LmsSsoProperties;
import com.ssuai.domain.lms.dto.AssignmentItem;
import com.ssuai.domain.lms.dto.AssignmentsResponse;
import com.ssuai.domain.lms.dto.LmsTermItem;
import com.ssuai.domain.lms.service.LmsTermResolver;
import com.ssuai.global.exception.ConnectorParseException;

/** LearningX assignment connector backed by the canonical session cookie jar. */
@Component
@ConditionalOnProperty(name = "ssuai.connector.lms-assignments", havingValue = "real")
class RealLmsAssignmentsConnector implements LmsAssignmentsConnector {

    private final LmsSsoProperties properties;
    private final ObjectMapper objectMapper;
    private final LmsSessionStore sessionStore;

    @Autowired
    RealLmsAssignmentsConnector(
            LmsSsoProperties properties,
            ObjectMapper objectMapper,
            LmsSessionStore sessionStore) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.sessionStore = sessionStore;
    }

    /** Compatibility constructor used by isolated connector tests. */
    RealLmsAssignmentsConnector(LmsSsoProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, null);
    }

    @Override
    public List<LmsTermItem> fetchTerms(String studentId, LmsCookies cookies) {
        LmsHttpSession http = http(cookies);
        return fetchTermItems(http, studentId);
    }

    @Override
    public AssignmentsResponse fetchAssignments(
            String studentId, LmsCookies cookies, Long termId) {
        LmsHttpSession http = http(cookies);
        long resolvedTermId = termId != null
                ? termId
                : LmsTermResolver.resolveCurrentTermId(fetchTermItems(http, studentId));
        Map<Long, String> courseNames = fetchCourseNames(http, resolvedTermId);
        List<AssignmentItem> items = fetchTodoItems(http, resolvedTermId, courseNames);
        return new AssignmentsResponse(resolvedTermId, items);
    }

    private List<LmsTermItem> fetchTermItems(LmsHttpSession http, String studentId) {
        String encoded = URLEncoder.encode(studentId, StandardCharsets.UTF_8);
        String url = properties.getCanvasBaseUrl()
                + "/learningx/api/v1/users/" + encoded
                + "/terms?include_invited_course_contained=true";
        JsonNode terms = http.getJson(url, true).path("enrollment_terms");
        List<LmsTermItem> result = new ArrayList<>();
        if (terms.isArray()) {
            for (JsonNode term : terms) {
                long id = term.path("id").asLong();
                if (id <= 0) {
                    continue;
                }
                result.add(new LmsTermItem(
                        id,
                        term.path("name").asText(""),
                        term.path("start_at").isNull() ? null : term.path("start_at").asText(null),
                        term.path("end_at").isNull() ? null : term.path("end_at").asText(null),
                        term.path("default").asBoolean(false)));
            }
        }
        if (result.isEmpty()) {
            throw new ConnectorParseException();
        }
        return List.copyOf(result);
    }

    private Map<Long, String> fetchCourseNames(LmsHttpSession http, long termId) {
        String url = properties.getCanvasBaseUrl()
                + "/learningx/api/v1/learn_activities/courses?term_ids[]=" + termId;
        JsonNode body = http.getJson(url, true);
        Map<Long, String> result = new HashMap<>();
        if (body.isArray()) {
            for (JsonNode course : body) {
                long id = course.path("id").asLong();
                if (id > 0) {
                    result.put(id, course.path("name").asText(""));
                }
            }
        }
        return result;
    }

    private List<AssignmentItem> fetchTodoItems(
            LmsHttpSession http,
            long termId,
            Map<Long, String> courseNames) {
        String url = properties.getCanvasBaseUrl()
                + "/learningx/api/v1/learn_activities/to_dos?term_ids[]=" + termId;
        JsonNode todos = http.getJson(url, true).path("to_dos");
        List<AssignmentItem> items = new ArrayList<>();
        if (!todos.isArray()) {
            return items;
        }
        for (JsonNode courseNode : todos) {
            long courseId = courseNode.path("course_id").asLong();
            String courseName = courseNames.getOrDefault(courseId, "Unknown Course");
            JsonNode todoList = courseNode.path("todo_list");
            if (!todoList.isArray()) {
                continue;
            }
            for (JsonNode todo : todoList) {
                String dueDate = todo.path("due_date").isNull()
                        ? null : todo.path("due_date").asText(null);
                items.add(new AssignmentItem(
                        courseName,
                        todo.path("title").asText(""),
                        todo.path("component_type").asText("assignment"),
                        dueDate));
            }
        }
        return items;
    }

    private LmsHttpSession http(LmsCookies cookies) {
        LmsCookies latest = latest(cookies);
        return new LmsHttpSession(
                objectMapper,
                sessionStore,
                latest,
                properties.getTimeout(),
                properties.getCanvasBaseUrl());
    }

    private LmsCookies latest(LmsCookies cookies) {
        if (sessionStore == null || cookies.sessionKey() == null || cookies.sessionKey().isBlank()) {
            return cookies;
        }
        return sessionStore.cookies(cookies.sessionKey())
                .orElseThrow(com.ssuai.global.exception.LmsSessionExpiredException::new);
    }
}
