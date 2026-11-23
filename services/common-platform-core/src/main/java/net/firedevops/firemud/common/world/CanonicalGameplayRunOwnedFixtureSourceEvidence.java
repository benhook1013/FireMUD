package net.firedevops.firemud.common.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closed run-owned fixture counterpart of {@link CanonicalGameplayRosterOwnerReadEvidence}: the
 * exact committed Game Session owner proof plus the catalog-retained sealed fixture policy instead
 * of a Game Design publication. The fixture has no Human publication, so this carrier never
 * satisfies a Human published-source consumer. The account UUID is correlation only; this is source
 * evidence, not Account authority, actor ownership or gameplay admission.
 */
public final class CanonicalGameplayRunOwnedFixtureSourceEvidence {
  public static final String SCHEMA = "canonical-gameplay-run-owned-fixture-source/v1";
  public static final int MAX_CANONICAL_BYTES = 64 * 1024;

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern POINTER_SNAPSHOT_DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern PREFIXED_SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Set<String> FIELDS =
      Set.of(
          "schema",
          "requestUuid",
          "canonicalAccountUuid",
          "targetNamespace",
          "canonicalTenantUuid",
          "worldSlug",
          "realmUuid",
          "realmSlug",
          "playableStateNamespaceUuid",
          "playableStateScope",
          "canonicalGameInstanceUuid",
          "canonicalVersionUuid",
          "expectedCatalogRevision",
          "expectedPointerVersion",
          "expectedActiveWorldEpoch",
          "admissionPointerSnapshotDigest",
          "ownerProofBase64",
          "fixturePolicyRequestBase64",
          "fixturePolicySealOperationId",
          "fixturePolicySealRequestDigest",
          "fixturePolicySealEvidenceDigest");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private final CanonicalGameplayRosterOwnerReadEvidence.Request request;
  private final String admissionPointerSnapshotDigest;
  private final GameSessionCanonicalInitialAdmissionOwnerProof gameSessionOwnerProof;
  private final byte[] fixturePolicyRequestBytes;
  private final UUID fixturePolicySealOperationId;
  private final String fixturePolicySealRequestDigest;
  private final String fixturePolicySealEvidenceDigest;
  private final RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicyRequest fixturePolicy;

  public CanonicalGameplayRunOwnedFixtureSourceEvidence(
      CanonicalGameplayRosterOwnerReadEvidence.Request request,
      String admissionPointerSnapshotDigest,
      GameSessionCanonicalInitialAdmissionOwnerProof gameSessionOwnerProof,
      byte[] fixturePolicyRequestBytes,
      UUID fixturePolicySealOperationId,
      String fixturePolicySealRequestDigest,
      String fixturePolicySealEvidenceDigest) {
    this.request = Objects.requireNonNull(request, "request");
    if (admissionPointerSnapshotDigest == null
        || !POINTER_SNAPSHOT_DIGEST.matcher(admissionPointerSnapshotDigest).matches()) {
      throw new IllegalArgumentException(
          "Admission pointer snapshot digest must be raw lowercase 64-character SHA-256 hex");
    }
    this.admissionPointerSnapshotDigest = admissionPointerSnapshotDigest;
    Objects.requireNonNull(gameSessionOwnerProof, "gameSessionOwnerProof");
    byte[] proofBytes =
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(gameSessionOwnerProof);
    if (proofBytes.length > CanonicalGameplayRosterOwnerReadEvidence.MAX_OWNER_PROOF_BYTES) {
      throw new IllegalArgumentException("Run-owned fixture owner proof exceeds its size bound");
    }
    this.gameSessionOwnerProof =
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.fromStored(proofBytes);
    CanonicalGameplayRosterOwnerReadEvidence.requireProofMatches(
        request, this.gameSessionOwnerProof);

    Objects.requireNonNull(fixturePolicyRequestBytes, "fixturePolicyRequestBytes");
    this.fixturePolicyRequestBytes = fixturePolicyRequestBytes.clone();
    if (fixturePolicySealOperationId == null || NIL_UUID.equals(fixturePolicySealOperationId)) {
      throw new IllegalArgumentException("Fixture policy seal operation id must be non-nil");
    }
    requireDigest(fixturePolicySealRequestDigest, "fixturePolicySealRequestDigest");
    requireDigest(fixturePolicySealEvidenceDigest, "fixturePolicySealEvidenceDigest");
    this.fixturePolicySealOperationId = fixturePolicySealOperationId;
    this.fixturePolicySealRequestDigest = fixturePolicySealRequestDigest;
    this.fixturePolicySealEvidenceDigest = fixturePolicySealEvidenceDigest;
    this.fixturePolicy = requireFixturePolicyMatches(request, this.fixturePolicyRequestBytes);
    var capability = fixturePolicy.capability();
    String requestDigest =
        RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestDigest(
            capability,
            fixturePolicy.creationEvidence(),
            fixturePolicy.sourceEvidence(),
            fixturePolicy.launchReadEvidence());
    if (!UUID.fromString(capability.operationId()).equals(fixturePolicySealOperationId)
        || !requestDigest.equals(fixturePolicySealRequestDigest)
        || !RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyEvidenceDigest(
                fixturePolicySealOperationId, fixturePolicySealRequestDigest)
            .equals(fixturePolicySealEvidenceDigest)) {
      throw new IllegalArgumentException(
          "Run-owned fixture policy seal differs from its exact retained policy request");
    }
  }

