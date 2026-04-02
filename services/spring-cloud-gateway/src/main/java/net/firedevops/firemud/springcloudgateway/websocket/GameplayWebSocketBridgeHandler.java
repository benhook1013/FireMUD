package net.firedevops.firemud.springcloudgateway.websocket;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.runtime.RuntimeLoggingContext;
import net.firedevops.firemud.springcloudgateway.config.GameplayWebSocketBridgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class GameplayWebSocketBridgeHandler implements WebSocketHandler {
  private static final Logger LOG = LoggerFactory.getLogger(GameplayWebSocketBridgeHandler.class);
  private static final Duration UPSTREAM_CONNECT_TIMEOUT = Duration.ofSeconds(1);
  private static final String CONNECTION_MODE_HEADER = "X-Firemud-Connection-Mode";
  private static final String CONNECT_CONTEXT_HEADER = "X-Firemud-Connect-Context";
  private static final String TRANSPORT_SESSION_HEADER = "X-Firemud-Transport-Session-Id";
  private static final CloseStatus BACKEND_UNAVAILABLE =
      new CloseStatus(1011, "backend_unavailable");
  private static final List<String> FORWARDED_HEADERS =
      List.of(
          "X-Game-Instance-Id",
          "X-Tenant-Id",
          "X-Requires-Solo-Tick",
          "X-Proxy-Connection-Id",
          CONNECTION_MODE_HEADER,
          CONNECT_CONTEXT_HEADER,
          TRANSPORT_SESSION_HEADER);

  private final ReactorNettyWebSocketClient client;
  private final GameplayWebSocketBridgeProperties properties;
  private final RuntimeIdentity runtimeIdentity;

  public GameplayWebSocketBridgeHandler(
      ReactorNettyWebSocketClient client,
      GameplayWebSocketBridgeProperties properties,
      RuntimeIdentity runtimeIdentity) {
    this.client = client;
    this.properties = properties;
    this.runtimeIdentity = runtimeIdentity;
  }

  @Override
  public Mono<Void> handle(WebSocketSession downstream) {
    BridgeState state = new BridgeState();
    HttpHeaders upstreamHeaders = buildUpstreamHeaders(downstream);
    URI upstreamUri = URI.create(properties.upstreamUrl());

    Mono<Void> downstreamSend =
        downstream.send(state.outboundToClient.asFlux().map(text -> downstream.textMessage(text)));

    Mono<Void> downstreamReceive =
        downstream
            .receive()
            .map(WebSocketMessage::getPayloadAsText)
            .doOnNext(payload -> emitIfConnected(state, payload))
            .doFinally(
                signal -> {
                  state.downstreamClosed.set(true);
                  state.inboundToUpstream.tryEmitComplete();
                  state.outboundToClient.tryEmitComplete();
                })
            .then();

    Mono<Void> upstreamLoop = connectWithRetry(downstream, upstreamUri, upstreamHeaders, state, 0);
    return Mono.when(downstreamSend, downstreamReceive, upstreamLoop).then();
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
              return bridgeUpstreamSession(upstream, state);
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
                return downstream.close(BACKEND_UNAVAILABLE);
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
              return downstream.close(terminal.status());
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
      return downstream.close(BACKEND_UNAVAILABLE);
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

  private Mono<Void> bridgeUpstreamSession(WebSocketSession upstream, BridgeState state) {
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
            .doOnNext(
                payload -> {
                  Sinks.EmitResult result = state.outboundToClient.tryEmitNext(payload);
                  if (result.isFailure()) {
                    try (RuntimeLoggingContext ignored = openLoggingContext(upstream)) {
                      LOG.warn(
                          "Failed to emit gameplay bridge payload '{}' to downstream: {}",
                          payload,
                          result);
                    }
                  }
                })
            .then();

    return Mono.firstWithSignal(send, receive)
        .then(
            upstream.closeStatus().defaultIfEmpty(BACKEND_UNAVAILABLE).flatMap(this::closeOutcome));
  }

  private Mono<Void> closeOutcome(CloseStatus status) {
    if (shouldReconnect(status)) {
      return Mono.error(new GameplayBridgeReconnectException(status));
    }
    return Mono.error(new GameplayBridgeTerminalCloseException(status));
  }

  private boolean shouldReconnect(CloseStatus status) {
    if (status == null) {
      return true;
    }
    if (status.getCode() == 1000 && "logout;subreason=gateway_restart".equals(status.getReason())) {
      return false;
    }
    if (status.getCode() == 1011 && "internal_error".equals(status.getReason())) {
      return false;
    }
    return status.getCode() != 1000 && status.getCode() != 1008;
  }

  private void emitIfConnected(BridgeState state, String payload) {
    if (!state.upstreamConnected.get()) {
      try (RuntimeLoggingContext ignored = RuntimeLoggingContext.open(runtimeIdentity)) {
        LOG.debug("Queuing downstream gameplay message during upstream stall");
      }
    }
    Sinks.EmitResult result = state.inboundToUpstream.tryEmitNext(payload);
    if (result.isFailure()) {
      try (RuntimeLoggingContext ignored = RuntimeLoggingContext.open(runtimeIdentity)) {
        LOG.debug("Failed to queue downstream gameplay message: {}", result);
      }
    }
  }

  private RuntimeLoggingContext openLoggingContext(WebSocketSession session) {
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
    private final Sinks.Many<String> inboundToUpstream =
        Sinks.many().multicast().onBackpressureBuffer();
    private final Sinks.Many<String> outboundToClient =
        Sinks.many().unicast().onBackpressureBuffer();
    private final AtomicBoolean upstreamConnected = new AtomicBoolean(false);
    private final AtomicBoolean downstreamClosed = new AtomicBoolean(false);
  }
}
