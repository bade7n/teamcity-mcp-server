package com.example.teamcity.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.util.KeepAliveScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class McpSseServerTransportProvider implements McpServerTransportProvider {
  private static final Logger logger = LoggerFactory.getLogger(McpSseServerTransportProvider.class);

  public static final String UTF_8 = "UTF-8";
  public static final String APPLICATION_JSON = "application/json";
  public static final String FAILED_TO_SEND_ERROR_RESPONSE = "Failed to send error response: {}";
  public static final String DEFAULT_SSE_ENDPOINT = "/mcp/sse";
  public static final String MESSAGE_EVENT_TYPE = "message";
  public static final String ENDPOINT_EVENT_TYPE = "endpoint";
  public static final String SESSION_ID = "sessionId";
  public static final String DEFAULT_BASE_URL = "http://localhost:8080";

  private final McpJsonMapper jsonMapper;
  private final String baseUrl;
  private final String messageEndpoint;
  private final String sseEndpoint;
  private final Map<String, McpServerSession> sessions = new ConcurrentHashMap<>();
  private final AtomicBoolean isClosing = new AtomicBoolean(false);

  private McpTransportContextExtractor<HttpServletRequest> contextExtractor;
  private McpServerSession.Factory sessionFactory;
  private KeepAliveScheduler keepAliveScheduler;

  private McpSseServerTransportProvider(McpJsonMapper jsonMapper,
                                        String baseUrl,
                                        String messageEndpoint,
                                        String sseEndpoint,
                                        Duration keepAliveInterval,
                                        McpTransportContextExtractor<HttpServletRequest> contextExtractor) {
    if (jsonMapper == null) {
      throw new IllegalArgumentException("JsonMapper must not be null");
    }
    if (messageEndpoint == null) {
      throw new IllegalArgumentException("messageEndpoint must not be null");
    }
    if (sseEndpoint == null) {
      throw new IllegalArgumentException("sseEndpoint must not be null");
    }
    if (contextExtractor == null) {
      throw new IllegalArgumentException("Context extractor must not be null");
    }
    this.jsonMapper = jsonMapper;
    this.baseUrl = baseUrl;
    this.messageEndpoint = messageEndpoint;
    this.sseEndpoint = sseEndpoint;
    this.contextExtractor = contextExtractor;

    if (keepAliveInterval != null) {
      this.keepAliveScheduler = KeepAliveScheduler.builder(() -> Flux.fromIterable(sessions.values()))
        .initialDelay(keepAliveInterval)
        .interval(keepAliveInterval)
        .build();
      this.keepAliveScheduler.start();
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public List<String> protocolVersions() {
    return List.of("2024-11-05");
  }

  @Override
  public void setSessionFactory(McpServerSession.Factory sessionFactory) {
    this.sessionFactory = sessionFactory;
  }

  @Override
  public Mono<Void> notifyClients(String method, Object params) {
    if (sessions.isEmpty()) {
      logger.debug("No active sessions to broadcast message to");
      return Mono.empty();
    }
    logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());
    return Flux.fromIterable(sessions.values())
      .flatMap(session -> session.sendNotification(method, params)
        .doOnError(ex -> logger.warn("Broadcast failed for session {}: {}", session.getId(), ex.getMessage())))
      .then();
  }

  public void handleSse(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String uri = request.getRequestURI();
    if (!uri.endsWith(sseEndpoint)) {
      response.sendError(404);
      return;
    }
    if (isClosing.get()) {
      response.sendError(503, "Server is shutting down");
      return;
    }

    response.setContentType("text/event-stream");
    response.setCharacterEncoding(UTF_8);
    response.setHeader("Cache-Control", "no-cache");
    response.setHeader("Connection", "keep-alive");
    response.setHeader("Access-Control-Allow-Origin", "*");

    String sessionId = UUID.randomUUID().toString();
    AsyncContext asyncContext = request.startAsync();
    asyncContext.setTimeout(0);

    PrintWriter writer = response.getWriter();
    SseSessionTransport transport = new SseSessionTransport(sessionId, asyncContext, writer);
    McpServerSession session = sessionFactory.create(transport);
    sessions.put(sessionId, session);

    sendEvent(writer, ENDPOINT_EVENT_TYPE, buildEndpointUrl(sessionId));
  }

  public void handleMessage(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (isClosing.get()) {
      response.sendError(503, "Server is shutting down");
      return;
    }

    String uri = request.getRequestURI();
    if (!uri.endsWith(messageEndpoint)) {
      response.sendError(404);
      return;
    }

    String sessionId = request.getParameter(SESSION_ID);
    if (sessionId == null) {
      writeError(response, 400, new McpError("Session ID missing in message endpoint"));
      return;
    }

    McpServerSession session = sessions.get(sessionId);
    if (session == null) {
      writeError(response, 404, new McpError("Session not found: " + sessionId));
      return;
    }

    try {
      BufferedReader reader = request.getReader();
      StringBuilder payload = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        payload.append(line);
      }

      McpTransportContext context = contextExtractor.extract(request);
      if (context == null) {
        context = McpTransportContext.EMPTY;
      }
      McpTransportContext finalContext = context;

      McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, payload.toString());
      session.handle(message)
        .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, finalContext))
        .block();

      response.setStatus(200);
    } catch (Exception ex) {
      logger.error("Error processing message: {}", ex.getMessage());
      try {
        writeError(response, 500, new McpError(ex.getMessage()));
      } catch (IOException ioEx) {
        logger.error(FAILED_TO_SEND_ERROR_RESPONSE, ioEx.getMessage());
        response.sendError(500, "Error processing message");
      }
    }
  }

  @Override
  public Mono<Void> closeGracefully() {
    isClosing.set(true);
    logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());
    return Flux.fromIterable(sessions.values())
      .flatMap(McpServerSession::closeGracefully)
      .then()
      .doOnSuccess(ignored -> {
        sessions.clear();
        logger.debug("Graceful shutdown completed");
        if (keepAliveScheduler != null) {
          keepAliveScheduler.shutdown();
        }
      });
  }

  public void destroy() {
    closeGracefully().block();
  }

  private void sendEvent(PrintWriter writer, String eventType, String data) throws IOException {
    writer.write("event: " + eventType + "\n");
    writer.write("data: " + data + "\n\n");
    writer.flush();
    if (writer.checkError()) {
      throw new IOException("Client disconnected");
    }
  }

  private String buildEndpointUrl(String sessionId) {
    String resolvedBase = baseUrl == null ? "" : baseUrl;
    if (resolvedBase.endsWith("/")) {
      return resolvedBase.substring(0, resolvedBase.length() - 1) + messageEndpoint + "?" + SESSION_ID + "=" + sessionId;
    }
    return resolvedBase + messageEndpoint + "?" + SESSION_ID + "=" + sessionId;
  }

  private void writeError(HttpServletResponse response, int status, McpError error) throws IOException {
    response.setContentType(APPLICATION_JSON);
    response.setCharacterEncoding(UTF_8);
    response.setStatus(status);
    String payload = jsonMapper.writeValueAsString(error);
    PrintWriter writer = response.getWriter();
    writer.write(payload);
    writer.flush();
  }

  private final class SseSessionTransport implements McpServerTransport {
    private final String sessionId;
    private final AsyncContext asyncContext;
    private final PrintWriter writer;

    private SseSessionTransport(String sessionId, AsyncContext asyncContext, PrintWriter writer) {
      this.sessionId = sessionId;
      this.asyncContext = asyncContext;
      this.writer = writer;
      logger.debug("Session transport {} initialized with SSE writer", sessionId);
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
      return Mono.fromRunnable(() -> {
        try {
          String json = jsonMapper.writeValueAsString(message);
          sendEvent(writer, MESSAGE_EVENT_TYPE, json);
          logger.debug("Message sent to session {}", sessionId);
        } catch (Exception ex) {
          logger.error("Failed to send message to session {}: {}", sessionId, ex.getMessage());
          sessions.remove(sessionId);
          asyncContext.complete();
        }
      });
    }

    @Override
    public <T> T unmarshalFrom(Object value, io.modelcontextprotocol.json.TypeRef<T> typeRef) {
      return jsonMapper.convertValue(value, typeRef);
    }

    @Override
    public Mono<Void> closeGracefully() {
      return Mono.fromRunnable(this::close);
    }

    @Override
    public void close() {
      sessions.remove(sessionId);
      try {
        asyncContext.complete();
      } catch (Exception ex) {
        logger.warn("Failed to complete async context for session {}: {}", sessionId, ex.getMessage());
      }
    }
  }

  public static final class Builder {
    private McpJsonMapper jsonMapper = McpJsonMapper.getDefault();
    private String baseUrl = DEFAULT_BASE_URL;
    private String messageEndpoint = "/mcp/message";
    private String sseEndpoint = DEFAULT_SSE_ENDPOINT;
    private Duration keepAliveInterval;
    private McpTransportContextExtractor<HttpServletRequest> contextExtractor = request -> McpTransportContext.EMPTY;

    public Builder jsonMapper(McpJsonMapper jsonMapper) {
      this.jsonMapper = jsonMapper;
      return this;
    }

    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    public Builder messageEndpoint(String messageEndpoint) {
      this.messageEndpoint = messageEndpoint;
      return this;
    }

    public Builder sseEndpoint(String sseEndpoint) {
      this.sseEndpoint = sseEndpoint;
      return this;
    }

    public Builder keepAliveInterval(Duration keepAliveInterval) {
      this.keepAliveInterval = keepAliveInterval;
      return this;
    }

    public Builder contextExtractor(McpTransportContextExtractor<HttpServletRequest> contextExtractor) {
      this.contextExtractor = contextExtractor;
      return this;
    }

    public McpSseServerTransportProvider build() {
      return new McpSseServerTransportProvider(jsonMapper, baseUrl, messageEndpoint, sseEndpoint, keepAliveInterval, contextExtractor);
    }
  }
}
