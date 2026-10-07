package net.firedevops.firemud.gamedesign.publication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;

/** Immutable internal evidence associating a frozen source set with its actual sealed release. */
public final class RealmPolicyPublishedEvidence {
  public static final String SET_DIGEST_SCHEMA = "game-design-published-realm-policy-set/v1";
  public static final String POLICY_DIGEST_SCHEMA = "game-design-published-realm-policy/v1";

  private RealmPolicyPublishedEvidence() {}

  /** One Game Design allocated immutable identity for one effective authored policy. */
  public record Policy(UUID policyId, RealmPolicySource.Policy source, String policyDigest) {
    public Policy {
      nonNil(policyId, "policyId");
      Objects.requireNonNull(source, "source");
      if (policyDigest == null || !policyDigest.matches("sha256:[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Canonical policy digest required");
      }
    }
  }

  /** Complete immutable set and the original owner evidence that authorized its publication. */
  public record PublishedSet(
      DraftCommitBinding.TargetProof target,
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
      List<Policy> policies) {
    public PublishedSet {
      Objects.requireNonNull(target, "target");
      nonNil(sourceCommitId, "sourceCommitId");
      if (versionNumber <= 0
          || sourceEpoch == null
          || !sourceEpoch.matches("0|[1-9][0-9]*")
          || publishedReleaseBundleRef == null
          || publishedReleaseBundleRef.isBlank()
          || !digest(publishedReleaseBundleDigest)
          || publishWorkflowId == null
          || publishWorkflowId.isBlank()
          || !digest(manifestHash)
          || publicationVersionStateEpoch <= 0) {
        throw new IllegalArgumentException("Complete publication-bound policy identity required");
      }
      operationBytes = nonEmptyCopy(operationBytes, "operationBytes");
      captureBytes = nonEmptyCopy(captureBytes, "captureBytes");
      terminalEvidenceBytes = nonEmptyCopy(terminalEvidenceBytes, "terminalEvidenceBytes");
      policies = List.copyOf(Objects.requireNonNull(policies, "policies"));
      if (policyCount < 1
          || policyCount > RealmPolicySource.MAX_POLICIES
          || policyCount != policies.size()
          || !digest(policySetDigest)) {
        throw new IllegalArgumentException("Complete bounded policy-set evidence required");
      }
      RealmPolicySource.ordered(policies.stream().map(Policy::source).toList());
      var operation = GameDesignPublicationOperation.fromStored(operationBytes);
      if (!operation.account().input().selection().target().equals(target)
          || !operation.workflowId().equals(publishWorkflowId)) {
        throw new IllegalArgumentException("Publication evidence differs from exact TargetProof");
      }
      var terminal = GameDesignPublicationTerminalEvidence.fromStored(terminalEvidenceBytes);
      var release = terminal.releaseContent();
      if (terminal.outcome() != GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
          || !Arrays.equals(terminal.operationBytes(), operationBytes)
          || !release.canonicalTenantId().equals(target.canonicalTenantId())
          || !release.canonicalVersionId().equals(target.canonicalVersionId())
          || release.versionNumber() != versionNumber
          || !release.publishedReleaseBundleRef().equals(publishedReleaseBundleRef)
          || !release.contentDigest().equals(publishedReleaseBundleDigest)
          || !release.publishWorkflowId().equals(publishWorkflowId)
          || !release.manifestHash().equals(manifestHash)
          || terminal.publicationVersionStateEpoch() != publicationVersionStateEpoch) {
        throw new IllegalArgumentException("Terminal release evidence differs from policy set");
      }
      if (!computePolicySetDigest(
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
    public List<Policy> policies() {
      return List.copyOf(policies);
    }
  }

  public static String targetProofJson(DraftCommitBinding.TargetProof target) {
    Objects.requireNonNull(target, "target");
    return RealmPolicySource.canonical(
        Map.of(
            "canonicalTenantId", target.canonicalTenantId().toString(),
            "canonicalVersionId", target.canonicalVersionId().toString(),
            "gameDesignVersionRowId", Long.toString(target.gameDesignVersionRowId()),
            "gameDesignVersionTenantKey", target.gameDesignVersionTenantKey(),
            "sourceGameRowId", Long.toString(target.sourceGameRowId()),
            "sourceGameTenantKey", target.sourceGameTenantKey(),
            "sourceProvenanceKind", target.sourceProvenanceKind()));
  }

  public static String policyDigest(
      UUID policyId,
      DraftCommitBinding.TargetProof target,
      int versionNumber,
      String publishedReleaseBundleRef,
      String publishedReleaseBundleDigest,
      String publishWorkflowId,
      String manifestHash,
      RealmPolicySource.Policy source) {
    nonNil(policyId, "policyId");
    Objects.requireNonNull(source, "source");
    String evidence =
        RealmPolicySource.canonical(
            map(
                "schema",
                POLICY_DIGEST_SCHEMA,
                "policyId",
                policyId.toString(),
                "target",
                RealmPolicySource.tree(targetProofJson(target)),
                "versionNumber",
                versionNumber,
                "sourceCommitId",
                source.commitId().toString(),
                "sourceRevisionId",
                source.revisionId().toString(),
                "logicalRevisionId",
                source.logicalRevisionId(),
                "publishedReleaseBundleRef",
                publishedReleaseBundleRef,
                "publishedReleaseBundleDigest",
                publishedReleaseBundleDigest,
                "publishWorkflowId",
                publishWorkflowId,
                "manifestHash",
                manifestHash,
                "policy",
                RealmPolicySource.tree(source.policy().canonicalJson())));
    return sha256(evidence.getBytes(StandardCharsets.UTF_8));
  }

  public static String computePolicySetDigest(
      DraftCommitBinding.TargetProof target,
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
      List<Policy> policies) {
    var ordered = RealmPolicySource.ordered(policies.stream().map(Policy::source).toList());
    if (ordered.size() != policies.size()) {
      throw new IllegalArgumentException("Canonical policy order required");
    }
    var bySource =
        policies.stream().collect(java.util.stream.Collectors.toMap(Policy::source, p -> p));
    var orderedEvidence =
        ordered.stream()
            .map(
                source -> {
                  Policy policy = bySource.get(source);
                  return Map.of(
                      "policyId", policy.policyId().toString(),
                      "sourceCommitId", source.commitId().toString(),
                      "sourceRevisionId", source.revisionId().toString(),
                      "logicalRevisionId", source.logicalRevisionId(),
                      "worldSlug", source.policy().worldSlug(),
                      "realmSlug", source.policy().realmSlug(),
                      "policyDigest", policy.policyDigest());
                })
            .toList();
    String evidence =
        RealmPolicySource.canonical(
            map(
                "schema", SET_DIGEST_SCHEMA,
                "target", RealmPolicySource.tree(targetProofJson(target)),
                "versionNumber", versionNumber,
                "sourceCommitId", sourceCommitId.toString(),
                "sourceEpoch", sourceEpoch,
                "publishedReleaseBundleRef", publishedReleaseBundleRef,
                "publishedReleaseBundleDigest", publishedReleaseBundleDigest,
                "publishWorkflowId", publishWorkflowId,
                "manifestHash", manifestHash,
                "publicationVersionStateEpoch", Long.toString(publicationVersionStateEpoch),
                "operationDigest", sha256(operationBytes),
                "captureDigest", sha256(captureBytes),
                "terminalEvidenceDigest", sha256(terminalEvidenceBytes),
                "policyCount", orderedEvidence.size(),
                "policies", orderedEvidence));
    return sha256(evidence.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] nonEmptyCopy(byte[] bytes, String name) {
    if (bytes == null || bytes.length == 0) {
      throw new IllegalArgumentException("Nonempty " + name + " required");
    }
    return bytes.clone();
  }

  private static Map<String, Object> map(Object... entries) {
    if (entries.length % 2 != 0) {
      throw new IllegalArgumentException("Even evidence key/value entries required");
    }
    var result = new java.util.LinkedHashMap<String, Object>();
    for (int index = 0; index < entries.length; index += 2) {
      result.put((String) entries[index], entries[index + 1]);
    }
    return result;
  }

  private static boolean digest(String value) {
    return value != null && value.matches("sha256:[0-9a-f]{64}");
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void nonNil(UUID value, String name) {
    if (value == null || new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException("Non-nil " + name + " required");
    }
  }
}
