package net.firedevops.firemud.gamedesign.publication;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.AppliedEpoch;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerOutcome;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerStatus;

/** Actual owner application result, returned only after the atomic source write/readback. */
public record RealmPolicyApplication(
    RealmPolicyGenesis genesis,
    UUID inheritedCommitId,
    String expectedEpoch,
    RealmPolicySnapshot snapshot) {
  public RealmPolicyApplication(
      UUID inheritedCommitId, String expectedEpoch, RealmPolicySnapshot snapshot) {
    this(null, inheritedCommitId, expectedEpoch, snapshot);
  }

  public RealmPolicyApplication {
    if ((inheritedCommitId == null && genesis == null)
        || new UUID(0, 0).equals(inheritedCommitId)
        || (genesis != null && !genesis.target().equals(snapshot.binding().target()))
        || expectedEpoch == null
        || !expectedEpoch.matches("0|[1-9][0-9]*")
        || !new BigInteger(expectedEpoch)
            .add(BigInteger.ONE)
            .toString()
            .equals(snapshot.sourceEpoch())) {
      throw new IllegalArgumentException("Policy application must advance its exact source epoch");
    }
  }

  public byte[] canonicalBytes() {
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, "game-design-realm-policy-application/v1");
    DraftAuthorizationFenceBinding.frame(out, genesis == null ? "false" : "true");
    DraftAuthorizationFenceBinding.frame(
        out, genesis == null ? new byte[0] : genesis.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, inheritedCommitId == null ? "false" : "true");
    DraftAuthorizationFenceBinding.frame(
        out, inheritedCommitId == null ? "" : inheritedCommitId.toString());
    DraftAuthorizationFenceBinding.frame(out, expectedEpoch);
    DraftAuthorizationFenceBinding.frame(out, snapshot.canonicalBytes());
    return out.toByteArray();
  }

  public OwnerOutcome ownerOutcome() {
    var binding = snapshot.binding();
    return new OwnerOutcome(
        DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
        OwnerStatus.APPLIED,
        binding.commitId(),
        binding.digest(),
        "realm-policy:" + binding.commitId(),
        canonicalBytes(),
        List.of(
            new AppliedEpoch(
                RealmPolicySource.SCOPE,
                binding.target().canonicalVersionId().toString(),
                RealmPolicySource.SCOPE,
                "effective",
                expectedEpoch,
                snapshot.sourceEpoch())));
  }

  /** Parent composes actual sibling source evidence and its complete exact epoch vector once. */
  public OwnerOutcome combinedOwnerOutcome(
      byte[] siblingEvidence, List<AppliedEpoch> completeEpochs) {
    if (siblingEvidence == null || siblingEvidence.length == 0) {
      throw new IllegalArgumentException("Actual sibling owner application evidence required");
    }
    var binding = snapshot.binding();
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, "game-design-control-plane-source-application/v1");
    DraftAuthorizationFenceBinding.frame(out, binding.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, siblingEvidence);
    return new OwnerOutcome(
        DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
        OwnerStatus.APPLIED,
        binding.commitId(),
        binding.digest(),
        "control-plane-source:" + binding.commitId(),
        out.toByteArray(),
        completeEpochs);
  }
}
