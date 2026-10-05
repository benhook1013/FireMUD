package net.firedevops.firemud.accountservice.service;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceReader.AccountSourceSnapshot;
import net.firedevops.firemud.common.redis.contracts.RedisInvocationContract;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Explicitly initialized, unwired Account-owned store for the current account-generation
 * projection.
 */
public final class RedisAccountGenerationProjectionStore implements AutoCloseable {
  private final AccountAuthoritySourceReader sourceReader;
  private final CoordinationEndpoint coordinationEndpoint;

  private LettuceConnectionFactory connectionFactory;
  private StringRedisTemplate redisTemplate;
  private boolean closed;

  /** Captures explicit role endpoints without creating a bean or opening a Redis connection. */
  public RedisAccountGenerationProjectionStore(
      AccountAuthoritySourceReader reader,
      CoordinationEndpoint coordination,
      CacheRateLimitEndpoint cache) {
    sourceReader = Objects.requireNonNull(reader, "Account source reader is required");
    coordinationEndpoint =
        Objects.requireNonNull(coordination, "Coordination endpoint is required");
    Objects.requireNonNull(cache, "Cache/Rate-Limit endpoint is required");
    validateDistinctEndpoints(coordination, cache);
  }

  /** Initializes this store's private Coordination-only Redis client. */
  public synchronized void init() {
    if (closed) {
      throw new IllegalStateException("Account generation projection store is closed");
    }
    if (connectionFactory != null) {
      return;
    }
    CurrentGenerationProjectionRedisSupport.verifyScriptDigest(
        AccountGenerationProjectionRedisContract.descriptor());
    CurrentGenerationProjectionRedisSupport.InitializedClient client =
        CurrentGenerationProjectionRedisSupport.initialize(
            coordinationEndpoint.host(),
            coordinationEndpoint.port(),
            coordinationEndpoint.principal(),
            coordinationEndpoint.password());
    connectionFactory = client.connectionFactory();
    redisTemplate = client.template();
  }

