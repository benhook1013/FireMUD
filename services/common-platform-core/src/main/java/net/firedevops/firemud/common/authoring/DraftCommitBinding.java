package net.firedevops.firemud.common.authoring;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Complete immutable Game Design binding for one coordinated Draft commit.
 *
 * <p>This type retains a declared mutation and its affected tuple set exactly. Its owner labels and
 * opaque payloads are not proof that the declaration is semantically complete; each typed owner
 * producer must derive and verify its required tuple subset before any owner action.
 */
public final class DraftCommitBinding {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Comparator<AffectedUnit> AFFECTED_ORDER =
      Comparator.comparing(AffectedUnit::owner)
          .thenComparing(AffectedUnit::aggregateType)
          .thenComparing(AffectedUnit::aggregateId)
          .thenComparing(AffectedUnit::scopeType)
          .thenComparing(AffectedUnit::scopeId);

  /** Owners participating in the canonical multi-owner Draft commit contract. */
  public enum Owner {
    WORLD_MANAGEMENT,
    ENTITY_MANAGEMENT,
    GAME_LOGIC,
    AUTOMATION_SCRIPTING,
    GAME_DESIGN_CONTROL_PLANE
  }

  /** Canonical identity plus the exact private Game Design Version/source lineage. */
  public record TargetProof(
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      long gameDesignVersionRowId,
      String gameDesignVersionTenantKey,
      long sourceGameRowId,
      String sourceGameTenantKey,
      String sourceProvenanceKind) {
    public TargetProof {
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      requireNonNil(canonicalVersionId, "canonicalVersionId");
      if (gameDesignVersionRowId <= 0 || sourceGameRowId <= 0) {
        throw new IllegalArgumentException("Game Design source row identities must be positive");
      }
      requireBoundedText(gameDesignVersionTenantKey, 36, "gameDesignVersionTenantKey");
      requireBoundedText(sourceGameTenantKey, 36, "sourceGameTenantKey");
      if (!gameDesignVersionTenantKey.equals(sourceGameTenantKey)) {
        throw new IllegalArgumentException(
            "Version and source Game Design tenant keys must match exactly");
      }
      if (!"NEW_GAME_ROW".equals(sourceProvenanceKind)
          && !"RETAINED_GAME_V30".equals(sourceProvenanceKind)) {
        throw new IllegalArgumentException("Unsupported canonical Version source provenance");
      }
    }
  }

  /** One full owner-typed revision payload. The payload is opaque and is never normalized here. */
  public record RevisionPayload(
      String revisionOrder, UUID revisionId, Owner owner, String payload) {
    public RevisionPayload {
      Objects.requireNonNull(revisionOrder, "revisionOrder");
      requireNonNil(revisionId, "revisionId");
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(payload, "payload");
    }
  }

  /** The expected epoch for one owner-local aggregate and scope fence. */
  public record AffectedUnit(
      Owner owner,
      String aggregateType,
      String aggregateId,
      String scopeType,
      String scopeId,
      String expectedEpoch) {
    public AffectedUnit {
      Objects.requireNonNull(owner, "owner");
      requireBoundedText(aggregateType, 128, "aggregateType");
      requireBoundedText(aggregateId, 256, "aggregateId");
      requireBoundedText(scopeType, 128, "scopeType");
      requireBoundedText(scopeId, 256, "scopeId");
      requireCanonicalCounter(expectedEpoch, "expectedEpoch");
    }
  }

  private final TargetProof target;
  private final UUID requestId;
  private final UUID commitId;
  private final String baseCommitId;
  private final List<RevisionPayload> revisions;
  private final List<AffectedUnit> affectedUnits;
  private final List<Owner> requiredOwners;
  private final String canonicalJson;
  private final byte[] canonicalBytes;
  private final String digest;

