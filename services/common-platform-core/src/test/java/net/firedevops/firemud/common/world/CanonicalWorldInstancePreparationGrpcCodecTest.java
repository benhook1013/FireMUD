package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Request;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.worldmanagement.v1.PrepareCanonicalWorldInstanceResponse;
import org.junit.jupiter.api.Test;

class CanonicalWorldInstancePreparationGrpcCodecTest {
  private static final long ABOVE_JAVASCRIPT_INTEGER = 9_007_199_254_740_993L;

  @Test
  void roundTripsV2LaunchBindingAndPreservesLargeLifecycleCounters() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence evidence = evidence();
    Request request = request(evidence);

    var wireRequest = CanonicalWorldInstancePreparationGrpcCodec.toRequest(request);
    assertThat(CanonicalWorldInstancePreparationGrpcCodec.fromRequest(wireRequest))
        .isEqualTo(request);

    var response = CanonicalWorldInstancePreparationGrpcCodec.toResponse(request, evidence);
    var decoded =
        CanonicalWorldInstancePreparationGrpcCodec.fromResponse(
            request, evidence.request(), response);
    assertThat(decoded).isEqualTo(evidence);
    assertThat(decoded.launchBinding().releaseAttestation().schemaVersion())
        .isEqualTo(AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION);
    assertThat(decoded.runtimeRoomInstanceId()).isEqualTo(ABOVE_JAVASCRIPT_INTEGER);
    assertThat(decoded.lifecycleEpoch()).isEqualTo(ABOVE_JAVASCRIPT_INTEGER + 1);
    assertThat(decoded.rowVersion()).isEqualTo(ABOVE_JAVASCRIPT_INTEGER + 2);
  }

  @Test
  void rejectsChangedOuterAndInnerTuplesAndCompleteBindingSelectors() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence evidence = evidence();
    Request request = request(evidence);
    var response = CanonicalWorldInstancePreparationGrpcCodec.toResponse(request, evidence);

    var changedOuter =
        response.toBuilder()
            .setRequest(response.getRequest().toBuilder().setWorldSlug("other-world"))
            .build();
    assertThatThrownBy(
            () ->
                CanonicalWorldInstancePreparationGrpcCodec.fromResponse(
                    request, evidence.request(), changedOuter))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed the exact canonical preparation request");

    var changedLifecycleRequest =
        copyLifecycleRequest(
            evidence.request(), uuid("99999999-9999-4999-8999-999999999999"), null, null);
    assertThatThrownBy(
            () ->
                CanonicalWorldInstancePreparationGrpcCodec.fromResponse(
                    request, changedLifecycleRequest, response))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differs from the World lifecycle request");

    Request changedDescriptor =
        new Request(
            request.readRequestId(),
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.gameInstanceUuid(),
            request.controlPlaneRequestId(),
            "different-launch-descriptor",
            request.expectedDescriptorRequestDigest(),
            request.expectedDescriptorResultDigest(),
            request.expectedReleaseAttestationEvidenceDigest());
    assertThatThrownBy(
            () ->
                CanonicalWorldInstancePreparationGrpcCodec.toResponse(changedDescriptor, evidence))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete launch descriptor");

    Request changedDigest =
        new Request(
            request.readRequestId(),
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.gameInstanceUuid(),
            request.controlPlaneRequestId(),
            request.launchDescriptorId(),
            "sha256:" + "f".repeat(64),
            request.expectedDescriptorResultDigest(),
            request.expectedReleaseAttestationEvidenceDigest());
    assertThatThrownBy(
            () -> CanonicalWorldInstancePreparationGrpcCodec.toResponse(changedDigest, evidence))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differs from the World lifecycle request");

    var changedInnerRequest =
        copyLifecycleRequest(
            evidence.request(), null, null, uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    WorldCanonicalInstanceLifecycleEvidence changedInnerEvidence =
        withRequest(evidence, changedInnerRequest);
    var responseWithChangedInner =
        response.toBuilder()
            .setLifecycle(
                WorldCanonicalInstanceLifecycleGrpcCodec.toResponse(
                    changedInnerRequest, changedInnerEvidence))
            .build();
    assertThatThrownBy(
            () ->
                CanonicalWorldInstancePreparationGrpcCodec.fromResponse(
                    request, evidence.request(), responseWithChangedInner))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed exact read request");
  }

  @Test
  void rejectsMissingLifecycleOwnerErrorsAndUnknownNestedFields() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence evidence = evidence();
    Request request = request(evidence);
    var response = CanonicalWorldInstancePreparationGrpcCodec.toResponse(request, evidence);

    assertThatThrownBy(
            () ->
                CanonicalWorldInstancePreparationGrpcCodec.fromResponse(
                    request, evidence.request(), response.toBuilder().clearLifecycle().build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lifecycle response is required");
    assertThatThrownBy(
            () ->
                CanonicalWorldInstancePreparationGrpcCodec.fromResponse(
                    request,
                    evidence.request(),
                    PrepareCanonicalWorldInstanceResponse.newBuilder()
                        .setRequest(CanonicalWorldInstancePreparationGrpcCodec.toRequest(request))
                        .setError(ErrorDetail.newBuilder().setCode("FAILED_PRECONDITION"))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rejected canonical instance preparation");

    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    var unknownOuterRequest =
        response.toBuilder()
            .setRequest(response.getRequest().toBuilder().setUnknownFields(unknown))
            .build();
    assertThatThrownBy(
            () ->
                CanonicalWorldInstancePreparationGrpcCodec.fromResponse(
                    request, evidence.request(), unknownOuterRequest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");

    var unknownLifecycle =
        response.toBuilder()
            .setLifecycle(response.getLifecycle().toBuilder().setUnknownFields(unknown))
            .build();
    assertThatThrownBy(
            () ->
                CanonicalWorldInstancePreparationGrpcCodec.fromResponse(
                    request, evidence.request(), unknownLifecycle))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
  }

  @Test
  void requestDecoderUsesExistingCanonicalSelectorValidation() throws Exception {
    var valid = CanonicalWorldInstancePreparationGrpcCodec.toRequest(request(evidence()));
    assertThatThrownBy(
            () ->
                CanonicalWorldInstancePreparationGrpcCodec.fromRequest(
                    valid.toBuilder()
                        .setReadRequestId("32345678-1234-4234-8234-123456789abc ")
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical non-nil UUID");
    assertThatThrownBy(
            () ->
                CanonicalWorldInstancePreparationGrpcCodec.fromRequest(
                    valid.toBuilder().setTargetNamespace("Bad_Namespace").build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical DNS label");
    assertThatThrownBy(
            () ->
                CanonicalWorldInstancePreparationGrpcCodec.fromRequest(
                    valid.toBuilder().setExpectedDescriptorResultDigest("not-a-digest").build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expectedDescriptorResultDigest");
  }

  private static Request request(WorldCanonicalInstanceLifecycleEvidence evidence) {
    var lifecycleRequest = evidence.request();
    return new Request(
        lifecycleRequest.readRequestId(),
        lifecycleRequest.targetNamespace(),
        lifecycleRequest.canonicalTenantId(),
        lifecycleRequest.worldSlug(),
        lifecycleRequest.canonicalGameInstanceId(),
        lifecycleRequest.controlPlaneRequestId(),
        evidence.launchBinding().descriptor().launchDescriptorId(),
        lifecycleRequest.expectedDescriptorRequestDigest(),
        lifecycleRequest.expectedDescriptorResultDigest(),
        lifecycleRequest.expectedReleaseAttestationDigest());
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request copyLifecycleRequest(
      WorldCanonicalInstanceLifecycleEvidence.Request source,
      UUID readRequestId,
      String worldSlug,
      UUID gameInstanceId) {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        source.schemaVersion(),
        readRequestId == null ? source.readRequestId() : readRequestId,
        source.targetNamespace(),
        source.canonicalTenantId(),
        worldSlug == null ? source.worldSlug() : worldSlug,
        gameInstanceId == null ? source.canonicalGameInstanceId() : gameInstanceId,
        source.playableStateNamespaceId(),
        source.playableStateScope(),
        source.publicProduction(),
        source.controlPlaneRequestId(),
        source.canonicalVersionId(),
        source.expectedDescriptorRequestDigest(),
        source.expectedDescriptorResultDigest(),
        source.expectedReleaseAttestationDigest());
  }

  private static WorldCanonicalInstanceLifecycleEvidence withRequest(
      WorldCanonicalInstanceLifecycleEvidence source,
      WorldCanonicalInstanceLifecycleEvidence.Request request) {
    return new WorldCanonicalInstanceLifecycleEvidence(
        request,
        source.launchBinding(),
        source.startLocation(),
        source.runtimeRoomInstanceId(),
        source.lifecycleStatus(),
        source.lifecycleEpoch(),
        source.rowVersion(),
        source.captureId(),
        source.graphSha256(),
        source.preparationInputDigest(),
        source.operationalRegionAssignments());
  }

  private static WorldCanonicalInstanceLifecycleEvidence evidence() throws Exception {
    WorldPublishedStartLocationEvidence selector =
        WorldPublishedStartLocationGrpcCodecTest.evidence();
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            selector.request().targetNamespace(),
            "world-lifecycle-control-request",
            selector.request().canonicalTenantId(),
            "synthetic-world",
            uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            "sha256:" + "a".repeat(64),
            19L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            "canonical-instance-launch-descriptor",
            42L,
            false,
            null,
            "{}",
            "generation-revision",
            9L,
            7L,
            "release-bundle",
            false,
            null);
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        selector.request().appliedCommitId(),
                        selector.request().contentDigest(),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "c".repeat(64) : null))
            .toList();
    AuthoredWorldReleaseAttestationEvidence release =
        AuthoredWorldReleaseAttestationEvidence.create(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            selector.request().canonicalVersionId(),
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            selector.request().publishWorkflowId(),
            selector.request().appliedCommitId(),
            participants,
            "sha256:" + "d".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision(),
            selector);
    CompleteLaunchBindingEvidence binding = new CompleteLaunchBindingEvidence(descriptor, release);
    WorldDraftStartLocationEvidence selectorReceipt =
        WorldDraftStartLocationEvidence.fromStored(selector.selectorReceiptBytes());
    var request =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            descriptor.targetNamespace(),
            descriptor.canonicalTenantId(),
            descriptor.worldSlug(),
            uuid("11111111-1111-4111-8111-111111111111"),
            uuid("22222222-2222-4222-8222-222222222222"),
            "SHARED",
            true,
            descriptor.controlPlaneRequestId(),
            release.canonicalVersionId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    return new WorldCanonicalInstanceLifecycleEvidence(
        request,
        binding,
        selectorReceipt.startLocation(),
        ABOVE_JAVASCRIPT_INTEGER,
        "PREPARING",
        ABOVE_JAVASCRIPT_INTEGER + 1,
        ABOVE_JAVASCRIPT_INTEGER + 2,
        uuid("33333333-3333-4333-8333-333333333333"),
        selectorReceipt.graphDigest().substring("sha256:".length()),
        "sha256:" + "e".repeat(64),
        java.util.Map.of(
            uuid("11111111-1111-4111-8111-111111111111"),
            uuid("22222222-2222-4222-8222-222222222222")));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
