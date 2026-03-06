package com.example.teamcity.mcp.controller;

import com.example.teamcity.mcp.McpSseServerTransportProvider;
import com.example.teamcity.mcp.McpTokenAuth;
import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;


@RestController
@RequestMapping(McpSSETransportController.ENDPOINT)
public class McpSSETransportController {
  public static final String ENDPOINT = "/mcp/sse";
  public static final String START_ENDPOINT = "/start";

  private final McpSseServerTransportProvider transport;


  public McpSSETransportController(@NotNull McpSseServerTransportProvider transport) {
    this.transport = transport;
    System.out.println("McpSSEMessageController being registered/started");
  }


  @RequestMapping(method = RequestMethod.GET)
  public ModelAndView handle(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) throws IOException {
    if (!"GET".equalsIgnoreCase(request.getMethod())) {
      response.setStatus(405);
      return null;
    }
    transport.handleSse(request, response);
    return null;
  }

  @RequestMapping(value = START_ENDPOINT, method = RequestMethod.GET)
  public ModelAndView handleStart(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) throws IOException {
    if (!"GET".equalsIgnoreCase(request.getMethod())) {
      response.setStatus(405);
      return null;
    }

    String buildTypeId = trimToNull(request.getParameter("buildTypeId"));
    String buildConfigName = trimToNull(request.getParameter("buildConfigName"));
    if (buildTypeId == null && buildConfigName == null) {
      response.sendError(400, "Either buildTypeId or buildConfigName must be provided");
      return null;
    }

    String sessionId = transport.openSseSession(request, response);
    if (sessionId == null) {
      return null;
    }

    transport.sendJsonRpcToSession(sessionId, initializePayload());
    transport.sendJsonRpcToSession(sessionId, initializedNotificationPayload());
    transport.sendJsonRpcToSession(sessionId, startBuildToolCallPayload(buildTypeId, buildConfigName));
    return null;
  }

  private static String initializePayload() {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"teamcity-sse-start\",\"version\":\"1.0\"}}}";
  }

  private static String initializedNotificationPayload() {
    return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}";
  }

  private static String startBuildToolCallPayload(String buildTypeId, String buildConfigName) {
    if (buildTypeId != null) {
      return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"start_build\",\"arguments\":{\"buildTypeId\":\"" + escapeJson(buildTypeId) + "\"}}}";
    }
    return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"start_build_by_name\",\"arguments\":{\"buildConfigName\":\"" + escapeJson(buildConfigName) + "\"}}}";
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String escapeJson(String value) {
    String escaped = value.replace("\\", "\\\\");
    return escaped.replace("\"", "\\\"");
  }
}
