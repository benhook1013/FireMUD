package net.firedevops.firemud.telnet;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handler that forwards Telnet lines to the gateway via WebSocket. */
public class TelnetServerHandler extends SimpleChannelInboundHandler<String> {
  private static final Logger logger = LoggerFactory.getLogger(TelnetServerHandler.class);

  private static final Duration DEFAULT_RECONNECT_BASE = Duration.ofSeconds(1);
  private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(20);
  private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(2);
  private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
  private static final int MAX_BUFFER_DEPTH = 256;
  private static final int MAX_IN_FLIGHT = 256;

  private final String gatewayWsUrl;
  private final Runnable onConnect;
  private final Runnable onDisconnect;
  private final io.micrometer.core.instrument.Counter connectionCounter;
  private final MeterRegistry meterRegistry;
  private final Timer commandTimer;
  private final Timer heartbeatTimer;
  private final Timer idleCloseTimer;
  private final ScheduledExecutorService scheduler;
  private final WebSocketFactory webSocketFactory;
  private final Duration reconnectBaseDelay;
  private final Duration heartbeatInterval;
  private final Duration idleTimeout;
  private final int maxBufferDepth;
  private final int maxInFlight;

  private WebSocket webSocket;
  private final Queue<String> buffer = new ConcurrentLinkedQueue<>();
  private final Set<CompletableFuture<WebSocket>> inFlightSends = ConcurrentHashMap.newKeySet();
  private final Map<CompletableFuture<WebSocket>, String> inFlightMessages = new ConcurrentHashMap<>();
  private ScheduledFuture<?> heartbeatTask;
  private ScheduledFuture<?> idleTask;
  private ScheduledFuture<?> reconnectFuture;
  private volatile ChannelHandlerContext channelContext;
  private volatile String lastKnownClientIp;
  private volatile int reconnectAttempts;
  private volatile long lastActivityNanos = System.nanoTime();
  private volatile boolean disconnected;

