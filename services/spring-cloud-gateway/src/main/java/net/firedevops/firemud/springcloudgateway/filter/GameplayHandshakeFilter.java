package net.firedevops.firemud.springcloudgateway.filter;

import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.runtime.RuntimeLoggingContext;
import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.springcloudgateway.websocket.GameplayWebSocketObservability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Preserves trusted TCP Proxy handshakes and denies first-party admission until canonical authority
 * is ready.
 */
@Component
public final class GameplayHandshakeFilter implements WebFilter, Ordered {
  private static final Logger logger = LoggerFactory.getLogger(GameplayHandshakeFilter.class);

  static final String HANDSHAKE_ERROR_CLASS_HEADER = "X-Firemud-Handshake-Error-Class";
  static final String CONNECTION_MODE_HEADER = "X-Firemud-Connection-Mode";
  static final String CONNECT_TOKEN_HEADER = "X-Firemud-Connect-Token";
  static final String CONNECT_TOKEN_COOKIE = "Firemud-Connect-Token";
  static final String CONNECT_CONTEXT_HEADER = "X-Firemud-Connect-Context";
  static final String TRANSPORT_SESSION_HEADER = "X-Firemud-Transport-Session-Id";
  static final String WORLD_SLUG_HEADER = "X-World-Slug";
  static final String REALM_SLUG_HEADER = "X-Realm-Slug";
  static final String POINTER_VERSION_HEADER = "X-Pointer-Version";
  static final String GAME_INSTANCE_ID_HEADER = "X-Game-Instance-Id";
  static final String TENANT_ID_HEADER = "X-Tenant-Id";
  static final String PROXY_CONNECTION_ID_HEADER = "X-Proxy-Connection-Id";
  static final String CONNECTION_MODE_TRUSTED_TCP_PROXY = "trusted_tcp_proxy";
  static final String CONNECT_TOKEN_REJECTED = "CONNECT_TOKEN_REJECTED";
  static final String HANDSHAKE_ERROR_REASON_HEADER = "X-Firemud-Handshake-Error-Reason";
  static final String CONNECT_TOKEN_UNSUPPORTED_CARRIER_OR_ROUTE = "unsupported_carrier_or_route";
  static final String CONNECT_TOKEN_MISSING = "CONNECT_TOKEN_MISSING";
  static final String CONNECT_SCOPE_MISMATCH = "CONNECT_SCOPE_MISMATCH";
  static final String CONNECT_REPLAY_PROTECTION_UNAVAILABLE =
      "CONNECT_REPLAY_PROTECTION_UNAVAILABLE";

  private final RuntimeIdentity runtimeIdentity;
  private final GameplayWebSocketObservability gameplayWebSocketObservability;

  @Autowired
  public GameplayHandshakeFilter(
      RuntimeIdentity runtimeIdentity,
      GameplayWebSocketObservability gameplayWebSocketObservability) {
    this.runtimeIdentity =
        Objects.requireNonNull(runtimeIdentity, "runtimeIdentity must not be null");
    this.gameplayWebSocketObservability =
        Objects.requireNonNull(
            gameplayWebSocketObservability, "gameplayWebSocketObservability must not be null");
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
    GameplayRouteClassifier.Classification route = GameplayRouteClassifier.classify(path);
    if (!route.gameplayRoute()) {
      return chain.filter(exchange);
    }
    if (route.matrixParameter()) {
      return reject(
          exchange,
          CONNECT_TOKEN_REJECTED,
          CONNECT_TOKEN_UNSUPPORTED_CARRIER_OR_ROUTE,
          "connect token rejected");
    }

    if (isTrustedTcpProxy(exchange)) {
      if (!trustedProxyRoutingBundleIsCoherent(exchange)) {
        return reject(exchange, CONNECT_SCOPE_MISMATCH, "connect scope mismatch");
      }
      return chain.filter(
          mutate(
              exchange,
              headers -> {
                headers.remove(CONNECT_TOKEN_HEADER);
                removeConnectTokenCookie(headers);
                headers.remove(CONNECT_CONTEXT_HEADER);
                headers.remove(CONNECTION_MODE_HEADER);
                headers.remove(TRANSPORT_SESSION_HEADER);
                headers.remove(HANDSHAKE_ERROR_CLASS_HEADER);
                headers.remove(HANDSHAKE_ERROR_REASON_HEADER);
                headers.set(CONNECTION_MODE_HEADER, CONNECTION_MODE_TRUSTED_TCP_PROXY);
              }));
    }

    String connectTokenHeader = exchange.getRequest().getHeaders().getFirst(CONNECT_TOKEN_HEADER);
    HttpCookie connectTokenCookie =
        exchange.getRequest().getCookies().getFirst(CONNECT_TOKEN_COOKIE);
    if (connectTokenHeader != null) {
      return reject(
          exchange,
          CONNECT_TOKEN_REJECTED,
          CONNECT_TOKEN_UNSUPPORTED_CARRIER_OR_ROUTE,
          "connect token rejected");
    }
    String connectToken = connectTokenCookie == null ? null : connectTokenCookie.getValue();
    if (!StringUtils.hasText(connectToken)) {
      return reject(exchange, CONNECT_TOKEN_MISSING, "connect token required");
    }

    return reject(
        exchange, CONNECT_REPLAY_PROTECTION_UNAVAILABLE, "connect replay protection unavailable");
  }

  private boolean isTrustedTcpProxy(ServerWebExchange exchange) {
    return StringUtils.hasText(
        exchange.getRequest().getHeaders().getFirst(PROXY_CONNECTION_ID_HEADER));
  }

