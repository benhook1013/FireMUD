package net.firedevops.firemud.tcpproxy.telnet;

import io.grpc.Status;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.tcpproxy.service.TcpProxyEventService;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/** Handler that forwards Telnet lines to the gateway via WebSocket. */
public class TelnetServerHandler extends SimpleChannelInboundHandler<String> {
  private static final Logger logger = LoggerFactory.getLogger(TelnetServerHandler.class);
  private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
  private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(5);
  private static final int MAX_BUFFER_DEPTH = 512;
  private static final String OK = "OK";
  private static final String STARTUP_UNAVAILABLE_MESSAGE =
      "DISCONNECT startup_unavailable Gameplay path starting; please reconnect\n";
  private static final Set<String> SENSITIVE_COMMANDS = Set.of("LOGIN", "LOGON");

  private final String gatewayWsUrl;
  private final boolean devIsolated;
  private final Runnable onConnect;
  private final Runnable onDisconnect;
  private final io.micrometer.core.instrument.Counter connectionCounter;
  private final io.micrometer.core.instrument.Counter discardedCommandCounter;
  private final boolean advertiseMcp;
  private final MeterRegistry meterRegistry;
  private final BooleanSupplier gameplayTrafficReady;
  private final Timer commandTimer;
  private final Timer heartbeatTimer;
  private final Timer idleCloseTimer;
  private final Counter reconnectCounter;
  private final AtomicInteger bufferDepth;
  private final WebSocketConnector webSocketConnector;
  private final TcpProxyEventService eventService;
  private final String defaultGameInstanceId;
  private final String defaultTenantId;
  private final TelnetSessionContext sessionContext = new TelnetSessionContext();
  private final String proxyConnectionId = UUID.randomUUID().toString();
  private final AtomicLong disconnectSequence = new AtomicLong();
  private final AtomicInteger reconnectAttempts = new AtomicInteger();
  private volatile ChannelHandlerContext context;
  private volatile boolean closing;
  private volatile boolean reconnecting;
  private volatile ScheduledFuture<?> heartbeatFuture;
  private volatile ScheduledFuture<?> idleFuture;
  private volatile long lastActivityNanos;
  private volatile long connectionStartNanos;
  private volatile boolean mcpNegotiated;
  private WebSocket webSocket;
  private volatile boolean connectedOnce;
  private final Queue<String> buffer = new ConcurrentLinkedQueue<>();
  private final Set<CompletableFuture<WebSocket>> outstandingSends = ConcurrentHashMap.newKeySet();
  private volatile CompletableFuture<WebSocket> inFlightSend;
  private String clientIp;
  private boolean connectEventRecorded;
  private final LookCacheService lookCacheService;
  private volatile boolean loginAcknowledged;
  private volatile boolean cachedLookDelivered;