  private DraftCommitBinding(
      TargetProof target,
      UUID requestId,
      UUID commitId,
      String baseCommitId,
      List<RevisionPayload> revisions,
      List<AffectedUnit> affectedUnits) {
    this.target = Objects.requireNonNull(target, "target");
    requireNonNil(requestId, "requestId");
    requireNonNil(commitId, "commitId");
    requireBoundedText(baseCommitId, 256, "baseCommitId");
    this.requestId = requestId;
    this.commitId = commitId;
    this.baseCommitId = baseCommitId;
    this.revisions = validateRevisions(revisions);
    this.affectedUnits = validateAffectedUnits(affectedUnits);
    this.requiredOwners = validateOwnerCoverage(this.revisions, this.affectedUnits);
    try {
      canonicalBytes = Rfc8785CanonicalJson.canonicalizeUtf8(serialize(toJsonObject()));
      canonicalJson = new String(canonicalBytes, StandardCharsets.UTF_8);
      digest = sha256(canonicalBytes);
    } catch (IOException exception) {
      throw new IllegalArgumentException(
          "Draft commit binding could not be canonicalized", exception);
    }
  }

  public static DraftCommitBinding create(
      TargetProof target,
      UUID requestId,
      UUID commitId,
      String baseCommitId,
      List<RevisionPayload> revisions,
      List<AffectedUnit> affectedUnits) {
    return new DraftCommitBinding(
        target, requestId, commitId, baseCommitId, revisions, affectedUnits);
  }

  /** Reconstructs an immutable binding from owner storage and rejects noncanonical/corrupt rows. */
  public static DraftCommitBinding fromStored(String storedJson, String storedDigest) {
    Objects.requireNonNull(storedJson, "storedJson");
    Objects.requireNonNull(storedDigest, "storedDigest");
    try {
      JsonNode root = JSON.readTree(storedJson);
      JsonNode targetNode = requireObject(root, "target");
      TargetProof target =
          new TargetProof(
              UUID.fromString(requiredText(root, "canonicalTenantId")),
              UUID.fromString(requiredText(root, "canonicalVersionId")),
              positiveLong(targetNode, "gameDesignVersionRowId"),
              requiredText(targetNode, "gameDesignVersionTenantKey"),
              positiveLong(targetNode, "sourceGameRowId"),
              requiredText(targetNode, "sourceGameTenantKey"),
              requiredText(targetNode, "sourceProvenanceKind"));
      List<RevisionPayload> revisions = new ArrayList<>();
      for (JsonNode node : requiredArray(root, "revisions")) {
        revisions.add(
            new RevisionPayload(
                requiredText(node, "revisionOrder"),
                UUID.fromString(requiredText(node, "revisionId")),
                Owner.valueOf(requiredText(node, "owner")),
                requiredText(node, "payload")));
      }
      List<AffectedUnit> affectedUnits = new ArrayList<>();
      for (JsonNode node : requiredArray(root, "affectedUnits")) {
        affectedUnits.add(
            new AffectedUnit(
                Owner.valueOf(requiredText(node, "owner")),
                requiredText(node, "aggregateType"),
                requiredText(node, "aggregateId"),
                requiredText(node, "scopeType"),
                requiredText(node, "scopeId"),
                requiredText(node, "expectedEpoch")));
      }
      DraftCommitBinding binding =
          create(
              target,
              UUID.fromString(requiredText(root, "requestId")),
              UUID.fromString(requiredText(root, "commitId")),
              requiredText(root, "baseCommitId"),
              revisions,
              affectedUnits);
      if (!binding.canonicalJson.equals(storedJson)
          || !binding.digest.equals(storedDigest)
          || !Arrays.equals(binding.canonicalBytes, storedJson.getBytes(StandardCharsets.UTF_8))) {
        throw new IllegalArgumentException(
            "Stored Draft commit binding is not exact canonical JSON");
      }
      return binding;
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Stored Draft commit binding is corrupt", exception);
    }
  }

  public TargetProof target() {
    return target;
  }

