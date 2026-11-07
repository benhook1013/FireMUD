package net.firedevops.firemud.common.account.authority;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closed canonical TENANT_OPERATION variant of Account's runtime entitlement evidence.
 *
 * <p>This value validates content, not source authenticity, transport authentication, freshness at
 * use, capacity commitment, or player admission. Player-bound consumers must reject this variant.
 * It has no account subject, issuer generation, membership, grant, or player-binding fields.
 */
public final class RuntimeTenantOperationEntitlementEvidence {
  public static final String SCHEMA = "account-auth-evidence-bundle/v1";
  public static final String VARIANT = "TENANT_OPERATION";
  public static final int MAX_CANONICAL_BYTES = 64 * 1024;
  private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern DECIMAL = Pattern.compile("0|[1-9][0-9]{0,18}");
  private static final Set<String> ROOT =
      Set.of(
          "schema",
          "variant",
          "bundleVersion",
          "tenantId",
          "requestId",
          "callerWorkload",
          "sourceKind",
          "authorityAvailability",
          "subscriptionStatus",
          "provisioningStatus",
          "gameplayAvailable",
          "allowPublicJoin",
          "allowNewGameplayBindings",
          "allowNewInstanceStarts",
          "quotas",
          "entitlementVersion",
          "tenantBillingSequence",
          "tenantAuthorityGeneration",
          "evaluatedAt",
          "snapshotIdentity",
          "evaluationIdentity",
          "authorityTuple",
          "outboxCheckpoints");
  private static final Set<String> FLAGS =
      Set.of(
          "gameplayAvailable",
          "allowPublicJoin",
          "allowNewGameplayBindings",
          "allowNewInstanceStarts");
  private static final Set<String> STATUSES =
      Set.of("trialing", "active", "past_due", "grace", "suspended", "canceled");
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(8)
                          .maxStringLength(2048)
                          .maxNumberLength(19)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final byte[] canonicalBytes;

  private RuntimeTenantOperationEntitlementEvidence(byte[] canonicalBytes) {
    this.canonicalBytes = canonicalBytes.clone();
  }

  /**
   * Production callers must derive every field inside the same locked Account source transaction.
   */
  public static RuntimeTenantOperationEntitlementEvidence fromOwnerEvaluation(
      Map<String, Object> fields) {
    validate(fields);
    return parseCanonical(canonicalize(fields));
  }

