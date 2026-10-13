package net.firedevops.firemud.common.publication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Immutable exact selection of an existing synchronized authored Draft for full-version
 * publication. This is retained selection evidence, not authorization, owner freeze, or release
 * evidence.
 */
public final class AuthoredDraftPublishSelectionBinding {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final PublishIntent intent;
  private final TargetProof target;
  private final DraftCommitBinding selectedCommit;
  private final UUID fenceRequestId;
  private final UUID fenceCommitId;
  private final String fenceInputDigest;
  private final String fenceResultVectorJson;
  private final String fenceCreatedAt;
  private final String canonicalJson;
  private final byte[] canonicalBytes;
  private final String digest;

  private AuthoredDraftPublishSelectionBinding(
      PublishIntent intent,
      TargetProof target,
      DraftCommitBinding selectedCommit,
      VisibilityFence fence) {
    this.intent = Objects.requireNonNull(intent, "intent");
    this.target = Objects.requireNonNull(target, "target");
    this.selectedCommit = Objects.requireNonNull(selectedCommit, "selectedCommit");
    Objects.requireNonNull(fence, "fence");
    if (!intent.canonicalTenantId().equals(target.canonicalTenantId())
        || !intent.canonicalVersionId().equals(target.canonicalVersionId())
        || !target.equals(selectedCommit.target())
        || !intent.selectedCommitRequestId().equals(selectedCommit.requestId())
        || !intent.selectedCommitId().equals(selectedCommit.commitId())
        || !intent.selectedCommitDigest().equals(selectedCommit.digest())
        || !target.equals(fence.target())
        || !intent.selectedCommitRequestId().equals(fence.requestId())
        || !intent.selectedCommitId().equals(fence.commitId())
        || !intent.selectedCommitDigest().equals(fence.inputDigest())) {
      throw new IllegalArgumentException(
          "Authored Draft publication selection differs from its exact synchronized commit fence");
    }
    requireCanonicalArray(fence.resultVectorJson());
    this.fenceRequestId = fence.requestId();
    this.fenceCommitId = fence.commitId();
    this.fenceInputDigest = fence.inputDigest();
    this.fenceResultVectorJson = fence.resultVectorJson();
    this.fenceCreatedAt = fence.createdAt().toInstant().toString();
    try {
      canonicalBytes =
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(toJsonObject()));
      canonicalJson = new String(canonicalBytes, StandardCharsets.UTF_8);
      digest = sha256(canonicalBytes);
    } catch (IOException exception) {
      throw new IllegalArgumentException(
          "Authored Draft publication selection could not be canonicalized", exception);
    }
  }

  /**
   * Constructs the exact retained value after the repository has independently verified the locked
   * Version, durable commit, owner result vector, and current visibility fence.
   */
  public static AuthoredDraftPublishSelectionBinding capture(
      PublishIntent intent, TargetProof target, DraftCommitBinding binding, VisibilityFence fence) {
    return new AuthoredDraftPublishSelectionBinding(intent, target, binding, fence);
  }

  /** Reconstructs a retained selection and rejects malformed or noncanonical stored bytes. */
  public static AuthoredDraftPublishSelectionBinding fromStored(
      String storedJson, String storedDigest) {
    Objects.requireNonNull(storedJson, "storedJson");
    Objects.requireNonNull(storedDigest, "storedDigest");
    try {
      JsonNode root = JSON.readTree(storedJson);
      if (root == null || !root.isObject() || !"1".equals(requiredText(root, "schemaVersion"))) {
        throw new IllegalArgumentException("Unsupported authored Draft selection schema");
      }
      JsonNode intentNode = requiredObject(root, "intent");
      PublishIntent intent =
          new PublishIntent(
              UUID.fromString(requiredText(intentNode, "canonicalTenantId")),
              UUID.fromString(requiredText(intentNode, "canonicalVersionId")),
              requiredText(intentNode, "publishRequestId"),
              requiredText(intentNode, "expectedVersionStateEpoch"),
              requiredText(intentNode, "notes"),
              UUID.fromString(requiredText(intentNode, "selectedCommitRequestId")),
              UUID.fromString(requiredText(intentNode, "selectedCommitId")),
              requiredText(intentNode, "selectedCommitDigest"));
      JsonNode targetNode = requiredObject(root, "target");
      TargetProof target =
          new TargetProof(
              UUID.fromString(requiredText(targetNode, "canonicalTenantId")),
              UUID.fromString(requiredText(targetNode, "canonicalVersionId")),
              positiveLong(targetNode, "gameDesignVersionRowId"),
              requiredText(targetNode, "gameDesignVersionTenantKey"),
              positiveLong(targetNode, "sourceGameRowId"),
              requiredText(targetNode, "sourceGameTenantKey"),
              requiredText(targetNode, "sourceProvenanceKind"));
      DraftCommitBinding selectedCommit =
          DraftCommitBinding.fromStored(
              requiredText(root, "selectedCommitBindingJson"),
              requiredText(root, "selectedCommitDigest"));
      JsonNode fenceNode = requiredObject(root, "synchronizedFence");
      String fenceResultVectorJson = requiredText(fenceNode, "resultVectorJson");
      requireCanonicalArray(fenceResultVectorJson);
      VisibilityFence fence =
          new VisibilityFence(
              target,
              UUID.fromString(requiredText(fenceNode, "requestId")),
              UUID.fromString(requiredText(fenceNode, "commitId")),
              requiredText(fenceNode, "inputDigest"),
              fenceResultVectorJson,
              OffsetDateTime.parse(requiredText(fenceNode, "createdAt")));
      AuthoredDraftPublishSelectionBinding selection =
          new AuthoredDraftPublishSelectionBinding(intent, target, selectedCommit, fence);
      if (!selection.canonicalJson.equals(storedJson)
          || !selection.digest.equals(storedDigest)
          || !Arrays.equals(
              selection.canonicalBytes, storedJson.getBytes(StandardCharsets.UTF_8))) {
        throw new IllegalArgumentException(
            "Stored authored Draft publication selection is not exact canonical JSON");
      }
      return selection;
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Stored authored Draft selection is corrupt", exception);
    }
  }

  public PublishIntent intent() {
    return intent;
  }

  public TargetProof target() {
    return target;
  }

  public DraftCommitBinding selectedCommit() {
    return selectedCommit;
  }

  public UUID fenceRequestId() {
    return fenceRequestId;
  }

  public UUID fenceCommitId() {
    return fenceCommitId;
  }

  public String fenceInputDigest() {
    return fenceInputDigest;
  }

  public String fenceResultVectorJson() {
    return fenceResultVectorJson;
  }

  public String fenceCreatedAt() {
    return fenceCreatedAt;
  }

  public String canonicalJson() {
    return canonicalJson;
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public String digest() {
    return digest;
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || (other instanceof AuthoredDraftPublishSelectionBinding selection
            && digest.equals(selection.digest)
            && Arrays.equals(canonicalBytes, selection.canonicalBytes));
  }

  @Override
  public int hashCode() {
    return 31 * digest.hashCode() + Arrays.hashCode(canonicalBytes);
  }

  private Map<String, Object> toJsonObject() {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schemaVersion", "1");
    root.put("intent", intentJson(intent));
    root.put("target", targetJson(target));
    root.put("selectedCommitBindingJson", selectedCommit.canonicalJson());
    root.put("selectedCommitDigest", selectedCommit.digest());
    Map<String, Object> fence = new LinkedHashMap<>();
    fence.put("requestId", fenceRequestId.toString());
    fence.put("commitId", fenceCommitId.toString());
    fence.put("inputDigest", fenceInputDigest);
    fence.put("resultVectorJson", fenceResultVectorJson);
    fence.put("createdAt", fenceCreatedAt);
    root.put("synchronizedFence", fence);
    return root;
  }

  private static Map<String, Object> intentJson(PublishIntent intent) {
    Map<String, Object> object = new LinkedHashMap<>();
    object.put("canonicalTenantId", intent.canonicalTenantId().toString());
    object.put("canonicalVersionId", intent.canonicalVersionId().toString());
    object.put("publishRequestId", intent.publishRequestId());
    object.put("expectedVersionStateEpoch", intent.expectedVersionStateEpoch());
    object.put("notes", intent.notes());
    object.put("selectedCommitRequestId", intent.selectedCommitRequestId().toString());
    object.put("selectedCommitId", intent.selectedCommitId().toString());
    object.put("selectedCommitDigest", intent.selectedCommitDigest());
    return object;
  }

  private static Map<String, Object> targetJson(TargetProof target) {
    Map<String, Object> object = new LinkedHashMap<>();
    object.put("canonicalTenantId", target.canonicalTenantId().toString());
    object.put("canonicalVersionId", target.canonicalVersionId().toString());
    object.put("gameDesignVersionRowId", Long.toString(target.gameDesignVersionRowId()));
    object.put("gameDesignVersionTenantKey", target.gameDesignVersionTenantKey());
    object.put("sourceGameRowId", Long.toString(target.sourceGameRowId()));
    object.put("sourceGameTenantKey", target.sourceGameTenantKey());
    object.put("sourceProvenanceKind", target.sourceProvenanceKind());
    return object;
  }

  private static JsonNode requiredObject(JsonNode object, String field) {
    JsonNode value = object == null ? null : object.get(field);
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException(
          "Stored authored Draft selection is missing object field " + field);
    }
    return value;
  }

  private static String requiredText(JsonNode object, String field) {
    JsonNode value = object == null ? null : object.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(
          "Stored authored Draft selection is missing text field " + field);
    }
    return value.textValue();
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

  private static void requireCanonicalArray(String storedJson) {
    try {
      JsonNode node = JSON.readTree(storedJson);
      if (node == null || !node.isArray()) {
        throw new IllegalArgumentException("Stored owner result vector is not an array");
      }
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(storedJson);
      if (!Arrays.equals(canonical, storedJson.getBytes(StandardCharsets.UTF_8))) {
        throw new IllegalArgumentException("Stored owner result vector is not canonical JSON");
      }
    } catch (IOException exception) {
      throw new IllegalArgumentException("Stored owner result vector is malformed JSON", exception);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  /** Full caller-supplied intent for selecting one exact Draft commit to publish. */
  public record PublishIntent(
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      String publishRequestId,
      String expectedVersionStateEpoch,
      String notes,
      UUID selectedCommitRequestId,
      UUID selectedCommitId,
      String selectedCommitDigest) {
    public PublishIntent {
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      requireNonNil(canonicalVersionId, "canonicalVersionId");
      PublicationDigestRequestBinding.validatePublicationIdentity(
          canonicalTenantId.toString(), publishRequestId);
      requirePositiveCounter(expectedVersionStateEpoch, "expectedVersionStateEpoch");
      Objects.requireNonNull(notes, "notes");
      requireNonNil(selectedCommitRequestId, "selectedCommitRequestId");
      requireNonNil(selectedCommitId, "selectedCommitId");
      requireDigest(selectedCommitDigest, "selectedCommitDigest");
    }
  }

  private static void requirePositiveCounter(String value, String label) {
    Objects.requireNonNull(value, label);
    if (!value.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException(label + " must be a positive canonical decimal string");
    }
  }

  private static void requireDigest(String value, String label) {
    Objects.requireNonNull(value, label);
    if (!value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(label + " must be a canonical SHA-256 digest");
    }
  }

  /**
   * Exact previously captured synchronized fence; its structure does not authenticate its owner.
   */
  public record VisibilityFence(
      TargetProof target,
      UUID requestId,
      UUID commitId,
      String inputDigest,
      String resultVectorJson,
      OffsetDateTime createdAt) {}

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
    }
  }
}