  /**
   * Validates retained fixture policy bytes for one exact route selector and returns the decoded
   * request. Decoding grants no authority; only the owning catalog readback establishes provenance.
   */
  public static RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicyRequest
      requireFixturePolicyMatches(
          CanonicalGameplayRosterOwnerReadEvidence.Request request, byte[] fixturePolicyBytes) {
    Objects.requireNonNull(request, "request");
    var parsed =
        RunOwnedInitialAdmissionFixtureWireCodec.parseFixturePolicyRequestBytes(fixturePolicyBytes);
    var capability = parsed.capability();
    var source = parsed.sourceEvidence();
    String canonicalVersion =
        parsed.launchReadEvidence().publishedReleaseBundle().getCanonicalVersionId();
    if (!capability.visible()
        || !capability.publicProductionRealm()
        || !"SHARED".equals(capability.stateScope())
        || !"PRESEEDED_ONLY".equals(capability.characterCreationPolicy())
        || !"SHARED".equals(request.playableStateScope())
        || !capability.worldSlug().equals(request.worldSlug())
        || !capability.realmSlug().equals(request.realmSlug())
        || !source.targetNamespace().equals(request.targetNamespace())
        || !source.canonicalTenantId().equals(request.canonicalTenantUuid())
        || !source.worldSlug().equals(request.worldSlug())
        || !request.canonicalVersionUuid().toString().equals(canonicalVersion)) {
      throw new IllegalArgumentException(
          "Run-owned fixture policy is not the exact visible public-production SHARED"
              + " PRESEEDED_ONLY route");
    }
    return parsed;
  }

  public CanonicalGameplayRosterOwnerReadEvidence.Request request() {
    return request;
  }

  public String admissionPointerSnapshotDigest() {
    return admissionPointerSnapshotDigest;
  }

  public GameSessionCanonicalInitialAdmissionOwnerProof gameSessionOwnerProof() {
    return gameSessionOwnerProof;
  }

  public byte[] fixturePolicyRequestBytes() {
    return fixturePolicyRequestBytes.clone();
  }

  public UUID fixturePolicySealOperationId() {
    return fixturePolicySealOperationId;
  }

  public String fixturePolicySealRequestDigest() {
    return fixturePolicySealRequestDigest;
  }

  public String fixturePolicySealEvidenceDigest() {
    return fixturePolicySealEvidenceDigest;
  }

  /** Decoded retained policy request; structurally validated, never independent authority. */
  public RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicyRequest fixturePolicy() {
    return fixturePolicy;
  }

  /** The sealed fixture policy's character-creation policy (always PRESEEDED_ONLY). */
  public String characterCreationPolicy() {
    return fixturePolicy.capability().characterCreationPolicy();
  }

