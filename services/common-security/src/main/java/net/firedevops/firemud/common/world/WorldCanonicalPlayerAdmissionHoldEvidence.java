package net.firedevops.firemud.common.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Distinct World-owned player-admission lifecycle hold. This is neither a first-pointer hold nor
 * Account authorization, Game Session's binding decision, or gameplay admission permission.
 *
 * <p>The complete original lease and exact World owner evidence remain immutable. Its deadline is
 * diagnostic only. Release requires independently authenticated exact Account terminal evidence AND
 * Game Session installation/cleanup evidence; those producers do not exist in this slice.
 */
public record WorldCanonicalPlayerAdmissionHoldEvidence(
    UUID holdId,
    UUID holdFence,
    Request request,
    WorldCanonicalInstanceLifecycleEvidence worldEvidence) {
  public static final String SCHEMA = "world-canonical-player-admission-hold-evidence/v1";
  public static final int MAX_EVIDENCE_BYTES = 1024 * 1024;
  public static final int MAX_LIFECYCLE_BYTES = 512 * 1024;
  private static final Set<String> FIELDS =
      Set.of(
          "schema",
          "schemaVersion",
          "holdId",
          "holdFence",
          "request",
          "worldLifecycleEvidenceBase64");
  private static final Set<String> REQUEST_FIELDS =
      Set.of(
          "accountLeaseJson", "accountLeaseSha256", "expectedLifecycleEpoch", "expectedRowVersion");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public WorldCanonicalPlayerAdmissionHoldEvidence {
    requireOpaque(holdId);
    requireOpaque(holdFence);
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(worldEvidence, "worldEvidence");
    request.requireExactActiveWorld(worldEvidence);
  }

  /** Closed outer carrier; nested original Account and World bytes are preserved exactly. */
  public byte[] canonicalBytes() {
    byte[] lifecycle = worldEvidence.canonicalBytes();
    requireBound(lifecycle, MAX_LIFECYCLE_BYTES);
    Map<String, Object> value =
        Map.of(
            "schema",
            SCHEMA,
            "schemaVersion",
            "1",
            "holdId",
            holdId.toString(),
            "holdFence",
            holdFence.toString(),
            "request",
            Map.of(
                "accountLeaseJson",
                request.lease().canonicalJson(),
                "accountLeaseSha256",
                request.lease().sha256(),
                "expectedLifecycleEpoch",
                Long.toString(request.expectedLifecycleEpoch()),
                "expectedRowVersion",
                Long.toString(request.expectedRowVersion())),
            "worldLifecycleEvidenceBase64",
            Base64.getEncoder().encodeToString(lifecycle));
    try {
      byte[] result = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      requireBound(result, MAX_EVIDENCE_BYTES);
      return result;
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot encode canonical World player hold", failure);
    }
  }

  /** Separate digest of the complete canonical outer bytes; never a self-referential field. */
  public String sha256() {
    return digest(canonicalBytes());
  }

  /** Shape and exact binding only: decoding does not authenticate World or authorize admission. */
  public static WorldCanonicalPlayerAdmissionHoldEvidence fromCanonical(
      byte[] bytes, String sha256) {
    requireBound(bytes, MAX_EVIDENCE_BYTES);
    if (sha256 == null || !sha256.matches("[0-9a-f]{64}") || !digest(bytes).equals(sha256)) {
      throw new IllegalArgumentException("World hold evidence SHA256 differs from original bytes");
    }
    return parseCanonical(bytes);
  }

  public static WorldCanonicalPlayerAdmissionHoldEvidence parseCanonical(byte[] bytes) {
    requireBound(bytes, MAX_EVIDENCE_BYTES);
    String json = strictUtf8(bytes);
    try {
      try (JsonParser parser = JSON.createParser(json)) {
        int depth = 0;
        JsonToken token;
        while ((token = parser.nextToken()) != null) {
          if ((token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) && ++depth > 4)
            throw new IllegalArgumentException("World hold evidence exceeds closed nesting bound");
          if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--;
        }
      }
      JsonNode root = JSON.readTree(json);
      exact(root, FIELDS);
      if (!SCHEMA.equals(text(root, "schema")) || !"1".equals(text(root, "schemaVersion"))) {
        throw new IllegalArgumentException("Unsupported World player hold evidence schema");
      }
      JsonNode bound = root.get("request");
      exact(bound, REQUEST_FIELDS);
      var lease =
          AccountGameplayAdmissionLeaseEvidence.parseCanonical(text(bound, "accountLeaseJson"));
      if (!lease.sha256().equals(text(bound, "accountLeaseSha256"))) {
        throw new IllegalArgumentException(
            "World hold request substituted its original Account lease digest");
      }
      var request =
          new Request(
              lease,
              decimal(text(bound, "expectedLifecycleEpoch"), true),
              decimal(text(bound, "expectedRowVersion"), false));
      String encoded = text(root, "worldLifecycleEvidenceBase64");
      byte[] lifecycle = Base64.getDecoder().decode(encoded);
      requireBound(lifecycle, MAX_LIFECYCLE_BYTES);
      if (!Base64.getEncoder().encodeToString(lifecycle).equals(encoded)) {
        throw new IllegalArgumentException("World lifecycle evidence base64 is not canonical");
      }
      var result =
          new WorldCanonicalPlayerAdmissionHoldEvidence(
              UUID.fromString(text(root, "holdId")),
              UUID.fromString(text(root, "holdFence")),
              request,
              WorldCanonicalInstanceLifecycleEvidence.fromStored(lifecycle));
      if (!Arrays.equals(bytes, result.canonicalBytes())) {
        throw new IllegalArgumentException("World hold evidence bytes are not canonical");
      }
      return result;
    } catch (tools.jackson.core.JacksonException failure) {
      throw new IllegalArgumentException("Malformed World player hold evidence", failure);
    }
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA256 is unavailable", failure);
    }
  }

  private static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (java.nio.charset.CharacterCodingException failure) {
      throw new IllegalArgumentException("World hold evidence must be strict UTF-8", failure);
    }
  }

  private static void requireBound(byte[] bytes, int maximum) {
    if (bytes == null || bytes.length == 0 || bytes.length > maximum) {
      throw new IllegalArgumentException(
          "World hold evidence exceeds its byte bound or is missing");
    }
  }

  private static String text(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
      throw new IllegalArgumentException("World hold evidence requires textual " + field);
    }
    return value.textValue();
  }

  private static void exact(JsonNode node, Set<String> fields) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("World hold evidence contains unknown or missing fields");
    }
    Set<String> actual = new java.util.HashSet<>();
    node.propertyNames().forEach(actual::add);
    if (!actual.equals(fields))
      throw new IllegalArgumentException("World hold evidence contains unknown or missing fields");
  }

  public static long decimal(String value, boolean positive) {
    if (value == null
        || !value.matches(positive ? "[1-9][0-9]*" : "0|[1-9][0-9]*")
        || value.length() > 19) {
      throw new IllegalArgumentException("World hold counters require canonical decimal strings");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException failure) {
      throw new IllegalArgumentException("World hold counter exceeds signed-long range", failure);
    }
  }

  /** Retained lease deadline triggers recovery diagnostics, never automatic release. */
  public long diagnosticExpiresAtMillis() {
    return Long.parseLong((String) request.lease().carrier().get("expiresAt"));
  }

  /**
   * Complete original Account lease plus independently expected World lifecycle tuple. Account's
   * regionEpoch is a separate domain and is never interpreted as this World lifecycle epoch.
   */
  public record Request(
      AccountGameplayAdmissionLeaseEvidence lease,
      long expectedLifecycleEpoch,
      long expectedRowVersion) {
    public Request {
      Objects.requireNonNull(lease, "lease");
      if (expectedLifecycleEpoch <= 0 || expectedRowVersion < 0) {
        throw new IllegalArgumentException("Expected World lifecycle tuple is invalid");
      }
      if (!(lease.carrier().get("bindingScope") instanceof Map<?, ?> scope)
          || !"SHARED".equals(scope.get("playableStateScope"))
          || !(lease.carrier().get("membershipBaseline") instanceof Map<?, ?> baseline)
          || !"ACTIVE".equals(baseline.get("membershipLifecycleState"))) {
        throw new IllegalArgumentException("Unsupported or non-admitting original lease scope");
      }
    }

    public UUID leaseId() {
      return UUID.fromString((String) lease.carrier().get("leaseId"));
    }

    public UUID attemptId() {
      return UUID.fromString((String) lease.carrier().get("requestId"));
    }

    public UUID canonicalGameInstanceId() {
      return UUID.fromString((String) scope().get("gameInstanceId"));
    }

    public String targetNamespace() {
      return (String) lease.carrier().get("targetNamespace");
    }

    public boolean sameBinding(Request other) {
      return other != null
          && lease.hasSameIdentity(other.lease)
          && expectedLifecycleEpoch == other.expectedLifecycleEpoch
          && expectedRowVersion == other.expectedRowVersion;
    }

    public void requireExactActiveWorld(WorldCanonicalInstanceLifecycleEvidence evidence) {
      Objects.requireNonNull(evidence, "evidence");
      requireExactWorldSelector(evidence.request());
      if (!"ACTIVE".equals(evidence.lifecycleStatus())
          || expectedLifecycleEpoch != evidence.lifecycleEpoch()
          || expectedRowVersion != evidence.rowVersion()) {
        throw new IllegalArgumentException("World target is not the exact expected ACTIVE tuple");
      }
    }

    public void requireExactWorldSelector(
        WorldCanonicalInstanceLifecycleEvidence.Request selector) {
      if (!targetNamespace().equals(selector.targetNamespace())
          || !scope().get("tenantId").equals(selector.canonicalTenantId().toString())
          || !scope().get("worldSlug").equals(selector.worldSlug())
          || !scope().get("gameInstanceId").equals(selector.canonicalGameInstanceId().toString())
          || !scope()
              .get("playableStateNamespaceId")
              .equals(selector.playableStateNamespaceId().toString())
          || !scope().get("playableStateScope").equals(selector.playableStateScope())
          || !selector.publicProduction()) {
        throw new IllegalArgumentException("Original lease differs from canonical World scope");
      }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> scope() {
      return (Map<String, Object>) lease.carrier().get("bindingScope");
    }
  }

  private static void requireOpaque(UUID value) {
    if (value == null || value.version() != 4 || value.variant() != 2) {
      throw new IllegalArgumentException("Hold identity and fence must be opaque UUIDs");
    }
  }
}
