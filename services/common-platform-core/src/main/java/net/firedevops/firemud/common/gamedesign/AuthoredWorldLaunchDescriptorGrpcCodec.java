package net.firedevops.firemud.common.gamedesign;

import com.google.protobuf.Message;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.gamedesign.v1.GetCompleteLaunchBindingResponse;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorResponse;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;

/** Closed mapping and exact-readback validation for authored-world launch descriptor RPCs. */
public final class AuthoredWorldLaunchDescriptorGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

  /** Exact owner read identity and the immutable resolve tuple whose result is being recovered. */
  public record GetRequest(
      UUID requestId,
      AuthoredWorldLaunchDescriptorEvidence.Request expectedRequest,
      String expectedResultDigest) {
    public GetRequest {
      if (requestId == null || NIL_UUID.equals(requestId)) {
        throw new IllegalArgumentException(
            "A nonnil launch descriptor read request ID is required");
      }
      Objects.requireNonNull(expectedRequest, "expectedRequest");
      if (requestId.equals(expectedRequest.authoredWorldSourceOperationId())
          || requestId.toString().equals(expectedRequest.controlPlaneRequestId())) {
        throw new IllegalArgumentException(
            "Launch descriptor read request ID must be distinct from the original request");
      }
      if (expectedResultDigest == null || !SHA256.matcher(expectedResultDigest).matches()) {
        throw new IllegalArgumentException(
            "Expected launch descriptor result digest must be canonical SHA-256 text");
      }
    }
  }

  private AuthoredWorldLaunchDescriptorGrpcCodec() {}

  /** Encodes only the exact request fields owned by the immutable shared request value. */
  public static ResolveLaunchDescriptorRequest toResolveRequest(
      AuthoredWorldLaunchDescriptorEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    ResolveLaunchDescriptorRequest.Builder builder =
        ResolveLaunchDescriptorRequest.newBuilder()
            .setCanonicalTenantId(request.canonicalTenantId().toString())
            .setGameTemplateId(request.gameTemplateId())
            .setControlPlaneRequestId(request.controlPlaneRequestId())
            .setWorldSlug(request.worldSlug())
            .setAuthoredWorldSourceOperationId(request.authoredWorldSourceOperationId().toString())
            .setExpectedAuthoredWorldSourceEvidenceDigest(
                request.authoredWorldSourceEvidenceDigest());
    if (request.requestedScriptPatchVersionPresent()) {
      builder.setRequestedScriptPatchVersion(request.requestedScriptPatchVersion());
    }
    if (request.sourceVersionIdPresent()) {
      builder.setSourceVersionId(request.sourceVersionId());
    }
    if (request.targetVersionIdPresent()) {
      builder.setTargetVersionId(request.targetVersionId());
    }
    if (request.requestedRuntimeFlagsJsonPresent()) {
      builder.setRequestedRuntimeFlagsJson(request.requestedRuntimeFlagsJson());
    }
    return builder.build();
  }

  /** Encodes an exact persisted owner read, kept distinct from the original resolve identity. */
  public static GetLaunchDescriptorRequest toGetRequest(GetRequest request) {
    Objects.requireNonNull(request, "request");
    AuthoredWorldLaunchDescriptorEvidence.Request expected = request.expectedRequest();
    return GetLaunchDescriptorRequest.newBuilder()
        .setRequestId(request.requestId().toString())
        .setCanonicalTenantId(expected.canonicalTenantId().toString())
        .setWorldSlug(expected.worldSlug())
        .setControlPlaneRequestId(expected.controlPlaneRequestId())
        .setExpectedRequestDigest(expected.requestDigest())
        .setExpectedResultDigest(request.expectedResultDigest())
        .build();
  }

  /** Validates the complete response and flat descriptor duplicates against the exact request. */
  public static AuthoredWorldLaunchDescriptorEvidence fromResolveResponse(
      AuthoredWorldLaunchDescriptorEvidence.Request expectedRequest,
      ResolveLaunchDescriptorResponse response) {
    Objects.requireNonNull(expectedRequest, "expectedRequest");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "ResolveLaunchDescriptor response");
    if (response.hasError()) {
      throw new IllegalArgumentException("Game Design rejected launch descriptor resolution");
    }
    if (!response.hasLaunchDescriptor()) {
      throw new IllegalArgumentException("Launch descriptor resolution returned no descriptor");
    }
    return decodeDescriptor(expectedRequest, response.getLaunchDescriptor());
  }

  /** Validates an exact read echo, original request tuple, result digest, and duplicate fields. */
  public static AuthoredWorldLaunchDescriptorEvidence fromGetResponse(
      GetRequest request, GetLaunchDescriptorResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "GetLaunchDescriptor response");
    if (response.hasError()) {
      throw new IllegalArgumentException("Game Design rejected the exact launch descriptor read");
    }
    UUID echoedRequestId = parseCanonicalNonNilUuid(response.getRequestId(), "read request ID");
    if (!request.requestId().equals(echoedRequestId)) {
      throw new IllegalArgumentException("Launch descriptor read request ID echo changed");
    }
    if (!response.hasLaunchDescriptor()) {
      throw new IllegalArgumentException("Exact launch descriptor read returned no descriptor");
    }
    AuthoredWorldLaunchDescriptorEvidence evidence =
        decodeDescriptor(request.expectedRequest(), response.getLaunchDescriptor());
    if (!request.expectedResultDigest().equals(evidence.resultDigest())) {
      throw new IllegalArgumentException(
          "Launch descriptor result digest does not match the exact read request");
    }
    return evidence;
  }

  /**
   * Validates the complete owner response as one atomic descriptor and attestation pair. The
   * request is the exact immutable read selector; neither component is accepted alone.
   */
  public static CompleteLaunchBindingEvidence fromCompleteResponse(
      GetLaunchDescriptorRequest request, GetCompleteLaunchBindingResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(request, "GetCompleteLaunchBinding request");
    requireNoUnknownFields(response, "GetCompleteLaunchBinding response");
    if (response.hasError()) {
      requireNoUnknownFields(response.getError(), "GetCompleteLaunchBinding error");
      throw new IllegalArgumentException("Game Design rejected the complete launch binding read");
    }
    UUID readRequestId = parseCanonicalNonNilUuid(request.getRequestId(), "read request ID");
    UUID echoedRequestId =
        parseCanonicalNonNilUuid(response.getRequestId(), "read request ID echo");
    if (!readRequestId.equals(echoedRequestId)) {
      throw new IllegalArgumentException("Complete launch binding read request ID echo changed");
    }
    if (!response.hasLaunchDescriptor() || !response.hasReleaseAttestation()) {
      throw new IllegalArgumentException(
          "Complete launch binding read must return both descriptor and release attestation");
    }

    AuthoredWorldLaunchDescriptorEvidence descriptor =
        decodeDescriptor(response.getLaunchDescriptor());
    if (readRequestId.equals(descriptor.authoredWorldSourceOperationId())
        || readRequestId.toString().equals(descriptor.controlPlaneRequestId())) {
      throw new IllegalArgumentException(
          "Complete launch binding read request ID must be distinct from the original request");
    }
    if (!descriptor.canonicalTenantId().toString().equals(request.getCanonicalTenantId())
        || !descriptor.worldSlug().equals(request.getWorldSlug())
        || !descriptor.controlPlaneRequestId().equals(request.getControlPlaneRequestId())
        || !descriptor.requestDigest().equals(request.getExpectedRequestDigest())
        || !descriptor.resultDigest().equals(request.getExpectedResultDigest())) {
      throw new IllegalArgumentException(
          "Complete launch binding does not match the exact read selector");
    }

    AuthoredWorldReleaseAttestationEvidence releaseAttestation =
        decodeReleaseAttestation(response.getReleaseAttestation());
    try {
      return new CompleteLaunchBindingEvidence(descriptor, releaseAttestation);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Complete launch binding evidence is invalid", exception);
    }
  }

  /** Encodes the separate closed release attestation without changing descriptor/v1. */
  public static net.firedevops.firemud.gamedesign.v1.AuthoredWorldReleaseAttestationEvidence
      toReleaseAttestation(AuthoredWorldReleaseAttestationEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    evidence.requireValid();
    var builder =
        net.firedevops.firemud.gamedesign.v1.AuthoredWorldReleaseAttestationEvidence.newBuilder()
            .setSchemaVersion(evidence.schemaVersion())
            .setTargetNamespace(evidence.targetNamespace())
            .setDescriptorResultDigest(evidence.descriptorResultDigest())
            .setCanonicalTenantId(evidence.canonicalTenantId().toString())
            .setCanonicalVersionId(evidence.canonicalVersionId().toString())
            .setWorldSlug(evidence.worldSlug())
            .setAuthoredWorldSourceOperationId(evidence.authoredWorldSourceOperationId().toString())
            .setAuthoredWorldSourceEvidenceDigest(evidence.authoredWorldSourceEvidenceDigest())
            .setLaunchDescriptorId(evidence.launchDescriptorId())
            .setPublishedReleaseBundleRef(evidence.publishedReleaseBundleRef())
            .setVersionStateEpoch(evidence.versionStateEpoch())
            .setPublishWorkflowId(evidence.publishWorkflowId())
            .setCommitId(evidence.commitId())
            .setManifestHash(evidence.manifestHash())
            .setManifestSchemaVersion(evidence.manifestSchemaVersion())
            .addAllRequiredManifestAssetKeys(evidence.requiredManifestAssetKeys())
            .addAllCommandDefinitions(evidence.commandDefinitions())
            .setGenerationConfigRevision(evidence.generationConfigRevision())
            .setEvidenceDigest(evidence.evidenceDigest());
    for (AuthoredWorldReleaseAttestationEvidence.Participant participant :
        evidence.participantDigests()) {
      ParticipantDigest.Builder participantBuilder =
          ParticipantDigest.newBuilder()
              .setParticipantKey(participant.participantKey())
              .setScopeValue(participant.scopeValue())
              .setAppliedCommitId(participant.appliedCommitId())
              .setContentDigest(participant.contentDigest())
              .setDigestSchemaVersion(participant.digestSchemaVersion());
      if (participant.abilitySchemaDigestPresent()) {
        participantBuilder.setAbilitySchemaDigest(participant.abilitySchemaDigest());
      }
      builder.addParticipantDigests(participantBuilder);
    }
    for (AuthoredWorldReleaseAttestationEvidence.Artifact artifact : evidence.artifactDigests()) {
      builder.addArtifactDigests(
          PublishedArtifactDigest.newBuilder()
              .setUsageKey(artifact.usageKey())
              .setArtifactKind(artifact.artifactKind())
              .setImmutableObjectKey(artifact.immutableObjectKey())
              .setContentDigest(artifact.contentDigest())
              .setContentType(artifact.contentType())
              .setArtifactSchemaVersion(artifact.artifactSchemaVersion()));
    }
    return builder.build();
  }

  private static AuthoredWorldLaunchDescriptorEvidence decodeDescriptor(
      AuthoredWorldLaunchDescriptorEvidence.Request expectedRequest, LaunchDescriptor descriptor) {
    AuthoredWorldLaunchDescriptorEvidence evidence = decodeDescriptor(descriptor);
    if (!evidence.request().equals(expectedRequest)) {
      throw new IllegalArgumentException(
          "Launch descriptor does not match the exact authored-world resolve request");
    }
    return evidence;
  }

  private static AuthoredWorldLaunchDescriptorEvidence decodeDescriptor(
      LaunchDescriptor descriptor) {
    requireNoUnknownFields(descriptor, "LaunchDescriptor");
    if (!descriptor.hasAuthoredWorldBinding()) {
      throw new IllegalArgumentException("Launch descriptor has no authored-world binding");
    }
    var binding = descriptor.getAuthoredWorldBinding();
    requireNoUnknownFields(binding, "AuthoredWorldLaunchDescriptorEvidence");

    AuthoredWorldLaunchDescriptorEvidence evidence;
    try {
      evidence =
          new AuthoredWorldLaunchDescriptorEvidence(
              binding.getSchemaVersion(),
              binding.getTargetNamespace(),
              binding.getControlPlaneRequestId(),
              parseCanonicalNonNilUuid(binding.getCanonicalTenantId(), "canonical tenant ID"),
              binding.getWorldSlug(),
              parseCanonicalNonNilUuid(
                  binding.getAuthoredWorldSourceOperationId(),
                  "authored-world source operation ID"),
              binding.getAuthoredWorldSourceEvidenceDigest(),
              binding.getGameTemplateId(),
              binding.hasRequestedScriptPatchVersion(),
              binding.hasRequestedScriptPatchVersion()
                  ? binding.getRequestedScriptPatchVersion()
                  : null,
              binding.hasSourceVersionId(),
              binding.hasSourceVersionId() ? binding.getSourceVersionId() : null,
              binding.hasTargetVersionId(),
              binding.hasTargetVersionId() ? binding.getTargetVersionId() : null,
              binding.hasRequestedRuntimeFlagsJson(),
              binding.hasRequestedRuntimeFlagsJson()
                  ? binding.getRequestedRuntimeFlagsJson()
                  : null,
              binding.getRequestDigest(),
              binding.getLaunchDescriptorId(),
              binding.getVersionId(),
              binding.hasScriptPatchVersion(),
              binding.hasScriptPatchVersion() ? binding.getScriptPatchVersion() : null,
              binding.getRuntimeFlagsJson(),
              binding.getGenerationConfigRevision(),
              binding.getVersionStateEpoch(),
              binding.getReleaseBundleId(),
              binding.getPublishedReleaseBundleRef(),
              binding.hasRemapSetId(),
              binding.hasRemapSetId() ? binding.getRemapSetId() : null,
              binding.getResultDigest());
      evidence.requireValid();
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Authored-world launch evidence is invalid", exception);
    }

    requireFlatDuplicate(
        descriptor.getLaunchDescriptorId(), evidence.launchDescriptorId(), "launchDescriptorId");
    requireFlatDuplicate(
        descriptor.getCanonicalTenantId(),
        evidence.canonicalTenantId().toString(),
        "canonicalTenantId");
    requireFlatDuplicate(
        descriptor.getGameTemplateId(), evidence.gameTemplateId(), "gameTemplateId");
    requireFlatDuplicate(
        descriptor.getControlPlaneRequestId(),
        evidence.controlPlaneRequestId(),
        "controlPlaneRequestId");
    requireFlatDuplicate(descriptor.getVersionId(), evidence.versionId(), "versionId");
    requireFlatDuplicate(
        descriptor.getScriptPatchVersion(),
        evidence.scriptPatchVersionPresent() ? evidence.scriptPatchVersion() : "",
        "scriptPatchVersion");
    requireFlatDuplicate(
        descriptor.getRuntimeFlagsJson(), evidence.runtimeFlagsJson(), "runtimeFlagsJson");
    requireFlatDuplicate(
        descriptor.getGenerationConfigRevision(),
        evidence.generationConfigRevision(),
        "generationConfigRevision");
    requireFlatDuplicate(
        descriptor.getVersionStateEpoch(), evidence.versionStateEpoch(), "versionStateEpoch");
    requireFlatDuplicate(
        descriptor.getReleaseBundleId(), evidence.releaseBundleId(), "releaseBundleId");
    requireFlatDuplicate(
        descriptor.getPublishedReleaseBundleRef(),
        evidence.publishedReleaseBundleRef(),
        "publishedReleaseBundleRef");
    requireFlatDuplicate(
        descriptor.getRemapSetId(),
        evidence.remapSetIdPresent() ? evidence.remapSetId() : "",
        "remapSetId");
    return evidence;
  }

  private static AuthoredWorldReleaseAttestationEvidence decodeReleaseAttestation(
      net.firedevops.firemud.gamedesign.v1.AuthoredWorldReleaseAttestationEvidence wire) {
    requireNoUnknownFields(wire, "AuthoredWorldReleaseAttestationEvidence");
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        new java.util.ArrayList<>(wire.getParticipantDigestsCount());
    for (ParticipantDigest participant : wire.getParticipantDigestsList()) {
      requireNoUnknownFields(participant, "ParticipantDigest");
      participants.add(
          new AuthoredWorldReleaseAttestationEvidence.Participant(
              participant.getParticipantKey(),
              participant.getScopeValue(),
              false,
              null,
              participant.getAppliedCommitId(),
              participant.getContentDigest(),
              participant.getDigestSchemaVersion(),
              participant.hasAbilitySchemaDigest(),
              participant.hasAbilitySchemaDigest() ? participant.getAbilitySchemaDigest() : null));
    }
    List<AuthoredWorldReleaseAttestationEvidence.Artifact> artifacts =
        new java.util.ArrayList<>(wire.getArtifactDigestsCount());
    for (PublishedArtifactDigest artifact : wire.getArtifactDigestsList()) {
      requireNoUnknownFields(artifact, "PublishedArtifactDigest");
      artifacts.add(
          new AuthoredWorldReleaseAttestationEvidence.Artifact(
              artifact.getUsageKey(),
              artifact.getArtifactKind(),
              artifact.getImmutableObjectKey(),
              artifact.getContentDigest(),
              artifact.getContentType(),
              artifact.getArtifactSchemaVersion()));
    }
    try {
      return new AuthoredWorldReleaseAttestationEvidence(
          wire.getSchemaVersion(),
          wire.getTargetNamespace(),
          wire.getDescriptorResultDigest(),
          parseCanonicalNonNilUuid(wire.getCanonicalTenantId(), "canonical tenant ID"),
          parseCanonicalNonNilUuid(wire.getCanonicalVersionId(), "canonical version ID"),
          wire.getWorldSlug(),
          parseCanonicalNonNilUuid(
              wire.getAuthoredWorldSourceOperationId(), "authored-world source operation ID"),
          wire.getAuthoredWorldSourceEvidenceDigest(),
          wire.getLaunchDescriptorId(),
          wire.getPublishedReleaseBundleRef(),
          wire.getVersionStateEpoch(),
          wire.getPublishWorkflowId(),
          wire.getCommitId(),
          participants,
          wire.getManifestHash(),
          wire.getManifestSchemaVersion(),
          wire.getRequiredManifestAssetKeysList(),
          artifacts,
          wire.getCommandDefinitionsList(),
          wire.getGenerationConfigRevision(),
          wire.getEvidenceDigest());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Release-attestation evidence is invalid", exception);
    }
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required", exception);
    }
    if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Canonical nonnil " + label + " is required");
    }
    return parsed;
  }

  private static void requireFlatDuplicate(String actual, String expected, String field) {
    if (!Objects.equals(actual, expected)) {
      throw new IllegalArgumentException(
          "Flat launch descriptor " + field + " does not match evidence");
    }
  }

  private static void requireFlatDuplicate(long actual, long expected, String field) {
    if (actual != expected) {
      throw new IllegalArgumentException(
          "Flat launch descriptor " + field + " does not match evidence");
    }
  }
}
