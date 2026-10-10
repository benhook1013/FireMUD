package net.firedevops.firemud.common.entity.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeCommandEvidence.Request;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec;
import net.firedevops.firemud.entitymanagement.v1.RetainSelectedEntitySourceRequest;
import net.firedevops.firemud.entitymanagement.v1.RetainSelectedEntitySourceResponse;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures.Fixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Synthetic fixture codec proof does not establish Entity producer or physical census proof. */
class EntitySelectedSourceIntakeCommandGrpcCodecTest {
  private static final String NAMESPACE = "test";
  private static Fixture fixture;

  @BeforeAll
  static void createSyntheticEntityInputs() {
    fixture = EntitySelectedSourceIntakeTerminalReadFixtures.create();
  }

  @Test
  void roundTripsCompleteOriginalAuthorizationFreezeAndCanonicalReceiptOnDistinctCorrelations() {
    var freeze = fixture.inputs().worldInventoryReadEvidence().request().freezeEvidence();
    var binding = fixture.authorization();
    var first = Request.create(NAMESPACE, binding, freeze);
    var retry = Request.create(NAMESPACE, binding, freeze);

    var firstWire = EntitySelectedSourceIntakeCommandGrpcCodec.toRequest(first);
    var retryWire = EntitySelectedSourceIntakeCommandGrpcCodec.toRequest(retry);
    assertThat(first.transportRequestId()).isNotEqualTo(retry.transportRequestId());
    assertThat(first.transportRequestId())
        .isNotIn(
            binding.operationId(),
            binding.fenceId(),
            binding.intakeRequestId(),
            binding.selected().requestId(),
            binding.selected().commitId(),
            freeze.acknowledgement().intakeRequestId(),
            freeze.acknowledgement().publicationFence());
    assertThat(EntitySelectedSourceIntakeCommandGrpcCodec.fromRequest(firstWire)).isEqualTo(first);
    assertThat(EntitySelectedSourceIntakeCommandGrpcCodec.fromRequest(retryWire)).isEqualTo(retry);
    assertThat(firstWire.getOriginalIntakeAuthorizationBinding().toByteArray())
        .isEqualTo(binding.canonicalBytes());
    assertThat(firstWire.getIntakeAuthorizationDigest()).isEqualTo(binding.digest());
    assertThat(firstWire.getFreezeRequest())
        .isEqualTo(WorldSelectedDraftPublicationFreezeGrpcCodec.toRequest(freeze.request()));
    assertThat(firstWire.getFreezeAcknowledgement())
        .isEqualTo(
            WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(freeze.acknowledgement()));
    assertThat(firstWire.getSerializedSize())
        .isLessThanOrEqualTo(EntitySelectedSourceIntakeCommandGrpcCodec.MAX_REQUEST_WIRE_BYTES);

    var evidence = new EntitySelectedSourceIntakeCommandEvidence(first, fixture.receipt());
    RetainSelectedEntitySourceResponse response =
        EntitySelectedSourceIntakeCommandGrpcCodec.toResponse(evidence);
    assertThat(response.getRequest()).isEqualTo(firstWire);
    assertThat(response.getCommittedEmptyReceipt().toByteArray())
        .isEqualTo(fixture.receipt().canonicalBytes());
    assertThat(response.getReceiptDigest()).isEqualTo(fixture.receipt().receiptDigest());
    assertThat(response.getSerializedSize())
        .isLessThanOrEqualTo(EntitySelectedSourceIntakeCommandGrpcCodec.MAX_RESPONSE_WIRE_BYTES);
    var decoded = EntitySelectedSourceIntakeCommandGrpcCodec.fromResponse(first, response);
    assertThat(decoded.request()).isEqualTo(first);
    assertThat(decoded.receipt().canonicalBytes()).isEqualTo(fixture.receipt().canonicalBytes());
    assertThat(decoded.receipt().authorizationBindingBytes()).isEqualTo(binding.canonicalBytes());
  }

