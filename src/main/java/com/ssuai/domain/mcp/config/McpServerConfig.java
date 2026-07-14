package com.ssuai.domain.mcp.config;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.ssuai.domain.mcp.tool.AcademicPolicyMcpTools;
import com.ssuai.domain.mcp.tool.CampusMcpTools;
import com.ssuai.domain.mcp.tool.ConfirmActionMcpTool;
import com.ssuai.domain.mcp.tool.DormMcpTools;
import com.ssuai.domain.mcp.tool.LibraryAvailableSeatsMcpTool;
import com.ssuai.domain.mcp.tool.LibraryBookMcpTool;
import com.ssuai.domain.mcp.tool.LibraryCancelMcpTool;
import com.ssuai.domain.mcp.tool.LibraryCurrentSeatMcpTool;
import com.ssuai.domain.mcp.tool.LibrarySeatCatalogMcpTool;
import com.ssuai.domain.mcp.tool.LibraryLoansMcpTool;
import com.ssuai.domain.mcp.tool.LibraryReservationMcpTool;
import com.ssuai.domain.mcp.tool.LibraryRoomAvailableSeatsMcpTool;
import com.ssuai.domain.mcp.tool.LibrarySeatMcpTool;
import com.ssuai.domain.mcp.tool.LibrarySeatRecommendationMcpTool;
import com.ssuai.domain.mcp.tool.LibrarySwapMcpTool;
import com.ssuai.domain.mcp.tool.LibraryWaitMcpTool;
import com.ssuai.domain.mcp.tool.LmsAssignmentsMcpTool;
import com.ssuai.domain.mcp.tool.LmsDashboardMcpTool;
import com.ssuai.domain.mcp.tool.LmsMaterialsMcpTool;
import com.ssuai.domain.mcp.tool.LmsMaterialExportMcpTool;
import com.ssuai.domain.mcp.tool.MealMcpTools;
import com.ssuai.domain.mcp.tool.McpAuthMcpTools;
import com.ssuai.domain.mcp.tool.NoticeMcpTools;
import com.ssuai.domain.mcp.tool.SaintExtendedMcpTools;
import com.ssuai.domain.mcp.tool.SaintGradesMcpTool;
import com.ssuai.domain.mcp.tool.SaintScheduleMcpTool;
import com.ssuai.global.kafka.ToolCallEventProducer;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

