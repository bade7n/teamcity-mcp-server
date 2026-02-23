package com.example.teamcity.mcp;

import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class McpTransportController extends BaseController {
  public enum Mode {
    SSE,
    MESSAGE
  }

  private final McpSseServerTransportProvider transport;
  private final McpTokenAuth auth;
  private final Mode mode;

  public McpTransportController(@NotNull WebControllerManager webControllerManager,
                                @NotNull McpTokenAuth auth,
                                @NotNull McpSseServerTransportProvider transport,
                                @NotNull String path,
                                @NotNull Mode mode) {
    this.transport = transport;
    this.auth = auth;
    this.mode = mode;
    webControllerManager.registerController(path, this);
  }

  @Override
  protected ModelAndView doHandle(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) throws Exception {
    if (!auth.isAuthorized(request)) {
      response.setStatus(401);
      return null;
    }

    if (mode == Mode.SSE) {
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
