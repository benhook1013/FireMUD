package net.firedevops.firemud.tcpproxy.telnet;

import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.command.text.LookCommandHandler;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import net.firedevops.firemud.tcpproxy.service.TcpProxyEventService;
import net.firedevops.firemud.tcpproxy.v1.PushBufferedInputResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handler that forwards Telnet lines to the gateway via WebSocket. */
public class TelnetServerHandler extends SimpleChannelInboundHandler<String> {
  private static final Logger logger = LoggerFactory.getLogger(TelnetServerHandler.class);
  private static final Duration INITIAL_RECONNECT_DELAY = Duration.ofSeconds(1);
  private static final Duration MAX_RECONNECT_DELAY = Duration.ofSeconds(30);
  private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
  private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(5);
  private static final int MAX_BUFFER_DEPTH = 512;
  private static final String OK = "OK";
  private static final Set<String> SENSITIVE_COMMANDS = Set.of("LOGIN", "LOGON");

  private final String gatewayWsUrl;
  private final boolean devIsolated;
  private final Runnable onConnect;
  private final Runnable onDisconnect;
  private final io.micrometer.core.instrument.Counter connectionCounter;
  private final io.micrometer.core.instrument.Counter discardedCommandCounter;
  private final boolean advertiseMcp;
  private final MeterRegistry meterRegistry;
  private final Timer commandTimer;
  private final Timer heartbeatTimer;
  private final Timer idleCloseTimer;
  private final Timer reconnectTimer;
  private final Counter reconnectCounter;
  private final AtomicInteger bufferDepth;
  private final WebSocketConnector webSocketConnector;
  private final TcpProxyEventService eventService;
  private final TelnetSessionContext sessionContext = new TelnetSessionContext();
  private volatile ChannelHandlerContext context;
  private volatile boolean closing;
  private volatile boolean reconnecting;
  private volatile int reconnectAttempts;
  private volatile ScheduledFuture<?> heartbeatFuture;
  private volatile ScheduledFuture<?> idleFuture;
  private volatile long lastActivityNanos;
  private volatile long connectionStartNanos;
  private volatile boolean mcpNegotiated;
  private WebSocket webSocket;
  private volatile boolean connectedOnce;
  private final Queue<String> buffer = new ConcurrentLinkedQueue<>();
  private final Set<CompletableFuture<WebSocket>> outstandingSends =
      ConcurrentHashMap.newKeySet();
  private volatile CompletableFuture<WebSocket> inFlightSend;
  private String clientIp;
  private boolean connectEventRecorded;
  private final LookCommandHandler lookCommandHandler;

  public TelnetServerHandler(
      String gatewayWsUrl,
      boolean devIsolated,
      Runnable onConnect,
      Runnable onDisconnect,
      io.micrometer.core.instrument.Counter connectionCounter,
      io.micrometer.core.instrument.Counter discardedCommandCounter,
      boolean advertiseMcp,
      MeterRegistry meterRegistry,
      TcpProxyEventService eventService,
      AtomicInteger bufferDepth) {
    this(
        gatewayWsUrl,
        devIsolated,
        onConnect,
        onDisconnect,
        connectionCounter,
        discardedCommandCounter,
        advertiseMcp,
        meterRegistry,
        TelnetServerHandler::createWebSocket,
        eventService,
        bufferDepth,
        null);
  }

  TelnetServerHandler(
      String gatewayWsUrl,
      boolean devIsolated,
      Runnable onConnect,
      Runnable onDisconnect,
      io.micrometer.core.instrument.Counter connectionCounter,
      io.micrometer.core.instrument.Counter discardedCommandCounter,
      boolean advertiseMcp,
      MeterRegistry meterRegistry,
      WebSocketConnector webSocketConnector,
      TcpProxyEventService eventService,
      AtomicInteger bufferDepth) {
    this(
        gatewayWsUrl,
        devIsolated,
        onConnect,
        onDisconnect,
        connectionCounter,
        discardedCommandCounter,
        advertiseMcp,
        meterRegistry,
        webSocketConnector,
        eventService,
        bufferDepth,
        null);
  }