@Configuration
class McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);

    /**
     * Tools that write/modify state: auth session creation and logout.
     * All other tools are read-only data queries.
     */
    private static final Set<String> DESTRUCTIVE_TOOLS = Set.of("logout_provider", "logout_all", "cancel_library_wait");
    private static final Set<String> WRITE_TOOLS = Set.of(
            "start_auth",
            "logout_provider",
            "logout_all",
            "prepare_reserve_library_seat",
            "prepare_cancel_library_seat",
            "prepare_swap_library_seat",
            "wait_for_library_seat",
            "cancel_library_wait",
            "confirm_action",
            "prepare_lms_material_export",
            "confirm_lms_material_export",
            "export_all_lms_materials");
    private static final Set<String> PLAYMCP_TOOLS = Set.of(
            "start_auth",
            "logout_all",
            "get_my_schedule",
            "get_my_grades",
            "get_my_assignments",
            "get_lms_dashboard",
            "recommend_library_seats",
            "prepare_reserve_library_seat",
            "confirm_action");

    @Bean
    ToolCallbackProvider ssuaiMcpTools(
            @Value("${ssuai.mcp.tool-profile:full}") String toolProfile,
            MealMcpTools mealMcpTools,
            DormMcpTools dormMcpTools,
            CampusMcpTools campusMcpTools,
            McpAuthMcpTools mcpAuthMcpTools,
            LibrarySeatMcpTool libraryMcpTool,
            LibrarySeatCatalogMcpTool librarySeatCatalogMcpTool,
            LibrarySeatRecommendationMcpTool librarySeatRecommendationMcpTool,
            LibraryAvailableSeatsMcpTool libraryAvailableSeatsMcpTool,
            LibraryRoomAvailableSeatsMcpTool libraryRoomAvailableSeatsMcpTool,
            LibraryBookMcpTool libraryBookMcpTool,
            LibraryLoansMcpTool libraryLoansMcpTool,
            LibraryReservationMcpTool libraryReservationMcpTool,
            LibraryCancelMcpTool libraryCancelMcpTool,
            LibraryCurrentSeatMcpTool libraryCurrentSeatMcpTool,
            LibrarySwapMcpTool librarySwapMcpTool,
            LibraryWaitMcpTool libraryWaitMcpTool,
            ConfirmActionMcpTool confirmActionMcpTool,
            SaintScheduleMcpTool saintScheduleMcpTool,
            SaintGradesMcpTool saintGradesMcpTool,
            SaintExtendedMcpTools saintExtendedMcpTools,
            LmsAssignmentsMcpTool lmsAssignmentsMcpTool,
            LmsDashboardMcpTool lmsDashboardMcpTool,
            LmsMaterialsMcpTool lmsMaterialsMcpTool,
            LmsMaterialExportMcpTool lmsMaterialExportMcpTool,
            NoticeMcpTools noticeMcpTools,
            AcademicPolicyMcpTools academicPolicyMcpTools
    ) {
        Object[] toolObjects = switch (toolProfile) {
            case "full" -> new Object[] {
                        mealMcpTools,
                        dormMcpTools,
                        campusMcpTools,
                        mcpAuthMcpTools,
                        libraryMcpTool,
                        librarySeatCatalogMcpTool,
                        librarySeatRecommendationMcpTool,
                        libraryAvailableSeatsMcpTool,
                        libraryRoomAvailableSeatsMcpTool,
                        libraryBookMcpTool,
                        libraryLoansMcpTool,
                        libraryReservationMcpTool,
                        libraryCancelMcpTool,
                        libraryCurrentSeatMcpTool,
                        librarySwapMcpTool,
                        libraryWaitMcpTool,
                        confirmActionMcpTool,
                        saintScheduleMcpTool,
                        saintGradesMcpTool,
                        saintExtendedMcpTools,
                        lmsAssignmentsMcpTool,
                        lmsDashboardMcpTool,
                        lmsMaterialsMcpTool,
                        lmsMaterialExportMcpTool,
                        noticeMcpTools,
                        academicPolicyMcpTools};
            // PlayMCP accepts no more than 20 tools and recommends 3–10. The contest
            // surface keeps the product's differentiator: user-authorized, live school
            // data and the explicit prepare → confirm library reservation workflow.
            case "playmcp" -> new Object[] {
                        mcpAuthMcpTools,
                        librarySeatRecommendationMcpTool,
                        libraryReservationMcpTool,
                        confirmActionMcpTool,
                        saintScheduleMcpTool,
                        saintGradesMcpTool,
                        lmsAssignmentsMcpTool,
                        lmsDashboardMcpTool};
            default -> throw new IllegalArgumentException(
                    "Unsupported ssuai.mcp.tool-profile: " + toolProfile + " (expected full or playmcp)");
        };

        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects)
                .build();
        if (!"playmcp".equals(toolProfile)) {
            return provider;
        }

        ToolCallback[] contestCallbacks = Arrays.stream(provider.getToolCallbacks())
                .filter(callback -> PLAYMCP_TOOLS.contains(callback.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
        if (contestCallbacks.length != PLAYMCP_TOOLS.size()) {
            throw new IllegalStateException("PlayMCP tool profile did not resolve every required tool");
        }
        return () -> contestCallbacks;
    }

    /**
     * Adds MCP tool annotations (readOnlyHint, destructiveHint) to all registered tools
     * so that Claude and other MCP clients can group tools visually into
     * "Read-only tools" and "Write/delete tools" sections.
     *
     * <p>Also sets immediateExecution(true) which is normally handled by the auto-configured
     * servletMcpSyncServerCustomizer — this bean replaces that one as {@code @Primary}.
     *
     * <p>Annotation semantics:
     * <ul>
     *   <li>readOnlyHint=true — tool only reads data, no side effects</li>
     *   <li>destructiveHint=true — tool deletes or invalidates state (logout operations)</li>
     * </ul>
     */
    @Primary
    @Bean
    McpSyncServerCustomizer ssuaiToolAnnotationsCustomizer(ObjectProvider<ToolCallEventProducer> producerProvider) {
        ToolCallEventProducer producer = producerProvider.getIfAvailable();
        return spec -> {
            // WebMVC servlet mode requires immediate (non-deferred) execution.
            // This mirrors what the auto-configured servletMcpSyncServerCustomizer does.
            spec.immediateExecution(true);

            // Access the package-private `tools` list via reflection to rebuild each
            // McpSchema.Tool with the appropriate ToolAnnotations.
            try {
                Field toolsField = McpServer.SyncSpecification.class.getDeclaredField("tools");
                toolsField.setAccessible(true);

                @SuppressWarnings("unchecked")
                List<McpServerFeatures.SyncToolSpecification> tools =
                        (List<McpServerFeatures.SyncToolSpecification>) toolsField.get(spec);

                List<McpServerFeatures.SyncToolSpecification> annotated = tools.stream()
                        .map(tool -> withAnnotations(tool, producer))
                        .collect(Collectors.toList());

                tools.clear();
                tools.addAll(annotated);

                log.debug("Applied MCP tool annotations to {} tools", annotated.size());
            } catch (NoSuchFieldException | IllegalAccessException e) {
                log.warn("Could not apply MCP tool annotations — tool grouping will be unavailable", e);
            }
        };
    }

    private static McpServerFeatures.SyncToolSpecification withAnnotations(
            McpServerFeatures.SyncToolSpecification original,
            ToolCallEventProducer producer) {

        String name = original.tool().name();
        boolean readOnly = !WRITE_TOOLS.contains(name);
        boolean destructive = DESTRUCTIVE_TOOLS.contains(name);

        McpSchema.ToolAnnotations annotations = new McpSchema.ToolAnnotations(
                displayTitle(name),
                readOnly,    // readOnlyHint: true for all data-query tools
                destructive, // destructiveHint: true only for logout operations
                readOnly,    // idempotentHint: read operations are safe to retry
                true,        // openWorldHint: tools use current campus data sources
                null         // returnDirect
        );

        McpSchema.Tool annotatedTool = McpSchema.Tool.builder()
                .name(original.tool().name())
                .description(original.tool().description())
                .inputSchema(original.tool().inputSchema())
                .annotations(annotations)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(annotatedTool)
                .callHandler(producer == null ? original.callHandler() : wrapCallHandler(original, producer))
                .build();
    }

    private static String displayTitle(String toolName) {
        return toolName.replace('_', ' ');
    }

    static BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> wrapCallHandler(
            McpServerFeatures.SyncToolSpecification original,
            ToolCallEventProducer producer) {

        var delegate = original.callHandler();
        String toolName = original.tool().name();
        return (exchange, request) -> {
            long start = System.nanoTime();
            String requestId = MDC.get("requestId");
            String outcome = "ok";
            try {
                McpSchema.CallToolResult result = delegate.apply(exchange, request);
                if (result != null && Boolean.TRUE.equals(result.isError())) {
                    outcome = "tool_error";
                }
                return result;
            } catch (RuntimeException ex) {
                outcome = "exception";
                throw ex;
            } finally {
                try {
                    producer.tryEmit(toolName, requestId, (System.nanoTime() - start) / 1_000_000L, outcome);
                } catch (Throwable ignored) {
                }
            }
        };
    }
}