  private boolean trustedProxyRoutingBundleIsCoherent(ServerWebExchange exchange) {
    try {
      validateRuntimeRoutingBundleFromHeaders(exchange);
      parseTrustedTcpProxyIdentityFromHeaders(exchange);
      return true;
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private static void validateRuntimeRoutingBundleFromHeaders(ServerWebExchange exchange) {
    validateRuntimeRoutingBundle(
        exchange.getRequest().getHeaders().getFirst(WORLD_SLUG_HEADER),
        exchange.getRequest().getHeaders().getFirst(REALM_SLUG_HEADER),
        exchange.getRequest().getHeaders().getFirst(POINTER_VERSION_HEADER),
        true);
  }

  private static void parseTrustedTcpProxyIdentityFromHeaders(ServerWebExchange exchange) {
    TrustedTcpProxyIdentity.validateIncoming(
        exchange.getRequest().getHeaders().getFirst(TENANT_ID_HEADER),
        exchange.getRequest().getHeaders().getFirst(GAME_INSTANCE_ID_HEADER));
  }

  private static void validateRuntimeRoutingBundle(
      String worldSlug, String realmSlug, String pointerVersion, boolean allowMissing) {
    boolean hasWorld = StringUtils.hasText(worldSlug);
    boolean hasRealm = StringUtils.hasText(realmSlug);
    boolean hasPointer = StringUtils.hasText(pointerVersion);
    boolean hasFullBundle = hasWorld && hasRealm && hasPointer;
    if (!hasFullBundle) {
      if (allowMissing && !hasWorld && !hasRealm && !hasPointer) {
        return;
      }
      throw new IllegalArgumentException("Malformed gameplay routing bundle");
    }
    JwtClaims.requireLong(pointerVersion, POINTER_VERSION_HEADER, false);
  }

  private ServerWebExchange mutate(
      ServerWebExchange exchange,
      java.util.function.Consumer<org.springframework.http.HttpHeaders> op) {
    return exchange.mutate().request(request -> request.headers(op)).build();
  }

  private static void removeConnectTokenCookie(HttpHeaders headers) {
    List<String> cookieHeaders = headers.get(HttpHeaders.COOKIE);
    if (cookieHeaders != null) {
      cookieHeaders = List.copyOf(cookieHeaders);
    }
    headers.remove(HttpHeaders.COOKIE);
    if (cookieHeaders == null) {
      return;
    }
    for (String cookieHeader : cookieHeaders) {
      String filteredCookieHeader = removeConnectTokenCookie(cookieHeader);
      if (filteredCookieHeader != null) {
        headers.add(HttpHeaders.COOKIE, filteredCookieHeader);
      }
    }
  }

  @Nullable
  private static String removeConnectTokenCookie(String cookieHeader) {
    StringBuilder filtered = new StringBuilder(cookieHeader.length());
    for (String cookiePair : cookieHeader.split(";")) {
      String trimmedPair = cookiePair.trim();
      int equalsIndex = trimmedPair.indexOf('=');
      if (equalsIndex > 0
          && CONNECT_TOKEN_COOKIE.equals(trimmedPair.substring(0, equalsIndex).trim())) {
        continue;
      }
      if (trimmedPair.isEmpty()) {
        continue;
      }
      if (filtered.length() > 0) {
        filtered.append("; ");
      }
      filtered.append(trimmedPair);
    }
    return filtered.length() == 0 ? null : filtered.toString();
  }

  RuntimeLoggingContext openLoggingContext(ServerWebExchange exchange) {
    String correlationId =
        firstNonBlank(
            exchange.getRequest().getHeaders().getFirst(TRANSPORT_SESSION_HEADER),
            exchange.getRequest().getId());
    return RuntimeLoggingContext.open(runtimeIdentity, correlationId);
  }

  private void logRejectedHandshake(
      ServerWebExchange exchange, String errorClass, String reason, String message) {
    try (RuntimeLoggingContext ignored = openLoggingContext(exchange)) {
      if (reason == null) {
        logger.warn(
            "Gameplay handshake rejected route={} status={} errorClass={} message={}",
            GameplayWebSocketObservability.GAMEPLAY_ROUTE,
            HttpStatus.FORBIDDEN.value(),
            errorClass,
            message);
      } else {
        logger.warn(
            "Gameplay handshake rejected route={} status={} errorClass={} reason={} message={}",
            GameplayWebSocketObservability.GAMEPLAY_ROUTE,
            HttpStatus.FORBIDDEN.value(),
            errorClass,
            reason,
            message);
      }
    }
  }

  private Mono<Void> reject(ServerWebExchange exchange, String errorClass, String message) {
    return reject(exchange, errorClass, null, message);
  }

  private Mono<Void> reject(
      ServerWebExchange exchange, String errorClass, String reason, String message) {
    gameplayWebSocketObservability.recordHandshakeRejection(
        HttpStatus.FORBIDDEN.value(), errorClass);
    logRejectedHandshake(exchange, errorClass, reason, message);
    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
    exchange.getResponse().getHeaders().set(HANDSHAKE_ERROR_CLASS_HEADER, errorClass);
    if (CONNECT_TOKEN_REJECTED.equals(errorClass)) {
      exchange.getResponse().getHeaders().set(HANDSHAKE_ERROR_REASON_HEADER, reason);
    } else {
      exchange.getResponse().getHeaders().remove(HANDSHAKE_ERROR_REASON_HEADER);
    }
    return exchange.getResponse().setComplete();
  }

  @Override
  public int getOrder() {
    return -3;
  }

  private static String firstNonBlank(String primary, String fallback) {
    return StringUtils.hasText(primary) ? primary : fallback;
  }
}
