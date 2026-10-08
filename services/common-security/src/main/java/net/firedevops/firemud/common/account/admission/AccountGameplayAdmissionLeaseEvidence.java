package net.firedevops.firemud.common.account.admission;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Immutable, shape-only public-production lease carrier. This value authenticates no issuer,
 * establishes no current source evidence, and never grants admission or proves COMMITTED state.
 *
 * <p>Tuple counters and membership versions inherit the public delegation validator's positive
 * signed-long range. Values above that supported source range are rejected without substitution.
 */
public final class AccountGameplayAdmissionLeaseEvidence {
  public static final String SCHEMA = "account-gameplay-admission-lease-evidence/v1";
  public static final long MAX_DURATION_MILLIS = 15_000L;
  public static final int MAX_EVIDENCE_BYTES = 64 * 1024;
  public static final int MAX_DEPTH = 8;
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final Set<String> FIELDS =
      Set.of(
          "schema",
          "schemaVersion",
          "mode",
          "targetNamespace",
          "callerWorkload",
          "requestId",
          "leaseId",
          "leaseFence",
          "bindingScope",
          "leaseKind",
          "authorityTuple",
          "issuanceFence",
          "membershipBaseline",
          "outboxCheckpoints",
          "tokenIdentityEvidence",
          "evaluatedAt",
          "expiresAt");
  private final Map<String, Object> carrier;
  private final String canonicalJson;
  private final String digest;

