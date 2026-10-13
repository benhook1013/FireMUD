package net.firedevops.firemud.common.gamedesign;

import java.util.Objects;

/** The exact independently validated descriptor and release-attestation pair for a launch. */
public record CompleteLaunchBindingEvidence(
    AuthoredWorldLaunchDescriptorEvidence descriptor,
    AuthoredWorldReleaseAttestationEvidence releaseAttestation) {
  public CompleteLaunchBindingEvidence {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(releaseAttestation, "releaseAttestation");
    releaseAttestation.requireValid(descriptor);
  }
}
