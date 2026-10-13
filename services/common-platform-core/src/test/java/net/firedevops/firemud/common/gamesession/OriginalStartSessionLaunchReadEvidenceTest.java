package net.firedevops.firemud.common.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodecTest;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Result;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import org.junit.jupiter.api.Test;

class OriginalStartSessionLaunchReadEvidenceTest {
  private static final UUID READ_ID = uuid("01111111-1111-4111-8111-111111111111");
  private static final UUID NEXT_READ_ID = uuid("02222222-2222-4222-8222-222222222222");
  private static final UUID GAME_INSTANCE_ID = uuid("03333333-3333-4333-8333-333333333333");
  private static final UUID PLAYABLE_NAMESPACE_ID = uuid("04444444-4444-4444-8444-444444444444");
  private static final UUID REALM_ID = uuid("05555555-5555-4555-8555-555555555555");

  static OriginalStartSessionLaunchReadEvidence.Request requestForClientTest() throws Exception {
    return fixture().request();
  }

  @Test
  void requestAndResultNormalizeOnlyTheTransientSelectorReadId() throws Exception {
    Fixture fixture = fixture();
    var nextSelector = selector(fixture.request().selector(), NEXT_READ_ID);
    var nextRequest =
        new OriginalStartSessionLaunchReadEvidence.Request(nextSelector, fixture.tupleBytes());
    var nextLaunch = launch(fixture.result().launch(), nextSelector);
    var nextResult =
        new OriginalStartSessionLaunchReadEvidence.Result(
            nextRequest, nextLaunch, fixture.tupleBytes());

    assertThat(
            OriginalStartSessionLaunchReadEvidence.Request.decode(
                fixture.request().canonicalBytes()))
        .isEqualTo(fixture.request());
    assertThat(nextRequest.canonicalBytes()).isNotEqualTo(fixture.request().canonicalBytes());
    assertThat(nextRequest.operationIdentityBytes())
        .containsExactly(fixture.request().operationIdentityBytes());
    var originalSelector = fixture.request().selector();
    var changedSelector =
        new Request(
            originalSelector.readRequestId(),
            originalSelector.targetNamespace(),
            originalSelector.canonicalTenantId(),
            originalSelector.worldSlug(),
            originalSelector.gameInstanceUuid(),
            originalSelector.controlPlaneRequestId(),
            originalSelector.launchDescriptorId(),
            originalSelector.expectedDescriptorRequestDigest(),
            "sha256:" + "e".repeat(64),
            originalSelector.expectedReleaseAttestationEvidenceDigest());
    var changedRequest =
        new OriginalStartSessionLaunchReadEvidence.Request(changedSelector, fixture.tupleBytes());
    assertThat(changedRequest.operationIdentityBytes())
        .isNotEqualTo(fixture.request().operationIdentityBytes());
    assertThat(
            OriginalStartSessionLaunchReadEvidence.Result.decode(fixture.result().canonicalBytes()))
        .isEqualTo(fixture.result());
    assertThat(nextResult.canonicalBytes()).isNotEqualTo(fixture.result().canonicalBytes());
    assertThat(nextResult.operationIdentityBytes())
        .containsExactly(fixture.result().operationIdentityBytes());
    assertThat(nextResult.operationIdentityBytes())
        .isNotEqualTo(
            new OriginalStartSessionLaunchReadEvidence.Result(
                    fixture.request(),
                    launch(
                        fixture.result().launch(),
                        selector(
                            fixture.request().selector(),
                            fixture.request().selector().readRequestId()),
                        fixture.result().launch().currentRowVersion() + 1L),
                    fixture.tupleBytes())
                .operationIdentityBytes());
  }

