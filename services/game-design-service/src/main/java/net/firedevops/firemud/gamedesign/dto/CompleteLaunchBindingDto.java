package net.firedevops.firemud.gamedesign.dto;

import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;

/** The closed descriptor and its independently validated full release attestation. */
public record CompleteLaunchBindingDto(
    AuthoredWorldLaunchDescriptorEvidence descriptor,
    AuthoredWorldReleaseAttestationEvidence releaseAttestation) {
  public CompleteLaunchBindingDto {
    Objects.requireNonNull(descriptor, "descriptor").requireValid();
    Objects.requireNonNull(releaseAttestation, "releaseAttestation").requireValid(descriptor);
  }
}
