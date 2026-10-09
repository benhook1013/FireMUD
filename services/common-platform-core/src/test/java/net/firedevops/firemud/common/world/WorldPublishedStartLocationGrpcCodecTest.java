package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedStartLocationResponse;
import org.junit.jupiter.api.Test;

class WorldPublishedStartLocationGrpcCodecTest {
  private static final String LARGE_EPOCH = "900719925474099312345678901234567890";

  @Test
  void exactSelectionAndRetainedEvidenceRoundTripAcrossReadRetries() throws Exception {
    WorldPublishedStartLocationEvidence expected = evidence();
    var wireRequest = WorldPublishedStartLocationGrpcCodec.toRequest(expected.request());
    assertThat(WorldPublishedStartLocationGrpcCodec.fromRequest(wireRequest))
        .isEqualTo(expected.request());

    var firstResponse =
        WorldPublishedStartLocationGrpcCodec.toResponse(expected.request(), expected);
    var retryResponse =
        WorldPublishedStartLocationGrpcCodec.toResponse(expected.request(), expected);
    assertThat(retryResponse).isEqualTo(firstResponse);
    var first =
        WorldPublishedStartLocationGrpcCodec.fromResponse(expected.request(), firstResponse);
    var retry =
        WorldPublishedStartLocationGrpcCodec.fromResponse(expected.request(), retryResponse);
    assertThat(first.canonicalBytes()).containsExactly(expected.canonicalBytes());
    assertThat(retry.canonicalBytes()).containsExactly(first.canonicalBytes());
    assertThat(
            WorldPublishedStartLocationEvidence.fromStored(first.canonicalBytes()).canonicalBytes())
        .containsExactly(first.canonicalBytes());
    var decoded = WorldPublishedStartLocationEvidence.fromStored(first.canonicalBytes());
    assertThat(decoded).isEqualTo(expected);
    assertThat(decoded.hashCode()).isEqualTo(expected.hashCode());
  }

  @Test
  void rejectsChangedSelectionAndSubstitutedReceiptAccountOrAppliedBytes() throws Exception {
    WorldPublishedStartLocationEvidence evidence = evidence();
    ReadWorldPublishedStartLocationResponse response =
        WorldPublishedStartLocationGrpcCodec.toResponse(evidence.request(), evidence);

    assertThatThrownBy(
            () ->
                WorldPublishedStartLocationGrpcCodec.fromResponse(
                    evidence.request(),
                    response.toBuilder()
                        .setPublicationFence(
                            uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd").toString())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed exact selection");

    assertThatThrownBy(
            () ->
                WorldPublishedStartLocationGrpcCodec.fromResponse(
                    evidence.request(),
                    response.toBuilder()
                        .setSelectorReceiptBytes(ByteString.copyFrom(new byte[] {1}))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldPublishedStartLocationGrpcCodec.fromResponse(
                    evidence.request(),
                    response.toBuilder()
                        .setOriginalAccountBindingBytes(ByteString.copyFrom(new byte[] {1}))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldPublishedStartLocationGrpcCodec.fromResponse(
                    evidence.request(),
                    response.toBuilder()
                        .setAppliedResultBytes(ByteString.copyFrom(new byte[] {1}))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new WorldPublishedStartLocationEvidence(
                    evidence.request(),
                    evidence.selectorReceiptBytes(),
                    evidence.originalAccountBindingBytes(),
                    new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class);

    var changedIntake =
        new WorldPublishedStartLocationEvidence.Request(
            evidence.request().targetNamespace(),
            evidence.request().canonicalTenantId(),
            evidence.request().canonicalVersionId(),
            uuid("99999999-9999-4999-8999-999999999999"),
            evidence.request().publicationFence(),
            evidence.request().publicationRequestId(),
            evidence.request().requestDigest(),
            evidence.request().versionStateEpoch(),
            evidence.request().publishWorkflowId(),
            evidence.request().appliedCommitId(),
            evidence.request().contentDigest(),
            evidence.request().digestSchemaVersion(),
            evidence.request().worldAffectedTuples());
    assertThatThrownBy(
            () ->
                new WorldPublishedStartLocationEvidence(
                    changedIntake,
                    evidence.selectorReceiptBytes(),
                    evidence.originalAccountBindingBytes(),
                    evidence.appliedResultBytes()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("intake request");
  }

  @Test
  void requestCarrierAcceptsUnboundedDraftEpochAndRejectsMalformedCounters() throws Exception {
    WorldPublishedStartLocationEvidence.Request base = evidence().request();
    var tuple = base.worldAffectedTuples().getFirst();
    var largeEpochTuple =
        new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
            tuple.owner(),
            tuple.aggregateType(),
            tuple.aggregateId(),
            tuple.scopeType(),
            tuple.scopeId(),
            LARGE_EPOCH);
    var carrierRequest =
        new WorldPublishedStartLocationEvidence.Request(
            base.targetNamespace(),
            base.canonicalTenantId(),
            base.canonicalVersionId(),
            base.intakeRequestId(),
            base.publicationFence(),
            base.publicationRequestId(),
            base.requestDigest(),
            base.versionStateEpoch(),
            base.publishWorkflowId(),
            base.appliedCommitId(),
            base.contentDigest(),
            base.digestSchemaVersion(),
            List.of(largeEpochTuple));
    var roundTrip =
        WorldPublishedStartLocationGrpcCodec.fromRequest(
            WorldPublishedStartLocationGrpcCodec.toRequest(carrierRequest));

    assertThat(roundTrip).isEqualTo(carrierRequest);
    assertThat(roundTrip.worldAffectedTuples().getFirst().expectedEpoch()).isEqualTo(LARGE_EPOCH);

    assertInvalidExpectedEpoch(tuple, null);
    assertInvalidExpectedEpoch(tuple, "-1");
    assertInvalidExpectedEpoch(tuple, "01");
    assertInvalidExpectedEpoch(tuple, "not-a-decimal");
  }

  static WorldPublishedStartLocationEvidence evidence() throws Exception {
    return AuthoringFixtures.startLocationEvidence();
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static void assertInvalidExpectedEpoch(
      WorldPublishedStartLocationEvidence.OwnedAffectedTuple template, String expectedEpoch) {
    assertThatThrownBy(
            () ->
                new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                    template.owner(),
                    template.aggregateType(),
                    template.aggregateId(),
                    template.scopeType(),
                    template.scopeId(),
                    expectedEpoch))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical non-negative decimal");
  }
}
