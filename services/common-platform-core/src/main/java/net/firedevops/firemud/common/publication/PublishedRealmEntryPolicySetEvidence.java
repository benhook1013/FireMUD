package net.firedevops.firemud.common.publication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.FrameReader;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Complete immutable policy source and original PUBLISHED terminal evidence; decoding proves
 * integrity only.
 */
public record PublishedRealmEntryPolicySetEvidence(
    TargetProof target,
    int versionNumber,
    UUID sourceCommitId,
    String sourceEpoch,
    String publishedReleaseBundleRef,
    String publishedReleaseBundleDigest,
    String publishWorkflowId,
    String manifestHash,
    long publicationVersionStateEpoch,
    byte[] operationBytes,
    byte[] captureBytes,
    byte[] terminalEvidenceBytes,
    int policyCount,
    String policySetDigest,
    List<PublishedRealmEntryPolicyEvidence> policies) {
  public static final String POLICY_DIGEST_SCHEMA = "game-design-published-realm-policy/v1";
  public static final String SET_DIGEST_SCHEMA = "game-design-published-realm-policy-set/v1";
  public static final String CARRIER_SCHEMA = "game-design-published-realm-policy-evidence/v1";
  public static final String CAPTURE_SCHEMA = "game-design-realm-policy-source-capture/v1";
  public static final String SNAPSHOT_SCHEMA = "game-design-realm-policy-snapshot/v1";
  public static final int MAX_POLICIES = 128;

  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public PublishedRealmEntryPolicySetEvidence {
    Objects.requireNonNull(target, "target");
    PublishedRealmEntryPolicyEvidence.requireNonNil(sourceCommitId, "sourceCommitId");
    if (versionNumber <= 0) {
      throw new IllegalArgumentException("Published policy version number must be positive");
    }
    if (sourceEpoch == null || !sourceEpoch.matches("0|[1-9][0-9]*")) {
      throw new IllegalArgumentException("Canonical captured source epoch required");
    }
    PublishedRealmEntryPolicyEvidence.requireText(
        publishedReleaseBundleRef, "publishedReleaseBundleRef");
    PublishedRealmEntryPolicyEvidence.requireDigest(
        publishedReleaseBundleDigest, "publishedReleaseBundleDigest");
    PublishedRealmEntryPolicyEvidence.requireText(publishWorkflowId, "publishWorkflowId");
    PublishedRealmEntryPolicyEvidence.requireDigest(manifestHash, "manifestHash");
    if (publicationVersionStateEpoch <= 0) {
      throw new IllegalArgumentException("Original publication epoch must be positive");
    }
    operationBytes = nonEmptyCopy(operationBytes, "operationBytes");
    captureBytes = nonEmptyCopy(captureBytes, "captureBytes");
    terminalEvidenceBytes = nonEmptyCopy(terminalEvidenceBytes, "terminalEvidenceBytes");
    policies = List.copyOf(Objects.requireNonNull(policies, "policies"));
    if (policyCount < 1 || policyCount > MAX_POLICIES || policyCount != policies.size()) {
      throw new IllegalArgumentException("Complete bounded policy-set cardinality required");
    }
    PublishedRealmEntryPolicyEvidence.requireDigest(policySetDigest, "policySetDigest");

    validatePublicationContext(
        target,
        versionNumber,
        sourceCommitId,
        sourceEpoch,
        publishedReleaseBundleRef,
        publishedReleaseBundleDigest,
        publishWorkflowId,
        manifestHash,
        publicationVersionStateEpoch,
        operationBytes,
        captureBytes,
        terminalEvidenceBytes,
        policies);
    if (!calculatePolicySetDigest(
            target,
            versionNumber,
            sourceCommitId,
            sourceEpoch,
            publishedReleaseBundleRef,
            publishedReleaseBundleDigest,
            publishWorkflowId,
            manifestHash,
            publicationVersionStateEpoch,
            operationBytes,
            captureBytes,
            terminalEvidenceBytes,
            policies)
        .equals(policySetDigest)) {
      throw new IllegalArgumentException("Published policy-set digest differs from evidence");
    }
    for (PublishedRealmEntryPolicyEvidence policy : policies) {
      if (!policy
          .policyDigest()
          .equals(
              PublishedRealmEntryPolicyEvidence.calculateDigest(
                  policy.policyId(),
                  target,
                  versionNumber,
                  publishedReleaseBundleRef,
                  publishedReleaseBundleDigest,
                  publishWorkflowId,
                  manifestHash,
                  policy.sourceCommitId(),
                  policy.sourceRevisionId(),
                  policy.logicalRevisionId(),
                  policy.policy()))) {
        throw new IllegalArgumentException("Published policy digest differs from evidence");
      }
    }
  }

  @Override
  public byte[] operationBytes() {
    return operationBytes.clone();
  }

  @Override
  public byte[] captureBytes() {
    return captureBytes.clone();
  }

  @Override
  public byte[] terminalEvidenceBytes() {
    return terminalEvidenceBytes.clone();
  }

  @Override
  public List<PublishedRealmEntryPolicyEvidence> policies() {
    return List.copyOf(policies);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof PublishedRealmEntryPolicySetEvidence set)) return false;
    return versionNumber == set.versionNumber
        && publicationVersionStateEpoch == set.publicationVersionStateEpoch
        && policyCount == set.policyCount
        && target.equals(set.target)
        && sourceCommitId.equals(set.sourceCommitId)
        && sourceEpoch.equals(set.sourceEpoch)
        && publishedReleaseBundleRef.equals(set.publishedReleaseBundleRef)
        && publishedReleaseBundleDigest.equals(set.publishedReleaseBundleDigest)
        && publishWorkflowId.equals(set.publishWorkflowId)
        && manifestHash.equals(set.manifestHash)
        && policySetDigest.equals(set.policySetDigest)
        && Arrays.equals(operationBytes, set.operationBytes)
        && Arrays.equals(captureBytes, set.captureBytes)
        && Arrays.equals(terminalEvidenceBytes, set.terminalEvidenceBytes)
        && policies.equals(set.policies);
  }

  @Override
  public int hashCode() {
    int hash =
        Objects.hash(
            target,
            versionNumber,
            sourceCommitId,
            sourceEpoch,
            publishedReleaseBundleRef,
            publishedReleaseBundleDigest,
            publishWorkflowId,
            manifestHash,
            publicationVersionStateEpoch,
            policyCount,
            policySetDigest,
            policies);
    hash = 31 * hash + Arrays.hashCode(operationBytes);
    hash = 31 * hash + Arrays.hashCode(captureBytes);
    return 31 * hash + Arrays.hashCode(terminalEvidenceBytes);
  }

  /** Exact RFC 8785 JSON representation used for canonical shared storage or handoff. */
  public String canonicalJson() {
    return new String(canonicalBytes(), StandardCharsets.UTF_8);
  }

  /** Returns a defensive copy of the exact canonical carrier bytes. */
  public byte[] canonicalBytes() {
    return canonicalize(toJsonObject());
  }

  /** Strictly decodes the complete owner evidence and rejects unknown or noncanonical bytes. */
  public static PublishedRealmEntryPolicySetEvidence fromStored(byte[] stored) {
    Objects.requireNonNull(stored, "stored");
    try {
      String json = strictUtf8(stored);
      if (!Arrays.equals(stored, Rfc8785CanonicalJson.canonicalizeUtf8(json))) {
        throw new IllegalArgumentException("Stored policy evidence is not RFC 8785 canonical JSON");
      }
      JsonNode root = JSON.readTree(json);
      requireFields(
          root,
          "schema",
          "target",
          "versionNumber",
          "sourceCommitId",
          "sourceEpoch",
          "publishedReleaseBundleRef",
          "publishedReleaseBundleDigest",
          "publishWorkflowId",
          "manifestHash",
          "publicationVersionStateEpoch",
          "operationBytesBase64",
          "captureBytesBase64",
          "terminalEvidenceBytesBase64",
          "policyCount",
          "policySetDigest",
          "policies");
      if (!CARRIER_SCHEMA.equals(requiredText(root, "schema"))) {
        throw new IllegalArgumentException("Unsupported published policy evidence schema");
      }
      TargetProof target = parseTarget(requiredObject(root, "target"));
      int versionNumber = positiveInt(root, "versionNumber");
      UUID sourceCommitId = canonicalUuid(requiredText(root, "sourceCommitId"));
      String sourceEpoch = requiredText(root, "sourceEpoch");
      String releaseRef = requiredText(root, "publishedReleaseBundleRef");
      String releaseDigest = requiredText(root, "publishedReleaseBundleDigest");
      String workflowId = requiredText(root, "publishWorkflowId");
      String manifestHash = requiredText(root, "manifestHash");
      long publicationEpoch = positiveLongString(root, "publicationVersionStateEpoch");
      byte[] operationBytes = requiredBase64(root, "operationBytesBase64");
      byte[] captureBytes = requiredBase64(root, "captureBytesBase64");
      byte[] terminalBytes = requiredBase64(root, "terminalEvidenceBytesBase64");
      int policyCount = positiveInt(root, "policyCount");
      String setDigest = requiredText(root, "policySetDigest");
      JsonNode policyNodes = requiredArray(root, "policies");
      List<PublishedRealmEntryPolicyEvidence> policies = new ArrayList<>(policyNodes.size());
      for (JsonNode node : policyNodes) {
        requireFields(
            node,
            "policyId",
            "sourceCommitId",
            "sourceRevisionId",
            "logicalRevisionId",
            "policy",
            "policyDigest");
        String policyJson = canonicalJson(node.get("policy"));
        policies.add(
            new PublishedRealmEntryPolicyEvidence(
                canonicalUuid(requiredText(node, "policyId")),
                canonicalUuid(requiredText(node, "sourceCommitId")),
                canonicalUuid(requiredText(node, "sourceRevisionId")),
                requiredText(node, "logicalRevisionId"),
                RealmEntryPolicy.parseCanonical(policyJson, JSON),
                requiredText(node, "policyDigest")));
      }
      var result =
          new PublishedRealmEntryPolicySetEvidence(
              target,
              versionNumber,
              sourceCommitId,
              sourceEpoch,
              releaseRef,
              releaseDigest,
              workflowId,
              manifestHash,
              publicationEpoch,
              operationBytes,
              captureBytes,
              terminalBytes,
              policyCount,
              setDigest,
              policies);
      if (!Arrays.equals(stored, result.canonicalBytes())) {
        throw new IllegalArgumentException("Stored published policy evidence changed on readback");
      }
      return result;
    } catch (IOException | RuntimeException invalid) {
      if (invalid instanceof IllegalArgumentException argument) throw argument;
      throw new IllegalArgumentException("Stored published policy evidence is invalid", invalid);
    }
  }

  /** Verifies the set and all child digests against the retained immutable evidence. */
  public boolean hasValidDigest() {
    try {
      return calculatePolicySetDigest(
                  target,
                  versionNumber,
                  sourceCommitId,
                  sourceEpoch,
                  publishedReleaseBundleRef,
                  publishedReleaseBundleDigest,
                  publishWorkflowId,
                  manifestHash,
                  publicationVersionStateEpoch,
                  operationBytes,
                  captureBytes,
                  terminalEvidenceBytes,
                  policies)
              .equals(policySetDigest)
          && policies.stream().allMatch(policy -> policy.hasValidDigest(this));
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  public PublishedRealmEntryPolicySetEvidence requireValidDigest() {
    if (!hasValidDigest()) {
      throw new IllegalArgumentException(
          "Published realm-entry policy-set digest differs from evidence");
    }
    return this;
  }

  /** Exact current Game Design policy digest preimage and RFC 8785/SHA-256 encoding. */
  public static String policyDigest(
      UUID policyId,
      TargetProof target,
      int versionNumber,
      String publishedReleaseBundleRef,
      String publishedReleaseBundleDigest,
      String publishWorkflowId,
      String manifestHash,
      UUID sourceCommitId,
      UUID sourceRevisionId,
      String logicalRevisionId,
      RealmEntryPolicy policy) {
    return PublishedRealmEntryPolicyEvidence.calculateDigest(
        policyId,
        target,
        versionNumber,
        publishedReleaseBundleRef,
        publishedReleaseBundleDigest,
        publishWorkflowId,
        manifestHash,
        sourceCommitId,
        sourceRevisionId,
        logicalRevisionId,
        policy);
  }

  /** Exact current Game Design complete-set digest preimage and RFC 8785/SHA-256 encoding. */
  public static String calculatePolicySetDigest(
      TargetProof target,
      int versionNumber,
      UUID sourceCommitId,
      String sourceEpoch,
      String publishedReleaseBundleRef,
      String publishedReleaseBundleDigest,
      String publishWorkflowId,
      String manifestHash,
      long publicationVersionStateEpoch,
      byte[] operationBytes,
      byte[] captureBytes,
      byte[] terminalEvidenceBytes,
      List<PublishedRealmEntryPolicyEvidence> policies) {
    Objects.requireNonNull(policies, "policies");
    List<PublishedRealmEntryPolicyEvidence> exactPolicies = List.copyOf(policies);
    requirePolicySetSemantics(exactPolicies);
    List<PublishedRealmEntryPolicyEvidence> orderedPolicies =
        exactPolicies.stream()
            .sorted(
                java.util.Comparator.comparing(
                        (PublishedRealmEntryPolicyEvidence policy) -> policy.policy().worldSlug())
                    .thenComparing(policy -> policy.policy().realmSlug()))
            .toList();
    List<Map<String, Object>> orderedEvidence =
        orderedPolicies.stream()
            .map(
                policy -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("policyId", policy.policyId().toString());
                  row.put("sourceCommitId", policy.sourceCommitId().toString());
                  row.put("sourceRevisionId", policy.sourceRevisionId().toString());
                  row.put("logicalRevisionId", policy.logicalRevisionId());
                  row.put("worldSlug", policy.policy().worldSlug());
                  row.put("realmSlug", policy.policy().realmSlug());
                  row.put("policyDigest", policy.policyDigest());
                  return row;
                })
            .toList();
    Map<String, Object> preimage = new LinkedHashMap<>();
    preimage.put("schema", SET_DIGEST_SCHEMA);
    preimage.put("target", targetProofObject(target));
    preimage.put("versionNumber", versionNumber);
    preimage.put("sourceCommitId", sourceCommitId.toString());
    preimage.put("sourceEpoch", sourceEpoch);
    preimage.put("publishedReleaseBundleRef", publishedReleaseBundleRef);
    preimage.put("publishedReleaseBundleDigest", publishedReleaseBundleDigest);
    preimage.put("publishWorkflowId", publishWorkflowId);
    preimage.put("manifestHash", manifestHash);
    preimage.put("publicationVersionStateEpoch", Long.toString(publicationVersionStateEpoch));
    preimage.put("operationDigest", digestBytes(operationBytes));
    preimage.put("captureDigest", digestBytes(captureBytes));
    preimage.put("terminalEvidenceDigest", digestBytes(terminalEvidenceBytes));
    preimage.put("policyCount", orderedEvidence.size());
    preimage.put("policies", orderedEvidence);
    return PublishedRealmEntryPolicyEvidence.sha256(canonicalize(preimage));
  }

  /** Exact shared TargetProof representation used by both local owner digest preimages. */
  public static String targetProofJson(TargetProof target) {
    return new String(canonicalize(targetProofObject(target)), StandardCharsets.UTF_8);
  }

  static Map<String, Object> targetProofObject(TargetProof target) {
    Objects.requireNonNull(target, "target");
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("canonicalTenantId", target.canonicalTenantId().toString());
    value.put("canonicalVersionId", target.canonicalVersionId().toString());
    value.put("gameDesignVersionRowId", Long.toString(target.gameDesignVersionRowId()));
    value.put("gameDesignVersionTenantKey", target.gameDesignVersionTenantKey());
    value.put("sourceGameRowId", Long.toString(target.sourceGameRowId()));
    value.put("sourceGameTenantKey", target.sourceGameTenantKey());
    value.put("sourceProvenanceKind", target.sourceProvenanceKind());
    return value;
  }

  private static void validatePublicationContext(
      TargetProof target,
      int versionNumber,
      UUID sourceCommitId,
      String sourceEpoch,
      String publishedReleaseBundleRef,
      String publishedReleaseBundleDigest,
      String publishWorkflowId,
      String manifestHash,
      long publicationVersionStateEpoch,
      byte[] operationBytes,
      byte[] captureBytes,
      byte[] terminalEvidenceBytes,
      List<PublishedRealmEntryPolicyEvidence> policies) {
    var operation = GameDesignPublicationOperationBinding.fromStored(operationBytes);
    var selection = operation.account().input().selection();
    if (!selection.target().equals(target)
        || !selection.selectedCommit().commitId().equals(sourceCommitId)
        || !operation.world().request().publishWorkflowId().equals(publishWorkflowId)) {
      throw new IllegalArgumentException(
          "Publication operation differs from exact TargetProof or source");
    }

    CapturedSource captured = parseCapture(captureBytes, operationBytes);
    if (!captured.target().equals(target)
        || !captured.binding().equals(selection.selectedCommit())
        || !captured.binding().commitId().equals(sourceCommitId)
        || !captured.sourceEpoch().equals(sourceEpoch)
        || !captured.policies().equals(policies.stream().map(PolicySource::from).toList())) {
      throw new IllegalArgumentException(
          "Captured source differs from selected synchronized policy set");
    }

    var terminal = GameDesignPublicationTerminalEvidence.fromStored(terminalEvidenceBytes);
    if (terminal.outcome() != GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
        || !Arrays.equals(terminal.operationBytes(), operationBytes)) {
      throw new IllegalArgumentException(
          "Original PUBLISHED terminal differs from operation bytes");
    }
    var release = terminal.releaseContent();
    if (!release.canonicalTenantId().equals(target.canonicalTenantId())
        || !release.canonicalVersionId().equals(target.canonicalVersionId())
        || release.versionNumber() != versionNumber
        || !release.publishedReleaseBundleRef().equals(publishedReleaseBundleRef)
        || !release.contentDigest().equals(publishedReleaseBundleDigest)
        || !release.publishWorkflowId().equals(publishWorkflowId)
        || !release.manifestHash().equals(manifestHash)
        || terminal.publicationVersionStateEpoch() != publicationVersionStateEpoch) {
      throw new IllegalArgumentException("Actual sealed release identity differs from policy set");
    }
    requireCanonicalPolicyOrder(policies);
  }

  private static CapturedSource parseCapture(byte[] bytes, byte[] expectedOperationBytes) {
    FrameReader capture = new FrameReader(bytes);
    capture.expect(CAPTURE_SCHEMA);
    byte[] operationBytes = capture.bytes();
    byte[] snapshotBytes = capture.bytes();
    capture.requireEnd();
    if (!Arrays.equals(operationBytes, expectedOperationBytes)
        || !Arrays.equals(
            operationBytes,
            GameDesignPublicationOperationBinding.fromStored(operationBytes).canonicalBytes())) {
      throw new IllegalArgumentException("Captured source operation bytes changed");
    }
    String snapshotJson = strictUtf8(snapshotBytes);
    try {
      if (!Arrays.equals(snapshotBytes, Rfc8785CanonicalJson.canonicalizeUtf8(snapshotJson))) {
        throw new IllegalArgumentException("Captured policy snapshot is not canonical JSON");
      }
      JsonNode snapshot = JSON.readTree(snapshotJson);
      requireFields(snapshot, "schema", "bindingJson", "bindingDigest", "sourceEpoch", "policies");
      if (!SNAPSHOT_SCHEMA.equals(requiredText(snapshot, "schema"))) {
        throw new IllegalArgumentException("Unsupported captured policy snapshot schema");
      }
      DraftCommitBinding binding =
          DraftCommitBinding.fromStored(
              requiredText(snapshot, "bindingJson"), requiredText(snapshot, "bindingDigest"));
      String sourceEpoch = requiredText(snapshot, "sourceEpoch");
      JsonNode policyNodes = requiredArray(snapshot, "policies");
      if (policyNodes.size() == 0 || policyNodes.size() > MAX_POLICIES) {
        throw new IllegalArgumentException("Complete nonempty captured policy source required");
      }
      List<PolicySource> policies = new ArrayList<>(policyNodes.size());
      for (JsonNode node : policyNodes) {
        requireFields(node, "commitId", "revisionId", "logicalRevisionId", "policy");
        policies.add(
            new PolicySource(
                canonicalUuid(requiredText(node, "commitId")),
                canonicalUuid(requiredText(node, "revisionId")),
                requiredText(node, "logicalRevisionId"),
                RealmEntryPolicy.parseCanonical(canonicalJson(node.get("policy")), JSON)));
      }
      validatePolicySources(policies);
      return new CapturedSource(binding.target(), binding, sourceEpoch, List.copyOf(policies));
    } catch (IOException | RuntimeException invalid) {
      if (invalid instanceof IllegalArgumentException argument) throw argument;
      throw new IllegalArgumentException("Captured policy source is invalid", invalid);
    }
  }

  private static void requireCanonicalPolicyOrder(
      List<PublishedRealmEntryPolicyEvidence> policies) {
    requirePolicySetSemantics(policies);
    String previousWorld = null;
    String previousRealm = null;
    for (PublishedRealmEntryPolicyEvidence evidence : policies) {
      String world = evidence.policy().worldSlug();
      String realm = evidence.policy().realmSlug();
      if (previousWorld != null
          && (previousWorld.compareTo(world) > 0
              || (previousWorld.equals(world) && previousRealm.compareTo(realm) >= 0))) {
        throw new IllegalArgumentException("Published policy selector order is not canonical");
      }
      previousWorld = world;
      previousRealm = realm;
    }
  }

  private static void requirePolicySetSemantics(List<PublishedRealmEntryPolicyEvidence> policies) {
    if (policies.isEmpty() || policies.size() > MAX_POLICIES) {
      throw new IllegalArgumentException("Complete policy set must contain 1 through 128 rows");
    }
    var realmSlugs = new HashSet<String>();
    var revisions = new HashSet<UUID>();
    var policyIds = new HashSet<UUID>();
    int visiblePublicProduction = 0;
    for (PublishedRealmEntryPolicyEvidence evidence : policies) {
      Objects.requireNonNull(evidence, "policy evidence");
      if (!realmSlugs.add(evidence.policy().realmSlug())) {
        throw new IllegalArgumentException("Tenant-wide realm selector is duplicated");
      }
      if (!revisions.add(evidence.sourceRevisionId())) {
        throw new IllegalArgumentException("Authored policy revision is duplicated");
      }
      if (!policyIds.add(evidence.policyId())) {
        throw new IllegalArgumentException("Allocated policy identity is duplicated");
      }
      if (evidence.policy().visible() && evidence.policy().publicProduction()) {
        visiblePublicProduction++;
      }
    }
    if (visiblePublicProduction != 1) {
      throw new IllegalArgumentException("Exactly one visible public-production realm is required");
    }
  }

  private static void validatePolicySources(List<PolicySource> policies) {
    var realms = new HashSet<String>();
    var revisions = new HashSet<UUID>();
    String previousWorld = null;
    String previousRealm = null;
    for (PolicySource policy : policies) {
      if (!realms.add(policy.policy().realmSlug()) || !revisions.add(policy.sourceRevisionId())) {
        throw new IllegalArgumentException("Captured source repeats a selector or revision");
      }
      String world = policy.policy().worldSlug();
      String realm = policy.policy().realmSlug();
      if (previousWorld != null
          && (previousWorld.compareTo(world) > 0
              || (previousWorld.equals(world) && previousRealm.compareTo(realm) >= 0))) {
        throw new IllegalArgumentException("Captured source policy order is not canonical");
      }
      previousWorld = world;
      previousRealm = realm;
    }
  }

  private Map<String, Object> toJsonObject() {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schema", CARRIER_SCHEMA);
    root.put("target", targetProofObject(target));
    root.put("versionNumber", versionNumber);
    root.put("sourceCommitId", sourceCommitId.toString());
    root.put("sourceEpoch", sourceEpoch);
    root.put("publishedReleaseBundleRef", publishedReleaseBundleRef);
    root.put("publishedReleaseBundleDigest", publishedReleaseBundleDigest);
    root.put("publishWorkflowId", publishWorkflowId);
    root.put("manifestHash", manifestHash);
    root.put("publicationVersionStateEpoch", Long.toString(publicationVersionStateEpoch));
    root.put("operationBytesBase64", Base64.getEncoder().encodeToString(operationBytes));
    root.put("captureBytesBase64", Base64.getEncoder().encodeToString(captureBytes));
    root.put(
        "terminalEvidenceBytesBase64", Base64.getEncoder().encodeToString(terminalEvidenceBytes));
    root.put("policyCount", policyCount);
    root.put("policySetDigest", policySetDigest);
    root.put(
        "policies",
        policies.stream()
            .map(
                evidence -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("policyId", evidence.policyId().toString());
                  row.put("sourceCommitId", evidence.sourceCommitId().toString());
                  row.put("sourceRevisionId", evidence.sourceRevisionId().toString());
                  row.put("logicalRevisionId", evidence.logicalRevisionId());
                  try {
                    row.put("policy", JSON.readTree(evidence.policy().canonicalJson()));
                  } catch (RuntimeException impossible) {
                    throw new IllegalStateException(
                        "Canonical realm policy became invalid", impossible);
                  }
                  row.put("policyDigest", evidence.policyDigest());
                  return row;
                })
            .toList());
    return root;
  }

  private static byte[] nonEmptyCopy(byte[] value, String name) {
    if (value == null || value.length == 0) {
      throw new IllegalArgumentException("Nonempty " + name + " required");
    }
    return value.clone();
  }

  private static String digestBytes(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw new IllegalArgumentException("Nonempty immutable source bytes required for digest");
    }
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static byte[] canonicalize(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException invalid) {
      throw new IllegalArgumentException(
          "Published policy evidence could not be canonicalized", invalid);
    }
  }

  private static String canonicalJson(JsonNode node) {
    if (node == null) throw new IllegalArgumentException("Policy JSON object is required");
    return new String(canonicalize(node), StandardCharsets.UTF_8);
  }

  private static TargetProof parseTarget(JsonNode node) {
    requireFields(
        node,
        "canonicalTenantId",
        "canonicalVersionId",
        "gameDesignVersionRowId",
        "gameDesignVersionTenantKey",
        "sourceGameRowId",
        "sourceGameTenantKey",
        "sourceProvenanceKind");
    return new TargetProof(
        canonicalUuid(requiredText(node, "canonicalTenantId")),
        canonicalUuid(requiredText(node, "canonicalVersionId")),
        positiveLongString(node, "gameDesignVersionRowId"),
        requiredText(node, "gameDesignVersionTenantKey"),
        positiveLongString(node, "sourceGameRowId"),
        requiredText(node, "sourceGameTenantKey"),
        requiredText(node, "sourceProvenanceKind"));
  }

  private static UUID canonicalUuid(String value) {
    UUID parsed = UUID.fromString(value);
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException("UUID text is not canonical");
    }
    return parsed;
  }

  private static String requiredText(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("Text value required: " + name);
    }
    return value.asText();
  }

  private static int positiveInt(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.intValue() <= 0) {
      throw new IllegalArgumentException("Positive integer required: " + name);
    }
    return value.intValue();
  }

  private static long positiveLongString(JsonNode node, String name) {
    String value = requiredText(node, name);
    if (!value.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException("Canonical positive decimal string required: " + name);
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("Counter out of range: " + name, invalid);
    }
  }

  private static byte[] requiredBase64(JsonNode node, String name) {
    String value = requiredText(node, name);
    try {
      byte[] decoded = Base64.getDecoder().decode(value);
      if (!Base64.getEncoder().encodeToString(decoded).equals(value)) {
        throw new IllegalArgumentException("Noncanonical Base64 value: " + name);
      }
      return decoded;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Invalid Base64 value: " + name, invalid);
    }
  }

  private static JsonNode requiredObject(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("Object value required: " + name);
    }
    return value;
  }

  private static JsonNode requiredArray(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isArray()) {
      throw new IllegalArgumentException("Array value required: " + name);
    }
    return value;
  }

  private static void requireFields(JsonNode node, String... expected) {
    if (node == null || !node.isObject() || node.size() != expected.length) {
      throw new IllegalArgumentException("Closed canonical evidence fields required");
    }
    for (String name : expected) {
      if (!node.has(name)) {
        throw new IllegalArgumentException("Missing canonical evidence field: " + name);
      }
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
    } catch (CharacterCodingException invalid) {
      throw new IllegalArgumentException("Evidence bytes are not valid UTF-8", invalid);
    }
  }

  private record PolicySource(
      UUID sourceCommitId,
      UUID sourceRevisionId,
      String logicalRevisionId,
      RealmEntryPolicy policy) {
    private static PolicySource from(PublishedRealmEntryPolicyEvidence policy) {
      return new PolicySource(
          policy.sourceCommitId(),
          policy.sourceRevisionId(),
          policy.logicalRevisionId(),
          policy.policy());
    }
  }

  private record CapturedSource(
      TargetProof target,
      DraftCommitBinding binding,
      String sourceEpoch,
      List<PolicySource> policies) {}
}