  public TelnetServerHandler(
      String gatewayWsUrl,
      boolean devIsolated,
      Runnable onConnect,
      Runnable onDisconnect,
      io.micrometer.core.instrument.Counter connectionCounter,
      io.micrometer.core.instrument.Counter discardedCommandCounter,
      boolean advertiseMcp,
      MeterRegistry meterRegistry,
      BooleanSupplier gameplayTrafficReady,
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
        gameplayTrafficReady,
        TelnetServerHandler::createWebSocket,
        eventService,
        bufferDepth,
        null,
        null,
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
      BooleanSupplier gameplayTrafficReady,
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
        gameplayTrafficReady,
        webSocketConnector,
        eventService,
        bufferDepth,
        null,
        null,
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
      BooleanSupplier gameplayTrafficReady,
      WebSocketConnector webSocketConnector,
      TcpProxyEventService eventService,
      AtomicInteger bufferDepth,
      LookCacheService lookCacheService) {
    this(
        gatewayWsUrl,
        devIsolated,
        onConnect,
        onDisconnect,
        connectionCounter,
        discardedCommandCounter,
        advertiseMcp,
        meterRegistry,
        gameplayTrafficReady,
        webSocketConnector,
        eventService,
        bufferDepth,
        null,
        null,
        lookCacheService);
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
      BooleanSupplier gameplayTrafficReady,
      WebSocketConnector webSocketConnector,
      TcpProxyEventService eventService,
      AtomicInteger bufferDepth,
      String defaultGameInstanceId,
      String defaultTenantId,
      LookCacheService lookCacheService) {
    this.gatewayWsUrl = gatewayWsUrl;
    this.devIsolated = devIsolated;
    this.onConnect = onConnect;
    this.onDisconnect = onDisconnect;
    this.connectionCounter = connectionCounter;
    this.discardedCommandCounter = discardedCommandCounter;
    this.advertiseMcp = advertiseMcp;
    this.meterRegistry = meterRegistry;
    this.gameplayTrafficReady = gameplayTrafficReady;
    this.webSocketConnector = webSocketConnector;
    this.eventService = eventService;
    this.bufferDepth = bufferDepth;
    this.defaultGameInstanceId = defaultGameInstanceId;
    this.defaultTenantId = defaultTenantId;
    this.commandTimer = meterRegistry.timer("tcpproxy.command");
    this.heartbeatTimer = meterRegistry.timer("tcpproxy.heartbeat");
    this.idleCloseTimer = meterRegistry.timer("tcpproxy.idleClose");
    this.reconnectCounter = meterRegistry.counter("tcpproxy.websocket.reconnects");
    this.reconnectCounter.increment(0.0);
    updateBufferDepthGauge();
    this.lookCacheService = lookCacheService;
  }

  @FunctionalInterface
  interface WebSocketConnector {
    CompletableFuture<WebSocket> connect(
        String gatewayWsUrl,
        String clientIp,
        String proxyConnectionId,
        String gameInstanceId,
        String tenantId,
        Listener listener);
  }

