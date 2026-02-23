package com.example.teamcity.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SQueuedBuild;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.DisposableBean;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class McpTeamCityServer implements DisposableBean {
  private static final String MESSAGE_ENDPOINT = "/mcp/message";
  private static final String SSE_ENDPOINT = "/mcp/sse";

  private final McpSseServerTransportProvider transport;
  private final McpSyncServer server;

  public McpTeamCityServer(@NotNull SBuildServer buildServer,
                           @NotNull WebControllerManager webControllerManager,
                           @NotNull McpTokenAuth auth) {
    String baseUrl = buildServer.getRootUrl();

    this.transport = McpSseServerTransportProvider.builder()
      .baseUrl(baseUrl == null ? "" : baseUrl)
      .messageEndpoint(MESSAGE_ENDPOINT)
      .sseEndpoint(SSE_ENDPOINT)
      .build();

    this.server = McpServer.sync(transport)
      .serverInfo("teamcity-mcp", "1.0.0")
      .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
      .toolCall(startBuildTool(), (exchange, request) -> startBuild(buildServer, request))
      .toolCall(buildStatusTool(), (exchange, request) -> buildStatus(buildServer, request))
      .toolCall(buildLogTool(), (exchange, request) -> buildLog(buildServer, request))
      .build();

    new McpTransportController(webControllerManager, auth, transport, SSE_ENDPOINT, McpTransportController.Mode.SSE);
    new McpTransportController(webControllerManager, auth, transport, MESSAGE_ENDPOINT, McpTransportController.Mode.MESSAGE);
  }

  @Override
  public void destroy() {
    server.closeGracefully();
    transport.closeGracefully().block();
  }

  private static McpSchema.Tool startBuildTool() {
    return McpSchema.Tool.builder()
      .name("start_build")
      .description("Start a TeamCity build by buildTypeId.")
      .inputSchema(objectSchema(
        Map.of("buildTypeId", stringSchema()),
        List.of("buildTypeId")
      ))
      .build();
  }

  private static McpSchema.Tool buildStatusTool() {
    return McpSchema.Tool.builder()
      .name("build_status")
      .description("Get TeamCity build status by buildId or queueItemId.")
      .inputSchema(objectSchema(
        Map.of("id", stringSchema()),
        List.of("id")
      ))
      .build();
  }

  private static McpSchema.Tool buildLogTool() {
    return McpSchema.Tool.builder()
      .name("build_log")
      .description("Get a link to the plain-text build log.")
      .inputSchema(objectSchema(
        Map.of("id", stringSchema()),
        List.of("id")
      ))
      .build();
  }

  private static McpSchema.CallToolResult startBuild(SBuildServer buildServer, McpSchema.CallToolRequest request) {
    Map<String, Object> args = safeArgs(request.arguments());
    String buildTypeId = asString(args.get("buildTypeId"));
    if (buildTypeId == null || buildTypeId.isBlank()) {
      return errorResult("buildTypeId is required");
    }

    SBuildType buildType = buildServer.getProjectManager().findBuildTypeById(buildTypeId);
    if (buildType == null) {
      return errorResult("buildType_not_found");
    }

    SQueuedBuild queued = buildType.addToQueue("MCP request");
    Long buildId = extractBuildId(queued);
    String queueItemId = extractQueueItemId(queued);

    Map<String, Object> payload = Map.of(
      "buildId", buildId,
      "queueItemId", queueItemId,
      "state", "queued"
    );

    return successResult(payload, "queued");
  }

  private static McpSchema.CallToolResult buildStatus(SBuildServer buildServer, McpSchema.CallToolRequest request) {
    Map<String, Object> args = safeArgs(request.arguments());
    String idParam = asString(args.get("id"));
    if (idParam == null || idParam.isBlank()) {
      return errorResult("id is required");
    }

    long buildId;
    try {
      buildId = Long.parseLong(idParam);
    } catch (NumberFormatException ex) {
      return errorResult("id must be a number");
    }

    SBuild build = buildServer.findBuildInstanceById(buildId);
    if (build != null) {
      String state = build.isFinished() ? "finished" : "running";
      String status = build.getBuildStatus() == null ? "unknown" : build.getBuildStatus().toString().toLowerCase();
      return successResult(Map.of(
        "buildId", buildId,
        "state", state,
        "status", status
      ), state + ":" + status);
    }

    SQueuedBuild queued = buildServer.getQueue().findQueued(String.valueOf(buildId));
    if (queued != null) {
      return successResult(Map.of(
        "buildId", buildId,
        "state", "queued",
        "status", "unknown"
      ), "queued");
    }

    return errorResult("build_not_found");
  }

  private static McpSchema.CallToolResult buildLog(SBuildServer buildServer, McpSchema.CallToolRequest request) {
    Map<String, Object> args = safeArgs(request.arguments());
    String idParam = asString(args.get("id"));
    if (idParam == null || idParam.isBlank()) {
      return errorResult("id is required");
    }

    String baseUrl = buildServer.getRootUrl();
    if (baseUrl == null) {
      baseUrl = "";
    }

    String logUrl = baseUrl + "/downloadBuildLog.html?buildId=" + idParam + "&plain=true";
    return successResult(Map.of(
      "buildId", idParam,
      "logUrl", logUrl
    ), "log_url");
  }

  private static McpSchema.CallToolResult successResult(Map<String, Object> structuredContent, String message) {
    return McpSchema.CallToolResult.builder()
      .isError(false)
      .structuredContent(structuredContent)
      .addContent(new McpSchema.TextContent(message))
      .build();
  }

  private static McpSchema.CallToolResult errorResult(String message) {
    return McpSchema.CallToolResult.builder()
      .isError(true)
      .addContent(new McpSchema.TextContent(message))
      .build();
  }

  private static McpSchema.JsonSchema objectSchema(Map<String, Object> properties, List<String> required) {
    return new McpSchema.JsonSchema(
      "object",
      properties,
      required,
      Boolean.FALSE,
      null,
      null
    );
  }

  private static McpSchema.JsonSchema stringSchema() {
    return new McpSchema.JsonSchema(
      "string",
      null,
      null,
      null,
      null,
      null
    );
  }

  private static Map<String, Object> safeArgs(Map<String, Object> args) {
    return args == null ? Collections.emptyMap() : args;
  }

  private static String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static Long extractBuildId(SQueuedBuild queued) {
    try {
      Object promotion = queued.getClass().getMethod("getBuildPromotion").invoke(queued);
      if (promotion == null) {
        return null;
      }
      Object id = promotion.getClass().getMethod("getId").invoke(promotion);
      if (id instanceof Number) {
        return ((Number) id).longValue();
      }
    } catch (Exception ignored) {
      // Method is not part of the open API in some versions.
    }
    return null;
  }

  private static String extractQueueItemId(SQueuedBuild queued) {
    try {
      Object id = queued.getClass().getMethod("getItemId").invoke(queued);
      return id == null ? null : String.valueOf(id);
    } catch (Exception ignored) {
      return null;
    }
  }
}
