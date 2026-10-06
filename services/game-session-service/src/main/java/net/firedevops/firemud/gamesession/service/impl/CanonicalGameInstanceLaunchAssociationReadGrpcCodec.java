package net.firedevops.firemud.gamesession.service.impl;

import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.service.CanonicalGameInstanceLaunchAssociationReadRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationResponse;

/** Lossless mapping between typed launch-owner evidence and the World-only RPC response. */
final class CanonicalGameInstanceLaunchAssociationReadGrpcCodec {
  private CanonicalGameInstanceLaunchAssociationReadGrpcCodec() {}

  static CanonicalGameInstanceLaunchAssociationReadRequest fromWire(
      GetCanonicalGameInstanceLaunchAssociationRequest request) {
    if (!request.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(
          "Canonical Game Session owner-read request has unknown fields");
    }
    return new CanonicalGameInstanceLaunchAssociationReadRequest(
        parseUuid(request.getReadRequestId(), "read_request_id"),
        request.getTargetNamespace(),
        parseUuid(request.getCanonicalTenantId(), "canonical_tenant_id"),
        request.getWorldSlug(),
        parseUuid(request.getGameInstanceUuid(), "game_instance_uuid"),
        request.getControlPlaneRequestId(),
        request.getLaunchDescriptorId(),
        request.getExpectedDescriptorRequestDigest(),
        request.getExpectedDescriptorResultDigest(),
        request.getExpectedReleaseAttestationEvidenceDigest());
  }

  static GetCanonicalGameInstanceLaunchAssociationResponse toWire(
      GetCanonicalGameInstanceLaunchAssociationRequest request,
      CanonicalGameInstanceLaunchAssociation association) {
    CompleteLaunchBindingEvidence binding = association.launchBindingEvidence();
    AuthoredWorldLaunchDescriptorEvidence descriptor = binding.descriptor();
    AuthoredWorldReleaseAttestationEvidence attestation = binding.releaseAttestation();
    descriptor.requireValid();
    attestation.requireValid(descriptor);
    return GetCanonicalGameInstanceLaunchAssociationResponse.newBuilder()
        .setReadRequestId(request.getReadRequestId())
        .setTargetNamespace(request.getTargetNamespace())
        .setCanonicalTenantId(request.getCanonicalTenantId())
        .setWorldSlug(request.getWorldSlug())
        .setGameInstanceUuid(request.getGameInstanceUuid())
        .setControlPlaneRequestId(request.getControlPlaneRequestId())
        .setLaunchDescriptorId(request.getLaunchDescriptorId())
        .setDescriptorRequestDigest(descriptor.requestDigest())
        .setDescriptorResultDigest(descriptor.resultDigest())
        .setReleaseAttestationEvidenceDigest(attestation.evidenceDigest())
        .setPlayableStateNamespaceId(association.playableStateNamespaceId().toString())
        .setPlayableStateScope(association.playableStateScope().name())
        .setPublicProduction(association.publicProduction())
        .setCurrentGameInstanceStatus(association.currentGameInstanceStatus().name())
        .setCurrentRowVersion(association.currentRowVersion())
        .setLaunchDescriptor(toLaunchDescriptor(descriptor))
        .setReleaseAttestation(
            AuthoredWorldLaunchDescriptorGrpcCodec.toReleaseAttestation(attestation))
        .build();
  }

  private static net.firedevops.firemud.gamedesign.v1.LaunchDescriptor toLaunchDescriptor(
      AuthoredWorldLaunchDescriptorEvidence source) {
    var binding =
        net.firedevops.firemud.gamedesign.v1.AuthoredWorldLaunchDescriptorEvidence.newBuilder()
            .setSchemaVersion(source.schemaVersion())
            .setTargetNamespace(source.targetNamespace())
            .setControlPlaneRequestId(source.controlPlaneRequestId())
            .setCanonicalTenantId(source.canonicalTenantId().toString())
            .setWorldSlug(source.worldSlug())
            .setAuthoredWorldSourceOperationId(source.authoredWorldSourceOperationId().toString())
            .setAuthoredWorldSourceEvidenceDigest(source.authoredWorldSourceEvidenceDigest())
            .setGameTemplateId(source.gameTemplateId())
            .setRequestDigest(source.requestDigest())
            .setLaunchDescriptorId(source.launchDescriptorId())
            .setVersionId(source.versionId())
            .setRuntimeFlagsJson(source.runtimeFlagsJson())
            .setGenerationConfigRevision(source.generationConfigRevision())
            .setVersionStateEpoch(source.versionStateEpoch())
            .setReleaseBundleId(source.releaseBundleId())
            .setPublishedReleaseBundleRef(source.publishedReleaseBundleRef())
            .setResultDigest(source.resultDigest());
    if (source.requestedScriptPatchVersionPresent()) {
      binding.setRequestedScriptPatchVersion(source.requestedScriptPatchVersion());
    }
    if (source.sourceVersionIdPresent()) {
      binding.setSourceVersionId(source.sourceVersionId());
    }
    if (source.targetVersionIdPresent()) {
      binding.setTargetVersionId(source.targetVersionId());
    }
    if (source.requestedRuntimeFlagsJsonPresent()) {
      binding.setRequestedRuntimeFlagsJson(source.requestedRuntimeFlagsJson());
    }
    if (source.scriptPatchVersionPresent()) {
      binding.setScriptPatchVersion(source.scriptPatchVersion());
    }
    if (source.remapSetIdPresent()) {
      binding.setRemapSetId(source.remapSetId());
    }

    var descriptor =
        net.firedevops.firemud.gamedesign.v1.LaunchDescriptor.newBuilder()
            .setLaunchDescriptorId(source.launchDescriptorId())
            .setCanonicalTenantId(source.canonicalTenantId().toString())
            .setGameTemplateId(source.gameTemplateId())
            .setControlPlaneRequestId(source.controlPlaneRequestId())
            .setVersionId(source.versionId())
            .setRuntimeFlagsJson(source.runtimeFlagsJson())
            .setGenerationConfigRevision(source.generationConfigRevision())
            .setVersionStateEpoch(source.versionStateEpoch())
            .setReleaseBundleId(source.releaseBundleId())
            .setPublishedReleaseBundleRef(source.publishedReleaseBundleRef())
            .setAuthoredWorldBinding(binding);
    if (source.scriptPatchVersionPresent()) {
      descriptor.setScriptPatchVersion(source.scriptPatchVersion());
    }
    if (source.remapSetIdPresent()) {
      descriptor.setRemapSetId(source.remapSetId());
    }
    return descriptor.build();
  }

  private static UUID parseUuid(String value, String field) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) {
        throw new IllegalArgumentException(field + " must be a canonical UUID");
      }
      return parsed;
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(field + " must be a canonical UUID", exception);
    }
  }
}
