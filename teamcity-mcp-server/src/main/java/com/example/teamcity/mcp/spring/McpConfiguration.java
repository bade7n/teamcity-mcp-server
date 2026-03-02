package com.example.teamcity.mcp.spring;

import com.example.teamcity.mcp.McpSseServerTransportProvider;
import com.example.teamcity.mcp.McpTeamCityServer;
import com.example.teamcity.mcp.McpTokenAuth;
import com.example.teamcity.mcp.controller.McpMessageController;
import com.example.teamcity.mcp.controller.McpSSETransportController;
import jetbrains.buildServer.serverSide.SBuildServer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfiguration {
  private static final Logger logger = LoggerFactory.getLogger(McpConfiguration.class);

  @Bean
  public McpTokenAuth tokenAuth() {
    logger.warn("MCP: Using token auth");
    return new McpTokenAuth();
  }

  @Bean
  public McpTeamCityServer teamCityServer(@NotNull SBuildServer buildServer, @NotNull McpSseServerTransportProvider transportProvider) {
    logger.warn("MCP: Initializing TeamCity server with token auth");
    return new McpTeamCityServer(buildServer, transportProvider);
  }

  @Bean
  public McpSseServerTransportProvider sseServerTransportProvider(@NotNull SBuildServer buildServer) {
    String baseUrl = buildServer.getRootUrl();
    return McpSseServerTransportProvider.builder()
                                 .baseUrl(baseUrl == null ? "" : baseUrl)
                                 .build();
  }
}