  @Test
  void rejectsWrongOwnerNamespaceAndChangedOriginalBindingOrFreeze() {
    var freeze = fixture.inputs().worldInventoryReadEvidence().request().freezeEvidence();
    assertThatThrownBy(() -> Request.create(NAMESPACE, fixture.wrongOwnerAuthorization(), freeze))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Request.create("other", fixture.authorization(), freeze))
        .isInstanceOf(IllegalArgumentException.class);

    var request = Request.create(NAMESPACE, fixture.authorization(), freeze);
    var wire = EntitySelectedSourceIntakeCommandGrpcCodec.toRequest(request);
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeCommandGrpcCodec.fromRequest(
                    wire.toBuilder().setIntakeAuthorizationDigest("0".repeat(64)).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest");

    byte[] changedBinding = fixture.authorization().canonicalBytes();
    changedBinding[changedBinding.length - 1] ^= 1;
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeCommandGrpcCodec.fromRequest(
                    wire.toBuilder()
                        .setOriginalIntakeAuthorizationBinding(ByteString.copyFrom(changedBinding))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);

    var changedFreezeRequest =
        wire.getFreezeRequest().toBuilder().setRequestDigest("0".repeat(64)).build();
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeCommandGrpcCodec.fromRequest(
                    wire.toBuilder().setFreezeRequest(changedFreezeRequest).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("freeze");
    var changedFreezeAck =
        wire.getFreezeAcknowledgement().toBuilder().setAppliedCommitId("0".repeat(36)).build();
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeCommandGrpcCodec.fromRequest(
                    wire.toBuilder().setFreezeAcknowledgement(changedFreezeAck).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("freeze");
  }

  @Test
  void rejectsUnknownFieldsOversizedWireAndChangedFullResponseEchoOrDigest() {
    var freeze = fixture.inputs().worldInventoryReadEvidence().request().freezeEvidence();
    var request = Request.create(NAMESPACE, fixture.authorization(), freeze);
    var requestWire = EntitySelectedSourceIntakeCommandGrpcCodec.toRequest(request);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeCommandGrpcCodec.fromRequest(
                    requestWire.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown");
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeCommandGrpcCodec.fromRequest(
                    requestWire.toBuilder()
                        .setFreezeAcknowledgement(
                            requestWire.getFreezeAcknowledgement().toBuilder()
                                .setUnknownFields(unknown)
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeCommandGrpcCodec.fromRequest(
                    RetainSelectedEntitySourceRequest.newBuilder()
                        .setOriginalIntakeAuthorizationBinding(
                            ByteString.copyFrom(
                                new byte
                                    [EntitySelectedSourceIntakeCommandGrpcCodec
                                            .MAX_REQUEST_WIRE_BYTES
                                        + 1]))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wire budget");

    var response =
        EntitySelectedSourceIntakeCommandGrpcCodec.toResponse(
            new EntitySelectedSourceIntakeCommandEvidence(request, fixture.receipt()));
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeCommandGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setRequest(
                            response.getRequest().toBuilder()
                                .setTransportRequestId(UUID.randomUUID().toString())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed");
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeCommandGrpcCodec.fromResponse(
                    request, response.toBuilder().setReceiptDigest("0".repeat(64)).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest");
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeCommandGrpcCodec.fromResponse(
                    request, response.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown");

    var changedRequest = Request.create(NAMESPACE, fixture.authorization(), freeze);
    assertThatThrownBy(
            () -> EntitySelectedSourceIntakeCommandGrpcCodec.fromResponse(changedRequest, response))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed");
  }

  @Test
  void transportCorrelationMustBeCanonicalNonzeroAndNotReuseRetainedIdentity() {
    var freeze = fixture.inputs().worldInventoryReadEvidence().request().freezeEvidence();
    var binding = fixture.authorization();
    assertThatThrownBy(
            () ->
                new Request(
                    Request.SCHEMA_VERSION, NAMESPACE, binding.operationId(), binding, freeze))
        .isInstanceOf(IllegalArgumentException.class);
    var valid = Request.create(NAMESPACE, binding, freeze);
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeCommandGrpcCodec.fromRequest(
                    EntitySelectedSourceIntakeCommandGrpcCodec.toRequest(valid).toBuilder()
                        .setTransportRequestId("00000000-0000-0000-0000-000000000000")
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UUID");
  }
}
