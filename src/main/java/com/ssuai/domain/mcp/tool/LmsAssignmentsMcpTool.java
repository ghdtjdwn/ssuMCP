package com.ssuai.domain.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.lms.dto.AssignmentsCompactResponse;
import com.ssuai.domain.lms.dto.AssignmentsResponse;
import com.ssuai.domain.lms.service.LmsAssignmentsService;

/**
 * MCP tool for the authenticated student's pending LMS assignments (Task 18 Slice C).
 *
 * <p>Requires mcp_session_id with the LMS provider linked. Returns AUTH_REQUIRED with
 * a loginUrl otherwise. The SAINT auth callback also links LMS as a best-effort, so a
 * single login usually covers both.
 *
 * <p>The chatbot path (LlmChatService) calls LmsAssignmentsService directly and does
 * not invoke this method.
 */
@Component
public class LmsAssignmentsMcpTool {

    private static final Logger log = LoggerFactory.getLogger(LmsAssignmentsMcpTool.class);

    private final LmsAssignmentsService assignmentsService;
    private final McpAuthHelper authHelper;

    public LmsAssignmentsMcpTool(LmsAssignmentsService assignmentsService, McpAuthHelper authHelper) {
        this.assignmentsService = assignmentsService;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "get_my_assignments",
            description = "Returns the authenticated student's pending LMS assignments and quizzes for the current term. "
                    + "Requires mcp_session_id with the LMS provider linked via start_auth. "
                    + "compact=true 지원. "
                    + "Returns AUTH_REQUIRED with a loginUrl if LMS is not authenticated — "
                    + "show the loginUrl to the user and ask them to open it in a browser, "
                    + "then retry this call with the returned mcp_session_id."
    )
    public McpPrivateToolResponse<Object> getMyAssignments(
            @ToolParam(description = "MCP session ID issued by start_auth(LMS). If absent or LMS not linked, returns AUTH_REQUIRED with a loginUrl.")
            String mcp_session_id,
            @ToolParam(description = "compact=true: 과제명·마감일만 반환. compact=false(기본): 상세 정보 포함.", required = false)
            Boolean compact) {
        boolean isCompact = Boolean.TRUE.equals(compact);
        return authHelper.principalKey(mcp_session_id, McpProviderType.LMS)
                .map(studentId -> {
                    log.debug("get_my_assignments: fetching assignments");
                    AssignmentsResponse data = assignmentsService.fetchAssignments(studentId);
                    Object payload = isCompact
                            ? AssignmentsCompactResponse.from(data)
                            : data;
                    return McpPrivateToolResponse.ok(mcp_session_id, payload);
                })
                .orElseGet(() -> {
                    log.debug("get_my_assignments: LMS not linked, returning AUTH_REQUIRED");
                    return authHelper.<Object>buildAuthRequired(mcp_session_id, McpProviderType.LMS);
                });
    }
}