  public byte[] canonicalBytes() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", SCHEMA);
    value.put("requestUuid", request.requestUuid().toString());
    value.put("canonicalAccountUuid", request.canonicalAccountUuid().toString());
    value.put("targetNamespace", request.targetNamespace());
    value.put("canonicalTenantUuid", request.canonicalTenantUuid().toString());
    value.put("worldSlug", request.worldSlug());
    value.put("realmUuid", request.realmUuid().toString());
    value.put("realmSlug", request.realmSlug());
    value.put("playableStateNamespaceUuid", request.playableStateNamespaceUuid().toString());
    value.put("playableStateScope", request.playableStateScope());
    value.put("canonicalGameInstanceUuid", request.canonicalGameInstanceUuid().toString());
    value.put("canonicalVersionUuid", request.canonicalVersionUuid().toString());
    value.put("expectedCatalogRevision", Long.toString(request.expectedCatalogRevision()));
    value.put("expectedPointerVersion", Long.toString(request.expectedPointerVersion()));
    value.put("expectedActiveWorldEpoch", Long.toString(request.expectedActiveWorldEpoch()));
    value.put("admissionPointerSnapshotDigest", admissionPointerSnapshotDigest);
    value.put(
        "ownerProofBase64",
        Base64.getEncoder()
            .encodeToString(
                GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
                    gameSessionOwnerProof)));
    value.put(
        "fixturePolicyRequestBase64",
        Base64.getEncoder().encodeToString(fixturePolicyRequestBytes));
    value.put("fixturePolicySealOperationId", fixturePolicySealOperationId.toString());
    value.put("fixturePolicySealRequestDigest", fixturePolicySealRequestDigest);
    value.put("fixturePolicySealEvidenceDigest", fixturePolicySealEvidenceDigest);
    try {
      byte[] bytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      if (bytes.length > MAX_CANONICAL_BYTES) {
        throw new IllegalArgumentException("Run-owned fixture source evidence exceeds its limit");
      }
      return bytes;
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "Run-owned fixture source evidence cannot be encoded", impossible);
    }
  }

  /** Decodes only complete, canonical, bounded evidence; this does not authenticate its sender. */
  public static CanonicalGameplayRunOwnedFixtureSourceEvidence fromStored(byte[] stored) {
    Objects.requireNonNull(stored, "stored");
    if (stored.length == 0 || stored.length > MAX_CANONICAL_BYTES) {
      throw new IllegalArgumentException("Run-owned fixture source evidence exceeds its limit");
    }
    try {
      JsonNode root = JSON.readTree(strictUtf8(stored));
      if (root == null || !root.isObject()) {
        throw new IllegalArgumentException("Run-owned fixture source evidence must be an object");
      }
      Set<String> actual = new HashSet<>();
      root.propertyNames().forEach(actual::add);
      if (!actual.equals(FIELDS) || !SCHEMA.equals(text(root, "schema"))) {
        throw new IllegalArgumentException(
            "Run-owned fixture source evidence has an unsupported shape");
      }
      var request =
          new CanonicalGameplayRosterOwnerReadEvidence.Request(
              uuid(root, "requestUuid"),
              uuid(root, "canonicalAccountUuid"),
              text(root, "targetNamespace"),
              uuid(root, "canonicalTenantUuid"),
              text(root, "worldSlug"),
              uuid(root, "realmUuid"),
              text(root, "realmSlug"),
              uuid(root, "playableStateNamespaceUuid"),
              text(root, "playableStateScope"),
              uuid(root, "canonicalGameInstanceUuid"),
              uuid(root, "canonicalVersionUuid"),
              parseLong(text(root, "expectedCatalogRevision")),
              parseLong(text(root, "expectedPointerVersion")),
              parseLong(text(root, "expectedActiveWorldEpoch")));
      var evidence =
          new CanonicalGameplayRunOwnedFixtureSourceEvidence(
              request,
              text(root, "admissionPointerSnapshotDigest"),
              GameSessionCanonicalInitialAdmissionOwnerProofCodec.fromStored(
                  base64(root, "ownerProofBase64")),
              base64(root, "fixturePolicyRequestBase64"),
              uuid(root, "fixturePolicySealOperationId"),
              text(root, "fixturePolicySealRequestDigest"),
              text(root, "fixturePolicySealEvidenceDigest"));
      if (!Arrays.equals(stored, evidence.canonicalBytes())) {
        throw new IllegalArgumentException("Run-owned fixture source evidence is not canonical");
      }
      return evidence;
    } catch (tools.jackson.core.JacksonException invalid) {
      throw new IllegalArgumentException("Run-owned fixture source evidence is invalid", invalid);
    }
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof CanonicalGameplayRunOwnedFixtureSourceEvidence that
        && Arrays.equals(canonicalBytes(), that.canonicalBytes());
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(canonicalBytes());
  }

  @Override
  public String toString() {
    return "CanonicalGameplayRunOwnedFixtureSourceEvidence[request="
        + request.requestUuid()
        + ", sealEvidenceDigest="
        + fixturePolicySealEvidenceDigest
        + "]";
  }

  private static byte[] base64(JsonNode root, String field) {
    String encoded = text(root, field);
    byte[] decoded = Base64.getDecoder().decode(encoded);
    if (!Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
      throw new IllegalArgumentException(field + " must use canonical base64");
    }
    return decoded;
  }

  private static UUID uuid(JsonNode root, String field) {
    String value = text(root, field);
    UUID parsed = UUID.fromString(value);
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical UUID");
    }
    return parsed;
  }

  private static long parseLong(String value) {
    try {
      long parsed = Long.parseLong(value);
      if (!Long.toString(parsed).equals(value)) throw new NumberFormatException(value);
      return parsed;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("Run-owned fixture source number is invalid", invalid);
    }
  }

  private static void requireDigest(String value, String label) {
    if (value == null || !PREFIXED_SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " must be a prefixed SHA-256 digest");
    }
  }

  private static String text(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return value.textValue();
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
          "Run-owned fixture source evidence is not strict UTF-8", invalid);
    }
  }
}
