package com.example.teamcity.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson.JacksonJsonSchemaValidatorSupplier;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SQueuedBuild;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.buildLog.LogMessage;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.DisposableBean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class McpTeamCityServer implements DisposableBean {
  private static final int DEFAULT_LOG_POLL_INTERVAL_MS = 1000;
  private static final int MIN_LOG_POLL_INTERVAL_MS = 200;
  private static final int MAX_MATCH_SUGGESTIONS = 10;
  private static final String METHOD_BUILD_LOG_CHUNK = "teamcity/buildLogChunk";
  private static final String METHOD_BUILD_LOG_FINISHED = "teamcity/buildLogFinished";
  private static final String METHOD_BUILD_LOG_ERROR = "teamcity/buildLogError";

  private final McpSseServerTransportProvider transport;
  private final McpSyncServer server;
  private final SBuildServer buildServer;
  private final ExecutorService logStreamExecutor = Executors.newCachedThreadPool();
  private final ConcurrentMap<Long, Future<?>> activeLogStreams = new ConcurrentHashMap<>();

  public McpTeamCityServer(@NotNull SBuildServer buildServer, McpSseServerTransportProvider transport) {
    this.buildServer = buildServer;
    this.transport = transport;

    McpJsonMapper jsonMapper = resolveDefaultMapper();
    JsonSchemaValidator schemaValidator = resolveDefaultSchemaValidator();
    this.server = McpServer.sync(transport)
      .serverInfo("teamcity-mcp", "1.0.0")
      .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
      .jsonMapper(jsonMapper)
      .jsonSchemaValidator(schemaValidator)
      .toolCall(startBuildTool(), (exchange, request) -> startBuild(buildServer, request))
      .toolCall(startBuildByNameTool(), (exchange, request) -> startBuildByName(buildServer, request))
      .toolCall(buildStatusTool(), (exchange, request) -> buildStatus(buildServer, request))
      .toolCall(buildLogTool(), (exchange, request) -> buildLog(buildServer, request))
      .toolCall(streamBuildLogTool(), (exchange, request) -> streamBuildLog(request))
      .build();
  }

  @Override
  public void destroy() {
    for (Future<?> streamTask : activeLogStreams.values()) {
      streamTask.cancel(true);
    }
    activeLogStreams.clear();
    logStreamExecutor.shutdownNow();
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

  private static McpSchema.Tool startBuildByNameTool() {
    return McpSchema.Tool.builder()
      .name("start_build_by_name")
      .description("Start a TeamCity build by build configuration name.")
      .inputSchema(objectSchema(
        Map.of("buildConfigName", stringSchema()),
        List.of("buildConfigName")
      ))
      .build();
  }

  private static McpSchema.Tool streamBuildLogTool() {
    return McpSchema.Tool.builder()
      .name("stream_build_log_sse")
      .description("Stream build log chunks to SSE as MCP notifications while build is running.")
      .inputSchema(objectSchema(
        Map.of(
          "buildId", stringSchema(),
          "pollIntervalMs", stringSchema()
        ),
        List.of("buildId")
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

    Map<String, Object> payload = new HashMap<>();
    payload.put("queueItemId", queueItemId);
    payload.put("state", "queued");
    if (buildId != null) {
      payload.put("buildId", buildId);
    }

    return successResult(payload, "queued");
  }

  private static McpSchema.CallToolResult startBuildByName(SBuildServer buildServer, McpSchema.CallToolRequest request) {
    Map<String, Object> args = safeArgs(request.arguments());
    String buildConfigName = asString(args.get("buildConfigName"));
    if (buildConfigName == null || buildConfigName.isBlank()) {
      return errorResult("buildConfigName is required");
    }

    List<SBuildType> allBuildTypes = buildServer.getProjectManager().getRootProject().getBuildTypes();
    List<SBuildType> exactMatches = new ArrayList<>();
    String needle = buildConfigName.trim().toLowerCase(Locale.ROOT);

    for (SBuildType bt : allBuildTypes) {
      if (bt == null) continue;
      String name = bt.getName();
      String fullName = bt.getFullName();
      String externalId = bt.getExternalId();

      if (equalsIgnoreCase(name, needle) || equalsIgnoreCase(fullName, needle) || equalsIgnoreCase(externalId, needle)) {
        exactMatches.add(bt);
      }
    }

    if (exactMatches.isEmpty()) {
      List<String> suggestions = new ArrayList<>();
      for (SBuildType bt : allBuildTypes) {
        if (bt == null) continue;
        String fullName = safeString(bt.getFullName()).toLowerCase(Locale.ROOT);
        String name = safeString(bt.getName()).toLowerCase(Locale.ROOT);
        String externalId = safeString(bt.getExternalId()).toLowerCase(Locale.ROOT);
        if (fullName.contains(needle) || name.contains(needle) || externalId.contains(needle)) {
          suggestions.add(bt.getFullName() + " [" + bt.getExternalId() + "]");
          if (suggestions.size() >= MAX_MATCH_SUGGESTIONS) break;
        }
      }
      return errorResult(suggestions.isEmpty()
                         ? "build_config_not_found"
                         : "build_config_not_found. suggestions: " + String.join(", ", suggestions));
    }

    if (exactMatches.size() > 1) {
      List<String> conflicts = new ArrayList<>();
      for (SBuildType bt : exactMatches) {
        conflicts.add(bt.getFullName() + " [" + bt.getExternalId() + "]");
      }
      return errorResult("ambiguous_build_config_name. matches: " + String.join(", ", conflicts));
    }

    SBuildType selected = exactMatches.get(0);
    SQueuedBuild queued = selected.addToQueue("MCP request");
    Long buildId = extractBuildId(queued);
    String queueItemId = extractQueueItemId(queued);

    Map<String, Object> payload = new HashMap<>();
    payload.put("state", "queued");
    payload.put("buildConfigName", selected.getFullName());
    payload.put("buildTypeId", selected.getExternalId());
    payload.put("queueItemId", queueItemId);
    if (buildId != null) {
      payload.put("buildId", buildId);
    }

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

  private McpSchema.CallToolResult streamBuildLog(McpSchema.CallToolRequest request) {
    Map<String, Object> args = safeArgs(request.arguments());
    String buildIdParam = asString(args.get("buildId"));
    if (buildIdParam == null || buildIdParam.isBlank()) {
      return errorResult("buildId is required");
    }

    long buildId;
    try {
      buildId = Long.parseLong(buildIdParam);
    } catch (NumberFormatException ex) {
      return errorResult("buildId must be a number");
    }

    int pollIntervalMs = parsePollInterval(asString(args.get("pollIntervalMs")));
    Future<?> existing = activeLogStreams.get(buildId);
    if (existing != null && !existing.isDone()) {
      return successResult(Map.of(
        "buildId", buildId,
        "streaming", true,
        "alreadyRunning", true
      ), "stream_already_running");
    }

    Future<?> started = logStreamExecutor.submit(() -> streamBuildLogLoop(buildId, pollIntervalMs));
    activeLogStreams.put(buildId, started);
    return successResult(Map.of(
      "buildId", buildId,
      "streaming", true,
      "pollIntervalMs", pollIntervalMs
    ), "stream_started");
  }

  private void streamBuildLogLoop(long buildId, int pollIntervalMs) {
    int lastMessageIndex = Integer.MIN_VALUE;
    try {
      while (!Thread.currentThread().isInterrupted()) {
        SBuild build = buildServer.findBuildInstanceById(buildId);
        boolean queueOrPromotionExists = false;
        Long resolvedBuildId = null;

        if (build == null) {
          SQueuedBuild queuedByItem = buildServer.getQueue().findQueued(String.valueOf(buildId));
          if (queuedByItem != null) {
            queueOrPromotionExists = true;
            BuildPromotion promotion = queuedByItem.getBuildPromotion();
            if (promotion != null) {
              resolvedBuildId = promotion.getAssociatedBuildId();
            }
          }

          if (!queueOrPromotionExists) {
            for (SQueuedBuild queued : buildServer.getQueue().getItems()) {
              BuildPromotion promotion = queued.getBuildPromotion();
              if (promotion == null) continue;
              if (promotion.getId() == buildId) {
                queueOrPromotionExists = true;
                resolvedBuildId = promotion.getAssociatedBuildId();
                break;
              }
            }
          }

          if (resolvedBuildId != null) {
            build = buildServer.findBuildInstanceById(resolvedBuildId);
          }
        }

        if (build == null) {
          if (queueOrPromotionExists) {
            TimeUnit.MILLISECONDS.sleep(pollIntervalMs);
            continue;
          }
          notifyLogEvent(METHOD_BUILD_LOG_ERROR, Map.of(
            "buildId", buildId,
            "message", "build_not_found"
          ));
          return;
        }

        Iterator<LogMessage> it = build.getBuildLog().getMessagesIterator();
        while (it.hasNext()) {
          LogMessage message = it.next();
          if (message == null) continue;
          int messageIndex = message.getIndex();
          if (messageIndex <= lastMessageIndex) {
            continue;
          }
          lastMessageIndex = messageIndex;

          String text = safeString(message.getText());
          if (text.isBlank()) {
            continue;
          }

          notifyLogEvent(METHOD_BUILD_LOG_CHUNK, Map.of(
            "buildId", buildId,
            "messageIndex", messageIndex,
            "text", text,
            "status", String.valueOf(message.getStatus()),
            "timestamp", message.getTimestamp().getTime()
          ));
        }

        if (build.isFinished()) {
          String status = build.getBuildStatus() == null ? "unknown" : build.getBuildStatus().toString().toLowerCase(Locale.ROOT);
          notifyLogEvent(METHOD_BUILD_LOG_FINISHED, Map.of(
            "buildId", buildId,
            "status", status
          ));
          return;
        }

        TimeUnit.MILLISECONDS.sleep(pollIntervalMs);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (Exception ex) {
      notifyLogEvent(METHOD_BUILD_LOG_ERROR, Map.of(
        "buildId", buildId,
        "message", safeString(ex.getMessage())
      ));
    } finally {
      activeLogStreams.remove(buildId);
    }
  }

  private void notifyLogEvent(@NotNull String method, @NotNull Object payload) {
    try {
      transport.notifyClients(method, payload).block();
    } catch (Exception ignored) {
      // Drop notification errors to keep log streamer resilient.
    }
  }

  private static McpJsonMapper resolveDefaultMapper() {
    try {
      return McpJsonMapper.getDefault();
    } catch (IllegalStateException ex) {
      return new JacksonMcpJsonMapperSupplier().get();
    }
  }

  private static JsonSchemaValidator resolveDefaultSchemaValidator() {
    try {
      return JsonSchemaValidator.getDefault();
    } catch (IllegalStateException ex) {
      return new JacksonJsonSchemaValidatorSupplier().get();
    }
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

  private static boolean equalsIgnoreCase(String value, String lowercaseNeedle) {
    if (value == null) return false;
    return value.trim().toLowerCase(Locale.ROOT).equals(lowercaseNeedle);
  }

  private static String safeString(String value) {
    return value == null ? "" : value;
  }

  private static int parsePollInterval(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT_LOG_POLL_INTERVAL_MS;
    }
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < MIN_LOG_POLL_INTERVAL_MS) return MIN_LOG_POLL_INTERVAL_MS;
      return parsed;
    } catch (NumberFormatException ex) {
      return DEFAULT_LOG_POLL_INTERVAL_MS;
    }
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
