package com.example.teamcity.mcp.spring;

import com.example.teamcity.mcp.McpTeamCityServer;
import com.example.teamcity.mcp.McpTokenAuth;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.web.openapi.WebControllerManager;
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
    logger.info("MCP: Using token auth");
    return new McpTokenAuth();
  }

  @Bean
  public McpTeamCityServer teamCityServer(@NotNull SBuildServer buildServer,
                                          @NotNull WebControllerManager webControllerManager,
                                          @NotNull McpTokenAuth auth) {
    logger.info("MCP: Initializing TeamCity server with token auth");
    return new McpTeamCityServer(buildServer, webControllerManager, auth);
  }
}