  /**
   * Reprojects one exact current Account snapshot. It reads source evidence before every attempt,
   * performs at most one registered CAS, verifies exact Redis bytes, then rereads Account before
   * reporting convergence. Source advancement never triggers an implicit retry.
   */
  public synchronized ApplyResult refreshCurrent(UUID accountId) {
    requireInitialized();
    String key = AccountGenerationProjection.keyForAccount(accountId);
    AccountSourceSnapshot source = sourceReader.readCurrent(accountId);
    AccountGenerationProjection candidate = AccountGenerationProjection.fromSource(source);
    if (!key.equals(candidate.key())) {
      return quarantined("ACCOUNT_SOURCE_IDENTITY_MISMATCH", Optional.empty());
    }
    String candidateJson = candidate.toJson();
    byte[] candidateBytes = candidateJson.getBytes(StandardCharsets.UTF_8);

    CurrentGenerationProjectionRedisSupport.StoredValue observed = readStoredValue(key);
    if (observed.bytes() != null && observed.ttlMillis() != -1L) {
      return quarantined("TTL_PRESENT", Optional.empty());
    }

    AccountGenerationProjection stored = null;
    String storedJson = null;
    if (observed.bytes() != null) {
      try {
        storedJson = decodeUtf8(observed.bytes());
        stored = AccountGenerationProjection.parse(storedJson);
      } catch (IOException | IllegalArgumentException malformed) {
        return quarantined("MALFORMED_STORED_PROJECTION", Optional.empty());
      }
      if (!key.equals(stored.key())) {
        return quarantined("STORED_ACCOUNT_ID_MISMATCH", Optional.empty());
      }
      if (isRegression(candidate, stored)) {
        return staleSource(key, storedJson);
      }
      if (candidate.outboxSequenceValue().equals(stored.outboxSequenceValue())
          && !candidate.equals(stored)) {
        return quarantined(
            "SAME_CHECKPOINT_DISAGREEMENT", Optional.of(new ProjectionSnapshot(key, storedJson)));
      }
    }

    String mode;
    String expectedJson;
    if (stored == null) {
      mode = "ABSENT";
      expectedJson = "";
    } else if (candidate.equals(stored)) {
      mode = "VERIFY";
      expectedJson = storedJson;
    } else {
      mode = "PRESENT";
      expectedJson = storedJson;
    }

    String scriptResult =
        executeRegistered(key, mode, expectedJson, "VERIFY".equals(mode) ? "" : candidateJson);
    if ("STALE".equals(scriptResult)) {
      return new ApplyResult(Outcome.STALE, Optional.empty(), Optional.of("REDIS_CAS_STALE"));
    }
    if ("TTL_PRESENT".equals(scriptResult)) {
      return quarantined("TTL_PRESENT", Optional.empty());
    }
    if ("INVALID".equals(scriptResult)) {
      throw new IllegalStateException(
          "Registered Account generation projection script rejected its invocation");
    }
    if (!"APPLIED".equals(scriptResult) && !"REPLAY".equals(scriptResult)) {
      throw new IllegalStateException(
          "Unknown registered Account generation projection script result: "
              + Objects.toString(scriptResult, "null"));
    }

    CurrentGenerationProjectionRedisSupport.StoredValue readback = readStoredValue(key);
    if (readback.bytes() == null) {
      return new ApplyResult(
          Outcome.STALE, Optional.empty(), Optional.of("REDIS_READBACK_MISSING"));
    }
    if (readback.ttlMillis() != -1L) {
      return quarantined("TTL_PRESENT", Optional.empty());
    }
    if (!MessageDigest.isEqual(candidateBytes, readback.bytes())) {
      return changedRedisReadback(key, readback.bytes());
    }

    AccountGenerationProjection current =
        AccountGenerationProjection.fromSource(sourceReader.readCurrent(accountId));
    if (!candidate.equals(current)) {
      return new ApplyResult(
          Outcome.SOURCE_CHANGED,
          Optional.of(new ProjectionSnapshot(key, candidateJson)),
          Optional.of("ACCOUNT_SOURCE_ADVANCED_DURING_REPROJECTION"));
    }
    Outcome convergedOutcome = "APPLIED".equals(scriptResult) ? Outcome.APPLIED : Outcome.REPLAYED;
    return new ApplyResult(
        convergedOutcome,
        Optional.of(new ProjectionSnapshot(key, candidateJson)),
        Optional.empty());
  }

  @Override
  public synchronized void close() {
    closed = true;
    if (connectionFactory != null) {
      connectionFactory.destroy();
      connectionFactory = null;
      redisTemplate = null;
    }
  }

  private ApplyResult changedRedisReadback(String key, byte[] bytes) {
    try {
      String json = decodeUtf8(bytes);
      AccountGenerationProjection projection = AccountGenerationProjection.parse(json);
      if (!key.equals(projection.key())) {
        return quarantined("READBACK_ACCOUNT_ID_MISMATCH", Optional.empty());
      }
      return new ApplyResult(
          Outcome.STALE,
          Optional.of(new ProjectionSnapshot(key, json)),
          Optional.of("REDIS_READBACK_CHANGED"));
    } catch (IOException | IllegalArgumentException malformed) {
      return quarantined("MALFORMED_REDIS_READBACK", Optional.empty());
    }
  }

  private static boolean isRegression(
      AccountGenerationProjection candidate, AccountGenerationProjection stored) {
    return candidate.generationValue().compareTo(stored.generationValue()) < 0
        || candidate.sourceVersionValue().compareTo(stored.sourceVersionValue()) < 0
        || candidate.outboxSequenceValue().compareTo(stored.outboxSequenceValue()) < 0;
  }

  private String executeRegistered(
      String key, String expectedMode, String expectedBytes, String candidateBytes) {
    RedisInvocationContract invocation =
        AccountGenerationProjectionRedisContract.prepareInvocation(
            key, expectedMode, expectedBytes, candidateBytes);
    return CurrentGenerationProjectionRedisSupport.executeRegistered(
        redisTemplate,
        invocation,
        AccountGenerationProjectionRedisContract.descriptor(),
        key,
        expectedMode,
        expectedBytes,
        candidateBytes);
  }