  TelnetServerHandler(
      String gatewayWsUrl,
      boolean devIsolated,
      Runnable onConnect,
      Runnable onDisconnect,
      io.micrometer.core.instrument.Counter connectionCounter,
      io.micrometer.core.instrument.Counter discardedCommandCounter,
      boolean advertiseMcp,
      MeterRegistry meterRegistry,
      WebSocketConnector webSocketConnector,
      TcpProxyEventService eventService,
      AtomicInteger bufferDepth,
      LookCommandHandler lookCommandHandler) {
    this.gatewayWsUrl = gatewayWsUrl;
    this.devIsolated = devIsolated;
    this.onConnect = onConnect;
    this.onDisconnect = onDisconnect;
    this.connectionCounter = connectionCounter;
    this.discardedCommandCounter = discardedCommandCounter;
    this.advertiseMcp = advertiseMcp;
    this.meterRegistry = meterRegistry;
    this.webSocketConnector = webSocketConnector;
    this.eventService = eventService;
    this.bufferDepth = bufferDepth;
    this.commandTimer = meterRegistry.timer("tcpproxy.command");
    this.heartbeatTimer = meterRegistry.timer("tcpproxy.heartbeat");
    this.idleCloseTimer = meterRegistry.timer("tcpproxy.idleClose");
    this.reconnectTimer = meterRegistry.timer("tcpproxy.websocket.reconnect.delay");
    this.reconnectCounter = meterRegistry.counter("tcpproxy.websocket.reconnects");
    this.reconnectCounter.increment(0.0);
    updateBufferDepthGauge();
    this.lookCommandHandler = lookCommandHandler;
  }

  @FunctionalInterface
  interface WebSocketConnector {
    CompletableFuture<WebSocket> connect(
        String gatewayWsUrl,
        String clientIp,
        String sessionId,
        String tenantId,
        Listener listener);
  }

  static CompletableFuture<WebSocket> createWebSocket(
      String gatewayWsUrl, String clientIp, String sessionId, String tenantId, Listener listener) {
    HttpClient client = HttpClient.newHttpClient();
    var builder = client.newWebSocketBuilder();
    if (clientIp != null) {
      builder.header("X-Client-IP", clientIp);
    }
    if (sessionId != null && !sessionId.isBlank()) {
      builder.header("X-Session-Id", sessionId);
    }
    if (tenantId != null && !tenantId.isBlank()) {
      builder.header("X-Tenant-Id", tenantId);
    }
    return builder.buildAsync(URI.create(gatewayWsUrl), listener);
  }

  void setWebSocket(WebSocket webSocket) {
    setWebSocket(webSocket, false);
  }

  private void setWebSocket(WebSocket webSocket, boolean reconnected) {
    this.webSocket = webSocket;
    reconnecting = false;
    startHeartbeat();
    touchActivity();
    if (reconnected) {
      List<String> drained = consumeBuffer();
      pushBufferedInputAsync(drained);
      return;
    }
    drainBuffer();
  }

  int getBufferedSize() {
    return buffer.size();
  }

