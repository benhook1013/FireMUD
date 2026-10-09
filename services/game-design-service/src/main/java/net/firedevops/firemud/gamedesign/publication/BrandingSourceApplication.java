package net.firedevops.firemud.gamedesign.publication;

import java.math.BigInteger;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.AppliedEpoch;

/** Exact branding-source application evidence, composed into one control-plane owner result. */
public record BrandingSourceApplication(
    DraftCommitBinding binding, String expectedEpoch, BrandingSourceSnapshot snapshot) {
  public BrandingSourceApplication {
    Objects.requireNonNull(binding);
    if (expectedEpoch == null
        || !expectedEpoch.matches("0|[1-9][0-9]*")
        || !binding.equals(snapshot.binding())
        || BrandingSource.mutations(binding).isEmpty()
        || !new BigInteger(expectedEpoch)
            .add(BigInteger.ONE)
            .toString()
            .equals(snapshot.sourceEpoch()))
      throw new IllegalArgumentException(
          "Branding asset application must advance its exact bound epoch");
  }

  public AppliedEpoch appliedEpoch() {
    return new AppliedEpoch(
        BrandingSource.SCOPE,
        binding.target().canonicalVersionId().toString(),
        BrandingSource.SCOPE,
        BrandingSource.SCOPE_ID,
        expectedEpoch,
        snapshot.sourceEpoch());
  }

  public byte[] canonicalBytes() {
    var out = new java.io.ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, "game-design-branding-asset-source-application/v1");
    DraftAuthorizationFenceBinding.frame(out, expectedEpoch);
    DraftAuthorizationFenceBinding.frame(out, snapshot.canonicalBytes());
    return out.toByteArray();
  }
}
