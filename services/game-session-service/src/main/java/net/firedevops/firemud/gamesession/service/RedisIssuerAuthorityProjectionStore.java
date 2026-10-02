package net.firedevops.firemud.gamesession.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.redis.contracts.RedisInvocationContract;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient.SourceReadback;
import net.firedevops.firemud.gamesession.service.IssuerAuthorityProjectionTransitions.Decision;
import net.firedevops.firemud.gamesession.service.IssuerAuthorityProjectionTransitions.Mutation;
import net.firedevops.firemud.gamesession.service.IssuerAuthorityProjectionTransitions.NoOp;
import net.firedevops.firemud.gamesession.service.IssuerAuthorityProjectionTransitions.Quarantine;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Explicitly owned, unwired Coordination Redis store for the derived issuer projection. */
public final class RedisIssuerAuthorityProjectionStore implements AutoCloseable {
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};
  private static final byte[] CAS_SCRIPT_BYTES = scriptBytes();
  private static final String CAS_SCRIPT_SHA1 = scriptSha1(CAS_SCRIPT_BYTES);
  private static final byte[] CAS_SCRIPT_SHA256 = scriptSha256(CAS_SCRIPT_BYTES);
  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(3);

  private final String expectedWorkloadNamespace;
  private final CoordinationEndpoint coordinationEndpoint;
  private final CacheRateLimitEndpoint cacheRateLimitEndpoint;

  private LettuceConnectionFactory connectionFactory;
  private StringRedisTemplate redisTemplate;
  private boolean closed;

  /**
   * Captures explicit role endpoints and credentials. This constructor creates no Spring bean and
   * opens no connection; callers must invoke {@link #init()} explicitly.
   */
  public RedisIssuerAuthorityProjectionStore(
      String expectedWorkloadNamespace,
      CoordinationEndpoint coordinationEndpoint,
      CacheRateLimitEndpoint cacheRateLimitEndpoint) {
    if (!GrpcPeerIdentity.isValidNamespace(expectedWorkloadNamespace)) {
      throw new IllegalArgumentException("A valid expected workload namespace is required");
    }
    this.expectedWorkloadNamespace = expectedWorkloadNamespace;
    this.coordinationEndpoint =
        Objects.requireNonNull(coordinationEndpoint, "Coordination endpoint is required");
    this.cacheRateLimitEndpoint =
        Objects.requireNonNull(cacheRateLimitEndpoint, "Cache/Rate-Limit endpoint is required");
    validateDistinctEndpoints(coordinationEndpoint, cacheRateLimitEndpoint);
  }

  /** Initializes this store's private Coordination-only Lettuce client and String template. */
  public synchronized void init() {
    if (closed) {
      throw new IllegalStateException("issuer projection store is closed");
    }
    if (connectionFactory != null) {
      return;
    }
    verifyScriptDigest();

    RedisStandaloneConfiguration redisConfiguration =
        new RedisStandaloneConfiguration(coordinationEndpoint.host(), coordinationEndpoint.port());
    redisConfiguration.setUsername(coordinationEndpoint.principal());
    redisConfiguration.setPassword(RedisPassword.of(coordinationEndpoint.password()));
    LettuceClientConfiguration clientConfiguration =
        LettuceClientConfiguration.builder().commandTimeout(COMMAND_TIMEOUT).build();

    LettuceConnectionFactory newConnectionFactory =
        new LettuceConnectionFactory(redisConfiguration, clientConfiguration);
    newConnectionFactory.afterPropertiesSet();
    StringRedisTemplate newTemplate = new StringRedisTemplate(newConnectionFactory);
    newTemplate.afterPropertiesSet();
    connectionFactory = newConnectionFactory;
    redisTemplate = newTemplate;
  }

  /**
   * Applies one privately verified Account readback. This path performs an exact pre-read, at most
   * one registered owner-script call, and an exact post-script readback after a positive script
   * outcome; it never retries after a stale result.
   */
  public synchronized ApplyResult apply(SourceReadback readback, String appliedAt) {
    requireInitialized();
    if (readback == null) {
      return quarantined("MALFORMED_ACCOUNT_READBACK", Optional.empty());
    }
    if (!expectedWorkloadNamespace.equals(readback.targetNamespace())) {
      return quarantined("ACCOUNT_NAMESPACE_MISMATCH", Optional.empty());
    }
    if (readback.sourceSnapshot() == null || readback.sourceSnapshot().issuerId() == null) {
      return quarantined("MALFORMED_ACCOUNT_READBACK", Optional.empty());
    }

    final String key;
    try {
      key =
          IssuerAuthorityProjectionRedisContract.keyForIssuer(readback.sourceSnapshot().issuerId());
    } catch (IllegalArgumentException malformed) {
      return quarantined("MALFORMED_ACCOUNT_READBACK", Optional.empty());
    }

    StoredValue observed = readStoredValue(key);
    if (observed.bytes() != null && observed.ttlMillis() != -1L) {
      return quarantined("TTL_PRESENT", Optional.empty());
    }

    final String observedJson;
    final Decision decision;
    if (observed.bytes() == null) {
      observedJson = null;
      decision = IssuerAuthorityProjectionTransitions.bootstrap(readback, appliedAt);
    } else {
      try {
        observedJson = decodeUtf8(observed.bytes());
        Map<String, Object> observedProjection = parseProjection(observedJson);
        decision =
            IssuerAuthorityProjectionTransitions.decide(observedProjection, readback, appliedAt);
      } catch (IOException | IllegalArgumentException malformed) {
        return quarantined("MALFORMED_STORED_JSON", Optional.empty());
      }
    }

    if (decision instanceof Quarantine quarantine) {
      return quarantined(quarantine.reason().name(), Optional.of(decision));
    }
    if (decision instanceof NoOp) {
      if (observedJson == null) {
        return quarantined("NO_OP_WITHOUT_EXISTING_PROJECTION", Optional.of(decision));
      }
      String result = executeRegistered(key, "VERIFY", observedJson, "");
      if ("REPLAY".equals(result)) {
        return exactReadback(key, observedJson, Outcome.NO_OP, decision);
      }
      return mutationOutcome(result, key, observedJson, decision);
    }
    if (!(decision instanceof Mutation mutation)) {
      return quarantined("UNSUPPORTED_TRANSITION_DECISION", Optional.of(decision));
    }

    String expectedMode = observedJson == null ? "ABSENT" : "PRESENT";
    if (mutation.expectedProjection().isPresent() != (observedJson != null)) {
      return quarantined("TRANSITION_EXPECTATION_MISMATCH", Optional.of(decision));
    }
    String candidateJson;
    try {
      candidateJson = serializeProjection(mutation.nextProjection());
    } catch (IOException | IllegalArgumentException malformed) {
      return quarantined("MALFORMED_CANDIDATE", Optional.of(decision));
    }
    String result =
        executeRegistered(
            key, expectedMode, observedJson == null ? "" : observedJson, candidateJson);
    return mutationOutcome(result, key, candidateJson, decision);
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

  private ApplyResult mutationOutcome(
      String result, String key, String candidateJson, Decision decision) {
    if ("APPLIED".equals(result)) {
      return exactReadback(key, candidateJson, Outcome.APPLIED, decision);
    }
    if ("REPLAY".equals(result)) {
      return exactReadback(key, candidateJson, Outcome.REPLAYED, decision);
    }
    if ("STALE".equals(result)) {
      return new ApplyResult(
          Outcome.STALE, Optional.empty(), Optional.empty(), Optional.of(decision));
    }
    if ("TTL_PRESENT".equals(result)) {
      return quarantined("TTL_PRESENT", Optional.of(decision));
    }
    if ("INVALID".equals(result)) {
      return quarantined("REGISTERED_SCRIPT_REJECTED_INVOCATION", Optional.of(decision));
    }
    return quarantined(
        "UNKNOWN_REGISTERED_SCRIPT_RESULT:" + Objects.toString(result, "null"),
        Optional.of(decision));
  }

  private ApplyResult exactReadback(
      String key, String expectedJson, Outcome outcome, Decision decision) {
    StoredValue readback = readStoredValue(key);
    if (readback.bytes() == null) {
      return quarantined("POST_SCRIPT_READBACK_MISSING", Optional.of(decision));
    }
    if (readback.ttlMillis() != -1L) {
      return quarantined("TTL_PRESENT", Optional.of(decision));
    }

    byte[] expectedBytes = expectedJson.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expectedBytes, readback.bytes())) {
      return quarantined("POST_SCRIPT_READBACK_MISMATCH", Optional.of(decision));
    }

    try {
      return new ApplyResult(
          outcome,
          Optional.of(new ProjectionSnapshot(key, decodeUtf8(readback.bytes()))),
          Optional.empty(),
          Optional.of(decision));
    } catch (CharacterCodingException malformedUtf8) {
      return quarantined("POST_SCRIPT_READBACK_INVALID_UTF8", Optional.of(decision));
    }
  }

  private String executeRegistered(
      String key, String expectedMode, String expectedBytes, String candidateBytes) {
    RedisInvocationContract invocation =
        IssuerAuthorityProjectionRedisContract.prepareInvocation(
            key, expectedMode, expectedBytes, candidateBytes);
    RedisScriptDescriptor descriptor = invocation.descriptor();
    if (descriptor != IssuerAuthorityProjectionRedisContract.descriptor()
        || !descriptor.scriptId().equals(IssuerAuthorityProjectionRedisContract.SCRIPT_ID)
        || descriptor.role() != RedisScriptDescriptor.RedisRole.COORDINATION
        || !IssuerAuthorityProjectionRedisContract.PRINCIPAL.equals(descriptor.principal())) {
      throw new IllegalStateException(
          "issuer projection invocation is not its registered owner contract");
    }
    byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
    byte[] expectedModeBytes = expectedMode.getBytes(StandardCharsets.US_ASCII);
    byte[] expectedBytesUtf8 = expectedBytes.getBytes(StandardCharsets.UTF_8);
    byte[] candidateBytesUtf8 = candidateBytes.getBytes(StandardCharsets.UTF_8);
    return redisTemplate.execute(
        (RedisCallback<String>)
            connection -> {
              String loadedSha = connection.scriptingCommands().scriptLoad(CAS_SCRIPT_BYTES);
              if (!CAS_SCRIPT_SHA1.equals(loadedSha)) {
                throw new IllegalStateException(
                    "Redis loaded a different issuer authority projection script");
              }
              Object result =
                  connection
                      .scriptingCommands()
                      .evalSha(
                          loadedSha,
                          ReturnType.VALUE,
                          1,
                          keyBytes,
                          expectedModeBytes,
                          expectedBytesUtf8,
                          candidateBytesUtf8);
              return decodeScriptResult(result);
            });
  }

  private StoredValue readStoredValue(String key) {
    byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
    return redisTemplate.execute(
        (RedisCallback<StoredValue>)
            connection -> {
              byte[] value = connection.stringCommands().get(keyBytes);
              long ttlMillis = value == null ? -2L : connection.keyCommands().pTtl(keyBytes);
              return new StoredValue(value, ttlMillis);
            });
  }

  private static Map<String, Object> parseProjection(String json) throws IOException {
    JsonNode root = JSON.readTree(json);
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("stored issuer projection must be a JSON object");
    }
    return JSON.convertValue(root, OBJECT_MAP);
  }

  private static String serializeProjection(Map<String, Object> projection) throws IOException {
    return JSON.writeValueAsString(projection);
  }

  private static String decodeUtf8(byte[] value) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value))
        .toString();
  }

  private static String decodeScriptResult(Object result) {
    if (result instanceof byte[] bytes) {
      try {
        return StandardCharsets.US_ASCII
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
      } catch (CharacterCodingException malformed) {
        throw new IllegalStateException(
            "Registered issuer projection script returned non-ASCII", malformed);
      }
    }
    if (result instanceof String text) {
      return text;
    }
    if (result == null) {
      return null;
    }
    throw new IllegalStateException(
        "Registered issuer projection script returned an unsupported result type");
  }

  private void requireInitialized() {
    if (closed || redisTemplate == null) {
      throw new IllegalStateException("issuer projection store requires explicit init()");
    }
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

  private static byte[] scriptBytes() {
    try (var input =
        new ClassPathResource(IssuerAuthorityProjectionRedisContract.RESOURCE_PATH)
            .getInputStream()) {
      return input.readAllBytes();
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static String scriptSha1(byte[] scriptBytes) {
    try {
      return java.util.HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-1").digest(scriptBytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static byte[] scriptSha256(byte[] scriptBytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(scriptBytes);
    } catch (NoSuchAlgorithmException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static void verifyScriptDigest() {
    String actual = java.util.HexFormat.of().formatHex(CAS_SCRIPT_SHA256);
    if (!IssuerAuthorityProjectionRedisContract.descriptor().sha256().equals(actual)) {
      throw new IllegalStateException(
          "issuer projection Lua resource digest differs from its registration");
    }
  }

  private static ApplyResult quarantined(String detail, Optional<Decision> decision) {
    return new ApplyResult(Outcome.QUARANTINED, Optional.empty(), Optional.of(detail), decision);
  }

  private record StoredValue(byte[] bytes, long ttlMillis) {}

  public enum Outcome {
    APPLIED,
    NO_OP,
    QUARANTINED,
    REPLAYED,
    STALE
  }

  /**
   * Immutable JSON snapshot returned for observation; it grants no admission or rebind authority.
   */
  public record ProjectionSnapshot(String key, String json) {
    public ProjectionSnapshot {
      Objects.requireNonNull(key, "projection key is required");
      Objects.requireNonNull(json, "projection JSON is required");
    }
  }

  public record ApplyResult(
      Outcome outcome,
      Optional<ProjectionSnapshot> snapshot,
      Optional<String> detail,
      Optional<Decision> transitionDecision) {
    public ApplyResult {
      Objects.requireNonNull(outcome, "outcome is required");
      snapshot = Objects.requireNonNull(snapshot, "snapshot optional is required");
      detail = Objects.requireNonNull(detail, "detail optional is required");
      transitionDecision =
          Objects.requireNonNull(transitionDecision, "transition decision optional is required");
    }
  }

  /** Explicit Coordination endpoint and ACL identity; the principal is not caller-selectable. */
  public record CoordinationEndpoint(String host, int port, String principal, String password) {
    public CoordinationEndpoint {
      validateHost(host, "Coordination host");
      validatePort(port, "Coordination port");
      if (!IssuerAuthorityProjectionRedisContract.PRINCIPAL.equals(principal)) {
        throw new IllegalArgumentException("Coordination principal must be gamesession_coord_app");
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

  /** Required separate Cache/Rate-Limit endpoint used only for collision validation. */
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
