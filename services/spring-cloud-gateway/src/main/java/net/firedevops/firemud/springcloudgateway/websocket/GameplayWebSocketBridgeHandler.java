package net.firedevops.firemud.springcloudgateway.websocket;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.runtime.RuntimeLoggingContext;
import net.firedevops.firemud.springcloudgateway.config.GameplayWebSocketBridgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Keeps the downstream gameplay socket open while rebinding a replacement upstream session. */
public class GameplayWebSocketBridgeHandler implements WebSocketHandler, SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(GameplayWebSocketBridgeHandler.class);
  private static final Duration UPSTREAM_CONNECT_TIMEOUT = Duration.ofSeconds(1);
  private static final Duration DOWNSTREAM_CLOSE_TIMEOUT = UPSTREAM_CONNECT_TIMEOUT;
  private static final CloseStatus PLANNED_DRAIN =
      new CloseStatus(1000, "logout;subreason=gateway_restart");
  private static final String CONNECTION_MODE_HEADER = "X-Firemud-Connection-Mode";
  private static final String CONNECT_CONTEXT_HEADER = "X-Firemud-Connect-Context";
  private static final String TRANSPORT_SESSION_HEADER = "X-Firemud-Transport-Session-Id";
  private static final String WORLD_SLUG_HEADER = "X-World-Slug";
  private static final String REALM_SLUG_HEADER = "X-Realm-Slug";
  private static final String POINTER_VERSION_HEADER = "X-Pointer-Version";
  private static final CloseStatus BACKEND_UNAVAILABLE =
      new CloseStatus(1013, "backend_unavailable");
  private static final CloseStatus INTERNAL_ERROR = new CloseStatus(1011, "internal_error");
  private static final List<String> FORWARDED_HEADERS =
      List.of(
          "X-Game-Instance-Id",
          "X-Tenant-Id",
          WORLD_SLUG_HEADER,
          REALM_SLUG_HEADER,
          POINTER_VERSION_HEADER,
          "X-Requires-Solo-Tick",
          "X-Proxy-Connection-Id",
          CONNECTION_MODE_HEADER,
          CONNECT_CONTEXT_HEADER,
          TRANSPORT_SESSION_HEADER);

  private final ReactorNettyWebSocketClient client;
  private final GameplayWebSocketBridgeProperties properties;
  private final RuntimeIdentity runtimeIdentity;
  private final GameplayWebSocketObservability gameplayWebSocketObservability;
  private final Set<BridgeState> activeBridges = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean shuttingDown = new AtomicBoolean();
  private final Object lifecycleMonitor = new Object();
  private volatile Mono<Void> shutdownCompletion;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected client, properties, and runtime identity remain internal.")
  public GameplayWebSocketBridgeHandler(
      ReactorNettyWebSocketClient client,
      GameplayWebSocketBridgeProperties properties,
      RuntimeIdentity runtimeIdentity,
      GameplayWebSocketObservability gameplayWebSocketObservability) {
    this.client = client;
    this.properties = properties;
    this.runtimeIdentity = runtimeIdentity;
    this.gameplayWebSocketObservability = gameplayWebSocketObservability;
  }

  GameplayWebSocketBridgeHandler(
      ReactorNettyWebSocketClient client,
      GameplayWebSocketBridgeProperties properties,
      RuntimeIdentity runtimeIdentity) {
    this(client, properties, runtimeIdentity, GameplayWebSocketObservability.disabled());
  }

  @Override
  public Mono<Void> handle(WebSocketSession downstream) {
    BridgeState state = new BridgeState(downstream, properties.bufferCapacity());
    synchronized (lifecycleMonitor) {
      activeBridges.add(state);
      if (shuttingDown.get()) {
        return closeDownstream(state, plannedDrainClassification())
            .doFinally(signal -> activeBridges.remove(state));
      }
    }
    HttpHeaders upstreamHeaders = buildUpstreamHeaders(downstream);
    URI upstreamUri = URI.create(properties.upstreamUrl());

    Mono<Void> downstreamSend =
        downstream.send(state.outboundToClient.asFlux().map(text -> downstream.textMessage(text)));

    Mono<Void> downstreamReceive =
        downstream
            .receive()
            .map(WebSocketMessage::getPayloadAsText)
            .doOnNext(payload -> emitIfConnected(downstream, state, payload))
            .doFinally(
                signal -> {
                  state.downstreamClosed.set(true);
                  state.inboundToUpstream.tryEmitComplete();
                  state.outboundToClient.tryEmitComplete();
                })
            .then();

    Mono<Void> upstreamLoop = connectWithRetry(downstream, upstreamUri, upstreamHeaders, state, 0);
    return Mono.when(downstreamSend, downstreamReceive, upstreamLoop)
        .then()
        .doFinally(signal -> activeBridges.remove(state));
  }

  @Override
  public void start() {
    synchronized (lifecycleMonitor) {
      shuttingDown.set(false);
      shutdownCompletion = null;
    }
  }

  @Override
  public void stop() {
    stop(() -> {});
  }

  @Override
  public void stop(Runnable callback) {
    getOrCreateShutdownCompletion()
        .doFinally(signal -> callback.run())
        .subscribe(
            ignored -> {},
            error -> LOG.warn("Gameplay websocket shutdown encountered a close failure", error));
  }

  @Override
  public boolean isRunning() {
    return !shuttingDown.get();
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  private Mono<Void> getOrCreateShutdownCompletion() {
    synchronized (lifecycleMonitor) {
      if (shutdownCompletion == null) {
        shuttingDown.set(true);
        GameplayWebSocketObservability.CloseClassification plannedDrain =
            plannedDrainClassification();
        List<Mono<Void>> closeOperations =
            activeBridges.stream().map(state -> closeDownstream(state, plannedDrain)).toList();
        shutdownCompletion = Mono.whenDelayError(closeOperations).cache();
      }
      return shutdownCompletion;
    }
  }

  private Mono<Void> connectWithRetry(
      WebSocketSession downstream,
      URI upstreamUri,
      HttpHeaders upstreamHeaders,
      BridgeState state,
      int attempt) {
    if (state.downstreamClosed.get()) {
      return Mono.empty();
    }
    Sinks.One<Void> connected = Sinks.one();
    Mono<Void> sessionLifecycle =
        client.execute(
            upstreamUri,
            upstreamHeaders,
            upstream -> {
              connected.tryEmitEmpty();
              return bridgeUpstreamSession(downstream, upstream, state);
            });
    return Mono.when(connected.asMono().timeout(UPSTREAM_CONNECT_TIMEOUT), sessionLifecycle)
        .onErrorResume(
            GameplayBridgeReconnectException.class,
            reconnect -> {
              if (state.downstreamClosed.get()) {
                return Mono.empty();
              }
              if (attempt >= properties.reconnectAttempts()) {
                try (RuntimeLoggingContext ignored = openLoggingContext(downstream)) {
                  LOG.warn(
                      "Gameplay upstream reconnect exhausted after {} attempts for downstream {}",
                      attempt + 1,
                      downstream.getId());
                }
                return closeDownstream(
                    state, gameplayWebSocketObservability.classify(BACKEND_UNAVAILABLE));
              }
              try (RuntimeLoggingContext ignored = openLoggingContext(downstream)) {
                LOG.info(
                    "Gameplay upstream reconnecting attempt {} for downstream {}",
                    attempt + 1,
                    downstream.getId());
              }
              return Mono.delay(Duration.ofMillis(properties.reconnectDelayMs()))
                  .then(
                      connectWithRetry(
                          downstream, upstreamUri, upstreamHeaders, state, attempt + 1));
            })
        .onErrorResume(
            GameplayBridgeTerminalCloseException.class,
            terminal -> {
              if (state.downstreamClosed.get()) {
                return Mono.empty();
              }
              return closeDownstream(state, terminal.closeClassification());
            })
        .onErrorResume(
            transportError ->
                retryTransportFailure(downstream, upstreamUri, upstreamHeaders, state, attempt));
  }

  private Mono<Void> retryTransportFailure(
      WebSocketSession downstream,
      URI upstreamUri,
      HttpHeaders upstreamHeaders,
      BridgeState state,
      int attempt) {
    if (state.downstreamClosed.get()) {
      return Mono.empty();
    }
    if (attempt >= properties.reconnectAttempts()) {
      try (RuntimeLoggingContext ignored = openLoggingContext(downstream)) {
        LOG.warn(
            "Gameplay upstream transport reconnect exhausted after {} attempts for downstream {}",
            attempt + 1,
            downstream.getId());
      }
      return closeDownstream(state, gameplayWebSocketObservability.classify(BACKEND_UNAVAILABLE));
    }
    try (RuntimeLoggingContext ignored = openLoggingContext(downstream)) {
      LOG.info(
          "Gameplay upstream transport reconnecting attempt {} for downstream {}",
          attempt + 1,
          downstream.getId());
    }
    return Mono.delay(Duration.ofMillis(properties.reconnectDelayMs()))
        .then(connectWithRetry(downstream, upstreamUri, upstreamHeaders, state, attempt + 1));
  }

  private Mono<Void> bridgeUpstreamSession(
      WebSocketSession downstream, WebSocketSession upstream, BridgeState state) {
    state.upstreamConnected.set(true);
    Mono<Void> send =
        upstream.send(
            state
                .inboundToUpstream
                .asFlux()
                .map(upstream::textMessage)
                .doFinally(signal -> state.upstreamConnected.set(false)));
    Mono<Void> receive =
        upstream
            .receive()
            .map(WebSocketMessage::getPayloadAsText)
            .doOnNext(payload -> emitToDownstream(downstream, state, payload))
            .then();

    return Mono.firstWithSignal(send, receive)
        .then(
            upstream.closeStatus().defaultIfEmpty(BACKEND_UNAVAILABLE).flatMap(this::closeOutcome));
  }

  private Mono<Void> closeOutcome(CloseStatus status) {
    if (shouldReconnect(status)) {
      return Mono.error(new GameplayBridgeReconnectException(status));
    }
    return Mono.error(
        new GameplayBridgeTerminalCloseException(gameplayWebSocketObservability.classify(status)));
  }

  private boolean shouldReconnect(CloseStatus status) {
    if (status == null) {
      return true;
    }
    return status.getCode() != 1000
        && status.getCode() != 1001
        && status.getCode() != 1008
        && status.getCode() != 1011;
  }

  private void emitToDownstream(WebSocketSession downstream, BridgeState state, String payload) {
    Sinks.EmitResult result = state.outboundToClient.tryEmitNext(payload);
    if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
      try (RuntimeLoggingContext ignored = openLoggingContext(downstream)) {
        LOG.warn("Gameplay downstream buffer overflow result={}", result);
      }
      GameplayWebSocketObservability.CloseClassification slowClientClose =
          gameplayWebSocketObservability.slowClientClose();
      throw new GameplayBridgeTerminalCloseException(slowClientClose);
    }
    if (!result.isFailure()) {
      return;
    }
    if (state.downstreamClosed.get()) {
      try (RuntimeLoggingContext ignored = openLoggingContext(downstream)) {
        LOG.debug("Ignoring gameplay downstream emission during closure result={}", result);
      }
      return;
    }
    try (RuntimeLoggingContext ignored = openLoggingContext(downstream)) {
      LOG.warn("Gameplay downstream emission failed result={}", result);
    }
    throw new GameplayBridgeTerminalCloseException(
        gameplayWebSocketObservability.classify(INTERNAL_ERROR));
  }

  private Mono<Void> closeDownstream(
      BridgeState state, GameplayWebSocketObservability.CloseClassification closeClassification) {
    Mono<Void> existing = state.closeOperation.get();
    if (existing != null) {
      return existing;
    }
    Mono<Void> closeOperation =
        Mono.defer(
                () -> {
                  if (!state.downstreamClosed.compareAndSet(false, true)) {
                    return Mono.empty();
                  }
                  state.inboundToUpstream.tryEmitComplete();
                  state.outboundToClient.tryEmitComplete();
                  return Mono.defer(() -> state.downstream.close(closeClassification.status()))
                      .timeout(DOWNSTREAM_CLOSE_TIMEOUT)
                      .doOnSuccess(
                          unused -> {
                            gameplayWebSocketObservability.recordClose(closeClassification);
                            try (RuntimeLoggingContext ignored =
                                openLoggingContext(state.downstream)) {
                              LOG.info(
                                  "Gameplay websocket closed reason={} subreason={} bridge_shutdown_class={} code={}",
                                  closeClassification.reason(),
                                  closeClassification.subreason(),
                                  closeClassification.bridgeShutdownClass(),
                                  closeClassification.status().getCode());
                            }
                          })
                      .doOnError(
                          error -> {
                            GameplayWebSocketObservability.CloseClassification failedClose =
                                closeClassification.asUnattributedFailure();
                            gameplayWebSocketObservability.recordClose(failedClose);
                            try (RuntimeLoggingContext ignored =
                                openLoggingContext(state.downstream)) {
                              LOG.warn(
                                  "Gameplay websocket close failed reason={} subreason={} bridge_shutdown_class={} code={} error={}",
                                  failedClose.reason(),
                                  failedClose.subreason(),
                                  failedClose.bridgeShutdownClass(),
                                  failedClose.status().getCode(),
                                  error.getMessage(),
                                  error);
                            }
                          });
                })
            .cache();
    if (state.closeOperation.compareAndSet(null, closeOperation)) {
      return closeOperation;
    }
    return state.closeOperation.get();
  }

  private GameplayWebSocketObservability.CloseClassification plannedDrainClassification() {
    return gameplayWebSocketObservability.classify(PLANNED_DRAIN);
  }

  private void emitIfConnected(WebSocketSession downstream, BridgeState state, String payload) {
    if (!state.upstreamConnected.get()) {
      try (RuntimeLoggingContext ignored = openLoggingContext(downstream)) {
        LOG.debug("Queuing downstream gameplay message during upstream stall");
      }
    }
    Sinks.EmitResult result = state.inboundToUpstream.tryEmitNext(payload);
    if (result.isFailure()) {
      try (RuntimeLoggingContext ignored = openLoggingContext(downstream)) {
        LOG.debug("Failed to queue downstream gameplay message: {}", result);
      }
    }
  }

  RuntimeLoggingContext openLoggingContext(WebSocketSession session) {
    String correlationId =
        firstNonBlank(
            session.getHandshakeInfo().getHeaders().getFirst(TRANSPORT_SESSION_HEADER),
            session.getId());
    return RuntimeLoggingContext.open(runtimeIdentity, correlationId);
  }

  private static String firstNonBlank(String primary, String fallback) {
    return StringUtils.hasText(primary) ? primary : fallback;
  }

  private HttpHeaders buildUpstreamHeaders(WebSocketSession downstream) {
    HttpHeaders headers = new HttpHeaders();
    HttpHeaders source = downstream.getHandshakeInfo().getHeaders();
    FORWARDED_HEADERS.forEach(
        name -> {
          String value = source.getFirst(name);
          if (StringUtils.hasText(value)) {
            headers.set(name, value);
          }
        });
    if (!StringUtils.hasText(headers.getFirst(TRANSPORT_SESSION_HEADER))) {
      headers.set(
          TRANSPORT_SESSION_HEADER, Long.toUnsignedString(stablePositiveLong(downstream.getId())));
    }
    return headers;
  }

  private long stablePositiveLong(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      long candidate = ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
      return candidate == Long.MIN_VALUE ? 0L : Math.abs(candidate);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private static final class BridgeState {
    private final WebSocketSession downstream;
    private final Sinks.Many<String> inboundToUpstream;
    private final Sinks.Many<String> outboundToClient;
    private final AtomicReference<Mono<Void>> closeOperation = new AtomicReference<>();
    private final AtomicBoolean upstreamConnected = new AtomicBoolean(false);
    private final AtomicBoolean downstreamClosed = new AtomicBoolean(false);

    private BridgeState(WebSocketSession downstream, int bufferCapacity) {
      this.downstream = downstream;
      this.inboundToUpstream = Sinks.many().multicast().onBackpressureBuffer(bufferCapacity, false);
      this.outboundToClient =
          Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(bufferCapacity));
    }
  }
}
