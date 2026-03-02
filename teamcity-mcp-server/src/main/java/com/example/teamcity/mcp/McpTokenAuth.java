package com.example.teamcity.mcp;

import jetbrains.buildServer.serverSide.TeamCityProperties;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;

public class McpTokenAuth {
  private static final String TOKEN_PROPERTY = "mcp.tokens";
  private static final String HEADER_AUTH = "Authorization";
  private static final String HEADER_TOKEN = "X-Api-Token";

  public boolean isAuthorized(HttpServletRequest request) {
    String token = extractToken(request);
    if (token == null || token.isEmpty()) {
      return false;
    }

    Set<String> allowed = loadTokens();
    return allowed.contains(token);
  }

  private String extractToken(HttpServletRequest request) {
    String auth = request.getHeader(HEADER_AUTH);
    if (auth != null && auth.startsWith("Bearer ")) {
      return auth.substring("Bearer ".length()).trim();
    }
    String token = request.getHeader(HEADER_TOKEN);
    if (token != null) {
      return token.trim();
    }
    return null;
  }

  private Set<String> loadTokens() {
    String raw = TeamCityProperties.getPropertyOrNull(TOKEN_PROPERTY);
    if (raw == null || raw.trim().isEmpty()) {
      raw = System.getProperty(TOKEN_PROPERTY);
    }
    if (raw == null || raw.trim().isEmpty()) {
      return Collections.emptySet();
    }
    return Arrays.stream(raw.split(","))
      .map(String::trim)
      .filter(s -> !s.isEmpty())
      .collect(Collectors.toSet());
  }
}