  static CompletableFuture<WebSocket> createWebSocket(
      String gatewayWsUrl,
      String clientIp,
      String proxyConnectionId,
      String gameInstanceId,
      String tenantId,
      Listener listener) {
    var builder = SHARED_HTTP_CLIENT.newWebSocketBuilder();
    if (clientIp != null) {
      builder.header("X-Client-IP", clientIp);
      builder.header("X-Proxy-Client-IP", clientIp);
    }
    if (proxyConnectionId != null && !proxyConnectionId.isBlank()) {
      builder.header("X-Proxy-Connection-Id", proxyConnectionId);
    }
    if (gameInstanceId != null && !gameInstanceId.isBlank()) {
      builder.header("X-Game-Instance-Id", gameInstanceId);
      builder.header("X-Proxy-Game-Instance-Id", gameInstanceId);
    }
    if (tenantId != null && !tenantId.isBlank()) {
      builder.header("X-Tenant-Id", tenantId);
      builder.header("X-Proxy-Tenant-Id", tenantId);
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
    loginAcknowledged = false;
    cachedLookDelivered = false;
    if (reconnected) {
      // On gateway reconnect, simply drain the existing buffer over the WebSocket
      // bridge; no side-channel gRPC replay is used.
      drainBuffer();
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
    CompletableFuture<WebSocket> pingFuture =
        socket.sendPing(ByteBuffer.wrap(new byte[] {1, 2, 3, 4}));
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
        "Telnet client connected from {} targeting {}",
        clientIp != null ? clientIp : remote,
        gatewayWsUrl);
    if (!devIsolated && !gameplayTrafficReady.getAsBoolean()) {
      closing = true;
      discardedCommandCounter.increment();
      ctx.writeAndFlush(STARTUP_UNAVAILABLE_MESSAGE).addListener(ChannelFutureListener.CLOSE);
      return;
    }
    if (devIsolated) {
      logger.info("Dev-isolated mode enabled; using internal Telnet echo handler");
    } else {
      bootstrapDefaultSessionIfConfigured();
    }
  }

  @Override
  @Timed(value = "tcpproxy.command")
  protected void channelRead0(ChannelHandlerContext ctx, String msg) {
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
      if (closing) {
        return;
      }
      String sanitized = sanitize(ctx, msg);
      if (sanitized == null) {
        return;
      }

      logTelnetInput(sanitized);
      touchActivity();

      if (devIsolated) {
        // In dev-isolated mode, use the same hidden bootstrap defaults as the normal path and
        // echo subsequent commands directly back to the Telnet client without opening a WebSocket
        // to the gateway.
        if (!sessionContext.isReady()) {
          bootstrapDefaultSessionIfConfigured();
        }
        if (sessionContext.isReady() && context != null) {
          context.writeAndFlush(sanitized + "\n");
        } else {
          logger.warn("Ignoring Telnet input before session bootstrap: {}", sanitized);
        }
        return;
      }

      if (!sessionContext.isReady()) {
        bootstrapDefaultSessionIfConfigured();
        if (!sessionContext.isReady()) {
          logger.warn("Ignoring Telnet input before session bootstrap: {}", sanitized);
          return;
        }
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
        sessionContext.gameInstanceId(), sessionContext.tenantId(), clientIp, connectionDuration);
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

  private void bootstrapDefaultSessionIfConfigured() {
    if (sessionContext.isReady()) {
      return;
    }
    if (!StringUtils.hasText(defaultGameInstanceId) || !StringUtils.hasText(defaultTenantId)) {
      return;
    }
    sessionContext.bootstrap(defaultGameInstanceId, defaultTenantId);
    notifyConnectIfReady();
    ensureGatewayConnected();
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
    if (devIsolated || closing || webSocket != null || reconnecting) {
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
            proxyConnectionId,
            sessionContext.gameInstanceId(),
            sessionContext.tenantId(),
            gatewayListener())
        .whenComplete(
            (socket, error) -> {
              if (error != null) {
                logger.error("WebSocket connection to {} failed", gatewayWsUrl, error);
                failCloseBackendUnavailable("Gateway link unavailable; please reconnect");
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
    recordBridgeShutdown("unattributed_failure");
    failCloseBackendUnavailable("Gateway link dropped; please reconnect");
  }

  private void handleGatewayClose(int statusCode, String reason) {
    if (closing) {
      return;
    }
    cancelOutstandingSends();
    closeGatewayWebSocket();
    stopHeartbeat();
    GatewayCloseClassification classification = classifyGatewayClose(statusCode, reason);
    recordBridgeShutdown(classification.shutdownClass());
    failClose(classification.reasonToken(), classification.message());
  }

  private void failCloseBackendUnavailable(String message) {
    failClose("backend_unavailable", message);
  }

  private void failClose(String reasonToken, String message) {
    if (closing) {
      return;
    }
    closing = true;
    reconnecting = false;
    if (context != null) {
      context
          .writeAndFlush("DISCONNECT " + reasonToken + " " + message + "\n")
          .addListener(ChannelFutureListener.CLOSE);
    }
  }

  private GatewayCloseClassification classifyGatewayClose(int statusCode, String reason) {
    String trimmedReason = reason == null ? "" : reason.trim();
    if (statusCode == 1000 && trimmedReason.startsWith("logout")) {
      return new GatewayCloseClassification(
          trimmedReason,
          "Gameplay session ended; please reconnect",
          shutdownClassForLogout(trimmedReason));
    }
    if (statusCode == 1001 && "idle_timeout".equals(trimmedReason)) {
      return new GatewayCloseClassification(
          "idle_timeout", "Gameplay session timed out; please reconnect", "unattributed_failure");
    }
    if (statusCode == 1008 && trimmedReason.startsWith("policy_violation")) {
      return new GatewayCloseClassification(
          trimmedReason,
          "Gameplay connection closed due to policy violation",
          "unattributed_failure");
    }
    if (statusCode == 1011 && "internal_error".equals(trimmedReason)) {
      return new GatewayCloseClassification(
          "internal_error", "Gameplay connection failed; please reconnect", "unattributed_failure");
    }
    return new GatewayCloseClassification(
        "backend_unavailable", "Gateway link dropped; please reconnect", "unattributed_failure");
  }

  private String shutdownClassForLogout(String reasonToken) {
    if ("logout;subreason=gateway_restart".equalsIgnoreCase(reasonToken)) {
      return "planned_drain";
    }
    return "upstream_logout";
  }

  private void recordBridgeShutdown(String shutdownClass) {
    meterRegistry.counter("tcpproxy.bridge.shutdown", "class", shutdownClass).increment();
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

  private void updateBufferDepthGauge() {
    bufferDepth.set(buffer.size() + outstandingSends.size());
  }

  private void notifyConnectIfReady() {
    if (devIsolated || connectEventRecorded || !sessionContext.isReady()) {
      return;
    }
    eventService.recordConnectEvent(
        sessionContext.gameInstanceId(), sessionContext.tenantId(), clientIp);
    connectEventRecorded = true;
  }

  private void sendCachedLookIfPresent() {
    if (lookCacheService == null
        || context == null
        || !sessionContext.isReady()
        || !loginAcknowledged
        || cachedLookDelivered) {
      return;
    }
    String sessionId = sessionContext.gameInstanceId();
    String tenantId = sessionContext.tenantId();
    try {
      long tenant = Long.parseLong(tenantId);
      long session = Long.parseLong(sessionId);
      lookCacheService
          .get(tenant, session)
          .map(LookCacheService.CachedLook::protocolText)
          .ifPresent(
              text -> {
                context.writeAndFlush(text);
                cachedLookDelivered = true;
              });
    } catch (NumberFormatException ex) {
      logger.debug("Invalid cached LOOK identifiers tenant={} session={}", tenantId, sessionId, ex);
    } catch (RuntimeException ex) {
      logger.debug("Unable to read cached LOOK for session {}", sessionId, ex);
    }
  }

  private void notifyDisconnectAsync() {
    if (devIsolated || !sessionContext.isReady()) {
      return;
    }
    long sequence = disconnectSequence.incrementAndGet();
    CompletableFuture.supplyAsync(
            () ->
                eventService.notifyDisconnect(
                    sessionContext.gameInstanceId(),
                    sessionContext.tenantId(),
                    proxyConnectionId,
                    sequence))
        .thenAccept(
            response ->
                handleDisconnectResponse(
                    response, sessionContext.gameInstanceId(), sessionContext.tenantId()))
        .exceptionally(
            failure -> {
              logger.warn(
                  "Failed to notify Game Session Service about disconnect for session {} tenant {}",
                  sessionContext.gameInstanceId(),
                  sessionContext.tenantId(),
                  failure);
              Status.Code status = Status.fromThrowable(failure).getCode();
              meterRegistry
                  .counter("tcpproxy.disconnect.notify.transport_failure", "status", status.name())
                  .increment();
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
      meterRegistry
          .counter("tcpproxy.disconnect.notify.transport_failure", "status", "UNKNOWN")
          .increment();
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
    meterRegistry
        .counter("tcpproxy.disconnect.notify.app_error", "code", detail.getCode())
        .increment();
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
        reconnectAttempts.set(0);
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
        String payload = data.toString().trim();
        if (payload.startsWith("OK LOGIN")) {
          loginAcknowledged = true;
          sendCachedLookIfPresent();
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
        handleGatewayClose(statusCode, reason);
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
  private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder().build();

  private record GatewayCloseClassification(
      String reasonToken, String message, String shutdownClass) {}

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

  private int handleIacSequence(
      ChannelHandlerContext ctx, byte[] bytes, int index, StringBuilder sb) {
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
