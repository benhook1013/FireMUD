package net.firedevops.firemud.gamedesign.publication;

import java.math.BigInteger;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.AssetSource;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.AppliedEpoch;

/** Exact ordinary-source application evidence, composed into one control-plane owner result. */
public record AssetApplication(
    DraftCommitBinding binding, String expectedEpoch, AssetSnapshot snapshot) {
  public AssetApplication {
    Objects.requireNonNull(binding);
    if (expectedEpoch == null
        || !expectedEpoch.matches("0|[1-9][0-9]*")
        || !binding.equals(snapshot.binding())
        || AssetSource.mutations(binding).isEmpty()
        || !new BigInteger(expectedEpoch)
            .add(BigInteger.ONE)
            .toString()
            .equals(snapshot.sourceEpoch()))
      throw new IllegalArgumentException(
          "Ordinary asset application must advance its exact bound epoch");
  }

  public AppliedEpoch appliedEpoch() {
    return new AppliedEpoch(
        AssetSource.SCOPE,
        binding.target().canonicalVersionId().toString(),
        AssetSource.SCOPE,
        AssetSource.SCOPE_ID,
        expectedEpoch,
        snapshot.sourceEpoch());
  }

  public byte[] canonicalBytes() {
    var out = new java.io.ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, "game-design-ordinary-asset-source-application/v1");
    DraftAuthorizationFenceBinding.frame(out, expectedEpoch);
    DraftAuthorizationFenceBinding.frame(out, snapshot.canonicalBytes());
    return out.toByteArray();
  }
}