  @Test
  void resultRejectsDifferentSelectorOrRetainedTuple() throws Exception {
    Fixture fixture = fixture();
    byte[] changedTuple = fixture.tupleBytes().clone();
    changedTuple[changedTuple.length - 2] ^= 0x01;

    assertThatThrownBy(
            () ->
                new OriginalStartSessionLaunchReadEvidence.Result(
                    fixture.request(), fixture.result().launch(), changedTuple))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new OriginalStartSessionLaunchReadEvidence.Result(
                    new OriginalStartSessionLaunchReadEvidence.Request(
                        selector(fixture.request().selector(), NEXT_READ_ID), fixture.tupleBytes()),
                    fixture.result().launch(),
                    fixture.tupleBytes()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact selector");
  }

  @Test
  void grpcCodecRoundTripsTheFullTupleAndRejectsChangedEchoesAndUnknownFields() throws Exception {
    Fixture fixture = fixture();
    var wireRequest = OriginalStartSessionLaunchReadGrpcCodec.toRequest(fixture.request());
    var wireResponse = OriginalStartSessionLaunchReadGrpcCodec.toResponse(fixture.result());

    assertThat(OriginalStartSessionLaunchReadGrpcCodec.fromRequest(wireRequest))
        .isEqualTo(fixture.request());
    assertThat(
            OriginalStartSessionLaunchReadGrpcCodec.fromResponse(fixture.request(), wireResponse))
        .isEqualTo(fixture.result());
    assertThat(wireResponse.getRetainedOriginalPostAuthorizationTupleBytes().toByteArray())
        .containsExactly(fixture.tupleBytes());

    assertThatThrownBy(
            () ->
                OriginalStartSessionLaunchReadGrpcCodec.fromRequest(
                    wireRequest.toBuilder().setUnknownFields(unknown(91)).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                OriginalStartSessionLaunchReadGrpcCodec.fromResponse(
                    fixture.request(),
                    wireResponse.toBuilder().setUnknownFields(unknown(92)).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                OriginalStartSessionLaunchReadGrpcCodec.fromResponse(
                    fixture.request(),
                    wireResponse.toBuilder()
                        .setRetainedOriginalPostAuthorizationTupleBytes(
                            com.google.protobuf.ByteString.copyFromUtf8("different"))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Fixture fixture() throws Exception {
    var launchFixture = StartSessionLaunchDescriptorGrpcCodecTest.fixture();
    AuthoredWorldLaunchDescriptorEvidence descriptor = launchFixture.descriptor();
    var publishedLocation = launchFixture.associationRead().worldPublishedStartLocationEvidence();
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        publishedLocation.request().appliedCommitId(),
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
            publishedLocation.request().canonicalVersionId(),
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            publishedLocation.request().publishWorkflowId(),
            publishedLocation.request().appliedCommitId(),
            participants,
            "sha256:" + "d".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of("LOOK"),
            descriptor.generationConfigRevision(),
            publishedLocation);
    var binding = new CompleteLaunchBindingEvidence(descriptor, release);
    Request selector =
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
    Result launch =
        new Result(
            selector,
            PLAYABLE_NAMESPACE_ID,
            RealmEntryPolicy.StateScope.SHARED,
            true,
            REALM_ID,
            17L,
            CurrentGameInstanceStatus.STARTING,
            12L,
            binding);
    byte[] tupleBytes = launchFixture.request().decodedTuple().canonicalBytes();
    var request = new OriginalStartSessionLaunchReadEvidence.Request(selector, tupleBytes);
    return new Fixture(
        request,
        new OriginalStartSessionLaunchReadEvidence.Result(request, launch, tupleBytes),
        tupleBytes);
  }

  private static Request selector(Request original, UUID readRequestId) {
    return new Request(
        readRequestId,
        original.targetNamespace(),
        original.canonicalTenantId(),
        original.worldSlug(),
        original.gameInstanceUuid(),
        original.controlPlaneRequestId(),
        original.launchDescriptorId(),
        original.expectedDescriptorRequestDigest(),
        original.expectedDescriptorResultDigest(),
        original.expectedReleaseAttestationEvidenceDigest());
  }

  private static Result launch(Result original, Request request) {
    return launch(original, request, original.currentRowVersion());
  }

  private static Result launch(Result original, Request request, long rowVersion) {
    return new Result(
        request,
        original.playableStateNamespaceId(),
        original.playableStateScope(),
        original.publicProduction(),
        original.canonicalRealmId(),
        original.catalogRevision(),
        original.currentGameInstanceStatus(),
        rowVersion,
        original.launchBindingEvidence());
  }

  private static UnknownFieldSet unknown(int number) {
    return UnknownFieldSet.newBuilder()
        .addField(number, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      OriginalStartSessionLaunchReadEvidence.Request request,
      OriginalStartSessionLaunchReadEvidence.Result result,
      byte[] tupleBytes) {
    private Fixture {
      tupleBytes = tupleBytes.clone();
    }

    @Override
    public byte[] tupleBytes() {
      return tupleBytes.clone();
    }
  }
}