  public UUID requestId() {
    return requestId;
  }

  public UUID commitId() {
    return commitId;
  }

  public String baseCommitId() {
    return baseCommitId;
  }

  public List<RevisionPayload> revisions() {
    return List.copyOf(revisions);
  }

  public List<AffectedUnit> affectedUnits() {
    return List.copyOf(affectedUnits);
  }

  public List<Owner> requiredOwners() {
    return List.copyOf(requiredOwners);
  }

  /** The canonical UTF-8 representation, retained as text for exact database equality. */
  public String canonicalJson() {
    return canonicalJson;
  }

  /** Returns a defensive copy of the exact RFC 8785 bytes stored with this binding. */
  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public String digest() {
    return digest;
  }

  public List<AffectedUnit> affectedUnits(Owner owner) {
    return affectedUnits.stream().filter(unit -> unit.owner() == owner).toList();
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || (other instanceof DraftCommitBinding binding
            && digest.equals(binding.digest)
            && Arrays.equals(canonicalBytes, binding.canonicalBytes));
  }

  @Override
  public int hashCode() {
    return 31 * digest.hashCode() + Arrays.hashCode(canonicalBytes);
  }

  private Map<String, Object> toJsonObject() {
    Map<String, Object> object = new LinkedHashMap<>();
    object.put("schemaVersion", "1");
    object.put("canonicalTenantId", target.canonicalTenantId().toString());
    object.put("canonicalVersionId", target.canonicalVersionId().toString());
    Map<String, Object> targetObject = new LinkedHashMap<>();
    targetObject.put("gameDesignVersionRowId", Long.toString(target.gameDesignVersionRowId()));
    targetObject.put("gameDesignVersionTenantKey", target.gameDesignVersionTenantKey());
    targetObject.put("sourceGameRowId", Long.toString(target.sourceGameRowId()));
    targetObject.put("sourceGameTenantKey", target.sourceGameTenantKey());
    targetObject.put("sourceProvenanceKind", target.sourceProvenanceKind());
    object.put("target", targetObject);
    object.put("requestId", requestId.toString());
    object.put("commitId", commitId.toString());
    object.put("baseCommitId", baseCommitId);
    object.put("revisions", revisions.stream().map(DraftCommitBinding::revisionJson).toList());
    object.put(
        "affectedUnits", affectedUnits.stream().map(DraftCommitBinding::affectedUnitJson).toList());
    object.put("requiredOwners", requiredOwners.stream().map(Enum::name).toList());
    return object;
  }

  private static Map<String, Object> revisionJson(RevisionPayload revision) {
    Map<String, Object> object = new LinkedHashMap<>();
    object.put("revisionOrder", revision.revisionOrder());
    object.put("revisionId", revision.revisionId().toString());
    object.put("owner", revision.owner().name());
    object.put("payload", revision.payload());
    return object;
  }

  private static Map<String, Object> affectedUnitJson(AffectedUnit unit) {
    Map<String, Object> object = new LinkedHashMap<>();
    object.put("owner", unit.owner().name());
    object.put("aggregateType", unit.aggregateType());
    object.put("aggregateId", unit.aggregateId());
    object.put("scopeType", unit.scopeType());
    object.put("scopeId", unit.scopeId());
    object.put("expectedEpoch", unit.expectedEpoch());
    return object;
  }

  private static List<RevisionPayload> validateRevisions(List<RevisionPayload> requested) {
    Objects.requireNonNull(requested, "revisions");
    if (requested.isEmpty()) {
      throw new IllegalArgumentException("Draft commit must contain at least one full revision");
    }
    List<RevisionPayload> copy = List.copyOf(requested);
    Set<UUID> revisionIds = new HashSet<>();
    for (int index = 0; index < copy.size(); index++) {
      RevisionPayload revision = Objects.requireNonNull(copy.get(index), "revision");
      if (!Integer.toString(index).equals(revision.revisionOrder())) {
        throw new IllegalArgumentException(
            "Revision order must be the contiguous canonical array order");
      }
      if (!revisionIds.add(revision.revisionId())) {
        throw new IllegalArgumentException("Draft commit contains a duplicate revision identity");
      }
      if (revision.payload().isEmpty()) {
        throw new IllegalArgumentException("A full revision payload must not be omitted");
      }
    }
    return copy;
  }

