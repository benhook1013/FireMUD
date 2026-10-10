package net.firedevops.firemud.common.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodecTest;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Result;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import org.junit.jupiter.api.Test;

class CanonicalGameInstanceLaunchAssociationReadGrpcCodecTest {
  private static final UUID READ_ID = uuid("10000000-0000-4000-8000-000000000001");
  private static final UUID GAME_INSTANCE_ID = uuid("20000000-0000-4000-8000-000000000002");
  private static final UUID PLAYABLE_NAMESPACE_ID = uuid("30000000-0000-4000-8000-000000000003");
  private static final UUID REALM_ID = uuid("40000000-0000-4000-8000-abcd00000004");

  @Test
  void roundTripsTheIndependentReadSelectorAndCompleteCapturedBinding() throws Exception {
    Fixture fixture = fixture();

    var wireRequest =
        CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toRequest(fixture.request());
    var decodedRequest =
        CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromRequest(wireRequest);
    var response = CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toResponse(fixture.result());
    var decoded =
        CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
            fixture.request(), response);

    assertThat(decodedRequest).isEqualTo(fixture.request());
    assertThat(decodedRequest.readRequestId().toString())
        .isNotEqualTo(fixture.request().controlPlaneRequestId());
    assertThat(decodedRequest.readRequestId())
        .isNotEqualTo(
            fixture.result().launchBindingEvidence().descriptor().authoredWorldSourceOperationId());
    assertThat(decodedRequest.controlPlaneRequestId())
        .isEqualTo(fixture.request().controlPlaneRequestId());
    assertThat(decoded).isEqualTo(fixture.result());
    assertThat(decoded.canonicalRealmId()).isEqualTo(REALM_ID);
    assertThat(decoded.catalogRevision()).isEqualTo(17L);
    assertThat(response.hasLaunchDescriptor()).isTrue();
    assertThat(response.hasReleaseAttestation()).isTrue();

    var expectedPair =
        AuthoredWorldLaunchDescriptorGrpcCodec.toCompleteResponse(
            completeBindingRequest(fixture.request()), fixture.result().launchBindingEvidence());
    assertThat(response.getLaunchDescriptor()).isEqualTo(expectedPair.getLaunchDescriptor());
    assertThat(response.getReleaseAttestation()).isEqualTo(expectedPair.getReleaseAttestation());
  }

  @Test
  void rejectsMissingMalformedNilAndNoncanonicalRealmIdentity() throws Exception {
    Fixture fixture = fixture();
    var response = CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toResponse(fixture.result());

    assertThatThrownBy(() -> result(fixture, new UUID(0L, 0L), 17L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonicalRealmId");
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    fixture.request(), response.toBuilder().clearCanonicalRealmId().build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    fixture.request(),
                    response.toBuilder().setCanonicalRealmId("not-a-uuid").build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    fixture.request(),
                    response.toBuilder()
                        .setCanonicalRealmId(REALM_ID.toString().toUpperCase(Locale.ROOT))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    fixture.request(),
                    response.toBuilder().setCanonicalRealmId(new UUID(0L, 0L).toString()).build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMissingZeroAndNegativeCatalogRevision() throws Exception {
    Fixture fixture = fixture();
    var response = CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toResponse(fixture.result());

    assertThatThrownBy(() -> result(fixture, REALM_ID, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("catalogRevision");
    assertThatThrownBy(() -> result(fixture, REALM_ID, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("catalogRevision");
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    fixture.request(), response.toBuilder().clearCatalogRevision().build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Catalog revision");
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    fixture.request(), response.toBuilder().setCatalogRevision(-1L).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Catalog revision");
  }

  @Test
  void rejectsChangedOriginalEchoMissingPairAndUnknownFields() throws Exception {
    Fixture fixture = fixture();
    var response = CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toResponse(fixture.result());

    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    fixture.request(),
                    response.toBuilder().setControlPlaneRequestId("changed").build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("controlPlaneRequestId echo changed");
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    fixture.request(), response.toBuilder().clearLaunchDescriptor().build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete descriptor and release attestation pair");
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    fixture.request(), response.toBuilder().clearReleaseAttestation().build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete descriptor and release attestation pair");
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    fixture.request(), response.toBuilder().setUnknownFields(unknown(91)).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromRequest(
                    CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toRequest(fixture.request())
                        .toBuilder()
                        .setUnknownFields(unknown(92))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
  }

  @Test
  void rejectsUnknownNestedDescriptorAndReleaseEvidence() throws Exception {
    Fixture fixture = fixture();
    var response = CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toResponse(fixture.result());
    var unknownDescriptor =
        response.toBuilder()
            .setLaunchDescriptor(
                response.getLaunchDescriptor().toBuilder().setUnknownFields(unknown(93)).build())
            .build();
    var unknownRelease =
        response.toBuilder()
            .setReleaseAttestation(
                response.getReleaseAttestation().toBuilder().setUnknownFields(unknown(94)).build())
            .build();

    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    fixture.request(), unknownDescriptor))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    fixture.request(), unknownRelease))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
  }

  private static Fixture fixture() throws Exception {
    var launchFixture = StartSessionLaunchDescriptorGrpcCodecTest.fixture();
    AuthoredWorldLaunchDescriptorEvidence descriptor = launchFixture.descriptor();
    var selector = launchFixture.associationRead().worldPublishedStartLocationEvidence();
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        selector.request().appliedCommitId(),
                        "b".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "c".repeat(64) : null))
            .toList();
    var release =
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
            List.of("LOOK"),
            descriptor.generationConfigRevision(),
            selector);
    var binding = new CompleteLaunchBindingEvidence(descriptor, release);
    Request request =
        new Request(
            READ_ID,
            descriptor.targetNamespace(),
            descriptor.canonicalTenantId(),
            descriptor.worldSlug(),
            GAME_INSTANCE_ID,
            descriptor.controlPlaneRequestId(),
            descriptor.launchDescriptorId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    Result result =
        new Result(
            request,
            PLAYABLE_NAMESPACE_ID,
            RealmEntryPolicy.StateScope.SHARED,
            true,
            REALM_ID,
            17L,
            CurrentGameInstanceStatus.STARTING,
            12L,
            binding);
    return new Fixture(request, result);
  }

  private static Result result(Fixture fixture, UUID canonicalRealmId, long catalogRevision) {
    Result original = fixture.result();
    return new Result(
        original.request(),
        original.playableStateNamespaceId(),
        original.playableStateScope(),
        original.publicProduction(),
        canonicalRealmId,
        catalogRevision,
        original.currentGameInstanceStatus(),
        original.currentRowVersion(),
        original.launchBindingEvidence());
  }

  private static GetLaunchDescriptorRequest completeBindingRequest(Request request) {
    return GetLaunchDescriptorRequest.newBuilder()
        .setRequestId(request.readRequestId().toString())
        .setCanonicalTenantId(request.canonicalTenantId().toString())
        .setWorldSlug(request.worldSlug())
        .setControlPlaneRequestId(request.controlPlaneRequestId())
        .setExpectedRequestDigest(request.expectedDescriptorRequestDigest())
        .setExpectedResultDigest(request.expectedDescriptorResultDigest())
        .build();
  }

  private static UnknownFieldSet unknown(int fieldNumber) {
    return UnknownFieldSet.newBuilder()
        .addField(fieldNumber, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(Request request, Result result) {}
}