  private CurrentGenerationProjectionRedisSupport.StoredValue readStoredValue(String key) {
    return CurrentGenerationProjectionRedisSupport.readStoredValue(redisTemplate, key);
  }

  private void requireInitialized() {
    if (closed || redisTemplate == null) {
      throw new IllegalStateException(
          "Account generation projection store requires explicit init()");
    }
  }

  private static String decodeUtf8(byte[] value) throws CharacterCodingException {
    return CurrentGenerationProjectionRedisSupport.decodeUtf8(value);
  }

  private static void validateDistinctEndpoints(
      CoordinationEndpoint coordination, CacheRateLimitEndpoint cache) {
    if (normalizeHost(coordination.host()).equals(normalizeHost(cache.host()))
        && coordination.port() == cache.port()) {
      throw new IllegalArgumentException(
          "Coordination and Cache/Rate-Limit Redis endpoints must be distinct");
    }
  }

  private static String normalizeHost(String host) {
    String normalized = host.toLowerCase(Locale.ROOT);
    if (normalized.startsWith("[") && normalized.endsWith("]")) {
      normalized = normalized.substring(1, normalized.length() - 1);
    }
    while (normalized.endsWith(".")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private static ApplyResult staleSource(String key, String storedJson) {
    return new ApplyResult(
        Outcome.STALE_SOURCE,
        Optional.of(new ProjectionSnapshot(key, storedJson)),
        Optional.of("STORED_PROJECTION_AHEAD_OF_ACCOUNT_SOURCE"));
  }

  private static ApplyResult quarantined(String detail, Optional<ProjectionSnapshot> snapshot) {
    return new ApplyResult(Outcome.QUARANTINED, snapshot, Optional.of(detail));
  }

  public enum Outcome {
    APPLIED,
    REPLAYED,
    SOURCE_CHANGED,
    STALE_SOURCE,
    STALE,
    QUARANTINED
  }

  /** Exact byte observation returned for inspection; it grants no authorization. */
  public record ProjectionSnapshot(String key, String json) {
    public ProjectionSnapshot {
      Objects.requireNonNull(key, "projection key is required");
      Objects.requireNonNull(json, "projection JSON is required");
    }
  }

  public record ApplyResult(
      Outcome outcome, Optional<ProjectionSnapshot> snapshot, Optional<String> detail) {
    public ApplyResult {
      Objects.requireNonNull(outcome, "outcome is required");
      snapshot = Objects.requireNonNull(snapshot, "snapshot optional is required");
      detail = Objects.requireNonNull(detail, "detail optional is required");
    }
  }

  /** Explicit Coordination endpoint and ACL identity; the principal is not caller-selectable. */
  public record CoordinationEndpoint(String host, int port, String principal, String password) {
    public CoordinationEndpoint {
      validateHost(host, "Coordination host");
      validatePort(port, "Coordination port");
      if (!AccountGenerationProjectionRedisContract.PRINCIPAL.equals(principal)) {
        throw new IllegalArgumentException("Coordination principal must be account_coord_app");
      }
      if (password == null || password.isBlank()) {
        throw new IllegalArgumentException("Coordination ACL credential is required");
      }
    }

    @Override
    public String toString() {
      return "CoordinationEndpoint[host="
          + host
          + ", port="
          + port
          + ", principal="
          + principal
          + ", password=<redacted>]";
    }
  }

  /** Separate Cache/Rate-Limit endpoint used only to reject role endpoint collisions. */
  public record CacheRateLimitEndpoint(String host, int port) {
    public CacheRateLimitEndpoint {
      validateHost(host, "Cache/Rate-Limit host");
      validatePort(port, "Cache/Rate-Limit port");
    }
  }

  private static void validateHost(String host, String label) {
    if (host == null
        || host.isBlank()
        || !host.equals(host.trim())
        || host.contains("://")
        || host.chars().anyMatch(Character::isWhitespace)
        || host.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(label + " must be an explicit host or IP address");
    }
  }

  private static void validatePort(int port, String label) {
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException(label + " must be between 1 and 65535");
    }
  }
}
