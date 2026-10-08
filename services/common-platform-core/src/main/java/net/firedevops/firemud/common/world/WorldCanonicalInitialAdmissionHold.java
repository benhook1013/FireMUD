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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closed request and immutable World-issued identity for canonical initial admission.
 *
 * <p>This carrier records acquisition identity only. It does not represent a hold status, an
 * admission outcome, current lifecycle proof, an authorization grant, or an expiry authority.
 */
public final class WorldCanonicalInitialAdmissionHold {
  public static final String REQUEST_SCHEMA = "world-canonical-initial-admission-hold-request/v1";
  public static final String IDENTITY_SCHEMA = "world-canonical-initial-admission-hold-identity/v1";

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern LOWER_SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Set<String> REQUEST_ENVELOPE_FIELDS = Set.of("schema", "request");
  private static final Set<String> REQUEST_FIELDS =
      Set.of(
          "targetNamespace",
          "canonicalTenantId",
          "worldSlug",
          "realmId",
          "playableStateNamespaceId",
          "playableStateScope",
          "canonicalGameInstanceId",
          "canonicalVersionId",
          "activeLifecycleEpoch",
          "initialAdmissionRequestId",
          "initialAdmissionRequestDigest",
          "initialAdmissionOrigin",
          "expectedCatalogRevision",
          "expectedPriorPointerVersion");
  private static final Set<String> IDENTITY_FIELDS =
      Set.of("schema", "holdId", "holdFence", "holdBindingDigest", "requestBytesBase64");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private WorldCanonicalInitialAdmissionHold() {}

  /** The only initial-admission origin preconditions accepted by the first-OPEN contract. */
  public enum InitialAdmissionOrigin {
    NO_PRIOR_POINTER,
    EXPECT_CLOSED
  }

  /**
   * Complete immutable acquisition input. The supplied Game Session digest remains opaque here;
   * World computes a separate hold binding digest over this complete versioned request.
   */
  public record Request(
      String targetNamespace,
      UUID canonicalTenantId,
      String worldSlug,
      UUID realmId,
      UUID playableStateNamespaceId,
      String playableStateScope,
      UUID canonicalGameInstanceId,
      UUID canonicalVersionId,
      long activeLifecycleEpoch,
      String initialAdmissionRequestId,
      String initialAdmissionRequestDigest,
      InitialAdmissionOrigin initialAdmissionOrigin,
      long expectedCatalogRevision,
      Long expectedPriorPointerVersion) {
    public Request {
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("targetNamespace must be a valid workload namespace");
      }
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
      requireNonNil(realmId, "realmId");
      requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
      if (!"SHARED".equals(playableStateScope)) {
        throw new IllegalArgumentException(
            "Only SHARED playableStateScope is supported for initial admission");
      }
      requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
      requireNonNil(canonicalVersionId, "canonicalVersionId");
      if (activeLifecycleEpoch <= 0) {
        throw new IllegalArgumentException("activeLifecycleEpoch must be positive");
      }
      requireBoundedText(initialAdmissionRequestId, "initialAdmissionRequestId", 128);
      requireLowerSha256(initialAdmissionRequestDigest, "initialAdmissionRequestDigest");
      Objects.requireNonNull(initialAdmissionOrigin, "initialAdmissionOrigin");
      if (expectedCatalogRevision <= 0) {
        throw new IllegalArgumentException("expectedCatalogRevision must be positive");
      }
      if (expectedPriorPointerVersion != null && expectedPriorPointerVersion <= 0) {
        throw new IllegalArgumentException(
            "expectedPriorPointerVersion must be positive when present");
      }
      if (initialAdmissionOrigin == InitialAdmissionOrigin.NO_PRIOR_POINTER
          && expectedPriorPointerVersion != null) {
        throw new IllegalArgumentException(
            "NO_PRIOR_POINTER cannot carry expectedPriorPointerVersion");
      }
      if (initialAdmissionOrigin == InitialAdmissionOrigin.EXPECT_CLOSED
          && expectedPriorPointerVersion == null) {
        throw new IllegalArgumentException("EXPECT_CLOSED requires expectedPriorPointerVersion");
      }
    }

