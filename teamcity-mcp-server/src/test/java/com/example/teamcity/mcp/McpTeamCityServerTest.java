package com.example.teamcity.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SQueuedBuild;
import jetbrains.buildServer.serverSide.BuildQueue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpTeamCityServerTest {
  @Test
  void startBuildMissingBuildTypeIdReturnsError() throws Exception {
    SBuildServer buildServer = mock(SBuildServer.class);

    McpSchema.CallToolResult result = invoke("startBuild", buildServer, Map.of());

    assertTrue(Boolean.TRUE.equals(result.isError()));
    assertEquals("buildTypeId is required", firstMessage(result));
  }

  @Test
  void startBuildUnknownBuildTypeReturnsError() throws Exception {
    SBuildServer buildServer = mock(SBuildServer.class);
    ProjectManager projectManager = mock(ProjectManager.class);
    when(buildServer.getProjectManager()).thenReturn(projectManager);
    when(projectManager.findBuildTypeById("bt42")).thenReturn(null);

    McpSchema.CallToolResult result = invoke("startBuild", buildServer, Map.of("buildTypeId", "bt42"));

    assertTrue(Boolean.TRUE.equals(result.isError()));
    assertEquals("buildType_not_found", firstMessage(result));
  }

  @Test
  void buildStatusRejectsNonNumericId() throws Exception {
    SBuildServer buildServer = mock(SBuildServer.class);

    McpSchema.CallToolResult result = invoke("buildStatus", buildServer, Map.of("id", "abc"));

    assertTrue(Boolean.TRUE.equals(result.isError()));
    assertEquals("id must be a number", firstMessage(result));
  }

  @Test
  void buildStatusReportsFinishedBuild() throws Exception {
    SBuildServer buildServer = mock(SBuildServer.class);
    SBuild build = mock(SBuild.class);
    when(buildServer.findBuildInstanceById(42L)).thenReturn(build);
    when(build.isFinished()).thenReturn(true);
    when(build.getBuildStatus()).thenReturn(null);

    McpSchema.CallToolResult result = invoke("buildStatus", buildServer, Map.of("id", "42"));

    assertTrue(Boolean.FALSE.equals(result.isError()));
    Map<String, Object> payload = structured(result);
    assertEquals(42L, payload.get("buildId"));
    assertEquals("finished", payload.get("state"));
    assertEquals("unknown", payload.get("status"));
  }

  @Test
  void buildStatusReportsQueuedBuild() throws Exception {
    SBuildServer buildServer = mock(SBuildServer.class);
    BuildQueue queue = mock(BuildQueue.class);
    SQueuedBuild queued = mock(SQueuedBuild.class);
    when(buildServer.findBuildInstanceById(77L)).thenReturn(null);
    when(buildServer.getQueue()).thenReturn(queue);
    when(queue.findQueued("77")).thenReturn(queued);

    McpSchema.CallToolResult result = invoke("buildStatus", buildServer, Map.of("id", "77"));

    assertTrue(Boolean.FALSE.equals(result.isError()));
    Map<String, Object> payload = structured(result);
    assertEquals(77L, payload.get("buildId"));
    assertEquals("queued", payload.get("state"));
    assertEquals("unknown", payload.get("status"));
  }

  @Test
  void buildLogBuildsAbsoluteUrlWhenBaseUrlSet() throws Exception {
    SBuildServer buildServer = mock(SBuildServer.class);
    when(buildServer.getRootUrl()).thenReturn("https://teamcity.local");

    McpSchema.CallToolResult result = invoke("buildLog", buildServer, Map.of("id", "101"));

    Map<String, Object> payload = structured(result);
    assertEquals("101", payload.get("buildId"));
    assertEquals("https://teamcity.local/downloadBuildLog.html?buildId=101&plain=true", payload.get("logUrl"));
  }

  @Test
  void buildLogUsesRelativeUrlWhenBaseUrlMissing() throws Exception {
    SBuildServer buildServer = mock(SBuildServer.class);
    when(buildServer.getRootUrl()).thenReturn(null);

    McpSchema.CallToolResult result = invoke("buildLog", buildServer, Map.of("id", "202"));

    Map<String, Object> payload = structured(result);
    assertEquals("202", payload.get("buildId"));
    assertEquals("/downloadBuildLog.html?buildId=202&plain=true", payload.get("logUrl"));
  }

  @Test
  void buildLogRequiresId() throws Exception {
    SBuildServer buildServer = mock(SBuildServer.class);

    McpSchema.CallToolResult result = invoke("buildLog", buildServer, Map.of());

    assertTrue(Boolean.TRUE.equals(result.isError()));
    assertEquals("id is required", firstMessage(result));
  }

  private static McpSchema.CallToolResult invoke(String methodName,
                                                 SBuildServer buildServer,
                                                 Map<String, Object> args) throws Exception {
    Method method = McpTeamCityServer.class.getDeclaredMethod(methodName, SBuildServer.class, McpSchema.CallToolRequest.class);
    method.setAccessible(true);
    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("tool", args);
    return (McpSchema.CallToolResult) method.invoke(null, buildServer, request);
  }

  private static String firstMessage(McpSchema.CallToolResult result) {
    List<McpSchema.Content> content = result.content();
    assertNotNull(content);
    assertEquals(1, content.size());
    return ((McpSchema.TextContent) content.get(0)).text();
  }

  private static Map<String, Object> structured(McpSchema.CallToolResult result) {
    Object structured = result.structuredContent();
    assertNotNull(structured);
    assertTrue(structured instanceof Map);
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = (Map<String, Object>) structured;
    return payload;
  }
}
