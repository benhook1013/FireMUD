package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;

/** Independently committed result of the bounded fixture activation owner. */
public record WorldCanonicalInstanceFixtureActivationResult(
    WorldInstanceLifecycleSnapshotDto snapshot,
    WorldCanonicalInstanceLifecycleEvidence lifecycleEvidence,
    byte[] lifecycleEvidenceBytes) {
  public WorldCanonicalInstanceFixtureActivationResult {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(lifecycleEvidence, "lifecycleEvidence");
    lifecycleEvidenceBytes = Objects.requireNonNull(lifecycleEvidenceBytes, "lifecycleEvidenceBytes").clone();
    if (!"ACTIVE".equals(lifecycleEvidence.lifecycleStatus())
        || lifecycleEvidence.lifecycleEpoch() != 2L
        || lifecycleEvidence.rowVersion() != 1L
        || !Arrays.equals(lifecycleEvidence.canonicalBytes(), lifecycleEvidenceBytes)
        || snapshot.lifecycleEpoch() != lifecycleEvidence.lifecycleEpoch()
        || !"ACTIVE".equals(snapshot.status())) {
      throw new IllegalArgumentException(
          "Fixture activation result must be the exact committed ACTIVE lifecycle evidence");
    }
  }

  @Override
  public byte[] lifecycleEvidenceBytes() {
    return lifecycleEvidenceBytes.clone();
  }
}
