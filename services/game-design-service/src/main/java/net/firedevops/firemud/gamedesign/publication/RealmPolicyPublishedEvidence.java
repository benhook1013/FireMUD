package net.firedevops.firemud.gamedesign.publication;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;

/** Immutable internal evidence associating a frozen source set with its actual sealed release. */
public final class RealmPolicyPublishedEvidence {
  public static final String SET_DIGEST_SCHEMA =
      PublishedRealmEntryPolicySetEvidence.SET_DIGEST_SCHEMA;
  public static final String POLICY_DIGEST_SCHEMA =
      PublishedRealmEntryPolicySetEvidence.POLICY_DIGEST_SCHEMA;

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
    return PublishedRealmEntryPolicySetEvidence.targetProofJson(target);
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
    Objects.requireNonNull(source, "source");
    return PublishedRealmEntryPolicyEvidence.calculateDigest(
        policyId,
        target,
        versionNumber,
        publishedReleaseBundleRef,
        publishedReleaseBundleDigest,
        publishWorkflowId,
        manifestHash,
        source.commitId(),
        source.revisionId(),
        source.logicalRevisionId(),
        source.policy());
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
    RealmPolicySource.ordered(policies.stream().map(Policy::source).toList());
    var shared =
        policies.stream()
            .map(
                policy ->
                    new PublishedRealmEntryPolicyEvidence(
                        policy.policyId(),
                        policy.source().commitId(),
                        policy.source().revisionId(),
                        policy.source().logicalRevisionId(),
                        policy.source().policy(),
                        policy.policyDigest()))
            .toList();
    return PublishedRealmEntryPolicySetEvidence.calculatePolicySetDigest(
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
        shared);
  }

  private static byte[] nonEmptyCopy(byte[] bytes, String name) {
    if (bytes == null || bytes.length == 0) {
      throw new IllegalArgumentException("Nonempty " + name + " required");
    }
    return bytes.clone();
  }

  private static boolean digest(String value) {
    return value != null && value.matches("sha256:[0-9a-f]{64}");
  }

  private static void nonNil(UUID value, String name) {
    if (value == null || new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException("Non-nil " + name + " required");
    }
  }
}
