package com.example.teamcity.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpTokenAuthTest {
  private static final String TOKEN_PROPERTY = "mcp.tokens";

  @AfterEach
  void clearTokens() {
    System.clearProperty(TOKEN_PROPERTY);
  }

  @Test
  void authorizesBearerToken() {
    System.setProperty(TOKEN_PROPERTY, "alpha, beta");
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("Authorization")).thenReturn("Bearer beta");

    McpTokenAuth auth = new McpTokenAuth();

    assertTrue(auth.isAuthorized(request));
  }

  @Test
  void authorizesApiTokenHeader() {
    System.setProperty(TOKEN_PROPERTY, "alpha, beta");
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("Authorization")).thenReturn(null);
    when(request.getHeader("X-Api-Token")).thenReturn("alpha");

    McpTokenAuth auth = new McpTokenAuth();

    assertTrue(auth.isAuthorized(request));
  }

  @Test
  void rejectsMissingToken() {
    System.setProperty(TOKEN_PROPERTY, "alpha");
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("Authorization")).thenReturn(null);
    when(request.getHeader("X-Api-Token")).thenReturn(null);

    McpTokenAuth auth = new McpTokenAuth();

    assertFalse(auth.isAuthorized(request));
  }

  @Test
  void rejectsUnknownToken() {
    System.setProperty(TOKEN_PROPERTY, "alpha");
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("Authorization")).thenReturn("Bearer gamma");

    McpTokenAuth auth = new McpTokenAuth();

    assertFalse(auth.isAuthorized(request));
  }
}
