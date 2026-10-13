package net.firedevops.firemud.common.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closed same-snapshot readback of a canonical initial-admission hold and its current lifecycle.
 *
 * <p>This is evidence of one observed owner snapshot, not a continuous hold, an admission grant, or
 * authorization to mutate either owner's state.
 */
public record WorldCanonicalInitialAdmissionHoldState(
    WorldCanonicalInitialAdmissionHold.HoldIdentity holdIdentity,
    HoldStatus holdStatus,
    WorldCanonicalInstanceLifecycleEvidence lifecycleEvidence) {
  public static final String SCHEMA = "world-canonical-initial-admission-hold-state/v1";
  public static final int MAX_CANONICAL_BYTES = 4 * 1024 * 1024;
  private static final int MAX_HOLD_IDENTITY_BYTES = 64 * 1024;
  private static final int MAX_LIFECYCLE_EVIDENCE_BYTES = 2 * 1024 * 1024;
  private static final Set<String> FIELDS =
      Set.of("schema", "holdIdentityBytesBase64", "holdStatus", "lifecycleEvidenceBytesBase64");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  public WorldCanonicalInitialAdmissionHoldState {
    Objects.requireNonNull(holdIdentity, "holdIdentity");
    Objects.requireNonNull(holdStatus, "holdStatus");
    Objects.requireNonNull(lifecycleEvidence, "lifecycleEvidence");
    requireTargetBinding(holdIdentity, lifecycleEvidence);
    if (holdIdentity.canonicalBytes().length > MAX_HOLD_IDENTITY_BYTES
        || lifecycleEvidence.canonicalBytes().length > MAX_LIFECYCLE_EVIDENCE_BYTES) {
      throw new IllegalArgumentException("Canonical World hold-state evidence exceeds its limit");
    }
  }

  /**
   * Exact canonical readback bytes, including immutable identity and complete current lifecycle.
   */
  public byte[] canonicalBytes() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", SCHEMA);
    value.put(
        "holdIdentityBytesBase64",
        Base64.getEncoder().encodeToString(holdIdentity.canonicalBytes()));
    value.put("holdStatus", holdStatus.name());
    value.put(
        "lifecycleEvidenceBytesBase64",
        Base64.getEncoder().encodeToString(lifecycleEvidence.canonicalBytes()));
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      if (canonical.length > MAX_CANONICAL_BYTES) {
        throw new IllegalStateException("Canonical World hold-state evidence exceeds its limit");
      }
      return canonical;
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "Canonical World hold-state evidence cannot be encoded", impossible);
    }
  }

  /**
   * This one read can identify the currently pending hold at its expected active epoch. It does not
   * prove that protection remains continuous after this snapshot or authorize a commit.
   */
  public boolean isPendingAtExpectedActiveEpoch() {
    return holdStatus == HoldStatus.PENDING
        && "ACTIVE".equals(lifecycleEvidence.lifecycleStatus())
        && lifecycleEvidence.lifecycleEpoch() == holdIdentity.request().activeLifecycleEpoch();
  }

  /** Decodes only complete, canonical, bounded state and both exact nested owner records. */
  public static WorldCanonicalInitialAdmissionHoldState fromStored(byte[] stored) {
    Objects.requireNonNull(stored, "stored");
    if (stored.length == 0 || stored.length > MAX_CANONICAL_BYTES) {
      throw new IllegalArgumentException("Canonical World hold-state evidence exceeds its limit");
    }
    try {
      JsonNode root = JSON.readTree(strictUtf8(stored));
      requireFields(root);
      if (!SCHEMA.equals(text(root, "schema"))) {
        throw new IllegalArgumentException("Unsupported canonical World hold-state schema");
      }
      byte[] identityBytes = decodeBase64(root, "holdIdentityBytesBase64");
      byte[] lifecycleBytes = decodeBase64(root, "lifecycleEvidenceBytesBase64");
      if (identityBytes.length == 0
          || identityBytes.length > MAX_HOLD_IDENTITY_BYTES
          || lifecycleBytes.length == 0
          || lifecycleBytes.length > MAX_LIFECYCLE_EVIDENCE_BYTES) {
        throw new IllegalArgumentException(
            "Canonical World hold-state component exceeds its limit");
      }
      WorldCanonicalInitialAdmissionHold.HoldIdentity identity =
          WorldCanonicalInitialAdmissionHold.HoldIdentity.fromStored(identityBytes);
      WorldCanonicalInstanceLifecycleEvidence lifecycle =
          WorldCanonicalInstanceLifecycleEvidence.fromStored(lifecycleBytes);
      WorldCanonicalInitialAdmissionHoldState state =
          new WorldCanonicalInitialAdmissionHoldState(
              identity, parseStatus(text(root, "holdStatus")), lifecycle);
      if (!Arrays.equals(stored, state.canonicalBytes())) {
        throw new IllegalArgumentException("Canonical World hold-state evidence is not canonical");
      }
      return state;
    } catch (tools.jackson.core.JacksonException invalid) {
      throw new IllegalArgumentException("Canonical World hold-state evidence is invalid", invalid);
    }
  }

  private static void requireTargetBinding(
      WorldCanonicalInitialAdmissionHold.HoldIdentity identity,
      WorldCanonicalInstanceLifecycleEvidence lifecycle) {
    var hold = identity.request();
    var current = lifecycle.request();
    if (!hold.targetNamespace().equals(current.targetNamespace())
        || !hold.canonicalTenantId().equals(current.canonicalTenantId())
        || !hold.worldSlug().equals(current.worldSlug())
        || !hold.canonicalGameInstanceId().equals(current.canonicalGameInstanceId())
        || !hold.playableStateNamespaceId().equals(current.playableStateNamespaceId())
        || !hold.playableStateScope().equals(current.playableStateScope())
        || !hold.canonicalVersionId().equals(current.canonicalVersionId())
        || !current.publicProduction()) {
      throw new IllegalArgumentException(
          "Canonical World hold state differs from the complete lifecycle target scope");
    }
  }

  private static HoldStatus parseStatus(String status) {
    try {
      return HoldStatus.valueOf(status);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(
          "Unsupported canonical World initial-admission hold status", invalid);
    }
  }

  private static byte[] decodeBase64(JsonNode object, String field) {
    String encoded = text(object, field);
    try {
      byte[] decoded = Base64.getDecoder().decode(encoded);
      if (!Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
        throw new IllegalArgumentException(field + " must use canonical base64");
      }
      return decoded;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(field + " must use canonical base64", invalid);
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
      throw new IllegalArgumentException(
          "Canonical World hold-state bytes are not strict UTF-8", invalid);
    }
  }

  private static String text(JsonNode object, String field) {
    JsonNode value = object == null ? null : object.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return value.textValue();
  }

  private static void requireFields(JsonNode object) {
    if (object == null || !object.isObject()) {
      throw new IllegalArgumentException("Canonical World hold-state evidence must be an object");
    }
    Set<String> actual = new java.util.HashSet<>();
    object.propertyNames().forEach(actual::add);
    if (!actual.equals(FIELDS)) {
      throw new IllegalArgumentException(
          "Canonical World hold-state evidence has missing or unsupported fields");
    }
  }

  public enum HoldStatus {
    PENDING,
    RECONCILIATION_REQUIRED,
    COMMITTED,
    ABORTED
  }
}
