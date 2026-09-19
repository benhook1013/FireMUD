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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.runtime.RuntimeLoggingContext;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.tcpproxy.service.TcpProxyEventService;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

/** Handler that forwards Telnet lines to the gateway via WebSocket. */
public final class TelnetServerHandler extends SimpleChannelInboundHandler<String> {
  private static final Logger logger = LoggerFactory.getLogger(TelnetServerHandler.class);
  private static final RuntimeIdentity DEFAULT_RUNTIME_IDENTITY =
      new RuntimeIdentity(
          "tcp-proxy-service", "tcp-proxy-test", null, java.time.Instant.EPOCH, null, null, null);
  private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
  private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration WEBSOCKET_CLOSE_GRACE = Duration.ofSeconds(1);
  static final int DEFAULT_MAX_BUFFERED_LINES = 64;
  static final int MAX_GATEWAY_TEXT_BYTES = 64 * 1024;
  private static final String OK = "OK";
  private static final String STARTUP_UNAVAILABLE_MESSAGE =
      "DISCONNECT startup_unavailable Gameplay path starting; please reconnect\n";
  private static final String DISCONNECT_NOTIFY_TRANSPORT_FAILURE_METRIC =
      "tcpproxy.disconnect.notify.transport_failure";
  private static final String EXPECTED_SHUTDOWN_DISCONNECT_NOTIFY_FAILURE_METRIC =
      "tcpproxy.disconnect.notify.expected_shutdown_transport_failure";
  private static final String INITIAL_GUIDANCE_MESSAGE =
      "OK CONNECTED\n"
          + "Type WORLDS to list available worlds.\n"
          + "Type LOGIN <email> <password> to authenticate.\n"
          + "Type PLAY <world> after LOGIN to enter a world.\n"
          + "Type HELP for commands.\n";
  private static final Set<String> SENSITIVE_COMMANDS = Set.of("LOGIN", "LOGON");
  private static final Set<String> VALID_CLOSE_SUBREASONS =
      Set.of(
          "user_logout",
          "takeover",
          "gateway_restart",
          "admin_termination",
          "edge_backpressure",
          "none");
  private static final Set<String> VALID_CLOSE_TOP_LEVEL_REASONS =
      Set.of("logout", "idle_timeout", "policy_violation", "internal_error");

  private final String gatewayWsUrl;
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
  private final String defaultWorldSlug;
  private final String defaultRealmSlug;
  private final String defaultPointerVersion;
  private final RuntimeIdentity runtimeIdentity;
  private final int maxBufferedLines;
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
  private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
  private volatile boolean connectedOnce;
  private final Queue<String> buffer = new ConcurrentLinkedQueue<>();
  private final Set<CompletableFuture<WebSocket>> outstandingSends = ConcurrentHashMap.newKeySet();
  private final AtomicReference<CompletableFuture<WebSocket>> inFlightGatewayConnection =
      new AtomicReference<>();
  // The lock order is intentionally one-way. A drainBuffer completion may run inline while the
  // intrinsic monitor is held, and its failure path may separately acquire lifecycle locks.
  // Heartbeat completions run outside that monitor and may acquire lifecycle locks on failure.
  // Code holding either lifecycle lock must not reverse into the intrinsic monitor or nest the
  // lifecycle locks.
  private final Object webSocketLifecycleLock = new Object();
  private final Object bufferLifecycleLock = new Object();
  private final Object gatewayTextLifecycleLock = new Object();
  private final StringBuilder gatewayTextBuffer = new StringBuilder();
  private int gatewayTextBufferBytes;
  private WebSocket closeAbortSocket;
  private ScheduledFuture<?> closeAbortTask;
  private volatile CompletableFuture<WebSocket> inFlightSend;
  private String clientIp;
  private boolean connectEventRecorded;

