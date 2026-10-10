package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.GetCompleteLaunchBindingResponse;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;

class AuthoredWorldLaunchDescriptorGrpcCodecTest {
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
  private static final UnknownFieldSet UNKNOWN_FIELD =
      UnknownFieldSet.newBuilder()
          .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
          .build();

  @Test
  void resolveRequestAndResponsePreserveEveryPresentOptionalAndExactDescriptorBinding()
      throws Exception {
    AuthoredWorldLaunchDescriptorEvidence descriptor = descriptorWithOptionalValues();

    var request = AuthoredWorldLaunchDescriptorGrpcCodec.toResolveRequest(descriptor.request());
    assertThat(request.getTenantId()).isEmpty();
    assertThat(request.getCanonicalTenantId()).isEqualTo(descriptor.canonicalTenantId().toString());
    assertThat(request.getWorldSlug()).isEqualTo(descriptor.worldSlug());
    assertThat(request.getAuthoredWorldSourceOperationId())
        .isEqualTo(descriptor.authoredWorldSourceOperationId().toString());
    assertThat(request.getExpectedAuthoredWorldSourceEvidenceDigest())
        .isEqualTo(descriptor.authoredWorldSourceEvidenceDigest());
    assertThat(request.hasRequestedScriptPatchVersion()).isTrue();
    assertThat(request.getRequestedScriptPatchVersion()).isEqualTo("script-patch-v2");
    assertThat(request.hasSourceVersionId()).isTrue();
    assertThat(request.getSourceVersionId()).isEqualTo(41L);
    assertThat(request.hasTargetVersionId()).isTrue();
    assertThat(request.getTargetVersionId()).isEqualTo(42L);
    assertThat(request.hasRequestedRuntimeFlagsJson()).isTrue();
    assertThat(request.getRequestedRuntimeFlagsJson()).isEqualTo("{\"quality\":\"high\"}");

    var response =
        ResolveLaunchDescriptorResponse.newBuilder()
            .setLaunchDescriptor(
                AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(descriptor))
            .build();
    assertThat(response.getLaunchDescriptor().getAuthoredWorldBinding().hasScriptPatchVersion())
        .isTrue();
    assertThat(response.getLaunchDescriptor().getAuthoredWorldBinding().getScriptPatchVersion())
        .isEqualTo("script-patch-v2");
    assertThat(response.getLaunchDescriptor().getAuthoredWorldBinding().hasRemapSetId()).isTrue();
    assertThat(response.getLaunchDescriptor().getAuthoredWorldBinding().getRemapSetId())
        .isEqualTo("approved-remap-41-42");
    assertThat(
            AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                descriptor.request(), response))
        .isEqualTo(descriptor);
  }

  @Test
  void omittedResolveOptionsRemainAbsentAndDecodeAsNull() throws Exception {
    var descriptor = authoredWorldFixture().launchBinding().descriptor();
    var request = AuthoredWorldLaunchDescriptorGrpcCodec.toResolveRequest(descriptor.request());

    assertThat(request.hasRequestedScriptPatchVersion()).isFalse();
    assertThat(request.hasSourceVersionId()).isFalse();
    assertThat(request.hasTargetVersionId()).isFalse();
    assertThat(request.hasRequestedRuntimeFlagsJson()).isFalse();
    assertThat(descriptor.request().requestedScriptPatchVersion()).isNull();
    assertThat(descriptor.request().sourceVersionId()).isNull();
    assertThat(descriptor.request().targetVersionId()).isNull();
    assertThat(descriptor.request().requestedRuntimeFlagsJson()).isNull();

    var response =
        ResolveLaunchDescriptorResponse.newBuilder()
            .setLaunchDescriptor(
                AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(descriptor))
            .build();
    assertThat(
            AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                descriptor.request(), response))
        .isEqualTo(descriptor);
  }

  @Test
  void getRequestAndResponseKeepReadCorrelationSeparateFromTheOriginalResolveIdentity()
      throws Exception {
    var descriptor = descriptorWithOptionalValues();
    var getRequest = getRequest(descriptor);
    var wireRequest = AuthoredWorldLaunchDescriptorGrpcCodec.toGetRequest(getRequest);

    assertThat(wireRequest.getRequestId()).isEqualTo(READ_REQUEST_ID.toString());
    assertThat(wireRequest.getRequestId())
        .isNotEqualTo(descriptor.authoredWorldSourceOperationId().toString())
        .isNotEqualTo(descriptor.controlPlaneRequestId());
    assertThat(wireRequest.getCanonicalTenantId())
        .isEqualTo(descriptor.canonicalTenantId().toString());
    assertThat(wireRequest.getWorldSlug()).isEqualTo(descriptor.worldSlug());
    assertThat(wireRequest.getControlPlaneRequestId())
        .isEqualTo(descriptor.controlPlaneRequestId());
    assertThat(wireRequest.getExpectedRequestDigest()).isEqualTo(descriptor.requestDigest());
    assertThat(wireRequest.getExpectedResultDigest()).isEqualTo(descriptor.resultDigest());

    var response =
        GetLaunchDescriptorResponse.newBuilder()
            .setRequestId(wireRequest.getRequestId())
            .setLaunchDescriptor(
                AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(descriptor))
            .build();
    assertThat(AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(getRequest, response))
        .isEqualTo(descriptor);
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(
                    getRequest,
                    response.toBuilder().setRequestId(UUID.randomUUID().toString()).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("request ID echo");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(
                    new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
                        READ_REQUEST_ID, descriptor.request(), "sha256:" + "f".repeat(64)),
                    response))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("result digest");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(
                    getRequest,
                    GetLaunchDescriptorResponse.newBuilder()
                        .setRequestId(wireRequest.getRequestId())
                        .setError(ErrorDetail.getDefaultInstance())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rejected");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(
                    getRequest,
                    GetLaunchDescriptorResponse.newBuilder()
                        .setRequestId(wireRequest.getRequestId())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no descriptor");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(
                    getRequest, response.toBuilder().setUnknownFields(UNKNOWN_FIELD).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
                    descriptor.authoredWorldSourceOperationId(),
                    descriptor.request(),
                    descriptor.resultDigest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("distinct");
  }

  @Test
  void completeBindingRoundTripsSupportedReleaseSchemasAndAllMappedSelections() throws Exception {
    var world = authoredWorldFixture();
    var descriptor = world.launchBinding().descriptor();
    var selectedRelease = releaseWithArtifact(world.launchBinding().releaseAttestation());
    var retainedRelease = retainedV1(selectedRelease);
    var closureRelease = closureSelected(selectedRelease);

    for (var release : List.of(retainedRelease, selectedRelease, closureRelease)) {
      var binding = new CompleteLaunchBindingEvidence(descriptor, release);
      var wireRequest = AuthoredWorldLaunchDescriptorGrpcCodec.toGetRequest(getRequest(descriptor));
      var wireRelease = AuthoredWorldLaunchDescriptorGrpcCodec.toReleaseAttestation(release);
      assertThat(wireRelease.getSchemaVersion()).isEqualTo(release.schemaVersion());
      assertThat(wireRelease.getParticipantDigestsCount())
          .isEqualTo(release.participantDigests().size());
      assertThat(wireRelease.getArtifactDigestsCount()).isEqualTo(1);
      assertThat(wireRelease.getArtifactDigests(0).getUsageKey()).isEqualTo("world.navmesh");
      assertThat(wireRelease.getArtifactDigests(0).getImmutableObjectKey())
          .isEqualTo(release.artifactDigests().getFirst().immutableObjectKey());
      assertThat(wireRelease.getParticipantDigests(2).hasAbilitySchemaDigest()).isTrue();
      assertThat(wireRelease.getParticipantDigests(2).getAbilitySchemaDigest())
          .isEqualTo(release.participantDigests().get(2).abilitySchemaDigest());
      if (AuthoredWorldReleaseAttestationEvidence.requiresWorldStartLocationEvidence(
          release.schemaVersion())) {
        assertThat(wireRelease.hasWorldStartLocationEvidence()).isTrue();
        assertThat(wireRelease.getWorldStartLocationEvidence())
            .isEqualTo(ByteString.copyFrom(release.worldStartLocationEvidence().canonicalBytes()));
      } else {
        assertThat(wireRelease.hasWorldStartLocationEvidence()).isFalse();
      }

      var response = completeResponse(wireRequest, binding);
      assertThat(AuthoredWorldLaunchDescriptorGrpcCodec.fromCompleteResponse(wireRequest, response))
          .isEqualTo(binding);
    }

    var unsupported =
        AuthoredWorldLaunchDescriptorGrpcCodec.toReleaseAttestation(selectedRelease).toBuilder()
            .setSchemaVersion(99)
            .build();
    var unsupportedResponse =
        GetCompleteLaunchBindingResponse.newBuilder()
            .setRequestId(READ_REQUEST_ID.toString())
            .setLaunchDescriptor(
                AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(descriptor))
            .setReleaseAttestation(unsupported)
            .build();
    var wireRequest = AuthoredWorldLaunchDescriptorGrpcCodec.toGetRequest(getRequest(descriptor));
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromCompleteResponse(
                    wireRequest, unsupportedResponse))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Release-attestation evidence is invalid");
  }

  @Test
  void resolveAndCompleteReadsRejectErrorsMissingBindingsAndChangedSelectors() throws Exception {
    var world = authoredWorldFixture();
    var descriptor = world.launchBinding().descriptor();
    var wireDescriptor =
        AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(descriptor);
    var resolveResponse =
        ResolveLaunchDescriptorResponse.newBuilder().setLaunchDescriptor(wireDescriptor).build();

    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    descriptor.request(),
                    ResolveLaunchDescriptorResponse.newBuilder()
                        .setError(ErrorDetail.getDefaultInstance())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rejected");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    descriptor.request(), ResolveLaunchDescriptorResponse.getDefaultInstance()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no descriptor");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    descriptor.request(),
                    resolveResponse.toBuilder()
                        .setLaunchDescriptor(wireDescriptor.toBuilder().clearAuthoredWorldBinding())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no authored-world binding");

    var wireRequest = AuthoredWorldLaunchDescriptorGrpcCodec.toGetRequest(getRequest(descriptor));
    var completeResponse = completeResponse(wireRequest, world.launchBinding());
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromCompleteResponse(
                    wireRequest,
                    completeResponse.toBuilder()
                        .setError(ErrorDetail.getDefaultInstance())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rejected");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromCompleteResponse(
                    wireRequest,
                    completeResponse.toBuilder()
                        .setRequestId(UUID.randomUUID().toString())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("request ID echo");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromCompleteResponse(
                    wireRequest,
                    completeResponse.toBuilder().setUnknownFields(UNKNOWN_FIELD).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromCompleteResponse(
                    wireRequest, completeResponse.toBuilder().clearLaunchDescriptor().build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("both descriptor and release");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromCompleteResponse(
                    wireRequest, completeResponse.toBuilder().clearReleaseAttestation().build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("both descriptor and release");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromCompleteResponse(
                    wireRequest.toBuilder()
                        .setExpectedRequestDigest("sha256:" + "f".repeat(64))
                        .build(),
                    completeResponse))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact read selector");
  }

  @Test
  void rejectsUnknownFieldsConflictingFlatDuplicatesAndLegacyTenantSelectors() throws Exception {
    var world = authoredWorldFixture();
    var descriptor = world.launchBinding().descriptor();
    var evidenceMessage =
        AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(descriptor);
    var resolveResponse =
        ResolveLaunchDescriptorResponse.newBuilder().setLaunchDescriptor(evidenceMessage).build();

    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    descriptor.request(),
                    resolveResponse.toBuilder().setUnknownFields(UNKNOWN_FIELD).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    descriptor.request(),
                    resolveResponse.toBuilder()
                        .setLaunchDescriptor(
                            evidenceMessage.toBuilder().setUnknownFields(UNKNOWN_FIELD))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    var bindingWithUnknownField =
        evidenceMessage.getAuthoredWorldBinding().toBuilder()
            .setUnknownFields(UNKNOWN_FIELD)
            .build();
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    descriptor.request(),
                    resolveResponse.toBuilder()
                        .setLaunchDescriptor(
                            evidenceMessage.toBuilder()
                                .setAuthoredWorldBinding(bindingWithUnknownField))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    descriptor.request(),
                    resolveResponse.toBuilder()
                        .setLaunchDescriptor(
                            evidenceMessage.toBuilder().setTenantId("legacy-tenant"))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("legacy tenant");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(
                    descriptor.request(),
                    resolveResponse.toBuilder()
                        .setLaunchDescriptor(evidenceMessage.toBuilder().setVersionId(43L))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match evidence");

    var wireRequest = AuthoredWorldLaunchDescriptorGrpcCodec.toGetRequest(getRequest(descriptor));
    var completeResponse = completeResponse(wireRequest, world.launchBinding());
    var releaseWithUnknownParticipant =
        completeResponse.getReleaseAttestation().toBuilder()
            .setParticipantDigests(
                0,
                completeResponse.getReleaseAttestation().getParticipantDigests(0).toBuilder()
                    .setUnknownFields(UNKNOWN_FIELD))
            .build();
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromCompleteResponse(
                    wireRequest,
                    completeResponse.toBuilder()
                        .setReleaseAttestation(releaseWithUnknownParticipant)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                AuthoredWorldLaunchDescriptorGrpcCodec.fromCompleteResponse(
                    wireRequest.toBuilder().setUnknownFields(UNKNOWN_FIELD).build(),
                    completeResponse))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
  }

  private static WorldCanonicalInstanceLifecycleEvidence authoredWorldFixture() throws Exception {
    return AuthoringFixtures.lifecycleEvidence("ACTIVE", 3L);
  }

  private static AuthoredWorldLaunchDescriptorEvidence descriptorWithOptionalValues()
      throws Exception {
    var base = authoredWorldFixture().launchBinding().descriptor();
    var request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            base.targetNamespace(),
            base.controlPlaneRequestId(),
            base.canonicalTenantId(),
            base.worldSlug(),
            base.authoredWorldSourceOperationId(),
            base.authoredWorldSourceEvidenceDigest(),
            base.gameTemplateId(),
            true,
            "script-patch-v2",
            true,
            41L,
            true,
            42L,
            true,
            "{\"quality\":\"high\"}");
    return AuthoredWorldLaunchDescriptorEvidence.create(
        request,
        "optional-launch-descriptor",
        42L,
        true,
        "script-patch-v2",
        "{\"runtime\":true}",
        "generation-revision",
        9L,
        7L,
        "published-release",
        true,
        "approved-remap-41-42");
  }

  private static AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest getRequest(
      AuthoredWorldLaunchDescriptorEvidence descriptor) {
    return new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
        READ_REQUEST_ID, descriptor.request(), descriptor.resultDigest());
  }

  private static GetCompleteLaunchBindingResponse completeResponse(
      GetLaunchDescriptorRequest request, CompleteLaunchBindingEvidence binding) {
    return GetCompleteLaunchBindingResponse.newBuilder()
        .setRequestId(request.getRequestId())
        .setLaunchDescriptor(
            AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(binding.descriptor()))
        .setReleaseAttestation(
            AuthoredWorldLaunchDescriptorGrpcCodec.toReleaseAttestation(
                binding.releaseAttestation()))
        .build();
  }

  private static AuthoredWorldReleaseAttestationEvidence releaseWithArtifact(
      AuthoredWorldReleaseAttestationEvidence release) {
    String digest = "a".repeat(64);
    var artifact =
        new AuthoredWorldReleaseAttestationEvidence.Artifact(
            "world.navmesh",
            "NAVMESH",
            "artifacts/sha256/" + digest,
            "sha256:" + digest,
            "application/vnd.firemud.navmesh+binary",
            1);
    return AuthoredWorldReleaseAttestationEvidence.create(
        release.targetNamespace(),
        release.descriptorResultDigest(),
        release.canonicalTenantId(),
        release.canonicalVersionId(),
        release.worldSlug(),
        release.authoredWorldSourceOperationId(),
        release.authoredWorldSourceEvidenceDigest(),
        release.launchDescriptorId(),
        release.publishedReleaseBundleRef(),
        release.versionStateEpoch(),
        release.publishWorkflowId(),
        release.commitId(),
        release.participantDigests(),
        release.manifestHash(),
        release.manifestSchemaVersion(),
        List.of(artifact.usageKey()),
        List.of(artifact),
        release.commandDefinitions(),
        release.generationConfigRevision(),
        release.worldStartLocationEvidence());
  }

  private static AuthoredWorldReleaseAttestationEvidence retainedV1(
      AuthoredWorldReleaseAttestationEvidence release) {
    var participants =
        release.participantDigests().stream()
            .map(
                participant ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        participant.participantKey(),
                        participant.scopeValue(),
                        participant.baseVersionIdPresent(),
                        participant.baseVersionId(),
                        participant.appliedCommitId(),
                        participant.contentDigest(),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            participant.participantKey(),
                            AuthoredWorldReleaseAttestationEvidence.SCHEMA_VERSION),
                        participant.abilitySchemaDigestPresent(),
                        participant.abilitySchemaDigest()))
            .toList();
    return AuthoredWorldReleaseAttestationEvidence.create(
        release.targetNamespace(),
        release.descriptorResultDigest(),
        release.canonicalTenantId(),
        release.canonicalVersionId(),
        release.worldSlug(),
        release.authoredWorldSourceOperationId(),
        release.authoredWorldSourceEvidenceDigest(),
        release.launchDescriptorId(),
        release.publishedReleaseBundleRef(),
        release.versionStateEpoch(),
        release.publishWorkflowId(),
        release.commitId(),
        participants,
        release.manifestHash(),
        release.manifestSchemaVersion(),
        release.requiredManifestAssetKeys(),
        release.artifactDigests(),
        release.commandDefinitions(),
        release.generationConfigRevision());
  }

  private static AuthoredWorldReleaseAttestationEvidence closureSelected(
      AuthoredWorldReleaseAttestationEvidence release) {
    var selected = release.worldStartLocationEvidence();
    var original = selected.request();
    var closureSelectorRequest =
        new WorldPublishedStartLocationEvidence.Request(
            original.targetNamespace(),
            original.canonicalTenantId(),
            original.canonicalVersionId(),
            original.intakeRequestId(),
            original.publicationFence(),
            original.publicationRequestId(),
            original.requestDigest(),
            original.versionStateEpoch(),
            original.publishWorkflowId(),
            original.appliedCommitId(),
            original.contentDigest(),
            4,
            original.worldAffectedTuples());
    var closureSelector =
        new WorldPublishedStartLocationEvidence(
            closureSelectorRequest,
            selected.selectorReceiptBytes(),
            selected.originalAccountBindingBytes(),
            selected.appliedResultBytes());
    var participants =
        release.participantDigests().stream()
            .map(
                participant ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        participant.participantKey(),
                        participant.scopeValue(),
                        participant.baseVersionIdPresent(),
                        participant.baseVersionId(),
                        participant.appliedCommitId(),
                        participant.contentDigest(),
                        "WORLD_MANAGEMENT".equals(participant.participantKey())
                            ? 4
                            : participant.digestSchemaVersion(),
                        participant.abilitySchemaDigestPresent(),
                        participant.abilitySchemaDigest()))
            .toList();
    return AuthoredWorldReleaseAttestationEvidence.createClosureSelector(
        release.targetNamespace(),
        release.descriptorResultDigest(),
        release.canonicalTenantId(),
        release.canonicalVersionId(),
        release.worldSlug(),
        release.authoredWorldSourceOperationId(),
        release.authoredWorldSourceEvidenceDigest(),
        release.launchDescriptorId(),
        release.publishedReleaseBundleRef(),
        release.versionStateEpoch(),
        release.publishWorkflowId(),
        release.commitId(),
        participants,
        release.manifestHash(),
        release.manifestSchemaVersion(),
        release.requiredManifestAssetKeys(),
        release.artifactDigests(),
        release.commandDefinitions(),
        release.generationConfigRevision(),
        closureSelector);
  }
}