  private static List<AffectedUnit> validateAffectedUnits(List<AffectedUnit> requested) {
    Objects.requireNonNull(requested, "affectedUnits");
    if (requested.isEmpty()) {
      throw new IllegalArgumentException("Draft commit must declare its complete affected units");
    }
    List<AffectedUnit> copy = new ArrayList<>(requested.size());
    Set<String> identities = new HashSet<>();
    for (AffectedUnit unit : requested) {
      Objects.requireNonNull(unit, "affectedUnit");
      String identity =
          unit.owner()
              + "\u0000"
              + unit.aggregateType()
              + "\u0000"
              + unit.aggregateId()
              + "\u0000"
              + unit.scopeType()
              + "\u0000"
              + unit.scopeId();
      if (!identities.add(identity)) {
        throw new IllegalArgumentException(
            "Draft commit contains a duplicate affected owner scope");
      }
      copy.add(unit);
    }
    copy.sort(AFFECTED_ORDER);
    return List.copyOf(copy);
  }

  private static List<Owner> validateOwnerCoverage(
      List<RevisionPayload> revisions, List<AffectedUnit> affectedUnits) {
    Set<Owner> revisionOwners = new HashSet<>();
    revisions.forEach(revision -> revisionOwners.add(revision.owner()));
    Set<Owner> affectedOwners = new HashSet<>();
    affectedUnits.forEach(unit -> affectedOwners.add(unit.owner()));
    if (!revisionOwners.equals(affectedOwners)) {
      throw new IllegalArgumentException(
          "Required owner set must exactly match owners derived from revisions and affected units");
    }
    return Arrays.stream(Owner.values()).filter(revisionOwners::contains).toList();
  }

  private static String serialize(Object object) {
    return JSON.writeValueAsString(object);
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String requiredText(JsonNode object, String field) {
    JsonNode value = object == null ? null : object.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(
          "Stored Draft commit binding is missing text field " + field);
    }
    return value.textValue();
  }

  private static JsonNode requireObject(JsonNode object, String field) {
    JsonNode value = object == null ? null : object.get(field);
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException(
          "Stored Draft commit binding is missing object field " + field);
    }
    return value;
  }

  private static JsonNode requiredArray(JsonNode object, String field) {
    JsonNode value = object == null ? null : object.get(field);
    if (value == null || !value.isArray()) {
      throw new IllegalArgumentException(
          "Stored Draft commit binding is missing array field " + field);
    }
    return value;
  }

  private static long positiveLong(JsonNode object, String field) {
    String value = requiredText(object, field);
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0 || !Long.toString(parsed).equals(value)) {
        throw new IllegalArgumentException(
            "Stored source row ID is not a positive canonical decimal");
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          "Stored source row ID is outside the supported range", exception);
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
    }
  }

  private static void requireCanonicalCounter(String value, String label) {
    Objects.requireNonNull(value, label);
    if (!value.matches("0|[1-9][0-9]*")) {
      throw new IllegalArgumentException(label + " must be a canonical nonnegative decimal string");
    }
    new BigInteger(value);
  }

  private static void requireBoundedText(String value, int maxUtf8Bytes, String label) {
    Objects.requireNonNull(value, label);
    if (value.isBlank()
        || value.getBytes(StandardCharsets.UTF_8).length > maxUtf8Bytes
        || !value.equals(value.trim())) {
      throw new IllegalArgumentException(label + " must be nonblank canonical bounded text");
    }
  }
}