  TelnetServerHandler(
      String gatewayWsUrl,
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
        null,
        null,
        null,
        DEFAULT_RUNTIME_IDENTITY,
        DEFAULT_MAX_BUFFERED_LINES);
  }

  TelnetServerHandler(
      String gatewayWsUrl,
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
      String defaultWorldSlug,
      String defaultRealmSlug,
      String defaultPointerVersion,
      int maxBufferedLines) {
    this(
        gatewayWsUrl,
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
        defaultGameInstanceId,
        defaultTenantId,
        defaultWorldSlug,
        defaultRealmSlug,
        defaultPointerVersion,
        DEFAULT_RUNTIME_IDENTITY,
        maxBufferedLines);
  }

  TelnetServerHandler(
      String gatewayWsUrl,
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
      String defaultWorldSlug,
      String defaultRealmSlug,
      String defaultPointerVersion) {
    this(
        gatewayWsUrl,
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
        defaultGameInstanceId,
        defaultTenantId,
        defaultWorldSlug,
        defaultRealmSlug,
        defaultPointerVersion,
        DEFAULT_RUNTIME_IDENTITY,
        DEFAULT_MAX_BUFFERED_LINES);
  }

  TelnetServerHandler(
      String gatewayWsUrl,
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
      String defaultWorldSlug,
      String defaultRealmSlug,
      String defaultPointerVersion,
      RuntimeIdentity runtimeIdentity) {
    this(
        gatewayWsUrl,
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
        defaultGameInstanceId,
        defaultTenantId,
        defaultWorldSlug,
        defaultRealmSlug,
        defaultPointerVersion,
        runtimeIdentity,
        DEFAULT_MAX_BUFFERED_LINES);
  }

  TelnetServerHandler(
      String gatewayWsUrl,
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
      String defaultWorldSlug,
      String defaultRealmSlug,
      String defaultPointerVersion,
      RuntimeIdentity runtimeIdentity,
      int maxBufferedLines) {
    if (maxBufferedLines <= 0) {
      throw new IllegalArgumentException("TCP_PROXY_GATEWAY_MAX_BUFFERED_LINES must be positive");
    }
    this.gatewayWsUrl = gatewayWsUrl;
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
    TelnetRoutingBundle defaultRoutingBundle =
        TelnetRoutingBundle.normalize(defaultWorldSlug, defaultRealmSlug, defaultPointerVersion);
    this.defaultWorldSlug = defaultRoutingBundle == null ? null : defaultRoutingBundle.worldSlug();
    this.defaultRealmSlug = defaultRoutingBundle == null ? null : defaultRoutingBundle.realmSlug();
    this.defaultPointerVersion =
        defaultRoutingBundle == null ? null : defaultRoutingBundle.pointerVersion();
    this.runtimeIdentity = runtimeIdentity;
    this.maxBufferedLines = maxBufferedLines;
    this.commandTimer = meterRegistry.timer("tcpproxy.command");
    this.heartbeatTimer = meterRegistry.timer("tcpproxy.heartbeat");
    this.idleCloseTimer = meterRegistry.timer("tcpproxy.idleClose");
    this.reconnectCounter = meterRegistry.counter("tcpproxy.websocket.reconnects");
    this.reconnectCounter.increment(0.0);
    updateBufferDepthGauge();
  }

  @FunctionalInterface
  interface WebSocketConnector {
    CompletableFuture<WebSocket> connect(
        String clientIp,
        String proxyConnectionId,
        String gameInstanceId,
        String tenantId,
        String worldSlug,
        String realmSlug,
        String pointerVersion,
        Listener listener);
  }

  void setWebSocket(WebSocket webSocket) {
    setWebSocket(webSocket, false);
  }

  private boolean setWebSocket(WebSocket webSocket, boolean reconnected) {
    boolean abort = false;
    synchronized (webSocketLifecycleLock) {
      if (closing) {
        reconnecting = false;
        abort = true;
      } else {
        this.webSocket.set(webSocket);
        if (closing && this.webSocket.compareAndSet(webSocket, null)) {
          reconnecting = false;
          abort = true;
        }
      }
    }
    if (abort) {
      webSocket.abort();
      return false;
    }
    reconnecting = false;
    startHeartbeat();
    touchActivity();
    if (reconnected) {
      // On gateway reconnect, simply drain the existing buffer over the WebSocket
      // bridge; no side-channel gRPC replay is used.
      drainBuffer();
      return true;
    }
    drainBuffer();
    return true;
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
    try (CombinedLoggingContext ignored = openLoggingContext()) {
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
  }

  private void sendHeartbeat() {
    WebSocket socket = this.webSocket.get();
    if (socket == null || closing) {
      return;
    }
    Timer.Sample sample = Timer.start(meterRegistry);
    CompletableFuture<WebSocket> pingFuture =
        socket.sendPing(ByteBuffer.wrap(new byte[] {1, 2, 3, 4}));
    outstandingSends.add(pingFuture);
    pingFuture.whenComplete(
        (ws, error) -> {
          try (CombinedLoggingContext ignored = openLoggingContext()) {
            outstandingSends.remove(pingFuture);
            sample.stop(heartbeatTimer);
            if (error != null) {
              logger.warn("Gateway heartbeat failed; triggering reconnect", error);
              handleGatewayDisconnect();
            }
          }
        });
  }

  private synchronized void drainBuffer() {
    CompletableFuture<WebSocket> sendFuture;
    synchronized (bufferLifecycleLock) {
      WebSocket socket = webSocket.get();
      if (socket == null || inFlightSend != null || closing) {
        return;
      }
      String next = buffer.peek();
      if (next == null) {
        return;
      }
      sendFuture = socket.sendText(next, true);
      inFlightSend = sendFuture;
      outstandingSends.add(sendFuture);
    }
    sendFuture.whenComplete(
        (ws, error) -> {
          try (CombinedLoggingContext ignored = openLoggingContext()) {
            synchronized (bufferLifecycleLock) {
              outstandingSends.remove(sendFuture);
              if (error == null) {
                inFlightSend = null;
                buffer.poll();
              }
            }
            if (error == null) {
              touchActivity();
            } else {
              logger.warn("Gateway send failed; scheduling reconnect", error);
              handleGatewayDisconnect();
            }
            updateBufferDepthGauge();
            drainBuffer();
          }
        });
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) {
    context = ctx;
    try (CombinedLoggingContext ignored = openLoggingContext()) {
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
      if (!gameplayTrafficReady.getAsBoolean()) {
        closing = true;
        discardedCommandCounter.increment();
        ctx.writeAndFlush(STARTUP_UNAVAILABLE_MESSAGE).addListener(ChannelFutureListener.CLOSE);
        return;
      }
      ctx.writeAndFlush(INITIAL_GUIDANCE_MESSAGE);
      bootstrapDefaultSessionIfConfigured();
    }
  }

  @Override
  @Timed(value = "tcpproxy.command")
  protected void channelRead0(ChannelHandlerContext ctx, String msg) {
    Timer.Sample sample = Timer.start(meterRegistry);
    try (CombinedLoggingContext ignored = openLoggingContext()) {
      try {
        if (closing) {
          return;
        }
        String sanitized = sanitize(ctx, msg);
        if (sanitized == null) {
          return;
        }
        if ("HELP".equals(extractCommandName(sanitized))) {
          ctx.writeAndFlush(INITIAL_GUIDANCE_MESSAGE);
          return;
        }

        logTelnetInput(sanitized);
        touchActivity();

        if (!sessionContext.isReady()) {
          bootstrapDefaultSessionIfConfigured();
        }

        ensureGatewayConnected();

        boolean bufferOverflow;
        synchronized (bufferLifecycleLock) {
          if (closing) {
            return;
          }
          bufferOverflow = !canBufferMore();
          if (!bufferOverflow) {
            buffer.add(sanitized);
          }
        }
        if (bufferOverflow) {
          handleBufferOverflow();
          return;
        }
        updateBufferDepthGauge();
        drainBuffer();
      } finally {
        sample.stop(commandTimer);
      }
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    try (CombinedLoggingContext ignored = openLoggingContext()) {
      synchronized (bufferLifecycleLock) {
        closing = true;
        buffer.clear();
        updateBufferDepthGauge();
      }
      clearGatewayTextBuffer();
      cancelInFlightGatewayConnection();
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
      cancelOutstandingSends();
      updateBufferDepthGauge();
    }
  }

  private boolean canBufferMore() {
    int depth = buffer.size() + outstandingSends.size();
    bufferDepth.set(depth);
    return depth < maxBufferedLines;
  }

  private void handleBufferOverflow() {
    logger.warn(
        "Telnet buffer depth {} exceeded for {}; closing connection to prevent memory pressure",
        maxBufferedLines,
        gatewayWsUrl);
    discardedCommandCounter.increment();
    if (webSocket.get() != null) {
      meterRegistry
          .counter("tcpproxy.telnet.discarded", "reason", "gateway_buffer_full")
          .increment();
      failClose(
          "policy_violation;subreason=edge_backpressure",
          "Gameplay connection closed due to policy violation");
    } else {
      failCloseBackendUnavailable("Gateway link dropped; please reconnect");
    }
  }

  private void bootstrapDefaultSessionIfConfigured() {
    if (sessionContext.isReady()) {
      return;
    }
    if (!StringUtils.hasText(defaultGameInstanceId) || !StringUtils.hasText(defaultTenantId)) {
      return;
    }
    sessionContext.bootstrap(
        defaultGameInstanceId,
        defaultTenantId,
        defaultWorldSlug,
        defaultRealmSlug,
        defaultPointerVersion);
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
    WebSocket socket;
    boolean abortImmediately = false;
    synchronized (webSocketLifecycleLock) {
      socket = webSocket.getAndSet(null);
      if (socket != null) {
        closeAbortSocket = socket;
        ChannelHandlerContext currentContext = context;
        try {
          if (currentContext == null || currentContext.executor() == null) {
            throw new IllegalStateException("no executor available for WebSocket close fallback");
          }
          closeAbortTask =
              currentContext
                  .executor()
                  .schedule(
                      () -> abortUnacknowledgedClose(socket),
                      WEBSOCKET_CLOSE_GRACE.toMillis(),
                      TimeUnit.MILLISECONDS);
        } catch (RuntimeException error) {
          closeAbortSocket = null;
          closeAbortTask = null;
          abortImmediately = true;
          logger.warn(
              "Unable to schedule Gateway WebSocket close fallback; aborting immediately", error);
        }
      }
    }
    if (socket != null) {
      try {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
      } catch (Exception e) {
        logger.warn("Failed to close gateway WebSocket cleanly", e);
      } finally {
        if (abortImmediately) {
          socket.abort();
        }
      }
    }
  }

  private void abortUnacknowledgedClose(WebSocket socket) {
    synchronized (webSocketLifecycleLock) {
      if (closeAbortSocket != socket) {
        return;
      }
      closeAbortSocket = null;
      closeAbortTask = null;
    }
    socket.abort();
  }

  private void cancelCloseAbortFallback(WebSocket socket) {
    ScheduledFuture<?> task;
    synchronized (webSocketLifecycleLock) {
      if (closeAbortSocket != socket) {
        return;
      }
      closeAbortSocket = null;
      task = closeAbortTask;
      closeAbortTask = null;
    }
    if (task != null) {
      task.cancel(false);
    }
  }

  private void ensureGatewayConnected() {
    if (closing || webSocket.get() != null || reconnecting) {
      return;
    }
    connectToGateway();
  }

  private void connectToGateway() {
    if (closing) {
      return;
    }
    reconnecting = true;
    CompletableFuture<WebSocket> connection;
    try {
      connection =
          webSocketConnector.connect(
              clientIp,
              proxyConnectionId,
              sessionContext.gameInstanceId(),
              sessionContext.tenantId(),
              sessionContext.worldSlug(),
              sessionContext.realmSlug(),
              sessionContext.pointerVersion(),
              gatewayListener());
    } catch (RuntimeException error) {
      try (CombinedLoggingContext ignored = openLoggingContext()) {
        logger.error("WebSocket connection to {} failed", gatewayWsUrl, error);
        handleGatewaySetupFailure(error);
      }
      return;
    }
    inFlightGatewayConnection.set(connection);
    if (closing && inFlightGatewayConnection.compareAndSet(connection, null)) {
      reconnecting = false;
      connection.cancel(true);
      return;
    }
    connection.whenComplete(
        (socket, error) -> {
          if (!inFlightGatewayConnection.compareAndSet(connection, null) || error == null) {
            return;
          }
          try (CombinedLoggingContext ignored = openLoggingContext()) {
            logger.error("WebSocket connection to {} failed", gatewayWsUrl, error);
            handleGatewaySetupFailure(error);
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

  private void handleGatewaySetupFailure(Throwable error) {
    handleGatewaySetupFailure(error, "Gateway link unavailable; please reconnect");
  }

  private void handleGatewaySetupFailure(Throwable error, String availabilityMessage) {
    if (GatewayWebSocketClient.isPolicyFailure(error)) {
      failClose("policy_violation", "Gateway link rejected by policy; please reconnect");
      return;
    }
    failCloseBackendUnavailable(availabilityMessage);
  }

  private void failClose(String reasonToken, String message) {
    ChannelHandlerContext closeContext;
    synchronized (bufferLifecycleLock) {
      if (closing) {
        return;
      }
      closing = true;
      reconnecting = false;
      buffer.clear();
      updateBufferDepthGauge();
      closeContext = context;
    }
    clearGatewayTextBuffer();
    cancelInFlightGatewayConnection();
    closeGatewayWebSocket();
    if (closeContext != null) {
      ChannelFutureListener closeListener = ChannelFutureListener.CLOSE;
      io.netty.channel.ChannelFuture writeFuture =
          closeContext.writeAndFlush("DISCONNECT " + reasonToken + " " + message + "\n");
      if (writeFuture != null) {
        writeFuture.addListener(closeListener);
      } else {
        closeContext.close();
      }
    }
  }

  private GatewayCloseClassification classifyGatewayClose(int statusCode, String reason) {
    String closeReason = reason == null ? "" : reason;
    ParsedCloseReason parsed = parseCloseReason(closeReason);
    if (parsed != null && statusCode == 1000 && "logout".equals(parsed.topLevelReason())) {
      return new GatewayCloseClassification(
          closeReason,
          "Gameplay session ended; please reconnect",
          shutdownClassForLogout(closeReason));
    }
    if (parsed != null && statusCode == 1001 && "idle_timeout".equals(parsed.topLevelReason())) {
      return new GatewayCloseClassification(
          closeReason, "Gameplay session timed out; please reconnect", "unattributed_failure");
    }
    if (parsed != null
        && statusCode == 1008
        && "policy_violation".equals(parsed.topLevelReason())) {
      return new GatewayCloseClassification(
          closeReason,
          "Gameplay connection closed due to policy violation",
          "unattributed_failure");
    }
    if (parsed != null && statusCode == 1011 && "internal_error".equals(parsed.topLevelReason())) {
      return new GatewayCloseClassification(
          closeReason, "Gameplay connection failed; please reconnect", "unattributed_failure");
    }
    return new GatewayCloseClassification(
        "backend_unavailable", "Gateway link dropped; please reconnect", "unattributed_failure");
  }

  private ParsedCloseReason parseCloseReason(String reason) {
    if (reason == null || reason.isEmpty()) {
      return null;
    }
    int separator = reason.indexOf(';');
    String topLevelReason = separator < 0 ? reason : reason.substring(0, separator);
    if (topLevelReason.isEmpty() || (separator >= 0 && reason.indexOf(';', separator + 1) >= 0)) {
      return null;
    }
    String subreason = null;
    if (separator >= 0) {
      String suffix = reason.substring(separator + 1);
      if (!suffix.startsWith("subreason=")) {
        return null;
      }
      subreason = suffix.substring("subreason=".length());
      if (!VALID_CLOSE_SUBREASONS.contains(subreason)) {
        return null;
      }
    }
    if (!VALID_CLOSE_TOP_LEVEL_REASONS.contains(topLevelReason)) {
      return null;
    }
    return new ParsedCloseReason(topLevelReason, subreason);
  }

  private String shutdownClassForLogout(String reasonToken) {
    if ("logout;subreason=gateway_restart".equals(reasonToken)) {
      return "planned_drain";
    }
    return "upstream_logout";
  }

  private void recordBridgeShutdown(String shutdownClass) {
    meterRegistry.counter("tcpproxy.bridge.shutdown", "classification", shutdownClass).increment();
  }

  private void cancelOutstandingSends() {
    CompletableFuture<WebSocket> flight;
    synchronized (bufferLifecycleLock) {
      flight = inFlightSend;
      inFlightSend = null;
    }
    if (flight != null) {
      flight.cancel(true);
    }
    outstandingSends.forEach(future -> future.cancel(true));
    outstandingSends.clear();
    updateBufferDepthGauge();
  }

  private void cancelInFlightGatewayConnection() {
    CompletableFuture<WebSocket> connection = inFlightGatewayConnection.getAndSet(null);
    if (connection != null) {
      connection.cancel(true);
    }
    reconnecting = false;
  }

  private void updateBufferDepthGauge() {
    bufferDepth.set(buffer.size() + outstandingSends.size());
  }

  private void clearGatewayTextBuffer() {
    synchronized (gatewayTextLifecycleLock) {
      gatewayTextBuffer.setLength(0);
      gatewayTextBufferBytes = 0;
    }
  }

  private void notifyConnectIfReady() {
    if (connectEventRecorded || !sessionContext.isReady()) {
      return;
    }
    eventService.recordConnectEvent(
        sessionContext.gameInstanceId(), sessionContext.tenantId(), clientIp);
    connectEventRecorded = true;
  }

  private void notifyDisconnectAsync() {
    if (!sessionContext.isReady()) {
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
              try (CombinedLoggingContext ignored = openLoggingContext()) {
                Status.Code status = Status.fromThrowable(failure).getCode();
                if (isExpectedShutdownDisconnectFailure(status)) {
                  logger.debug(
                      "Suppressing expected shutdown-path disconnect notification failure for session {} tenant {} status={}",
                      sessionContext.gameInstanceId(),
                      sessionContext.tenantId(),
                      status,
                      failure);
                  meterRegistry
                      .counter(
                          EXPECTED_SHUTDOWN_DISCONNECT_NOTIFY_FAILURE_METRIC,
                          "status",
                          status.name())
                      .increment();
                  return null;
                }
                logger.warn(
                    "Failed to notify Game Session Service about disconnect for session {} tenant {}",
                    sessionContext.gameInstanceId(),
                    sessionContext.tenantId(),
                    failure);
                meterRegistry
                    .counter(DISCONNECT_NOTIFY_TRANSPORT_FAILURE_METRIC, "status", status.name())
                    .increment();
              }
              return null;
            });
  }

  private void handleDisconnectResponse(
      NotifyDisconnectResponse response, String sessionId, String tenantId) {
    try (CombinedLoggingContext ignored = openLoggingContext()) {
      if (response == null) {
        logger.warn(
            "Disconnect notification returned no response for session {} tenant {}",
            sessionId,
            tenantId);
        meterRegistry
            .counter(DISCONNECT_NOTIFY_TRANSPORT_FAILURE_METRIC, "status", "UNKNOWN")
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
  }

  private boolean isExpectedShutdownDisconnectFailure(Status.Code status) {
    if (status != Status.Code.CANCELLED && status != Status.Code.UNAVAILABLE) {
      return false;
    }
    ChannelHandlerContext currentContext = context;
    return closing
        && currentContext != null
        && currentContext.executor() != null
        && currentContext.executor().isShuttingDown();
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
        try (CombinedLoggingContext ignored = openLoggingContext()) {
          if (closing) {
            webSocket.abort();
            return;
          }
          boolean wasConnected = connectedOnce;
          connectedOnce = true;
          if (!setWebSocket(webSocket, wasConnected)) {
            return;
          }
          webSocket.request(1);
          reconnectAttempts.set(0);
          logger.info("WebSocket connected to {}", gatewayWsUrl);
        }
      }

      @Override
      public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try (CombinedLoggingContext ignored = openLoggingContext()) {
          touchActivity();
          if (logger.isDebugEnabled()) {
            logger.debug("Gateway response: {}", data);
          }
          String completeLine = null;
          boolean overflow = false;
          String fragment = data == null ? "" : data.toString();
          int fragmentBytes = fragment.getBytes(StandardCharsets.UTF_8).length;
          synchronized (gatewayTextLifecycleLock) {
            if (!closing) {
              if (fragmentBytes > MAX_GATEWAY_TEXT_BYTES - gatewayTextBufferBytes) {
                gatewayTextBuffer.setLength(0);
                gatewayTextBufferBytes = 0;
                overflow = true;
              } else {
                gatewayTextBuffer.append(fragment);
                gatewayTextBufferBytes += fragmentBytes;
                if (last) {
                  completeLine = gatewayTextBuffer.toString();
                  gatewayTextBuffer.setLength(0);
                  gatewayTextBufferBytes = 0;
                }
              }
            }
          }
          if (overflow) {
            failClose("policy_violation", "Gateway response exceeded the maximum text limit");
          } else if (completeLine != null && !closing && context != null) {
            context.writeAndFlush(completeLine + "\n");
          }
          if (closing) {
            return null;
          }
          webSocket.request(1);
        }
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
        try (CombinedLoggingContext ignored = openLoggingContext()) {
          cancelCloseAbortFallback(webSocket);
          if (closing) {
            return Listener.super.onClose(webSocket, statusCode, reason);
          }
          logger.warn(
              "Gateway WebSocket closed for {} with status {} and reason {}",
              gatewayWsUrl,
              statusCode,
              reason);
          handleGatewayClose(statusCode, reason);
        }
        return Listener.super.onClose(webSocket, statusCode, reason);
      }

      @Override
      public void onError(WebSocket webSocket, Throwable error) {
        try (CombinedLoggingContext ignored = openLoggingContext()) {
          if (closing) {
            logger.debug("Ignoring late Gateway WebSocket error for {}", gatewayWsUrl, error);
            return;
          }
          logger.error("WebSocket error for {}", gatewayWsUrl, error);
          if (connectedOnce) {
            handleGatewayDisconnect();
          } else {
            handleGatewaySetupFailure(error, "Gateway link dropped; please reconnect");
          }
        }
      }
    };
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    try (CombinedLoggingContext ignored = openLoggingContext()) {
      logger.error("Telnet handler error", cause);
      ctx.close();
    }
  }

  private CombinedLoggingContext openLoggingContext() {
    RuntimeLoggingContext runtimeContext =
        RuntimeLoggingContext.open(runtimeIdentity, proxyConnectionId);
    AutoCloseable tenantContext =
        StringUtils.hasText(sessionContext.tenantId())
            ? MDC.putCloseable("tenantId", sessionContext.tenantId())
            : null;
    AutoCloseable gameInstanceContext =
        StringUtils.hasText(sessionContext.gameInstanceId())
            ? MDC.putCloseable("gameInstanceId", sessionContext.gameInstanceId())
            : null;
    return new CombinedLoggingContext(runtimeContext, gameInstanceContext, tenantContext);
  }

  private record CombinedLoggingContext(
      RuntimeLoggingContext runtimeContext,
      AutoCloseable gameInstanceContext,
      AutoCloseable tenantContext)
      implements AutoCloseable {
    @Override
    public void close() {
      closeQuietly(gameInstanceContext);
      closeQuietly(tenantContext);
      runtimeContext.close();
    }

    private static void closeQuietly(AutoCloseable closeable) {
      if (closeable == null) {
        return;
      }
      try {
        closeable.close();
      } catch (Exception ignored) {
        // MDC cleanup should never affect runtime flow.
      }
    }
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

  private record GatewayCloseClassification(
      String reasonToken, String message, String shutdownClass) {}

  private record ParsedCloseReason(String topLevelReason, String subreason) {}

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
