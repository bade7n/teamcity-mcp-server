package com.example.teamcity.mcp;

import com.example.teamcity.mcp.controller.McpMessageController;
import com.example.teamcity.mcp.controller.McpSSETransportController;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import jetbrains.buildServer.serverSide.SBuildServer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpPluginClientTest {
  @Test
  void clientCanOpenSessionAndSendJsonRpcMessage() throws Exception {
    McpSseServerTransportProvider transport = McpSseServerTransportProvider.builder().baseUrl("http://localhost:8111").build();
    McpServerSession.Factory sessionFactory = mock(McpServerSession.Factory.class);
    McpServerSession session = mock(McpServerSession.class);
    when(sessionFactory.create(any())).thenReturn(session);
    when(session.handle(any(McpSchema.JSONRPCMessage.class))).thenReturn(Mono.empty());
    transport.setSessionFactory(sessionFactory);

    McpSSETransportController sseController = new McpSSETransportController(transport);
    McpMessageController messageController = new McpMessageController(transport);
    TestMcpClient client = new TestMcpClient();

    SessionHandle sessionHandle = client.openSession(sseController);
    int status = client.sendJsonRpc(messageController, sessionHandle.sessionId, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");

    assertEquals(202, status);
    verify(session).handle(any(McpSchema.JSONRPCMessage.class));
  }

  @Test
  void toolsListResultIsDeliveredToSseStream() throws Exception {
    McpSseServerTransportProvider transport = McpSseServerTransportProvider.builder().baseUrl("http://localhost:8111").build();
    try {
      SBuildServer buildServer = mock(SBuildServer.class);
      McpTeamCityServer mcpServer = new McpTeamCityServer(buildServer, transport);
      McpSSETransportController sseController = new McpSSETransportController(transport);
      McpMessageController messageController = new McpMessageController(transport);
      TestMcpClient client = new TestMcpClient();

      SessionHandle session = client.openSession(sseController);
      int initializeStatus = client.sendJsonRpc(
        messageController,
        session.sessionId,
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}"
      );
      int initializedNotificationStatus = client.sendJsonRpc(
        messageController,
        session.sessionId,
        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}"
      );
      int toolsListStatus = client.sendJsonRpc(
        messageController,
        session.sessionId,
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"
      );

      assertEquals(202, initializeStatus);
      assertEquals(202, initializedNotificationStatus);
      assertEquals(202, toolsListStatus);
      assertTrue(waitForContains(session.sseBody, "\"id\":1", 5000), "initialize response id must arrive to SSE stream");
      assertTrue(waitForContains(session.sseBody, "\"id\":2", 5000), "tools/list response id must arrive to SSE stream");
      assertTrue(waitForContains(session.sseBody, "\"start_build\"", 5000), "tools/list response must contain tool definitions");
      assertTrue(waitForContains(session.sseBody, "\"start_build_and_stream\"", 5000), "tools/list response must include start_build_and_stream tool");

      mcpServer.destroy();
    } finally {
      transport.destroy();
    }
  }

  @Test
  void clientCanRestoreSseConnectionBySessionId() throws Exception {
    McpSseServerTransportProvider transport = McpSseServerTransportProvider.builder().baseUrl("http://localhost:8111").build();
    try {
      SBuildServer buildServer = mock(SBuildServer.class);
      McpTeamCityServer mcpServer = new McpTeamCityServer(buildServer, transport);
      McpSSETransportController sseController = new McpSSETransportController(transport);
      McpMessageController messageController = new McpMessageController(transport);
      TestMcpClient client = new TestMcpClient();

      SessionHandle openedSession = client.openSession(sseController);
      SessionHandle restoredSession = client.reconnectSession(sseController, openedSession.sessionId);
      int initializeStatus = client.sendJsonRpc(
        messageController,
        restoredSession.sessionId,
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}"
      );
      int initializedNotificationStatus = client.sendJsonRpc(
        messageController,
        restoredSession.sessionId,
        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}"
      );
      int toolsListStatus = client.sendJsonRpc(
        messageController,
        restoredSession.sessionId,
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"
      );

      assertEquals(202, initializeStatus);
      assertEquals(202, initializedNotificationStatus);
      assertEquals(202, toolsListStatus);
      assertTrue(waitForContains(restoredSession.sseBody, "\"id\":2", 5000), "tools/list response must arrive to restored SSE stream");
      assertTrue(waitForContains(restoredSession.sseBody, "\"start_build\"", 5000), "restored SSE stream must receive tools/list payload");

      mcpServer.destroy();
    } finally {
      transport.destroy();
    }
  }

  @Test
  void eventsAreBufferedWhileSseIsDisconnectedAndDeliveredAfterReconnect() throws Exception {
    McpSseServerTransportProvider transport = McpSseServerTransportProvider.builder().baseUrl("http://localhost:8111").build();
    try {
      SBuildServer buildServer = mock(SBuildServer.class);
      McpTeamCityServer mcpServer = new McpTeamCityServer(buildServer, transport);
      McpSSETransportController sseController = new McpSSETransportController(transport);
      McpMessageController messageController = new McpMessageController(transport);
      TestMcpClient client = new TestMcpClient();

      SessionHandle openedSession = client.openSession(sseController);
      int initializeStatus = client.sendJsonRpc(
        messageController,
        openedSession.sessionId,
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}"
      );
      int initializedNotificationStatus = client.sendJsonRpc(
        messageController,
        openedSession.sessionId,
        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}"
      );
      assertEquals(202, initializeStatus);
      assertEquals(202, initializedNotificationStatus);

      transport.disconnectSseStreamForSession(openedSession.sessionId);
      int toolsListStatus = client.sendJsonRpc(
        messageController,
        openedSession.sessionId,
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"
      );
      assertEquals(202, toolsListStatus);

      SessionHandle restoredSession = client.reconnectSession(sseController, openedSession.sessionId);
      assertTrue(waitForContains(restoredSession.sseBody, "\"id\":2", 5000), "buffered tools/list response must be delivered after reconnect");
      assertTrue(waitForContains(restoredSession.sseBody, "\"start_build\"", 5000), "buffered tools/list payload must be delivered after reconnect");

      mcpServer.destroy();
    } finally {
      transport.destroy();
    }
  }

  @Test
  void clientGetsNotFoundForUnknownSession() throws Exception {
    McpSseServerTransportProvider transport = McpSseServerTransportProvider.builder().build();
    McpMessageController messageController = new McpMessageController(transport);
    TestMcpClient client = new TestMcpClient();

    int status = client.sendJsonRpc(messageController, "missing-session", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");

    assertEquals(404, status);
  }

  private static final class TestMcpClient {
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("sessionId=([a-zA-Z0-9\\-]+)");

    SessionHandle openSession(McpSSETransportController controller) throws Exception {
      return openSession(controller, null);
    }

    SessionHandle reconnectSession(McpSSETransportController controller, String sessionId) throws Exception {
      return openSession(controller, sessionId);
    }

    private SessionHandle openSession(McpSSETransportController controller, String existingSessionId) throws Exception {
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      AsyncContext asyncContext = mock(AsyncContext.class);
      StringWriter body = new StringWriter();

      when(request.getMethod()).thenReturn("GET");
      when(request.getRequestURI()).thenReturn("/mcp/sse");
      when(request.getContextPath()).thenReturn("/bs");
      when(request.getParameter(McpSseServerTransportProvider.SESSION_ID)).thenReturn(existingSessionId);
      when(request.startAsync()).thenReturn(asyncContext);
      when(response.getWriter()).thenReturn(new PrintWriter(body, true));

      controller.handle(request, response);

      Matcher matcher = SESSION_ID_PATTERN.matcher(body.toString());
      assertTrue(matcher.find(), "SSE response must contain sessionId");
      assertTrue(body.toString().contains("/bs/app/mcp/message?sessionId="), "SSE endpoint must point to MCP message URL");
      String sessionId = matcher.group(1);
      if (existingSessionId != null) {
        assertEquals(existingSessionId, sessionId, "SSE reconnect must keep the same sessionId");
      }
      assertNotNull(sessionId);
      return new SessionHandle(sessionId, body);
    }

    int sendJsonRpc(McpMessageController controller, String sessionId, String payload) throws Exception {
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      ResponseStatusCapture statusCapture = new ResponseStatusCapture();
      StringWriter body = new StringWriter();

      when(request.getParameter(McpSseServerTransportProvider.SESSION_ID)).thenReturn(sessionId);
      when(request.getReader()).thenReturn(new BufferedReader(new StringReader(payload)));
      when(response.getWriter()).thenReturn(new PrintWriter(body, true));
      statusCapture.capture(response);

      controller.handle(request, response);
      return statusCapture.status;
    }
  }

  private static final class SessionHandle {
    private final String sessionId;
    private final StringWriter sseBody;

    private SessionHandle(String sessionId, StringWriter sseBody) {
      this.sessionId = sessionId;
      this.sseBody = sseBody;
    }
  }

  private static boolean waitForContains(StringWriter writer, String expectedSubstring, long timeoutMillis) throws InterruptedException {
    Instant deadline = Instant.now().plusMillis(timeoutMillis);
    while (Instant.now().isBefore(deadline)) {
      if (writer.toString().contains(expectedSubstring)) {
        return true;
      }
      Thread.sleep(20);
    }
    return writer.toString().contains(expectedSubstring);
  }

  private static final class ResponseStatusCapture {
    private int status = 200;

    void capture(HttpServletResponse response) throws IOException {
      org.mockito.Mockito.doAnswer(invocation -> {
        status = invocation.getArgument(0);
        return null;
      }).when(response).setStatus(any(Integer.class));
      org.mockito.Mockito.doAnswer(invocation -> {
        status = invocation.getArgument(0);
        return null;
      }).when(response).sendError(any(Integer.class), any(String.class));
    }
  }
}
