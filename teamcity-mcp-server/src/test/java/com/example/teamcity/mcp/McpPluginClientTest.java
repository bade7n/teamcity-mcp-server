package com.example.teamcity.mcp;

import com.example.teamcity.mcp.controller.McpMessageController;
import com.example.teamcity.mcp.controller.McpSSETransportController;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
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

    String sessionId = client.openSession(sseController);
    int status = client.sendJsonRpc(messageController, sessionId, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");

    assertEquals(200, status);
    verify(session).handle(any(McpSchema.JSONRPCMessage.class));
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

    String openSession(McpSSETransportController controller) throws Exception {
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      AsyncContext asyncContext = mock(AsyncContext.class);
      StringWriter body = new StringWriter();

      when(request.getMethod()).thenReturn("GET");
      when(request.getRequestURI()).thenReturn("/mcp/sse");
      when(request.getContextPath()).thenReturn("/mcp/message");
      when(request.startAsync()).thenReturn(asyncContext);
      when(response.getWriter()).thenReturn(new PrintWriter(body, true));

      controller.handle(request, response);

      Matcher matcher = SESSION_ID_PATTERN.matcher(body.toString());
      assertTrue(matcher.find(), "SSE response must contain sessionId");
      String sessionId = matcher.group(1);
      assertNotNull(sessionId);
      return sessionId;
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
