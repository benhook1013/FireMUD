package net.firedevops.firemud.common.entity.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadGrpcCodec;
import net.firedevops.firemud.entitymanagement.v1.EntitySelectedSourceIntakeTerminalResult;
import net.firedevops.firemud.entitymanagement.v1.ReadSelectedSourceIntakeTerminalRequest;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures.Fixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EntitySelectedSourceIntakeTerminalReadGrpcCodecTest {
  private static Fixture fixture;

  @BeforeAll
  static void createSyntheticFixture() {
    fixture = EntitySelectedSourceIntakeTerminalReadFixtures.create();
  }

  @Test
  void roundTripsCompleteCanonicalReceiptAndOriginalBindingBytes() {
    byte[] bindingBytes = fixture.authorization().canonicalBytes();
    byte[] receiptBytes = fixture.receipt().canonicalBytes();
    var request =
        EntitySelectedSourceIntakeTerminalReadEvidence.Request.create(
            "test", fixture.authorization());
    var requestWire = EntitySelectedSourceIntakeTerminalReadGrpcCodec.toRequest(request);
    var decodedRequest = EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromRequest(requestWire);

    assertThat(decodedRequest).isEqualTo(request);
    assertThat(decodedRequest.readRequestId())
        .isNotIn(
            fixture.authorization().operationId(),
            fixture.authorization().fenceId(),
            fixture.authorization().intakeRequestId());
    assertThat(decodedRequest.binding().canonicalBytes()).isEqualTo(bindingBytes);
    assertThat(decodedRequest.binding().digest()).isEqualTo(fixture.authorization().digest());

    var response =
        EntitySelectedSourceIntakeTerminalReadGrpcCodec.toResponse(
            new EntitySelectedSourceIntakeTerminalReadEvidence(request, fixture.receipt()));
    assertThat(response.getSerializedSize()).isGreaterThan(4 * 1024 * 1024);
    assertThat(response.getResult())
        .isEqualTo(
            EntitySelectedSourceIntakeTerminalResult
                .ENTITY_SELECTED_SOURCE_INTAKE_TERMINAL_RESULT_COMMITTED_EMPTY);
    assertThat(response.getRequest()).isEqualTo(requestWire);
    var decoded = EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromResponse(request, response);
    assertThat(decoded.request()).isEqualTo(request);
    assertThat(decoded.receipt().canonicalBytes()).isEqualTo(receiptBytes);
    assertThat(decoded.receipt().authorizationBindingBytes()).isEqualTo(bindingBytes);
    assertThat(decoded.receipt().authorizationBindingDigest())
        .isEqualTo(fixture.authorization().digest());
    assertThat(decoded.receipt().selectedSourceBytes())
        .isEqualTo(fixture.inputs().sourceContent().canonicalBytes());
    assertThat(decoded.receipt().selectedSourceDigest())
        .isEqualTo(fixture.inputs().sourceContent().digest());
    assertThat(decoded.receipt().selectedSourceRevisionBindingDigest())
        .isEqualTo(fixture.inputs().ownerSourceInventoryDeclaration().sourceBinding().digest());
    byte[] originalWorldRequest =
        SelectedOwnerWorldInventoryReadGrpcCodec.toRequest(
                fixture.inputs().worldInventoryReadEvidence().request())
            .toByteArray();
    assertThat(decoded.receipt().worldReadRequestBytes()).isEqualTo(originalWorldRequest);
    assertThat(decoded.receipt().worldReadRequestDigest())
        .isEqualTo(DraftAuthorizationFenceBinding.digest(originalWorldRequest));
    assertThat(decoded.receipt().worldClosureBytes())
        .isEqualTo(fixture.inputs().worldInventoryReadEvidence().inventory().canonicalBytes());
    assertThat(decoded.receipt().worldClosureDigest())
        .isEqualTo(fixture.inputs().worldInventoryReadEvidence().inventory().digest());
    assertThat(decoded.receipt().familyStates()).hasSize(23);
    assertThat(EntityEmptySelectedSourceIntakeReceipt.fromStored(receiptBytes).canonicalBytes())
        .isEqualTo(receiptBytes);
  }

  @Test
  void rejectsNamespaceReaderPurposeOwnerAndAuthorizationDrift() {
    var binding = fixture.authorization();
    assertThatThrownBy(
            () ->
                new EntitySelectedSourceIntakeTerminalReadEvidence.Request(
                    1, "other", UUID.randomUUID(), binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new EntitySelectedSourceIntakeTerminalReadEvidence.Request(
                    1, "test", UUID.randomUUID(), fixture.wrongOwnerAuthorization()))
        .isInstanceOf(IllegalArgumentException.class);

    var request = EntitySelectedSourceIntakeTerminalReadGrpcCodec.toRequest(request());
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromRequest(
                    request.toBuilder()
                        .setIntendedReader("spiffe://firemud/ns/test/sa/entity-management-service")
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromRequest(
                    request.toBuilder().setTerminalReadPurpose("ENTITY_INTAKE_RETENTION").build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromRequest(
                    request.toBuilder()
                        .setIntakeAuthorizationDigest("sha256:" + "0".repeat(64))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);

    byte[] changedBinding = request.getOriginalIntakeAuthorizationBinding().toByteArray();
    changedBinding[4] ^= 1;
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromRequest(
                    request.toBuilder()
                        .setOriginalIntakeAuthorizationBinding(ByteString.copyFrom(changedBinding))
                        .setIntakeAuthorizationDigest(
                            DraftAuthorizationFenceBinding.digest(changedBinding))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsChangedReceiptDigestEchoUnknownFieldsAndTrailingBytes() {
    var request = request();
    var response =
        EntitySelectedSourceIntakeTerminalReadGrpcCodec.toResponse(
            new EntitySelectedSourceIntakeTerminalReadEvidence(request, fixture.receipt()));

    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setResult(
                            EntitySelectedSourceIntakeTerminalResult
                                .ENTITY_SELECTED_SOURCE_INTAKE_TERMINAL_RESULT_UNSPECIFIED)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder().setReceiptDigest("sha256:" + "0".repeat(64)).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setRequest(
                            response.getRequest().toBuilder()
                                .setReadRequestId("12121212-1212-4212-8212-121212121212"))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);

    var requestUnknown =
        ReadSelectedSourceIntakeTerminalRequest.newBuilder()
            .mergeFrom(EntitySelectedSourceIntakeTerminalReadGrpcCodec.toRequest(request))
            .setUnknownFields(unknownFields())
            .build();
    assertThatThrownBy(
            () -> EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromRequest(requestUnknown))
        .isInstanceOf(IllegalArgumentException.class);
    var responseUnknown = response.toBuilder().setUnknownFields(unknownFields()).build();
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromResponse(
                    request, responseUnknown))
        .isInstanceOf(IllegalArgumentException.class);

    byte[] trailing =
        java.util.Arrays.copyOf(
            response.getCommittedEmptyReceipt().toByteArray(),
            response.getCommittedEmptyReceipt().size() + 1);
    trailing[trailing.length - 1] = 0x55;
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setCommittedEmptyReceipt(ByteString.copyFrom(trailing))
                        .setReceiptDigest(DraftAuthorizationFenceBinding.digest(trailing))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);

    byte[] changedReceipt = response.getCommittedEmptyReceipt().toByteArray();
    changedReceipt[changedReceipt.length - 1] ^= 1;
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setCommittedEmptyReceipt(ByteString.copyFrom(changedReceipt))
                        .setReceiptDigest(DraftAuthorizationFenceBinding.digest(changedReceipt))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsOverBudgetRequestAndReceipt() {
    var request = request();
    byte[] oversizedBinding =
        new byte
            [net.firedevops.firemud.common.account.sourceintake
                    .SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES
                + 1];
    var oversizedRequest =
        EntitySelectedSourceIntakeTerminalReadGrpcCodec.toRequest(request).toBuilder()
            .setOriginalIntakeAuthorizationBinding(ByteString.copyFrom(oversizedBinding))
            .build();
    assertThatThrownBy(
            () -> EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromRequest(oversizedRequest))
        .isInstanceOf(IllegalArgumentException.class);

    byte[] oversizedReceipt = new byte[EntityEmptySelectedSourceIntakeReceipt.MAX_BYTES + 1];
    var oversizedResponse =
        EntitySelectedSourceIntakeTerminalReadGrpcCodec.toResponse(
                new EntitySelectedSourceIntakeTerminalReadEvidence(request, fixture.receipt()))
            .toBuilder()
            .setCommittedEmptyReceipt(ByteString.copyFrom(oversizedReceipt))
            .build();
    assertThatThrownBy(
            () ->
                EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromResponse(
                    request, oversizedResponse))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static EntitySelectedSourceIntakeTerminalReadEvidence.Request request() {
    return EntitySelectedSourceIntakeTerminalReadEvidence.Request.create(
        "test", fixture.authorization());
  }

  private static UnknownFieldSet unknownFields() {
    return UnknownFieldSet.newBuilder()
        .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }
}