  private void startHeartbeat() {
    if (context == null || closing) {
      return;
    }
    stopHeartbeat();
    heartbeatFuture =
        context
            .executor()
            .scheduleAtFixedRate(
                this::sendHeartbeat,
                HEARTBEAT_INTERVAL.toMillis(),
                HEARTBEAT_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
  }

  private void stopHeartbeat() {
    if (heartbeatFuture != null) {
      heartbeatFuture.cancel(false);
      heartbeatFuture = null;
    }
  }

  private void touchActivity() {
    lastActivityNanos = System.nanoTime();
    scheduleIdleCheck();
  }

  private void scheduleIdleCheck() {
    if (context == null || closing) {
      return;
    }
    if (idleFuture != null) {
      idleFuture.cancel(false);
    }
    idleFuture =
        context
            .executor()
            .schedule(this::closeIfIdle, IDLE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void closeIfIdle() {
    if (closing) {
      return;
    }
    long idleNanos = System.nanoTime() - lastActivityNanos;
    if (idleNanos < IDLE_TIMEOUT.toNanos()) {
      scheduleIdleCheck();
      return;
    }
    logger.warn(
        "Closing Telnet session for {} after {} ms of inactivity",
        gatewayWsUrl,
        Duration.ofNanos(idleNanos).toMillis());
    idleCloseTimer.record(Duration.ofNanos(idleNanos));
    if (context != null) {
      context.close();
    }
  }

  private void sendHeartbeat() {
    WebSocket socket = this.webSocket;
    if (socket == null || closing) {
      return;
    }
    Timer.Sample sample = Timer.start(meterRegistry);
    CompletableFuture<WebSocket> pingFuture = socket.sendPing(ByteBuffer.wrap(new byte[] {1, 2, 3, 4}));
    outstandingSends.add(pingFuture);
    pingFuture.whenComplete(
        (ws, error) -> {
          outstandingSends.remove(pingFuture);
          sample.stop(heartbeatTimer);
          if (error != null) {
            logger.warn("Gateway heartbeat failed; triggering reconnect", error);
            handleGatewayDisconnect();
          }
        });
  }

  private synchronized void drainBuffer() {
    if (webSocket == null || inFlightSend != null || closing) {
      return;
    }
    String next = buffer.peek();
    if (next == null) {
      return;
    }
    CompletableFuture<WebSocket> sendFuture = webSocket.sendText(next, true);
    inFlightSend = sendFuture;
    outstandingSends.add(sendFuture);
    sendFuture.whenComplete(
        (ws, error) -> {
          outstandingSends.remove(sendFuture);
          inFlightSend = null;
          if (error == null) {
            buffer.poll();
            touchActivity();
          } else {
            logger.warn("Gateway send failed; scheduling reconnect", error);
            handleGatewayDisconnect();
          }
          updateBufferDepthGauge();
          drainBuffer();
        });
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) {
    context = ctx;
    touchActivity();
    var remote = ctx.channel() != null ? ctx.channel().remoteAddress() : null;
    clientIp = extractIp(remote);
    connectionStartNanos = System.nanoTime();
    connectionCounter.increment();
    updateBufferDepthGauge();
    onConnect.run();
    logger.info(
        "Telnet client connected from {} targeting {}", clientIp != null ? clientIp : remote, gatewayWsUrl);
    if (devIsolated) {
      logger.info(
          "Dev-isolated mode enabled; bridging Telnet commands to {} (echo/dev stub)", gatewayWsUrl);
    }
  }

  @Override
  @Timed(value = "tcpproxy.command")
  protected void channelRead0(ChannelHandlerContext ctx, String msg) {
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
      String sanitized = sanitize(ctx, msg);
      if (sanitized == null) {
        return;
      }

      logTelnetInput(sanitized);
      touchActivity();

      // continue processing even in dev mode so the bridge forwards commands
      if (devIsolated) {
        logger.debug("Dev-isolated Telnet input still flows through the gateway bridge");
      }

      if (!sessionContext.isReady()) {
        if (!captureSessionContext(sanitized)) {
          logger.warn("Ignoring Telnet input before session envelope: {}", sanitized);
          return;
        }
        notifyConnectIfReady();
        ensureGatewayConnected();
        return;
      }

      ensureGatewayConnected();

      if (!canBufferMore()) {
        return;
      }

      buffer.add(sanitized);
      updateBufferDepthGauge();
      drainBuffer();
    } finally {
      sample.stop(commandTimer);
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    closing = true;
    stopHeartbeat();
    cancelIdleCheck();
    closeGatewayWebSocket();
    onDisconnect.run();
    Duration connectionDuration = null;
    if (connectionStartNanos > 0) {
      connectionDuration = Duration.ofNanos(System.nanoTime() - connectionStartNanos);
    }
    eventService.recordDisconnectEvent(
        sessionContext.sessionId(), sessionContext.tenantId(), clientIp, connectionDuration);
    notifyDisconnectAsync();
    buffer.clear();
    cancelOutstandingSends();
    updateBufferDepthGauge();
  }

  private boolean canBufferMore() {
    int depth = buffer.size() + outstandingSends.size();
    bufferDepth.set(depth);
    if (depth >= MAX_BUFFER_DEPTH) {
      if (!closing) {
        closing = true;
        logger.warn(
            "Telnet buffer depth {} exceeded for {}; closing connection to prevent memory pressure",
            MAX_BUFFER_DEPTH,
            gatewayWsUrl);
        discardedCommandCounter.increment();
        if (context != null) {
          context.close();
        }
      }
      return false;
    }
    return true;
  }

  private boolean captureSessionContext(String sanitized) {
    boolean captured = sessionContext.captureFromEnvelope(sanitized);
    if (captured) {
      notifyConnectIfReady();
    }
    return captured;
  }

  private void cancelIdleCheck() {
    if (idleFuture != null) {
      idleFuture.cancel(false);
      idleFuture = null;
    }
  }

  private void closeGatewayWebSocket() {
    WebSocket socket = this.webSocket;
    webSocket = null;
    if (socket != null) {
      try {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
      } catch (Exception e) {
        logger.warn("Failed to close gateway WebSocket cleanly", e);
      }
    }
  }

  private void ensureGatewayConnected() {
    if (closing || webSocket != null || reconnecting) {
      return;
    }
    connectToGateway();
  }

  private void connectToGateway() {
    if (closing) {
      return;
    }
    reconnecting = true;
    webSocketConnector
        .connect(
            gatewayWsUrl,
            clientIp,
            sessionContext.sessionId(),
            sessionContext.tenantId(),
            gatewayListener())
        .whenComplete(
            (socket, error) -> {
              if (error != null) {
                logger.error("WebSocket connection to {} failed", gatewayWsUrl, error);
                reconnecting = false;
                scheduleReconnect();
              }
            });
  }

  private void handleGatewayDisconnect() {
    if (closing) {
      return;
    }
    cancelOutstandingSends();
    closeGatewayWebSocket();
    stopHeartbeat();
    reconnecting = false;
    scheduleReconnect();
  }

  private void scheduleReconnect() {
    if (context == null || closing || reconnecting) {
      return;
    }
    reconnecting = true;
    reconnectCounter.increment();
    long delayMillis = backoffDelayMillis();
    context.executor().schedule(this::connectToGateway, delayMillis, TimeUnit.MILLISECONDS);
    reconnectTimer.record(Duration.ofMillis(delayMillis));
  }

  private void cancelOutstandingSends() {
    CompletableFuture<WebSocket> flight = inFlightSend;
    inFlightSend = null;
    if (flight != null) {
      flight.cancel(true);
    }
    outstandingSends.forEach(future -> future.cancel(true));
    outstandingSends.clear();
    updateBufferDepthGauge();
  }

  private long backoffDelayMillis() {
    long baseDelay = INITIAL_RECONNECT_DELAY.toMillis();
    long delay = baseDelay * (1L << Math.min(reconnectAttempts, 10));
    reconnectAttempts++;
    return Math.min(delay, MAX_RECONNECT_DELAY.toMillis());
  }

  private List<String> consumeBuffer() {
    List<String> drained = new java.util.ArrayList<>();
    String next;
    while ((next = buffer.poll()) != null) {
      drained.add(next);
    }
    updateBufferDepthGauge();
    return drained;
  }

  private void pushBufferedInputAsync(List<String> buffered) {
    if (devIsolated || !sessionContext.isReady() || buffered.isEmpty()) {
      return;
    }
    CompletableFuture
        .supplyAsync(
            () ->
                eventService.pushBufferedInput(
                    sessionContext.sessionId(), buffered, sessionContext.tenantId()))
        .thenAccept(
            response -> {
              if (!isOk(response)) {
                String code = response != null && response.hasError() ? response.getError().getCode() : "UNKNOWN";
                logger.warn(
                    "Failed to push buffered input for session {} with code {}",
                    sessionContext.sessionId(),
                    code);
                buffer.addAll(buffered);
                updateBufferDepthGauge();
                drainBuffer();
              }
            })
        .exceptionally(
            error -> {
              logger.warn(
                  "Failed to push buffered input for session {}",
                  sessionContext.sessionId(),
                  error);
              buffer.addAll(buffered);
              updateBufferDepthGauge();
              drainBuffer();
              return null;
            });
  }

  private boolean isOk(PushBufferedInputResponse response) {
    if (response == null) {
      return false;
    }
    if (!response.hasError()) {
      return true;
    }
    return OK.equals(response.getError().getCode());
  }

  private void updateBufferDepthGauge() {
    bufferDepth.set(buffer.size() + outstandingSends.size());
  }

  private void notifyConnectIfReady() {
    if (devIsolated || connectEventRecorded || !sessionContext.isReady()) {
      return;
    }
    eventService.recordConnectEvent(sessionContext.sessionId(), sessionContext.tenantId(), clientIp);
    connectEventRecorded = true;
    sendCachedLookIfPresent();
  }

  private void sendCachedLookIfPresent() {
    if (lookCommandHandler == null || context == null || !sessionContext.isReady()) {
      return;
    }
    String sessionId = sessionContext.sessionId();
    String tenantId = sessionContext.tenantId();
    lookCommandHandler
        .cachedLook(tenantId, sessionId)
        .ifPresent(text -> context.writeAndFlush(text + "\n\n"));
  }

  private void notifyDisconnectAsync() {
    if (devIsolated || !sessionContext.isReady()) {
      return;
    }
    CompletableFuture.supplyAsync(
            () ->
                eventService.notifyDisconnect(
                    sessionContext.sessionId(), sessionContext.tenantId()))
        .thenAccept(
            response ->
                handleDisconnectResponse(
                    response, sessionContext.sessionId(), sessionContext.tenantId()))
        .exceptionally(
            failure -> {
              logger.warn(
                  "Failed to notify Game Session Service about disconnect for session {} tenant {}",
                  sessionContext.sessionId(),
                  sessionContext.tenantId(),
                  failure);
              meterRegistry.counter("tcpproxy.disconnect.notify.failure").increment();
              return null;
            });
  }

  private void handleDisconnectResponse(
      NotifyDisconnectResponse response, String sessionId, String tenantId) {
    if (response == null) {
      logger.warn(
          "Disconnect notification returned no response for session {} tenant {}",
          sessionId,
          tenantId);
      meterRegistry.counter("tcpproxy.disconnect.notify.failure").increment();
      return;
    }
    ErrorDetail detail = response.hasError() ? response.getError() : null;
    if (detail == null || OK.equals(detail.getCode())) {
      return;
    }
    logger.warn(
        "Disconnect notification rejected for session {} tenant {}: {} {}",
        sessionId,
        tenantId,
        detail.getCode(),
        detail.getMessage());
    meterRegistry.counter("tcpproxy.disconnect.notify.failure").increment();
  }

  private void logTelnetInput(String sanitized) {
    String trimmed = sanitized.strip();
    if (trimmed.isEmpty()) {
      return;
    }
    String commandName = extractCommandName(trimmed);
    if (SENSITIVE_COMMANDS.contains(commandName)) {
      if (logger.isInfoEnabled()) {
        logger.info("Received Telnet command: {} (arguments redacted)", commandName);
      } else if (logger.isDebugEnabled()) {
        logger.debug("Received Telnet command: {} (arguments redacted)", commandName);
      }
      return;
    }
    if (logger.isInfoEnabled()) {
      logger.info("Received Telnet command: {}", trimmed);
    } else if (logger.isDebugEnabled()) {
      logger.debug("Received Telnet command: {}", trimmed);
    }
  }

  private static String extractCommandName(String sanitizedLine) {
    String trimmed = sanitizedLine.stripLeading();
    if (trimmed.isEmpty()) {
      return "";
    }
    int firstSpaceIndex = trimmed.indexOf(' ');
    String token = firstSpaceIndex == -1 ? trimmed : trimmed.substring(0, firstSpaceIndex);
    return token.toUpperCase(Locale.ROOT);
  }

  private String extractIp(Object remote) {
    if (remote instanceof java.net.InetSocketAddress address) {
      var inetAddress = address.getAddress();
      if (inetAddress != null) {
        return inetAddress.getHostAddress();
      }
    }
    return null;
  }

  private Listener gatewayListener() {
    return new Listener() {
      @Override
      public void onOpen(WebSocket webSocket) {
        boolean wasConnected = connectedOnce;
        connectedOnce = true;
        setWebSocket(webSocket, wasConnected);
        webSocket.request(1);
        reconnectAttempts = 0;
        logger.info("WebSocket connected to {}", gatewayWsUrl);
      }

      @Override
      public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        touchActivity();
        if (logger.isDebugEnabled()) {
          logger.debug("Gateway response: {}", data);
        }
        if (context != null) {
          context.writeAndFlush(data.toString() + "\n");
        }
        webSocket.request(1);
        return null;
      }

      @Override
      public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
        touchActivity();
        webSocket.request(1);
        return Listener.super.onPong(webSocket, message);
      }

      @Override
      public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
        touchActivity();
        webSocket.sendPong(message);
        webSocket.request(1);
        return Listener.super.onPing(webSocket, message);
      }

      @Override
      public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        logger.warn(
            "Gateway WebSocket closed for {} with status {} and reason {}",
            gatewayWsUrl,
            statusCode,
            reason);
        handleGatewayDisconnect();
        return Listener.super.onClose(webSocket, statusCode, reason);
      }

      @Override
      public void onError(WebSocket webSocket, Throwable error) {
        logger.error("WebSocket error for {}", gatewayWsUrl, error);
        handleGatewayDisconnect();
      }
    };
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    logger.error("Telnet handler error", cause);
    ctx.close();
  }

  private static final byte IAC = (byte) 255;

  private static final byte WILL = (byte) 251;
  private static final byte WONT = (byte) 252;
  private static final byte DO = (byte) 253;
  private static final byte DONT = (byte) 254;
  private static final byte SB = (byte) 250;
  private static final byte SE = (byte) 240;
  private static final String MCP_PREFIX = "#$#";

  private static final Set<Byte> ALLOWED_COMMANDS =
      Set.of((byte) 240, (byte) 241, (byte) 249, (byte) 251, (byte) 252, (byte) 253, (byte) 254);

  private static final Set<Byte> SUPPORTED_OPTIONS = Set.of((byte) 1, (byte) 3);

  boolean isMcpNegotiated() {
    return mcpNegotiated;
  }

  private String sanitize(ChannelHandlerContext ctx, String msg) {
    byte[] bytes = msg.getBytes(StandardCharsets.ISO_8859_1);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < bytes.length; i++) {
      byte b = bytes[i];
      if (b == IAC) {
        i = handleIacSequence(ctx, bytes, i + 1, sb);
        continue;
      }
      if (b == '\r') {
        if (i + 1 < bytes.length && bytes[i + 1] == '\n') {
          i++;
        }
        sb.append('\n');
        continue;
      }
      if (b == '\n') {
        sb.append('\n');
        continue;
      }
      if (b >= 32 && b <= 126) {
        sb.append((char) b);
      }
    }
    String cleaned = sb.toString();
    String negotiationCandidate = cleaned.stripLeading();
    if (!negotiationCandidate.isEmpty() && negotiationCandidate.startsWith(MCP_PREFIX)) {
      boolean initialNegotiation = !mcpNegotiated;
      mcpNegotiated = true;
      if (advertiseMcp && initialNegotiation) {
        ctx.writeAndFlush("#$#mcp version:2.1\r\n");
      }
    }
    return cleaned.isBlank() ? null : cleaned;
  }

  private int handleIacSequence(ChannelHandlerContext ctx, byte[] bytes, int index, StringBuilder sb) {
    if (index >= bytes.length) {
      discardedCommandCounter.increment();
      return bytes.length;
    }
    byte command = bytes[index];
    if (command == IAC) {
      sb.append((char) IAC);
      return index;
    }
    switch (command) {
      case DO:
      case DONT:
        return negotiate(ctx, command, bytes, index);
      case WILL:
      case WONT:
        return negotiate(ctx, command, bytes, index);
      case SB:
        return handleSubNegotiation(ctx, bytes, index);
      default:
        if (!ALLOWED_COMMANDS.contains(command)) {
          discardedCommandCounter.increment();
        }
        return index;
    }
  }

  private int negotiate(ChannelHandlerContext ctx, byte command, byte[] bytes, int index) {
    if (index + 1 >= bytes.length) {
      discardedCommandCounter.increment();
      return bytes.length;
    }
    byte option = bytes[index + 1];
    boolean supported = SUPPORTED_OPTIONS.contains(option);
    byte response;
    if (command == DO) {
      response = supported ? WILL : WONT;
    } else if (command == DONT) {
      response = WONT;
    } else if (command == WILL) {
      response = supported ? DO : DONT;
    } else {
      response = DONT;
    }
    if (!supported) {
      discardedCommandCounter.increment();
    }
    writeNegotiationResponse(ctx, response, option);
    return index + 1;
  }

  private int handleSubNegotiation(ChannelHandlerContext ctx, byte[] bytes, int index) {
    if (index + 1 >= bytes.length) {
      discardedCommandCounter.increment();
      return bytes.length;
    }
    byte option = bytes[index + 1];
    int cursor = index + 2;
    while (cursor < bytes.length - 1) {
      if (bytes[cursor] == IAC && bytes[cursor + 1] == SE) {
        break;
      }
      cursor++;
    }
    if (cursor >= bytes.length - 1) {
      discardedCommandCounter.increment();
      return bytes.length;
    }
    if (!SUPPORTED_OPTIONS.contains(option)) {
      discardedCommandCounter.increment();
      writeNegotiationResponse(ctx, DONT, option);
    }
    return cursor + 1;
  }

  private void writeNegotiationResponse(ChannelHandlerContext ctx, byte response, byte option) {
    ByteBuf responseBuf = Unpooled.buffer(3);
    responseBuf.writeByte(IAC);
    responseBuf.writeByte(response);
    responseBuf.writeByte(option);
    ctx.writeAndFlush(responseBuf);
  }
}
