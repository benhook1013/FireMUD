package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence;

/**
 * Internal Game Design-shaped reviewed-row input, not authentication or a wire protocol.
 *
 * <p>The future Game Design producer must derive this from the immutable reviewed-base row for the
 * complete incoming binding and the historical synchronized fence for that exact base. The retained
 * review-evidence digest is opaque provenance, not the base commit's input digest. Construction
 * cannot prove that producer or grant Account permission.
 */
record WorldDraftReviewedBaseCompanion(
    DraftCommitBinding reviewedIncomingBinding,
    DraftBaseReference reviewedBase,
    String retainedReviewEvidenceDigest,
    DraftSynchronizedVisibilityEvidence baseVisibility) {
  WorldDraftReviewedBaseCompanion {
    Objects.requireNonNull(reviewedIncomingBinding, "reviewedIncomingBinding");
    Objects.requireNonNull(reviewedBase, "reviewedBase");
    Objects.requireNonNull(baseVisibility, "baseVisibility");
    if (retainedReviewEvidenceDigest == null
        || !retainedReviewEvidenceDigest.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "Canonical retained reviewed-base evidence digest required");
    }
    baseVisibility.requireValid();
    if (reviewedBase.kind() != DraftBaseReference.Kind.AUTHORED_COMMIT
        || !reviewedBase.equals(DraftBaseReference.parse(reviewedIncomingBinding.baseCommitId()))
        || !reviewedBase.canonicalValue().equals(reviewedIncomingBinding.baseCommitId())
        || !reviewedBase.identity().equals(baseVisibility.binding().commitId())
        || !reviewedBase.identity().equals(baseVisibility.fence().commitId())
        || !reviewedIncomingBinding.target().equals(baseVisibility.binding().target())) {
      throw new IllegalArgumentException(
          "Exact authored reviewed base and synchronized target required");
    }
  }
}
