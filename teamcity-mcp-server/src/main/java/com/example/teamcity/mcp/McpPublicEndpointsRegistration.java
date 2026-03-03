package com.example.teamcity.mcp;

import com.example.teamcity.mcp.controller.McpMessageController;
import com.example.teamcity.mcp.controller.McpSSETransportController;
import jetbrains.buildServer.controllers.AuthorizationInterceptor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

@Component
public class McpPublicEndpointsRegistration {
  private static final String APP_PREFIX = "/app";

  public McpPublicEndpointsRegistration(@NotNull AuthorizationInterceptor authorizationInterceptor) {
    registerPublicPath(authorizationInterceptor, McpSSETransportController.ENDPOINT);
    registerPublicPath(authorizationInterceptor, McpMessageController.ENDPOINT);
  }

  @SuppressWarnings("deprecation")
  private static void registerPublicPath(@NotNull AuthorizationInterceptor authorizationInterceptor,
                                         @NotNull String endpoint) {
    String appEndpoint = APP_PREFIX + endpoint;
    authorizationInterceptor.addPathNotRequiringAuth(appEndpoint);
    authorizationInterceptor.addPathNotRequiringAuth(appEndpoint + "/**");
    authorizationInterceptor.addPathNotRequiringAuth(endpoint);
    authorizationInterceptor.addPathNotRequiringAuth(endpoint + "/**");
  }
}