  public static RuntimeTenantOperationEntitlementEvidence parseCanonical(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_CANONICAL_BYTES) {
      throw invalid("Bundle is absent or exceeds 64 KiB");
    }
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException ex) {
      throw invalid("Bundle must be strict UTF-8");
    }
    Map<String, Object> fields = parse(text);
    validate(fields);
    if (!Arrays.equals(bytes, canonicalize(fields))) {
      throw invalid("Bundle bytes must be canonical");
    }
    return new RuntimeTenantOperationEntitlementEvidence(bytes);
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  /** A fresh decoded copy; changing it cannot change this immutable value. */
  public Map<String, Object> fields() {
    return parse(new String(canonicalBytes, StandardCharsets.UTF_8));
  }

  /** Digest of exact canonical JSON, also used by Account's committed-source snapshot preimage. */
  public static String canonicalDigest(Map<String, Object> preimage) {
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalize(preimage)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private static void validate(Map<String, Object> root) {
    exact(root, ROOT, "bundle");
    equal(root.get("schema"), SCHEMA, "schema");
    equal(root.get("variant"), VARIANT, "variant");
    equal(root.get("authorityAvailability"), "AVAILABLE", "authorityAvailability");
    equal(root.get("bundleVersion"), "1", "bundleVersion");
    String tenant = uuid(root.get("tenantId"), "tenantId");
    uuid(root.get("requestId"), "requestId");
    String workload = text(root.get("callerWorkload"), "callerWorkload");
    GrpcPeerIdentity peer =
        GrpcPeerIdentity.parseUri(workload)
            .filter(identity -> identity.uri().equals(workload))
            .orElseThrow(() -> invalid("callerWorkload must be canonical"));
    equal(peer.service(), "world-management-service", "callerWorkload service");
    String kind = text(root.get("sourceKind"), "sourceKind");
    if (!Set.of("PAID_HOSTING", "NON_PAID_DEMO").contains(kind)) {
      throw invalid("Unsupported sourceKind");
    }
    for (String flag : FLAGS) {
      if (!(root.get(flag) instanceof Boolean)) throw invalid(flag + " must be explicit boolean");
    }
    Object provisioning = root.get("provisioningStatus");
    Object status = root.get("subscriptionStatus");
    if (provisioning != null) {
      if (!Set.of("pending", "provisioning").contains(provisioning)
          || status != null
          || !"PAID_HOSTING".equals(kind)
          || FLAGS.stream().anyMatch(flag -> Boolean.TRUE.equals(root.get(flag)))) {
        throw invalid("Provisioning evidence cannot grant hosting authority");
      }
    } else if ("PAID_HOSTING".equals(kind)) {
      if (!(status instanceof String value) || !STATUSES.contains(value)) {
        throw invalid("Paid evidence requires resolved subscriptionStatus");
      }
    } else if (status != null) {
      throw invalid("Demo evidence cannot assert a paid subscriptionStatus");
    }
    for (String field : ROOT) {
      if (!Set.of("subscriptionStatus", "provisioningStatus").contains(field)
          && root.get(field) == null) throw invalid(field + " cannot be null");
    }
    Map<String, Object> quotas = object(root.get("quotas"), "quotas");
    exact(
        quotas,
        Set.of("maxActiveSessions", "maxConcurrentGameInstances", "maxStorageBytes"),
        "quotas");
    quotas.forEach((key, value) -> decimal(value, false, key));
    decimal(root.get("entitlementVersion"), true, "entitlementVersion");
    String billing = decimal(root.get("tenantBillingSequence"), true, "tenantBillingSequence");
    String generation =
        decimal(root.get("tenantAuthorityGeneration"), true, "tenantAuthorityGeneration");
    String evaluated = text(root.get("evaluatedAt"), "evaluatedAt");
    try {
      Instant instant = Instant.parse(evaluated);
      if (!instant.toString().equals(evaluated) || !instant.isAfter(Instant.EPOCH)) {
        throw invalid("evaluatedAt must be a canonical positive UTC instant");
      }
    } catch (java.time.format.DateTimeParseException ex) {
      throw invalid("evaluatedAt must be a canonical UTC instant");
    }
    digest(root.get("snapshotIdentity"), "snapshotIdentity");
    digest(root.get("evaluationIdentity"), "evaluationIdentity");
    Map<String, Object> tuple = object(root.get("authorityTuple"), "authorityTuple");
    exact(tuple, Set.of("tenantAuthorityGeneration", "tenantBillingCutoff"), "authorityTuple");
    Map<String, Object> generations =
        object(tuple.get("tenantAuthorityGeneration"), "tenant generations");
    exact(generations, Set.of(tenant), "tenant generations");
    equal(generations.get(tenant), generation, "tenant generation echo");
    Map<String, Object> cutoffs = object(tuple.get("tenantBillingCutoff"), "tenantBillingCutoff");
    exact(cutoffs, Set.of(tenant), "tenantBillingCutoff");
    Map<String, Object> cutoff = object(cutoffs.get(tenant), "tenant cutoff");
    exact(
        cutoff,
        Set.of(
            "tenantAuthorityGeneration",
            "tenantBillingSequence",
            "outboxStreamKey",
            "outboxSequence"),
        "tenant cutoff");
    equal(cutoff.get("tenantAuthorityGeneration"), generation, "cutoff generation");
    equal(cutoff.get("tenantBillingSequence"), billing, "cutoff billing sequence");
    String stream = TenantAuthorityEventV1Codec.streamKey(UUID.fromString(tenant));
    equal(cutoff.get("outboxStreamKey"), stream, "cutoff stream");
    String sequence = decimal(cutoff.get("outboxSequence"), true, "cutoff sequence");
    if (!(root.get("outboxCheckpoints") instanceof List<?> checkpoints)
        || checkpoints.size() != 1) {
      throw invalid("Exactly one applicable tenant authority checkpoint is required");
    }
    Map<String, Object> checkpoint = object(checkpoints.getFirst(), "checkpoint");
    exact(
        checkpoint,
        Set.of("outboxStreamKey", "outboxSequence", "sourceEventId", "sourceEventDigest"),
        "checkpoint");
    equal(checkpoint.get("outboxStreamKey"), stream, "checkpoint stream");
    equal(checkpoint.get("outboxSequence"), sequence, "checkpoint sequence");
    uuid(checkpoint.get("sourceEventId"), "checkpoint event ID");
    digest(checkpoint.get("sourceEventDigest"), "checkpoint event digest");
  }

  private static Map<String, Object> parse(String text) {
    try {
      return JSON.readValue(text, new TypeReference<>() {});
    } catch (RuntimeException ex) {
      throw invalid("Bundle JSON is malformed");
    }
  }

  private static byte[] canonicalize(Map<String, Object> fields) {
    try {
      byte[] bytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(fields));
      if (bytes.length > MAX_CANONICAL_BYTES) throw invalid("Canonical value exceeds 64 KiB");
      return bytes;
    } catch (IOException ex) {
      throw invalid("Cannot canonicalize bundle");
    }
  }

  private static Map<String, Object> object(Object value, String field) {
    if (!(value instanceof Map<?, ?> supplied)) throw invalid(field + " must be an object");
    Map<String, Object> result = new LinkedHashMap<>();
    supplied.forEach(
        (key, item) -> {
          if (!(key instanceof String name)) throw invalid(field + " contains a non-text key");
          result.put(name, item);
        });
    return result;
  }

  private static void exact(Map<String, Object> value, Set<String> expected, String field) {
    if (value == null || !value.keySet().equals(expected))
      throw invalid(field + " has missing or extra fields");
  }

  private static String text(Object value, String field) {
    if (!(value instanceof String text) || text.isEmpty() || text.length() > 2048) {
      throw invalid(field + " must be bounded text");
    }
    return text;
  }

  private static String uuid(Object value, String field) {
    String text = text(value, field);
    UUID parsed;
    try {
      parsed = UUID.fromString(text);
    } catch (IllegalArgumentException ex) {
      throw invalid(field + " must be a canonical non-nil UUID");
    }
    if (!parsed.toString().equals(text) || parsed.equals(new UUID(0L, 0L))) {
      throw invalid(field + " must be a canonical non-nil UUID");
    }
    return text;
  }

  private static String decimal(Object value, boolean positive, String field) {
    String text = text(value, field);
    if (!DECIMAL.matcher(text).matches()) throw invalid(field + " must be canonical decimal text");
    try {
      if (Long.parseLong(text) < (positive ? 1 : 0)) throw invalid(field + " is outside bounds");
    } catch (NumberFormatException ex) {
      throw invalid(field + " is outside BIGINT bounds");
    }
    return text;
  }

  private static void digest(Object value, String field) {
    if (!(value instanceof String text) || !DIGEST.matcher(text).matches()) {
      throw invalid(field + " must be a canonical SHA-256 digest");
    }
  }

  private static void equal(Object supplied, Object expected, String field) {
    if (!java.util.Objects.equals(supplied, expected)) throw invalid(field + " differs");
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof RuntimeTenantOperationEntitlementEvidence value
            && Arrays.equals(canonicalBytes, value.canonicalBytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(canonicalBytes);
  }

  @Override
  public String toString() {
    return "RuntimeTenantOperationEntitlementEvidence[TENANT_OPERATION, redacted]";
  }
}
