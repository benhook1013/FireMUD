package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;

/** Reads and qualifies exact durable Game Session owner evidence without changing it. */
public final class CanonicalGameInstanceLaunchAssociationReadService {
  private final CanonicalGameInstanceLaunchAssociationRepository repository;
  private final String configuredNamespace;

  public CanonicalGameInstanceLaunchAssociationReadService(
      CanonicalGameInstanceLaunchAssociationRepository repository, String configuredNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.configuredNamespace = Objects.requireNonNull(configuredNamespace, "configuredNamespace");
  }

  public CanonicalGameInstanceLaunchAssociation read(
      CanonicalGameInstanceLaunchAssociationReadRequest request) {
    Objects.requireNonNull(request, "request");
    if (!configuredNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException("Read namespace does not match this Game Session owner");
    }
    CanonicalGameInstanceLaunchAssociation association =
        repository
            .read(request.controlPlaneRequestId())
            .orElseThrow(
                () -> new IllegalStateException("Canonical launch association is unavailable"));
    CompleteLaunchBindingEvidence binding = association.launchBindingEvidence();
    var descriptor = binding.descriptor();
    var attestation = binding.releaseAttestation();
    descriptor.requireValid();
    attestation.requireValid(descriptor);
    if (request.readRequestId().equals(descriptor.authoredWorldSourceOperationId())
        || request.readRequestId().toString().equals(descriptor.controlPlaneRequestId())
        || !configuredNamespace.equals(association.targetNamespace())
        || !request.targetNamespace().equals(association.targetNamespace())
        || !request.canonicalTenantId().equals(association.canonicalTenantId())
        || !request.worldSlug().equals(association.worldSlug())
        || !request.gameInstanceUuid().equals(association.gameInstanceUuid())
        || !request.controlPlaneRequestId().equals(association.controlPlaneRequestId())
        || !request.launchDescriptorId().equals(association.launchDescriptorId())
        || !request.expectedDescriptorRequestDigest().equals(descriptor.requestDigest())
        || !request.expectedDescriptorResultDigest().equals(descriptor.resultDigest())
        || !request
            .expectedReleaseAttestationEvidenceDigest()
            .equals(attestation.evidenceDigest())) {
      throw new IllegalStateException(
          "Canonical launch association does not match the exact read selector");
    }
    return association;
  }
}
