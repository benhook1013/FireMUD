package net.firedevops.firemud.springcloudgateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.common.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Enforces gameplay handshakes for /ws/game/** and classifies first-party gateway admissions. */
@Component
public class GameplayHandshakeFilter implements WebFilter, Ordered {
  private static final Logger logger = LoggerFactory.getLogger(GameplayHandshakeFilter.class);

  static final String HANDSHAKE_ERROR_CLASS_HEADER = "X-Firemud-Handshake-Error-Class";
  static final String CONNECTION_MODE_HEADER = "X-Firemud-Connection-Mode";
  static final String CONNECT_TOKEN_HEADER = "X-Firemud-Connect-Token";
  static final String CONNECT_CONTEXT_HEADER = "X-Firemud-Connect-Context";
  static final String TRANSPORT_SESSION_HEADER = "X-Firemud-Transport-Session-Id";
  static final String CONNECTION_MODE_FIRST_PARTY_WEB = "first_party_web";
  static final String CONNECTION_MODE_TRUSTED_TCP_PROXY = "trusted_tcp_proxy";
  static final String CONNECT_TOKEN_REJECTED = "CONNECT_TOKEN_REJECTED";
  static final String CONNECT_TOKEN_MISSING = "CONNECT_TOKEN_MISSING";
  static final String CONNECT_TOKEN_EXPIRED = "CONNECT_TOKEN_EXPIRED";
  static final String CONNECT_TOKEN_REPLAYED = "CONNECT_TOKEN_REPLAYED";
  static final String CONNECT_SCOPE_MISMATCH = "CONNECT_SCOPE_MISMATCH";
  private static final Duration REPLAY_SKEW = Duration.ofSeconds(5);

  private final JwtUtil jwtUtil;
  private final ConcurrentHashMap<String, Long> replayCache = new ConcurrentHashMap<>();

  public GameplayHandshakeFilter(JwtUtil jwtUtil) {
    this.jwtUtil = Objects.requireNonNull(jwtUtil, "jwtUtil must not be null");
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().pathWithinApplication().value();
    if (!path.startsWith("/ws/game")) {
      return chain.filter(exchange);
    }

    if (isTrustedTcpProxy(exchange)) {
      return chain.filter(
          mutate(
              exchange,
              headers -> {
                headers.remove(CONNECT_TOKEN_HEADER);
                headers.remove(CONNECT_CONTEXT_HEADER);
                headers.set(CONNECTION_MODE_HEADER, CONNECTION_MODE_TRUSTED_TCP_PROXY);
              }));
    }

    String connectToken = exchange.getRequest().getHeaders().getFirst(CONNECT_TOKEN_HEADER);
    if (!StringUtils.hasText(connectToken)) {
      return reject(exchange, CONNECT_TOKEN_MISSING, "connect token required");
    }

    try {
      Jws<Claims> claims = jwtUtil.parseToken(connectToken);
      Claims payload = claims.getPayload();
      String accountId = requiredClaim(payload, "accountId");
      String tenantId = requiredClaim(payload, "tenantId");
      String gameInstanceId = requiredClaim(payload, "gameInstanceId");
      String jti = requiredClaim(payload, "jti");
      recordReplayOrReject(jti, payload.getExpiration().getTime());

      if (mismatched(exchange, "X-Tenant-Id", tenantId)
          || mismatched(exchange, "X-Game-Instance-Id", gameInstanceId)) {
        return reject(exchange, CONNECT_SCOPE_MISMATCH, "connect scope mismatch");
      }

      Instant verifiedAt = Instant.now();
      String connectContext =
          jwtUtil.generateToken(
              accountId,
              Map.of(
                  "accountId", accountId,
                  "tenantId", tenantId,
                  "gameInstanceId", gameInstanceId,
                  "connectTokenJti", jti,
                  "verifiedAt", verifiedAt.toEpochMilli(),
                  "expiresAt", payload.getExpiration().getTime(),
                  "gatewayRequestId", exchange.getRequest().getId()));

      return chain.filter(
          mutate(
              exchange,
              headers -> {
                headers.remove(CONNECT_TOKEN_HEADER);
                headers.set(CONNECT_CONTEXT_HEADER, connectContext);
                headers.set(CONNECTION_MODE_HEADER, CONNECTION_MODE_FIRST_PARTY_WEB);
                headers.set(
                    TRANSPORT_SESSION_HEADER,
                    Long.toUnsignedString(stablePositiveLong(exchange.getRequest().getId())));
                headers.set("X-Tenant-Id", tenantId);
                headers.set("X-Game-Instance-Id", gameInstanceId);
              }));
    } catch (ReplayRejectedException ex) {
      logger.debug("Rejected replayed first-party gameplay handshake: {}", ex.getMessage());
      return reject(exchange, CONNECT_TOKEN_REPLAYED, "connect token replayed");
    } catch (ExpiredJwtException ex) {
      logger.debug("Rejected expired first-party gameplay handshake: {}", ex.getMessage());
      return reject(exchange, CONNECT_TOKEN_EXPIRED, "connect token expired");
    } catch (JwtException ex) {
      logger.debug("Rejected first-party gameplay handshake: {}", ex.getMessage());
      return reject(exchange, CONNECT_TOKEN_REJECTED, "connect token rejected");
    } catch (RuntimeException ex) {
      logger.debug("Rejected first-party gameplay handshake: {}", ex.getMessage());
      return reject(exchange, CONNECT_TOKEN_REJECTED, "connect token rejected");
    }
  }

  private boolean isTrustedTcpProxy(ServerWebExchange exchange) {
    return StringUtils.hasText(
        exchange.getRequest().getHeaders().getFirst("X-Proxy-Connection-Id"));
  }

  private static boolean mismatched(
      ServerWebExchange exchange, String headerName, String expected) {
    String actual = exchange.getRequest().getHeaders().getFirst(headerName);
    return StringUtils.hasText(actual) && !Objects.equals(actual, expected);
  }

  private ServerWebExchange mutate(
      ServerWebExchange exchange,
      java.util.function.Consumer<org.springframework.http.HttpHeaders> op) {
    return exchange.mutate().request(request -> request.headers(op)).build();
  }

  private Mono<Void> reject(ServerWebExchange exchange, String errorClass, String message) {
    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
    exchange.getResponse().getHeaders().set(HANDSHAKE_ERROR_CLASS_HEADER, errorClass);
    return exchange.getResponse().setComplete();
  }

  private String requiredClaim(Claims claims, String name) {
    Object value = claims.get(name);
    if (value == null) {
      throw new IllegalArgumentException("Missing claim: " + name);
    }
    String text = String.valueOf(value).trim();
    if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
      throw new IllegalArgumentException("Missing claim: " + name);
    }
    return text;
  }

  private void recordReplayOrReject(String jti, long expiryMillis) {
    long now = System.currentTimeMillis();
    replayCache.entrySet().removeIf(entry -> entry.getValue() <= now);
    long replayExpiry = expiryMillis + REPLAY_SKEW.toMillis();
    Long existing = replayCache.putIfAbsent(jti, replayExpiry);
    if (existing != null && existing > now) {
      throw new ReplayRejectedException("connect token replayed");
    }
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

  @Override
  public int getOrder() {
    return -3;
  }

  private static final class ReplayRejectedException extends RuntimeException {
    private ReplayRejectedException(String message) {
      super(message);
    }
  }
}
