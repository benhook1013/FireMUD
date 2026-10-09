package net.firedevops.firemud.gamedesign.publication;

import java.util.Objects;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;

/** Pure mapping from the verified Game Design owner result into the shared sealed-set carrier. */
final class RealmPolicyPublishedEvidenceMapper {
  private RealmPolicyPublishedEvidenceMapper() {}

  static PublishedRealmEntryPolicySetEvidence toShared(
      RealmPolicyPublishedEvidence.PublishedSet source) {
    Objects.requireNonNull(source, "source");
    var policies =
        source.policies().stream()
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
    var candidate =
        new PublishedRealmEntryPolicySetEvidence(
            source.target(),
            source.versionNumber(),
            source.sourceCommitId(),
            source.sourceEpoch(),
            source.publishedReleaseBundleRef(),
            source.publishedReleaseBundleDigest(),
            source.publishWorkflowId(),
            source.manifestHash(),
            source.publicationVersionStateEpoch(),
            source.operationBytes(),
            source.captureBytes(),
            source.terminalEvidenceBytes(),
            source.policyCount(),
            source.policySetDigest(),
            policies);
    return PublishedRealmEntryPolicySetEvidence.fromStored(candidate.canonicalBytes());
  }
}
