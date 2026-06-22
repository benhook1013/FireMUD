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
import net.firedevops.firemud.accountservice.dto.PublicProductionMembershipResult;
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
  @Timed(value = "session.get")
  public Long getAccountId(Long tenantId, String token) {
    Object value = redisTemplate.opsForValue().get(tenantKey(tenantId, token));
    return value != null ? Long.valueOf(value.toString()) : null;
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
    boolean success = Boolean.parseBoolean(stringValue(stored.get("success")));
    if (success) {
      Optional<Long> replayAccountId = parseRequiredLong(stored.get("accountId"));
      Optional<Long> replayTenantId = parseRequiredLong(stored.get("tenantId"));
      Optional<Long> gameInstanceId = parseRequiredLong(stored.get("gameInstanceId"));
      if (replayAccountId.isEmpty() || replayTenantId.isEmpty() || gameInstanceId.isEmpty()) {
        return Optional.empty();
      }
      ConnectTokenResult result =
          new ConnectTokenResult(
              replayAccountId.orElseThrow(),
              replayTenantId.orElseThrow(),
              gameInstanceId.orElseThrow(),
              stringValue(stored.get("realmSlug")),
              stringValue(stored.get("connectScopeId")),
              stringValue(stored.get("connectToken")),
              stringValue(stored.get("jti")),
              stringValue(stored.get("requestId")),
              stringValue(stored.get("issuedAt")),
              stringValue(stored.get("expiresAt")),
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

  @Override
  @Timed(value = "session.public_production_membership_replay.get")
  public Optional<PublicProductionMembershipReplay> getPublicProductionMembershipReplay(
      Long tenantId, Long accountId, String worldSlug, String realmSlug, String requestId) {
    Object value =
        redisTemplate
            .opsForValue()
            .get(
                publicProductionMembershipReplayKey(
                    tenantId, accountId, worldSlug, realmSlug, requestId));
    if (!(value instanceof Map<?, ?> stored)) {
      return Optional.empty();
    }
    boolean success = Boolean.parseBoolean(stringValue(stored.get("success")));
    if (success) {
      Optional<Long> replayAccountId = parseRequiredLong(stored.get("accountId"));
      Optional<Long> replayTenantId = parseRequiredLong(stored.get("tenantId"));
      Optional<Long> membershipVersion = parseRequiredLong(stored.get("membershipVersion"));
      if (replayAccountId.isEmpty() || replayTenantId.isEmpty() || membershipVersion.isEmpty()) {
        return Optional.empty();
      }
      PublicProductionMembershipResult result =
          new PublicProductionMembershipResult(
              replayAccountId.orElseThrow(),
              replayTenantId.orElseThrow(),
              stringValue(stored.get("worldSlug")),
              stringValue(stored.get("realmSlug")),
              membershipVersion.orElseThrow(),
              Boolean.parseBoolean(stringValue(stored.get("created"))),
              stringValue(stored.get("requestId")),
              stringValue(stored.get("evaluatedAt")),
              false);
      return Optional.of(new PublicProductionMembershipReplay(true, result, "", ""));
    }
    String errorCode = stringValue(stored.get("errorCode"));
    String errorMessage = stringValue(stored.get("errorMessage"));
    if (errorCode.isBlank() || errorMessage.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new PublicProductionMembershipReplay(false, null, errorCode, errorMessage));
  }

  @Override
  @Timed(value = "session.public_production_membership_replay.store")
  public void storePublicProductionMembershipReplay(
      Long tenantId,
      Long accountId,
      String worldSlug,
      String realmSlug,
      String requestId,
      PublicProductionMembershipReplay replay,
      long expirationMs) {
    Duration ttl = Duration.ofMillis(expirationMs);
    Map<String, String> stored = new HashMap<>();
    stored.put("success", Boolean.toString(replay.success()));
    if (replay.success()) {
      PublicProductionMembershipResult result =
          requireResult(replay.result(), "public production membership replay");
      stored.put("accountId", Long.toString(result.accountId()));
      stored.put("tenantId", Long.toString(result.tenantId()));
      stored.put("worldSlug", result.worldSlug());
      stored.put("realmSlug", result.realmSlug());
      stored.put("membershipVersion", Long.toString(result.membershipVersion()));
      stored.put("created", Boolean.toString(result.created()));
      stored.put("requestId", result.requestId());
      stored.put("evaluatedAt", result.evaluatedAt());
    } else {
      stored.put("errorCode", replay.errorCode());
      stored.put("errorMessage", replay.errorMessage());
    }
    redisTemplate
        .opsForValue()
        .set(
            publicProductionMembershipReplayKey(
                tenantId, accountId, worldSlug, realmSlug, requestId),
            stored,
            ttl);
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

  private String publicProductionMembershipReplayKey(
      Long tenantId, Long accountId, String worldSlug, String realmSlug, String requestId) {
    return "session:public-production-membership:tenant:"
        + tenantId
        + ":account:"
        + accountId
        + ":world:"
        + tokenHash(worldSlug)
        + ":realm:"
        + tokenHash(realmSlug)
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
      return Optional.of(Long.parseLong(value.toString()));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  private static <T> T requireResult(T result, String replayType) {
    if (result == null) {
      throw new IllegalArgumentException("Missing result for successful " + replayType);
    }
    return result;
  }
}