  public TelnetServerHandler(
      String gatewayWsUrl,
      Runnable onConnect,
      Runnable onDisconnect,
      io.micrometer.core.instrument.Counter connectionCounter,
      MeterRegistry meterRegistry) {
    this(
        gatewayWsUrl,
        onConnect,
        onDisconnect,
        connectionCounter,
        meterRegistry,
        DEFAULT_RECONNECT_BASE,
        DEFAULT_HEARTBEAT_INTERVAL,
        DEFAULT_IDLE_TIMEOUT,
        MAX_BUFFER_DEPTH,
        MAX_IN_FLIGHT,
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread thread = new Thread(r);
              thread.setDaemon(true);
              thread.setName("telnet-ws-handler");
              return thread;
            }),
        new HttpClientWebSocketFactory(HttpClient.newHttpClient()));
  }

  TelnetServerHandler(
      String gatewayWsUrl,
      Runnable onConnect,
      Runnable onDisconnect,
      io.micrometer.core.instrument.Counter connectionCounter,
      MeterRegistry meterRegistry,
      Duration reconnectBaseDelay,
      Duration heartbeatInterval,
      Duration idleTimeout,
      int maxBufferDepth,
      int maxInFlight,
      ScheduledExecutorService scheduler,
      WebSocketFactory webSocketFactory) {
    this.gatewayWsUrl = gatewayWsUrl;
    this.onConnect = onConnect;
    this.onDisconnect = onDisconnect;
    this.connectionCounter = connectionCounter;
    this.meterRegistry = meterRegistry;
    this.commandTimer = meterRegistry.timer("tcpproxy.command");
    this.heartbeatTimer = meterRegistry.timer("tcpproxy.heartbeat");
    this.idleCloseTimer = meterRegistry.timer("tcpproxy.idle.close");
    this.reconnectBaseDelay = reconnectBaseDelay;
    this.heartbeatInterval = heartbeatInterval;
    this.idleTimeout = idleTimeout;
    this.maxBufferDepth = maxBufferDepth;
    this.maxInFlight = maxInFlight;
    this.scheduler = scheduler;
    this.webSocketFactory = webSocketFactory;
  }

  void setWebSocket(WebSocket webSocket) {
    this.webSocket = webSocket;
    reconnectAttempts = 0;
    flushBuffer();
  }

  int getBufferedSize() {
    return buffer.size();
  }

  private void flushBuffer() {
    if (webSocket == null) {
      return;
    }
    String msg;
    while ((msg = buffer.poll()) != null) {
      sendToGateway(msg);
    }
  }

  private void updateLastActivity() {
    lastActivityNanos = System.nanoTime();
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) {
    var remote = ctx.channel() != null ? ctx.channel().remoteAddress() : null;
    String ip = null;
    if (remote instanceof java.net.InetSocketAddress address) {
      var inetAddress = address.getAddress();
      if (inetAddress != null) {
        ip = inetAddress.getHostAddress();
      }
    }
    channelContext = ctx;
    lastKnownClientIp = ip;
    disconnected = false;
    updateLastActivity();
    connectionCounter.increment();
    onConnect.run();
    logger.info("Telnet client connected from {} targeting {}", ip != null ? ip : remote, gatewayWsUrl);
    connectWebSocket();
    heartbeatTask =
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
    idleTask =
        scheduler.scheduleAtFixedRate(this::closeIfIdle, idleTimeout.toMillis(), idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, String msg) {
    String sanitized = sanitize(msg);
    if (sanitized == null) {
      return;
    }

    logger.info("Received Telnet input: {}", sanitized);
    Timer.Sample sample = Timer.start(meterRegistry);
    updateLastActivity();
    sendToGateway(sanitized);
    sample.stop(commandTimer);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    disconnected = true;
    if (heartbeatTask != null) {
      heartbeatTask.cancel(true);
    }
    if (idleTask != null) {
      idleTask.cancel(true);
    }
    if (reconnectFuture != null) {
      reconnectFuture.cancel(true);
    }
    scheduler.shutdownNow();
    if (webSocket != null) {
      webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
      webSocket = null;
    }
    onDisconnect.run();
    buffer.clear();
    inFlightMessages.clear();
    inFlightSends.clear();
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    logger.error("Telnet handler error", cause);
    ctx.close();
  }

  private static final byte IAC = (byte) 255;

  private static final Set<Byte> ALLOWED_COMMANDS =
      Set.of((byte) 240, (byte) 241, (byte) 249, (byte) 251, (byte) 252, (byte) 253, (byte) 254);

  private String sanitize(String msg) {
    byte[] bytes = msg.getBytes(StandardCharsets.ISO_8859_1);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < bytes.length; i++) {
      byte b = bytes[i];
      if (b == IAC) {
        if (i + 1 < bytes.length) {
          byte cmd = bytes[++i];
          if (!ALLOWED_COMMANDS.contains(cmd)) {
            continue;
          }
          // drop allowed Telnet commands rather than forwarding
          continue;
        }
        continue;
      }
      if (b >= 32 && b <= 126) {
        sb.append((char) b);
      }
    }
    String cleaned = sb.toString().trim();
    return cleaned.isEmpty() ? null : cleaned;
  }

  private void connectWebSocket() {
    webSocketFactory
        .connect(
            gatewayWsUrl,
            lastKnownClientIp,
            new Listener() {
              @Override
              public void onOpen(WebSocket webSocket) {
                setWebSocket(webSocket);
                webSocket.request(1);
                logger.info("WebSocket connected to {}", gatewayWsUrl);
              }

              @Override
              public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                logger.info("Gateway response: {}", data);
                updateLastActivity();
                channelContext.writeAndFlush(data.toString() + "\n");
                webSocket.request(1);
                return null;
              }

              @Override
              public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
                updateLastActivity();
                webSocket.request(1);
                return Listener.super.onPong(webSocket, message);
              }

              @Override
              public void onError(WebSocket webSocket, Throwable error) {
                logger.warn("WebSocket connection to {} failed", gatewayWsUrl, error);
                handleGatewayDisconnect();
              }

              @Override
              public CompletionStage<?> onClose(
                  WebSocket webSocket, int statusCode, String reason) {
                logger.info("WebSocket closed from gateway: {} - {}", statusCode, reason);
                handleGatewayDisconnect();
                return Listener.super.onClose(webSocket, statusCode, reason);
              }
            })
        .whenComplete(
            (socket, error) -> {
              if (error != null) {
                logger.error("WebSocket connection to {} failed", gatewayWsUrl, error);
                handleGatewayDisconnect();
              }
            });
  }

  private void sendHeartbeat() {
    if (webSocket == null) {
      return;
    }
    Timer.Sample sample = Timer.start(meterRegistry);
    CompletableFuture<WebSocket> future = webSocket.sendPing(ByteBuffer.wrap(new byte[] {0x0}));
    registerSendFuture("__heartbeat__", future);
    future.whenComplete(
        (ws, error) -> {
          sample.stop(heartbeatTimer);
          if (error != null) {
            logger.warn("Heartbeat ping failed", error);
            handleGatewayDisconnect();
          }
        });
  }

  private void closeIfIdle() {
    Duration idleDuration = Duration.ofNanos(System.nanoTime() - lastActivityNanos);
    if (idleDuration.compareTo(idleTimeout) <= 0) {
      return;
    }
    Timer.Sample sample = Timer.start(meterRegistry);
    logger.warn("Closing idle Telnet session after {}", idleDuration);
    sample.stop(idleCloseTimer);
    if (channelContext != null) {
      channelContext.close();
    }
  }

  private void handleGatewayDisconnect() {
    if (disconnected) {
      inFlightMessages.clear();
      inFlightSends.clear();
      buffer.clear();
      return;
    }
    webSocket = null;
    if (!inFlightMessages.isEmpty()) {
      var pendingMessages = new ArrayList<>(inFlightMessages.values());
      inFlightMessages.clear();
      inFlightSends.clear();
      pendingMessages.stream()
          .filter(message -> message != null && !"__heartbeat__".equals(message))
          .forEach(this::bufferMessage);
    }
    if (channelContext == null || scheduler.isShutdown()) {
      return;
    }
    if (reconnectFuture != null && !reconnectFuture.isDone()) {
      return;
    }
    long backoffMillis =
        Math.min(
                reconnectBaseDelay.toMillis() * (long) Math.pow(2, reconnectAttempts),
                MAX_BACKOFF.toMillis());
    reconnectAttempts++;
    reconnectFuture =
        scheduler.schedule(
            this::connectWebSocket,
            backoffMillis,
            TimeUnit.MILLISECONDS);
    logger.info("Scheduling gateway reconnect in {} ms", backoffMillis);
  }

  private void sendToGateway(String sanitized) {
    if (webSocket == null) {
      bufferMessage(sanitized);
      return;
    }
    if (inFlightSends.size() >= maxInFlight) {
      logger.warn("In-flight send limit exceeded; closing session to protect memory");
      if (channelContext != null) {
        channelContext.close();
      }
      return;
    }
    CompletableFuture<WebSocket> future = webSocket.sendText(sanitized, true);
    registerSendFuture(sanitized, future);
  }

  private void bufferMessage(String sanitized) {
    if (disconnected) {
      return;
    }
    if (buffer.size() >= maxBufferDepth) {
      logger.warn("Buffer depth {} exceeded; closing Telnet session to avoid memory pressure", maxBufferDepth);
      if (channelContext != null) {
        channelContext.close();
      }
      return;
    }
    buffer.add(sanitized);
  }

  private void registerSendFuture(String payload, CompletableFuture<WebSocket> future) {
    inFlightSends.add(future);
    inFlightMessages.put(future, payload);
    future.whenComplete(
        (ws, error) -> {
          inFlightSends.remove(future);
          String message = inFlightMessages.remove(future);
          if (error != null && message != null && !"__heartbeat__".equals(message)) {
            bufferMessage(message);
            handleGatewayDisconnect();
          }
        });
  }

  interface WebSocketFactory {
    CompletableFuture<WebSocket> connect(String gatewayWsUrl, String clientIp, Listener listener);
  }

  private static final class HttpClientWebSocketFactory implements WebSocketFactory {
    private final HttpClient httpClient;

    private HttpClientWebSocketFactory(HttpClient httpClient) {
      this.httpClient = httpClient;
    }

    @Override
    public CompletableFuture<WebSocket> connect(String gatewayWsUrl, String clientIp, Listener listener) {
      var builder = httpClient.newWebSocketBuilder();
      if (clientIp != null) {
        builder.header("X-Client-IP", clientIp);
      }
      return builder.buildAsync(URI.create(gatewayWsUrl), listener);
    }
  }
}
