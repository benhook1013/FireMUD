package net.firedevops.firemud.accountservice.service.session;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.accountservice.config.AccountTokenProperties;
import net.firedevops.firemud.accountservice.dto.ConnectTokenResult;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SessionServiceImpl implements SessionService {
  private final RedisTemplate<String, Object> redisTemplate;
  private final AccountTokenProperties tokenProperties;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Dependencies are injected and managed by Spring")
  public SessionServiceImpl(
      RedisTemplate<String, Object> redisTemplate, AccountTokenProperties tokenProperties) {
    this.redisTemplate = redisTemplate;
    this.tokenProperties = tokenProperties;
  }

  @Override
  @Timed(value = "session.store")
  public void storeSession(Long tenantId, Long accountId, String token) {
    storeSession(tenantId, accountId, token, tokenProperties.getSessionExpirationMs());
  }

  @Override
  @Timed(value = "session.store_ttl")
  public void storeSession(Long tenantId, Long accountId, String token, long expirationMs) {
    Duration ttl = Duration.ofMillis(expirationMs);
    redisTemplate.opsForValue().set(accountKey(accountId, token), accountId, ttl);
    redisTemplate.opsForValue().set(tenantKey(tenantId, token), accountId, ttl);
  }

  @Override
  @Timed(value = "session.store_account")
  public void storeAccountSession(Long accountId, String token, long expirationMs) {
    redisTemplate
        .opsForValue()
        .set(accountKey(accountId, token), accountId, Duration.ofMillis(expirationMs));
  }

  @Override
  @Timed(value = "session.get")
  public Long getAccountId(Long tenantId, String token) {
    Object value = redisTemplate.opsForValue().get(tenantKey(tenantId, token));
    return parseRequiredLong(value).orElse(null);
  }

  @Override
  @Timed(value = "session.account_active")
  public boolean isAccountSessionActive(Long accountId, String token) {
    return parseRequiredLong(redisTemplate.opsForValue().get(accountKey(accountId, token)))
        .filter(accountId::equals)
        .isPresent();
  }

  @Override
  @Timed(value = "session.connect_token_replay.get")
  public Optional<ConnectTokenReplay> getConnectTokenReplay(
      Long tenantId, Long accountId, String connectScopeId, String requestId) {
    Object value =
        redisTemplate
            .opsForValue()
            .get(connectTokenReplayKey(tenantId, accountId, connectScopeId, requestId));
    if (!(value instanceof Map<?, ?> stored)) {
      return Optional.empty();
    }
    Optional<Boolean> success = parseRequiredBoolean(stored.get("success"));
    if (success.isEmpty()) {
      return Optional.empty();
    }
    if (success.get()) {
      Optional<Long> replayAccountId = parseRequiredLong(stored.get("accountId"));
      Optional<Long> replayTenantId = parseRequiredLong(stored.get("tenantId"));
      Optional<Long> gameInstanceId = parseRequiredLong(stored.get("gameInstanceId"));
      Optional<String> realmSlug = parseRequiredText(stored.get("realmSlug"));
      Optional<String> replayConnectScopeId = parseRequiredText(stored.get("connectScopeId"));
      Optional<String> connectToken = parseRequiredText(stored.get("connectToken"));
      Optional<String> jti = parseRequiredText(stored.get("jti"));
      Optional<String> replayRequestId = parseRequiredText(stored.get("requestId"));
      Optional<String> issuedAt = parseRequiredText(stored.get("issuedAt"));
      Optional<String> expiresAt = parseRequiredText(stored.get("expiresAt"));
      if (replayAccountId.isEmpty()
          || replayTenantId.isEmpty()
          || gameInstanceId.isEmpty()
          || realmSlug.isEmpty()
          || replayConnectScopeId.isEmpty()
          || connectToken.isEmpty()
          || jti.isEmpty()
          || replayRequestId.isEmpty()
          || issuedAt.isEmpty()
          || expiresAt.isEmpty()) {
        return Optional.empty();
      }
      if (!replayAccountId.get().equals(accountId) || !replayTenantId.get().equals(tenantId)) {
        return Optional.empty();
      }
      if (!connectScopeId.equals(replayConnectScopeId.get())
          || !requestId.equals(replayRequestId.get())) {
        return Optional.empty();
      }
      ConnectTokenResult result =
          new ConnectTokenResult(
              replayAccountId.orElseThrow(),
              replayTenantId.orElseThrow(),
              gameInstanceId.orElseThrow(),
              realmSlug.orElseThrow(),
              replayConnectScopeId.orElseThrow(),
              connectToken.orElseThrow(),
              jti.orElseThrow(),
              replayRequestId.orElseThrow(),
              issuedAt.orElseThrow(),
              expiresAt.orElseThrow(),
              false);
      return Optional.of(new ConnectTokenReplay(true, result, "", ""));
    }
    String errorCode = stringValue(stored.get("errorCode"));
    String errorMessage = stringValue(stored.get("errorMessage"));
    if (errorCode.isBlank() || errorMessage.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new ConnectTokenReplay(false, null, errorCode, errorMessage));
  }

  @Override
  @Timed(value = "session.connect_token_replay.store")
  public void storeConnectTokenReplay(
      Long tenantId,
      Long accountId,
      String connectScopeId,
      String requestId,
      ConnectTokenReplay replay,
      long expirationMs) {
    Duration ttl = Duration.ofMillis(expirationMs);
    Map<String, String> stored = new HashMap<>();
    stored.put("success", Boolean.toString(replay.success()));
    if (replay.success()) {
      ConnectTokenResult result = requireResult(replay.result(), "connect token replay");
      requireReplayIdMatch(accountId, result.accountId(), "accountId", "connect token replay");
      requireReplayIdMatch(tenantId, result.tenantId(), "tenantId", "connect token replay");
      requireReplayTextMatch(
          connectScopeId, result.connectScopeId(), "connectScopeId", "connect token replay");
      requireReplayTextMatch(requestId, result.requestId(), "requestId", "connect token replay");
      stored.put("accountId", Long.toString(result.accountId()));
      stored.put("tenantId", Long.toString(result.tenantId()));
      stored.put("gameInstanceId", Long.toString(result.gameInstanceId()));
      stored.put("realmSlug", result.realmSlug());
      stored.put("connectScopeId", result.connectScopeId());
      stored.put("connectToken", result.connectToken());
      stored.put("jti", result.jti());
      stored.put("requestId", result.requestId());
      stored.put("issuedAt", result.issuedAt());
      stored.put("expiresAt", result.expiresAt());
    } else {
      stored.put("errorCode", replay.errorCode());
      stored.put("errorMessage", replay.errorMessage());
    }
    redisTemplate
        .opsForValue()
        .set(connectTokenReplayKey(tenantId, accountId, connectScopeId, requestId), stored, ttl);
  }

  private String accountKey(Long accountId, String token) {
    return "session:auth:account:" + accountId + ":" + tokenHash(token);
  }

  private String tenantKey(Long tenantId, String token) {
    return "session:auth:tenant:" + tenantId + ":" + tokenHash(token);
  }

  private String connectTokenReplayKey(
      Long tenantId, Long accountId, String connectScopeId, String requestId) {
    return "session:connect-token:tenant:"
        + tenantId
        + ":account:"
        + accountId
        + ":scope:"
        + tokenHash(connectScopeId)
        + ":request:"
        + tokenHash(requestId);
  }

  private String tokenHash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 digest unavailable", ex);
    }
  }

  private static String stringValue(Object value) {
    return value == null ? "" : value.toString();
  }

  private static Optional<Long> parseRequiredLong(Object value) {
    if (value == null) {
      return Optional.empty();
    }
    try {
      long parsed = Long.parseLong(value.toString());
      return parsed <= 0 ? Optional.empty() : Optional.of(parsed);
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  private static Optional<Boolean> parseRequiredBoolean(Object value) {
    if (value instanceof Boolean booleanValue) {
      return Optional.of(booleanValue);
    }
    String text = stringValue(value).trim();
    if ("true".equalsIgnoreCase(text)) {
      return Optional.of(true);
    }
    if ("false".equalsIgnoreCase(text)) {
      return Optional.of(false);
    }
    return Optional.empty();
  }

  private static Optional<String> parseRequiredText(Object value) {
    String text = stringValue(value);
    return text.isBlank() ? Optional.empty() : Optional.of(text);
  }

  private static <T> T requireResult(T result, String replayType) {
    if (result == null) {
      throw new IllegalArgumentException("Missing result for successful " + replayType);
    }
    return result;
  }

  private static void requireReplayIdMatch(
      long expected, long actual, String fieldName, String replayType) {
    if (expected != actual) {
      throw new IllegalArgumentException(replayType + " payload mismatch for " + fieldName);
    }
  }

  private static void requireReplayTextMatch(
      String expected, String actual, String fieldName, String replayType) {
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException(replayType + " payload mismatch for " + fieldName);
    }
  }
}
