package net.firedevops.firemud.common.gamedesign;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Closed read evidence for one exact durable synchronized Game Design Draft visibility fence. */
public final class DraftSynchronizedVisibilityEvidence {
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Complete Game Design target selector, plus a distinct caller-owned read identity. */
  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      DraftCommitBinding.TargetProof target) {
    public Request {
      if (schemaVersion != SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported synchronized visibility schema");
      }
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("A canonical target namespace is required");
      }
      requireNonNil(readRequestId, "readRequestId");
      Objects.requireNonNull(target, "target");
    }
  }

  /** Exact immutable fence tuple as retained by the Game Design coordinator. */
  public record Fence(
      UUID requestId,
      UUID commitId,
      String inputDigest,
      String resultVectorJson,
      OffsetDateTime createdAt) {
    public Fence {
      requireNonNil(requestId, "fence requestId");
      requireNonNil(commitId, "fence commitId");
      requireDigest(inputDigest, "fence inputDigest");
      Objects.requireNonNull(resultVectorJson, "resultVectorJson");
      Objects.requireNonNull(createdAt, "createdAt");
    }
  }

  /** One exact APPLIED owner result decoded from the coordinator's immutable result vector. */
  public static final class AppliedOwnerResult {
    private final Owner owner;
    private final UUID commitId;
    private final String bindingDigest;
    private final String resultIdentity;
    private final byte[] resultBytes;
    private final List<AppliedEpoch> appliedEpochs;

    private AppliedOwnerResult(
        Owner owner,
        UUID commitId,
        String bindingDigest,
        String resultIdentity,
        byte[] resultBytes,
        List<AppliedEpoch> appliedEpochs) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.commitId = Objects.requireNonNull(commitId, "commitId");
      this.bindingDigest = Objects.requireNonNull(bindingDigest, "bindingDigest");
      this.resultIdentity = Objects.requireNonNull(resultIdentity, "resultIdentity");
      this.resultBytes = Objects.requireNonNull(resultBytes, "resultBytes").clone();
      this.appliedEpochs = List.copyOf(appliedEpochs);
    }

    public Owner owner() {
      return owner;
    }

    public UUID commitId() {
      return commitId;
    }

    public String bindingDigest() {
      return bindingDigest;
    }

    public String resultIdentity() {
      return resultIdentity;
    }

    public byte[] resultBytes() {
      return resultBytes.clone();
    }

    public List<AppliedEpoch> appliedEpochs() {
      return appliedEpochs;
    }
  }

  public record AppliedEpoch(
      String aggregateType,
      String aggregateId,
      String scopeType,
      String scopeId,
      String expectedEpoch,
      String resultingEpoch) {
    public AppliedEpoch {
      Objects.requireNonNull(aggregateType, "aggregateType");
      Objects.requireNonNull(aggregateId, "aggregateId");
      Objects.requireNonNull(scopeType, "scopeType");
      Objects.requireNonNull(scopeId, "scopeId");
      requireCounter(expectedEpoch, "expectedEpoch");
      requireCounter(resultingEpoch, "resultingEpoch");
    }
  }

  private final Request request;
  private final DraftCommitBinding binding;
  private final String workflowState;
  private final Fence fence;
  private final List<AppliedOwnerResult> appliedOwnerResults;

  public DraftSynchronizedVisibilityEvidence(
      Request request, DraftCommitBinding binding, String workflowState, Fence fence) {
    this.request = Objects.requireNonNull(request, "request");
    this.binding = Objects.requireNonNull(binding, "binding");
    this.workflowState = Objects.requireNonNull(workflowState, "workflowState");
    this.fence = Objects.requireNonNull(fence, "fence");
    this.appliedOwnerResults = validateAndReadOwnerResults();
  }

  public Request request() {
    return request;
  }

  public DraftCommitBinding binding() {
    return binding;
  }

  public String workflowState() {
    return workflowState;
  }

  public Fence fence() {
    return fence;
  }

  public List<AppliedOwnerResult> appliedOwnerResults() {
    return List.copyOf(appliedOwnerResults);
  }

  public AppliedOwnerResult appliedOwnerResult(Owner owner) {
    Objects.requireNonNull(owner, "owner");
    return appliedOwnerResults.stream()
        .filter(result -> result.owner() == owner)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Owner is not in the applied fence"));
  }

  /** Revalidates every redundant carrier before this evidence is used as a selector. */
  public void requireValid() {
    validateAndReadOwnerResults();
  }

  private List<AppliedOwnerResult> validateAndReadOwnerResults() {
    if (!binding.target().equals(request.target())
        || !binding.requiredOwners().contains(Owner.WORLD_MANAGEMENT)
        || request.readRequestId().equals(binding.requestId())
        || request.readRequestId().equals(binding.commitId())) {
      throw new IllegalArgumentException(
          "Synchronized visibility request and complete Draft target differ");
    }
    if (!"SYNCHRONIZED".equals(workflowState)
        || !fence.requestId().equals(binding.requestId())
        || !fence.commitId().equals(binding.commitId())
        || !fence.inputDigest().equals(binding.digest())) {
      throw new IllegalArgumentException(
          "Draft visibility fence is not bound to this synchronized full input");
    }

    byte[] supplied = fence.resultVectorJson().getBytes(StandardCharsets.UTF_8);
    byte[] canonical;
    JsonNode vector;
    try {
      canonical = Rfc8785CanonicalJson.canonicalizeUtf8(fence.resultVectorJson());
      vector = JSON.readTree(fence.resultVectorJson());
    } catch (IOException | RuntimeException exception) {
      throw new IllegalArgumentException("Draft result vector is not valid canonical JSON", exception);
    }
    if (!java.util.Arrays.equals(supplied, canonical)) {
      throw new IllegalArgumentException("Draft result vector is not exact canonical JSON");
    }
    if (vector == null || !vector.isArray() || vector.size() != binding.requiredOwners().size()) {
      throw new IllegalArgumentException("Draft result vector is incomplete");
    }

    List<AppliedOwnerResult> results = new ArrayList<>();
    Set<Owner> seen = new HashSet<>();
    for (int index = 0; index < vector.size(); index++) {
      JsonNode item = vector.get(index);
      requireFields(
          item,
          Set.of(
              "owner",
              "status",
              "commitId",
              "bindingDigest",
              "resultIdentity",
              "resultBytesBase64",
              "appliedEpochs"));
      Owner owner = enumText(item, "owner", Owner.class);
      if (owner != binding.requiredOwners().get(index) || !seen.add(owner)) {
        throw new IllegalArgumentException("Draft owner result order/set differs from binding");
      }
      if (!"APPLIED".equals(text(item, "status"))) {
        throw new IllegalArgumentException("Only terminal APPLIED owner results are visible");
      }
      UUID commitId = parseCanonicalUuid(text(item, "commitId"), "commitId");
      String bindingDigest = text(item, "bindingDigest");
      requireDigest(bindingDigest, "owner bindingDigest");
      String resultIdentity = text(item, "resultIdentity");
      if (resultIdentity.isBlank() || !resultIdentity.equals(resultIdentity.trim())) {
        throw new IllegalArgumentException("Owner result identity must be exact nonblank text");
      }
      String bytesBase64 = text(item, "resultBytesBase64");
      byte[] resultBytes;
      try {
        resultBytes = Base64.getDecoder().decode(bytesBase64);
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException("Owner result bytes must be canonical Base64", exception);
      }
      if (!Base64.getEncoder().encodeToString(resultBytes).equals(bytesBase64)) {
        throw new IllegalArgumentException("Owner result bytes must be canonical Base64");
      }
      if (!binding.commitId().equals(commitId) || !binding.digest().equals(bindingDigest)) {
        throw new IllegalArgumentException("Owner result is bound to different Draft input");
      }
      List<AppliedEpoch> epochs = parseEpochs(item.get("appliedEpochs"), binding, owner);
      results.add(
          new AppliedOwnerResult(
              owner, commitId, bindingDigest, resultIdentity, resultBytes, epochs));
    }
    return List.copyOf(results);
  }

  private static List<AppliedEpoch> parseEpochs(
      JsonNode vector, DraftCommitBinding binding, Owner owner) {
    List<AffectedUnit> expected = binding.affectedUnits(owner);
    if (vector == null || !vector.isArray() || vector.size() != expected.size()) {
      throw new IllegalArgumentException("Applied owner epoch vector is incomplete");
    }
    List<AppliedEpoch> epochs = new ArrayList<>();
    for (int index = 0; index < vector.size(); index++) {
      JsonNode item = vector.get(index);
      requireFields(
          item,
          Set.of(
              "aggregateType",
              "aggregateId",
              "scopeType",
              "scopeId",
              "expectedEpoch",
              "resultingEpoch"));
      AppliedEpoch epoch =
          new AppliedEpoch(
              text(item, "aggregateType"),
              text(item, "aggregateId"),
              text(item, "scopeType"),
              text(item, "scopeId"),
              text(item, "expectedEpoch"),
              text(item, "resultingEpoch"));
      AffectedUnit unit = expected.get(index);
      if (!unit.aggregateType().equals(epoch.aggregateType())
          || !unit.aggregateId().equals(epoch.aggregateId())
          || !unit.scopeType().equals(epoch.scopeType())
          || !unit.scopeId().equals(epoch.scopeId())
          || !unit.expectedEpoch().equals(epoch.expectedEpoch())
          || new BigInteger(epoch.resultingEpoch())
                  .compareTo(new BigInteger(epoch.expectedEpoch()))
              <= 0) {
        throw new IllegalArgumentException(
            "Applied owner tuple differs from the complete bound epoch declaration");
      }
      epochs.add(epoch);
    }
    return List.copyOf(epochs);
  }

  private static void requireFields(JsonNode node, Set<String> fields) {
    if (node == null
        || !node.isObject()
        || node.size() != fields.size()
        || node.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))) {
      throw new IllegalArgumentException("Draft owner result contains missing or unknown fields");
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("Draft owner result text field is missing: " + field);
    }
    return value.textValue();
  }

  private static <T extends Enum<T>> T enumText(JsonNode node, String field, Class<T> type) {
    try {
      return Enum.valueOf(type, text(node, field));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Draft owner result enum is unsupported: " + field, exception);
    }
  }

  private static UUID parseCanonicalUuid(String value, String label) {
    try {
      UUID parsed = UUID.fromString(value);
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException("Canonical non-nil " + label + " is required");
      }
      return parsed;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required", exception);
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
  }

  private static void requireDigest(String value, String label) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " must be canonical SHA-256 text");
    }
  }

  private static void requireCounter(String value, String label) {
    if (value == null || !value.matches("0|[1-9][0-9]*")) {
      throw new IllegalArgumentException(label + " must be a canonical nonnegative decimal");
    }
  }
}