  private AccountGameplayAdmissionLeaseEvidence(Map<String, Object> source) {
    Map<String, Object> value = Map.copyOf(object(freeze(source, 1, new ByteBudget())));
    byte[] canonicalBytes = canonicalBytes(value);
    Set<String> expected = new java.util.HashSet<>(FIELDS);
    LeaseKind kind = LeaseKind.valueOf(string(value, "leaseKind"));
    if (kind == LeaseKind.RESUME) {
      expected.add("resumeEpisodeId");
      expected.add("expectedOldBindingGeneration");
    } else if (value.containsKey("expectedOldBindingGeneration")) {
      expected.add("expectedOldBindingGeneration");
    }
    exact(value, expected);
    if (!SCHEMA.equals(value.get("schema"))
        || !"1".equals(value.get("schemaVersion"))
        || !"PUBLIC_PRODUCTION".equals(value.get("mode"))) throw invalid();
    uuid(string(value, "requestId"), true);
    uuid(string(value, "leaseId"), true);
    if (kind == LeaseKind.RESUME) uuid(string(value, "resumeEpisodeId"), true);
    if (value.containsKey("expectedOldBindingGeneration"))
      positive(string(value, "expectedOldBindingGeneration"));
    positive(string(value, "leaseFence"));
    String namespace = string(value, "targetNamespace");
    String peerUri = string(value, "callerWorkload");
    GrpcPeerIdentity peer =
        GrpcPeerIdentity.parseUri(peerUri)
            .orElseThrow(AccountGameplayAdmissionLeaseEvidence::invalid);
    if (!peer.uri().equals(peerUri)
        || !namespace.equals(peer.namespace())
        || !peer.isService("game-session-service")) throw invalid();

    Map<String, Object> scope = object(value.get("bindingScope"));
    exact(
        scope,
        Set.of(
            "accountId",
            "tenantId",
            "realmId",
            "worldSlug",
            "realmSlug",
            "playableStateNamespaceId",
            "playableStateScope",
            "gameInstanceId",
            "characterId",
            "sessionId",
            "bindingGeneration",
            "catalogRevision",
            "pointerVersion",
            "regionId",
            "regionEpoch"));
    for (String field :
        List.of(
            "accountId",
            "tenantId",
            "realmId",
            "playableStateNamespaceId",
            "gameInstanceId",
            "characterId",
            "sessionId",
            "regionId")) uuid(string(scope, field), false);
    for (String field :
        List.of("bindingGeneration", "catalogRevision", "pointerVersion", "regionEpoch"))
      positive(string(scope, field));
    for (String field : List.of("worldSlug", "realmSlug")) text(string(scope, field));
    if (!Set.of("SHARED", "ISOLATED").contains(scope.get("playableStateScope"))) throw invalid();
    String tenant = string(scope, "tenantId");
    String account = string(scope, "accountId");

    Map<String, Object> baseline = object(value.get("membershipBaseline"));
    exact(
        baseline,
        Set.of("membershipLifecycleState", "membershipVersion", "membershipAuthorityGeneration"));
    MembershipLifecycleState.valueOf(string(baseline, "membershipLifecycleState"));
    Map<String, Object> versions = object(baseline.get("membershipVersion"));
    exact(versions, Set.of(tenant));
    nonnegative(string(versions, tenant));
    String membershipGeneration = string(baseline, "membershipAuthorityGeneration");
    positive(membershipGeneration);
    Map<String, Object> tuple = object(value.get("authorityTuple"));
    // Reuse the profile's supported positive signed-long range, including exact values above 2^53.
    try {
      GameSessionAccountDelegationProfile.requirePublicTenantBoundAuthority(
          tenant, tuple, versions);
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException("Malformed public-tenant authority tuple", failure);
    }
    if (!membershipGeneration.equals(
        object(tuple.get("membershipAuthorityGeneration")).get(tenant))) throw invalid();
    String fence = string(value, "issuanceFence");
    positive(fence);

    List<?> checkpoints = list(value.get("outboxCheckpoints"));
    Map<String, String> coordinates = new LinkedHashMap<>();
    byte[] previous = null;
    for (Object entry : checkpoints) {
      Map<String, Object> checkpoint = object(entry);
      exact(checkpoint, Set.of("outboxStreamKey", "outboxSequence"));
      String key = string(checkpoint, "outboxStreamKey");
      text(key);
      String sequence = string(checkpoint, "outboxSequence");
      nonnegative(sequence);
      byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
      if (previous != null && Arrays.compareUnsigned(previous, bytes) >= 0) throw invalid();
      previous = bytes;
      coordinates.put(key, sequence);
    }
    String prefix = "account:auth-authority:v1:";
    String issuerStream = prefix + "issuer/" + GameSessionAccountDelegationProfile.ISSUER;
    String accountStream = prefix + "account/" + account;
    String tenantStream = prefix + "tenant/" + tenant;
    String membershipStream = prefix + "membership/" + account + "/" + tenant;
    exact(coordinates, Set.of(issuerStream, accountStream, tenantStream, membershipStream));
    for (String stream : List.of(tenantStream, membershipStream)) positive(coordinates.get(stream));
    if (tuple.containsKey("accountSecurityCutoff")) {
      cutoff(object(tuple.get("accountSecurityCutoff")), accountStream, coordinates);
    }
    if (tuple.containsKey("tenantBillingCutoff")) {
      cutoff(
          object(object(tuple.get("tenantBillingCutoff")).get(tenant)), tenantStream, coordinates);
    }

    Map<String, Object> token = object(value.get("tokenIdentityEvidence"));
    exact(
        token,
        Set.of(
            "accountId",
            "operationId",
            "issuanceRequestId",
            "tokenJti",
            "tokenSHA256",
            "tokenGeneration",
            "issuanceFence",
            "tokenIdentityFence",
            "tokenProfile",
            "issuedAt",
            "notBefore",
            "expiresAt"));
    uuid(string(token, "accountId"), false);
    for (String field : List.of("operationId", "issuanceRequestId", "tokenJti"))
      uuid(string(token, field), true);
    if (!account.equals(token.get("accountId"))
        || !fence.equals(token.get("issuanceFence"))
        || !string(token, "tokenSHA256").matches("[0-9a-f]{64}")
        || !GameSessionAccountDelegationProfile.PROFILE.equals(token.get("tokenProfile")))
      throw invalid();
    for (String field : List.of("tokenGeneration", "issuanceFence", "tokenIdentityFence"))
      positive(string(token, field));
    BigInteger issued = timestamp(token, "issuedAt").multiply(BigInteger.valueOf(1000L));
    BigInteger notBefore = timestamp(token, "notBefore").multiply(BigInteger.valueOf(1000L));
    BigInteger tokenExpiry = timestamp(token, "expiresAt").multiply(BigInteger.valueOf(1000L));
    BigInteger evaluated = timestamp(value, "evaluatedAt");
    BigInteger expiry = timestamp(value, "expiresAt");
    BigInteger duration = expiry.subtract(evaluated);
    if (issued.compareTo(evaluated) > 0
        || notBefore.compareTo(issued) > 0
        || expiry.compareTo(tokenExpiry) > 0
        || duration.signum() <= 0
        || duration.compareTo(BigInteger.valueOf(MAX_DURATION_MILLIS)) > 0) throw invalid();
    carrier = value;
    try {
      canonicalJson = new String(canonicalBytes, StandardCharsets.UTF_8);
      digest =
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalArgumentException("Cannot canonicalize lease evidence", failure);
    }
  }

  public enum LeaseKind {
    NEW_BINDING,
    RESUME
  }

  public enum MembershipLifecycleState {
    ACTIVE,
    INACTIVE
  }

  /** Validates and defensively freezes original carriers without establishing their provenance. */
  public static AccountGameplayAdmissionLeaseEvidence fromCarrier(Map<String, Object> carrier) {
    if (carrier == null) throw invalid();
    return new AccountGameplayAdmissionLeaseEvidence(carrier);
  }

  /** Accepts only the exact canonical serialization; duplicate and unknown fields fail closed. */
  public static AccountGameplayAdmissionLeaseEvidence parseCanonical(String json) {
    requireByteBound(json);
    try {
      // Inspect depth before allocating a nested object tree; duplicate detection applies here too.
      try (JsonParser parser = JSON.createParser(json)) {
        int depth = 0;
        JsonToken token;
        while ((token = parser.nextToken()) != null) {
          if ((token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY)
              && ++depth > MAX_DEPTH) throw invalid();
          if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--;
        }
      }
      AccountGameplayAdmissionLeaseEvidence result =
          fromCarrier(object(JSON.readValue(json, Object.class)));
      if (!result.canonicalJson.equals(json)) throw invalid();
      return result;
    } catch (JacksonException failure) {
      throw new IllegalArgumentException("Malformed lease evidence", failure);
    }
  }