    /** Complete versioned request bytes in RFC 8785 canonical JSON form. */
    public byte[] canonicalRequestBytes() {
      Map<String, Object> envelope = new LinkedHashMap<>();
      envelope.put("schema", REQUEST_SCHEMA);
      envelope.put("request", requestJson(this));
      try {
        return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(envelope));
      } catch (IOException impossible) {
        throw new IllegalStateException(
            "Canonical initial-admission request cannot be encoded", impossible);
      }
    }

    /** World hold-binding digest; distinct from the opaque Game Session request digest. */
    public String holdBindingDigest() {
      return sha256(canonicalRequestBytes());
    }

    /** Decodes only the complete exact request shape and requires canonical retained bytes. */
    public static Request fromStored(byte[] stored) {
      Objects.requireNonNull(stored, "stored");
      try {
        JsonNode root = JSON.readTree(strictUtf8(stored));
        requireFields(root, REQUEST_ENVELOPE_FIELDS, "Canonical initial-admission request");
        if (!REQUEST_SCHEMA.equals(text(root, "schema"))) {
          throw new IllegalArgumentException("Unsupported initial-admission request schema");
        }
        JsonNode value = root.get("request");
        requireFields(value, REQUEST_FIELDS, "Canonical initial-admission request body");
        Request request =
            new Request(
                text(value, "targetNamespace"),
                parseUuid(text(value, "canonicalTenantId"), "canonicalTenantId"),
                text(value, "worldSlug"),
                parseUuid(text(value, "realmId"), "realmId"),
                parseUuid(text(value, "playableStateNamespaceId"), "playableStateNamespaceId"),
                text(value, "playableStateScope"),
                parseUuid(text(value, "canonicalGameInstanceId"), "canonicalGameInstanceId"),
                parseUuid(text(value, "canonicalVersionId"), "canonicalVersionId"),
                positiveLong(text(value, "activeLifecycleEpoch"), "activeLifecycleEpoch"),
                text(value, "initialAdmissionRequestId"),
                text(value, "initialAdmissionRequestDigest"),
                parseOrigin(text(value, "initialAdmissionOrigin")),
                positiveLong(text(value, "expectedCatalogRevision"), "expectedCatalogRevision"),
                optionalPositiveLong(value, "expectedPriorPointerVersion"));
        if (!Arrays.equals(stored, request.canonicalRequestBytes())) {
          throw new IllegalArgumentException(
              "Canonical initial-admission request is not canonical");
        }
        return request;
      } catch (tools.jackson.core.JacksonException invalid) {
        throw new IllegalArgumentException(
            "Canonical initial-admission request is invalid", invalid);
      }
    }
  }

  /**
   * Immutable acquired identity: a complete request plus World-issued non-nil hold identity and
   * equality-only fence. Exact acquisition retries retain this object and its request digest.
   */
  public record HoldIdentity(Request request, UUID holdId, UUID holdFence) {
    public HoldIdentity {
      Objects.requireNonNull(request, "request");
      requireNonNil(holdId, "holdId");
      requireNonNil(holdFence, "holdFence");
    }

    public byte[] canonicalRequestBytes() {
      return request.canonicalRequestBytes();
    }

    public String holdBindingDigest() {
      return request.holdBindingDigest();
    }

    /** Canonical retained identity bytes; no lifecycle or mutable hold state is represented. */
    public byte[] canonicalBytes() {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("schema", IDENTITY_SCHEMA);
      value.put("holdId", holdId.toString());
      value.put("holdFence", holdFence.toString());
      value.put("holdBindingDigest", holdBindingDigest());
      value.put("requestBytesBase64", Base64.getEncoder().encodeToString(canonicalRequestBytes()));
      try {
        return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      } catch (IOException impossible) {
        throw new IllegalStateException(
            "Canonical initial-admission hold identity cannot be encoded", impossible);
      }
    }

    /** Decodes and verifies the entire immutable request/hold identity readback. */
    public static HoldIdentity fromStored(byte[] stored) {
      Objects.requireNonNull(stored, "stored");
      try {
        JsonNode root = JSON.readTree(strictUtf8(stored));
        requireFields(root, IDENTITY_FIELDS, "Canonical initial-admission hold identity");
        if (!IDENTITY_SCHEMA.equals(text(root, "schema"))) {
          throw new IllegalArgumentException("Unsupported initial-admission hold identity schema");
        }
        Request request = Request.fromStored(decodeBase64(root, "requestBytesBase64"));
        String storedDigest = text(root, "holdBindingDigest");
        if (!request.holdBindingDigest().equals(storedDigest)) {
          throw new IllegalArgumentException(
              "Initial-admission hold binding digest differs from request");
        }
        HoldIdentity identity =
            new HoldIdentity(
                request,
                parseUuid(text(root, "holdId"), "holdId"),
                parseUuid(text(root, "holdFence"), "holdFence"));
        if (!Arrays.equals(stored, identity.canonicalBytes())) {
          throw new IllegalArgumentException(
              "Canonical initial-admission hold identity is not canonical");
        }
        return identity;
      } catch (tools.jackson.core.JacksonException invalid) {
        throw new IllegalArgumentException(
            "Canonical initial-admission hold identity is invalid", invalid);
      }
    }
  }

  private static Map<String, Object> requestJson(Request request) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("targetNamespace", request.targetNamespace());
    value.put("canonicalTenantId", request.canonicalTenantId().toString());
    value.put("worldSlug", request.worldSlug());
    value.put("realmId", request.realmId().toString());
    value.put("playableStateNamespaceId", request.playableStateNamespaceId().toString());
    value.put("playableStateScope", request.playableStateScope());
    value.put("canonicalGameInstanceId", request.canonicalGameInstanceId().toString());
    value.put("canonicalVersionId", request.canonicalVersionId().toString());
    value.put("activeLifecycleEpoch", Long.toString(request.activeLifecycleEpoch()));
    value.put("initialAdmissionRequestId", request.initialAdmissionRequestId());
    value.put("initialAdmissionRequestDigest", request.initialAdmissionRequestDigest());
    value.put("initialAdmissionOrigin", request.initialAdmissionOrigin().name());
    value.put("expectedCatalogRevision", Long.toString(request.expectedCatalogRevision()));
    value.put(
        "expectedPriorPointerVersion",
        request.expectedPriorPointerVersion() == null
            ? null
            : Long.toString(request.expectedPriorPointerVersion()));
    return value;
  }

  private static InitialAdmissionOrigin parseOrigin(String value) {
    try {
      return InitialAdmissionOrigin.valueOf(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Unsupported initialAdmissionOrigin", invalid);
    }
  }

  private static Long optionalPositiveLong(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null) {
      throw new IllegalArgumentException(field + " must be present as text or null");
    }
    if (value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(
          field + " must be canonical positive decimal text or null");
    }
    return positiveLong(value.textValue(), field);
  }

  private static long positiveLong(String value, String field) {
    if (!value.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException(field + " must be canonical positive decimal text");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException(field + " exceeds the supported integer range", invalid);
    }
  }

  private static UUID parseUuid(String value, String field) {
    try {
      UUID parsed = UUID.fromString(value);
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException(field + " must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID", invalid);
    }
  }

  private static void requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(field + " must be non-nil");
    }
  }

  private static void requireBoundedText(String value, String field, int maximumCodePoints) {
    if (value == null
        || value.isBlank()
        || value.codePointCount(0, value.length()) > maximumCodePoints) {
      throw new IllegalArgumentException(field + " must be nonempty and bounded");
    }
    GameTenantCreationDigest.utf8ByteLength(value);
  }

  private static void requireLowerSha256(String value, String field) {
    if (value == null || !LOWER_SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be lowercase 64-character SHA-256 hex");
    }
  }

  private static String text(JsonNode object, String field) {
    JsonNode value = object == null ? null : object.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return value.textValue();
  }

  private static byte[] decodeBase64(JsonNode object, String field) {
    try {
      return Base64.getDecoder().decode(text(object, field));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(field + " must be canonical base64", invalid);
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
    } catch (java.nio.charset.CharacterCodingException invalid) {
      throw new IllegalArgumentException("Canonical hold bytes are not strict UTF-8", invalid);
    }
  }

  private static void requireFields(JsonNode object, Set<String> expected, String label) {
    if (object == null || !object.isObject() || object.size() != expected.size()) {
      throw new IllegalArgumentException(label + " has an invalid closed object shape");
    }
    for (String name : object.propertyNames()) {
      if (!expected.contains(name)) {
        throw new IllegalArgumentException(label + " has an unknown field");
      }
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }
}
