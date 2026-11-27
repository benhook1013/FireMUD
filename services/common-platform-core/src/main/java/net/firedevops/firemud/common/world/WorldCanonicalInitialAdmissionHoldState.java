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
    WorldCanonicalInstanceLifecycleEvidence lifecycleEvidence,
    WorldRunOwnedFixtureLifecycleEvidence fixtureLifecycleEvidence) {
  public static final String SCHEMA = "world-canonical-initial-admission-hold-state/v1";
  public static final int MAX_CANONICAL_BYTES = 4 * 1024 * 1024;
  private static final int MAX_HOLD_IDENTITY_BYTES = 64 * 1024;
  private static final int MAX_LIFECYCLE_EVIDENCE_BYTES = 2 * 1024 * 1024;
  private static final Set<String> FIELDS =
      Set.of("schema", "holdIdentityBytesBase64", "holdStatus", "lifecycleEvidenceBytesBase64");
  private static final Set<String> FIXTURE_FIELDS =
      Set.of(
          "schema", "holdIdentityBytesBase64", "holdStatus", "fixtureLifecycleEvidenceBytesBase64");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  public WorldCanonicalInitialAdmissionHoldState {
    Objects.requireNonNull(holdIdentity, "holdIdentity");
    Objects.requireNonNull(holdStatus, "holdStatus");
    if ((lifecycleEvidence == null) == (fixtureLifecycleEvidence == null)) {
      throw new IllegalArgumentException(
          "Canonical World hold state requires exactly one Human or fixture lifecycle observation");
    }
    requireTargetBinding(
        holdIdentity, lifecycleRequestOf(lifecycleEvidence, fixtureLifecycleEvidence));
    byte[] lifecycleBytes =
        lifecycleEvidence != null
            ? lifecycleEvidence.canonicalBytes()
            : fixtureLifecycleEvidence.canonicalBytes();
    if (holdIdentity.canonicalBytes().length > MAX_HOLD_IDENTITY_BYTES
        || lifecycleBytes.length > MAX_LIFECYCLE_EVIDENCE_BYTES) {
      throw new IllegalArgumentException("Canonical World hold-state evidence exceeds its limit");
    }
  }

  /** Existing Human hold-state constructor retained at its call sites. */
  public WorldCanonicalInitialAdmissionHoldState(
      WorldCanonicalInitialAdmissionHold.HoldIdentity holdIdentity,
      HoldStatus holdStatus,
      WorldCanonicalInstanceLifecycleEvidence lifecycleEvidence) {
    this(
        holdIdentity,
        holdStatus,
        Objects.requireNonNull(lifecycleEvidence, "lifecycleEvidence"),
        null);
  }

  /** Run-owned fixture hold state, observed with its fixture lifecycle evidence. */
  public static WorldCanonicalInitialAdmissionHoldState runOwnedFixture(
      WorldCanonicalInitialAdmissionHold.HoldIdentity holdIdentity,
      HoldStatus holdStatus,
      WorldRunOwnedFixtureLifecycleEvidence fixtureLifecycleEvidence) {
    return new WorldCanonicalInitialAdmissionHoldState(
        holdIdentity,
        holdStatus,
        null,
        Objects.requireNonNull(fixtureLifecycleEvidence, "fixtureLifecycleEvidence"));
  }

  public boolean isRunOwnedFixture() {
    return fixtureLifecycleEvidence != null;
  }

  /** The lifecycle read request echoed by whichever owner observation this state carries. */
  public WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest() {
    return lifecycleRequestOf(lifecycleEvidence, fixtureLifecycleEvidence);
  }

  public String lifecycleStatus() {
    return lifecycleEvidence != null
        ? lifecycleEvidence.lifecycleStatus()
        : fixtureLifecycleEvidence.lifecycleStatus();
  }

  public long lifecycleEpoch() {
    return lifecycleEvidence != null
        ? lifecycleEvidence.lifecycleEpoch()
        : fixtureLifecycleEvidence.lifecycleEpoch();
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequestOf(
      WorldCanonicalInstanceLifecycleEvidence human,
      WorldRunOwnedFixtureLifecycleEvidence fixture) {
    return human != null ? human.request() : fixture.request();
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
    if (lifecycleEvidence != null) {
      value.put(
          "lifecycleEvidenceBytesBase64",
          Base64.getEncoder().encodeToString(lifecycleEvidence.canonicalBytes()));
    } else {
      value.put(
          "fixtureLifecycleEvidenceBytesBase64",
          Base64.getEncoder().encodeToString(fixtureLifecycleEvidence.canonicalBytes()));
    }
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
        && "ACTIVE".equals(lifecycleStatus())
        && lifecycleEpoch() == holdIdentity.request().activeLifecycleEpoch();
  }

  /** Decodes only complete, canonical, bounded state and both exact nested owner records. */
  public static WorldCanonicalInitialAdmissionHoldState fromStored(byte[] stored) {
    Objects.requireNonNull(stored, "stored");
    if (stored.length == 0 || stored.length > MAX_CANONICAL_BYTES) {
      throw new IllegalArgumentException("Canonical World hold-state evidence exceeds its limit");
    }
    try {
      JsonNode root = JSON.readTree(strictUtf8(stored));
      boolean fixture = requireFields(root);
      if (!SCHEMA.equals(text(root, "schema"))) {
        throw new IllegalArgumentException("Unsupported canonical World hold-state schema");
      }
      byte[] identityBytes = decodeBase64(root, "holdIdentityBytesBase64");
      byte[] lifecycleBytes =
          decodeBase64(
              root,
              fixture ? "fixtureLifecycleEvidenceBytesBase64" : "lifecycleEvidenceBytesBase64");
      if (identityBytes.length == 0
          || identityBytes.length > MAX_HOLD_IDENTITY_BYTES
          || lifecycleBytes.length == 0
          || lifecycleBytes.length > MAX_LIFECYCLE_EVIDENCE_BYTES) {
        throw new IllegalArgumentException(
            "Canonical World hold-state component exceeds its limit");
      }
      WorldCanonicalInitialAdmissionHold.HoldIdentity identity =
          WorldCanonicalInitialAdmissionHold.HoldIdentity.fromStored(identityBytes);
      HoldStatus status = parseStatus(text(root, "holdStatus"));
      WorldCanonicalInitialAdmissionHoldState state =
          fixture
              ? runOwnedFixture(
                  identity,
                  status,
                  WorldRunOwnedFixtureLifecycleEvidence.fromStored(lifecycleBytes))
              : new WorldCanonicalInitialAdmissionHoldState(
                  identity,
                  status,
                  WorldCanonicalInstanceLifecycleEvidence.fromStored(lifecycleBytes));
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
      WorldCanonicalInstanceLifecycleEvidence.Request current) {
    var hold = identity.request();
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

  /** Returns true for the fixture field set; any other shape is rejected. */
  private static boolean requireFields(JsonNode object) {
    if (object == null || !object.isObject()) {
      throw new IllegalArgumentException("Canonical World hold-state evidence must be an object");
    }
    Set<String> actual = new java.util.HashSet<>();
    object.propertyNames().forEach(actual::add);
    if (actual.equals(FIELDS)) return false;
    if (actual.equals(FIXTURE_FIELDS)) return true;
    throw new IllegalArgumentException(
        "Canonical World hold-state evidence has missing or unsupported fields");
  }

  public enum HoldStatus {
    PENDING,
    RECONCILIATION_REQUIRED,
    COMMITTED,
    ABORTED
  }
}