  public Map<String, Object> carrier() {
    return carrier;
  }

  public String canonicalJson() {
    return canonicalJson;
  }

  public String sha256() {
    return digest;
  }

  public BigInteger leaseFence() {
    return positive(string(carrier, "leaseFence"));
  }

  public LeaseKind leaseKind() {
    return LeaseKind.valueOf(string(carrier, "leaseKind"));
  }

  /** Exact original immutable carriers and their canonical digest define retry identity. */
  public boolean hasSameIdentity(AccountGameplayAdmissionLeaseEvidence other) {
    return other != null && carrier.equals(other.carrier) && digest.equals(other.digest);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof AccountGameplayAdmissionLeaseEvidence evidence
        && hasSameIdentity(evidence);
  }

  @Override
  public int hashCode() {
    return carrier.hashCode();
  }

  @Override
  public String toString() {
    return "AccountGameplayAdmissionLeaseEvidence[redacted]";
  }

  private static void cutoff(
      Map<String, Object> cutoff, String stream, Map<String, String> checkpoints) {
    if (!stream.equals(cutoff.get("outboxStreamKey"))
        || nonnegative(checkpoints.get(stream))
                .compareTo(positive(string(cutoff, "outboxSequence")))
            < 0) throw invalid();
  }

  private static BigInteger timestamp(Map<String, Object> object, String field) {
    BigInteger value = positive(string(object, field));
    if (value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) throw invalid();
    return value;
  }

  private static BigInteger positive(String value) {
    BigInteger integer = nonnegative(value);
    if (integer.signum() <= 0) throw invalid();
    return integer;
  }

  private static BigInteger nonnegative(String value) {
    if (value == null || !value.matches("0|[1-9][0-9]*")) throw invalid();
    return new BigInteger(value);
  }

  private static void uuid(String value, boolean highEntropy) {
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (RuntimeException failure) {
      throw invalid();
    }
    if (!parsed.toString().equals(value)
        || parsed.equals(new UUID(0, 0))
        || (highEntropy && (parsed.version() != 4 || parsed.variant() != 2))) throw invalid();
  }

  private static void text(String value) {
    if (value.isBlank()
        || value.length() > 2048
        || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
  }

  private static String string(Map<String, ?> object, String field) {
    if (!(object.get(field) instanceof String value)) throw invalid();
    return value;
  }

  private static void exact(Map<String, ?> object, Set<String> fields) {
    if (!object.keySet().equals(fields)) throw invalid();
  }

  private static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?> map)) throw invalid();
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach(
        (key, child) -> {
          if (!(key instanceof String text)) throw invalid();
          result.put(text, child);
        });
    return result;
  }

  private static List<?> list(Object value) {
    if (!(value instanceof List<?> entries)) throw invalid();
    return entries;
  }

  private static Object freeze(Object value, int depth, ByteBudget budget) {
    if (depth > MAX_DEPTH) throw invalid();
    budget.consume(1);
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) throw invalid();
        budget.consume(requireByteBound(key) + 4);
        result.put(key, freeze(entry.getValue(), depth + 1, budget));
      }
      return java.util.Collections.unmodifiableMap(result);
    }
    if (value instanceof List<?> values) {
      List<Object> frozen = new ArrayList<>();
      for (Object child : values) frozen.add(freeze(child, depth + 1, budget));
      return List.copyOf(frozen);
    }
    if (!(value instanceof String text)) throw invalid();
    budget.consume(requireByteBound(text) + 2);
    return value;
  }

  private static int requireByteBound(String value) {
    if (value == null || value.length() > MAX_EVIDENCE_BYTES) throw invalid();
    int size = value.getBytes(StandardCharsets.UTF_8).length;
    if (size > MAX_EVIDENCE_BYTES) throw invalid();
    return size;
  }

  private static byte[] canonicalBytes(Map<String, Object> value) {
    try {
      String serialized = JSON.writeValueAsString(value);
      requireByteBound(serialized);
      byte[] bytes = Rfc8785CanonicalJson.canonicalizeUtf8(serialized);
      if (bytes.length > MAX_EVIDENCE_BYTES) throw invalid();
      return bytes;
    } catch (IOException | JacksonException failure) {
      throw new IllegalArgumentException("Cannot canonicalize lease evidence", failure);
    }
  }

  private static final class ByteBudget {
    private int remaining = MAX_EVIDENCE_BYTES;

    private void consume(int count) {
      remaining -= count;
      if (remaining < 0) throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Malformed public-production admission lease evidence");
  }
}
