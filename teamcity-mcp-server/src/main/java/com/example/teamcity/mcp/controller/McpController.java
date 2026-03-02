package com.example.teamcity.mcp.controller;

import com.example.teamcity.mcp.McpSseServerTransportProvider;
import com.example.teamcity.mcp.McpTokenAuth;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import jetbrains.buildServer.controllers.BaseController;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.servlet.ModelAndView;

public class McpController extends BaseController {
  public enum Mode {
    SSE,
    MESSAGE
  }

  private final McpTokenAuth auth;
  private final Mode mode;
  private final McpSseServerTransportProvider transport;

  public McpController(McpTokenAuth auth, Mode mode, McpSseServerTransportProvider transport) {
    this.auth = auth;
    this.mode = mode;
    this.transport = transport;
  }

  @Override
  protected ModelAndView doHandle(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) throws Exception {
    if (!auth.isAuthorized(request)) {
      response.setStatus(401);
      return null;
    }

    if (mode == McpSSETransportController.Mode.SSE) {
      if (!"GET".equalsIgnoreCase(request.getMethod())) {
        response.setStatus(405);
        return null;
      }
      transport.handleSse(request, response);
      return null;
    }

    if (!"POST".equalsIgnoreCase(request.getMethod())) {
      response.setStatus(405);
      return null;
    }
    transport.handleMessage(request, response);
    return null;
  }

}
