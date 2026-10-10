package net.firedevops.firemud.common.account.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeSettlementRequest;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSettlementEvidence.Request;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import org.junit.jupiter.api.Test;

/** Closed-schema codec tests; these values do not establish Account or Automation authority. */
class SelectedOwnerIntakeSettlementGrpcCodecTest {
  @Test
  void roundTripsCanonicalRequestWithIndependentTransportCorrelation() {
    var binding =
        SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(Owner.AUTOMATION_SCRIPTING);
    var request = Request.create("test", binding);
    assertThat(SelectedOwnerIntakeSettlementEvidence.MAX_REQUEST_BYTES)
        .isEqualTo(SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES + 64 * 1024);
    assertThat(SelectedOwnerIntakeSettlementEvidence.MAX_RESPONSE_BYTES)
        .isEqualTo(
            AccountSelectedOwnerIntakeSettlementReceipt.MAX_BYTES
                + SelectedOwnerIntakeSettlementEvidence.MAX_REQUEST_BYTES
                + 64 * 1024);
    var wire = SelectedOwnerIntakeSettlementGrpcCodec.toRequest(request);

    assertThat(wire.getSchemaVersion()).isEqualTo(1);
    assertThat(wire.getTargetNamespace()).isEqualTo("test");
    assertThat(wire.getTransportRequestId()).isEqualTo(request.transportRequestId().toString());
    assertThat(wire.getTransportRequestId())
        .isNotIn(
            binding.operationId().toString(),
            binding.fenceId().toString(),
            binding.intakeRequestId().toString());
    assertThat(wire.getOriginalIntakeAuthorizationBinding().toByteArray())
        .containsExactly(binding.canonicalBytes());
    assertThat(wire.getIntakeAuthorizationDigest()).isEqualTo(binding.digest());
    assertThat(SelectedOwnerIntakeSettlementGrpcCodec.fromRequest(wire)).isEqualTo(request);
  }

  @Test
  void rejectsDefaultUnknownAndChangedDigestRequestsWhileAcceptingClosedEntityOwner() {
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeSettlementGrpcCodec.fromRequest(
                    SelectedOwnerIntakeSettlementRequest.getDefaultInstance()))
        .isInstanceOf(IllegalArgumentException.class);

    var binding =
        SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(Owner.AUTOMATION_SCRIPTING);
    var valid = SelectedOwnerIntakeSettlementGrpcCodec.toRequest(Request.create("test", binding));
    var unknown =
        valid.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertThatThrownBy(() -> SelectedOwnerIntakeSettlementGrpcCodec.fromRequest(unknown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown");

    var changedDigest =
        valid.toBuilder().setIntakeAuthorizationDigest("sha256:" + "0".repeat(64)).build();
    assertThatThrownBy(() -> SelectedOwnerIntakeSettlementGrpcCodec.fromRequest(changedDigest))
        .isInstanceOf(IllegalArgumentException.class);

    var entity = SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(Owner.ENTITY_MANAGEMENT);
    var wrongOwner =
        valid.toBuilder()
            .setOriginalIntakeAuthorizationBinding(ByteString.copyFrom(entity.canonicalBytes()))
            .setIntakeAuthorizationDigest(entity.digest())
            .build();
    assertThat(
            SelectedOwnerIntakeSettlementGrpcCodec.fromRequest(wrongOwner)
                .authorizationBinding()
                .owner())
        .isEqualTo(Owner.ENTITY_MANAGEMENT);
  }

  @Test
  void rejectsOversizedAuthorizationBeforeCanonicalDecoding() {
    var oversized =
        SelectedOwnerIntakeSettlementRequest.newBuilder()
            .setSchemaVersion(1)
            .setTargetNamespace("test")
            .setTransportRequestId(UUID.randomUUID().toString())
            .setOriginalIntakeAuthorizationBinding(
                ByteString.copyFrom(
                    new byte[SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES + 1]))
            .setIntakeAuthorizationDigest("sha256:" + "0".repeat(64))
            .build();

    assertThatThrownBy(() -> SelectedOwnerIntakeSettlementGrpcCodec.fromRequest(oversized))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("16 MiB");
  }

  @Test
  void echoesRequestAndCarriesEverySettlementReceiptByteAndDigest() {
    var binding =
        SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(Owner.AUTOMATION_SCRIPTING);
    var request = Request.create("test", binding);
    byte[] completeReceipt = new byte[4096];
    for (int index = 0; index < completeReceipt.length; index++) {
      completeReceipt[index] = (byte) (index * 31);
    }
    var receipt = mock(AccountSelectedOwnerIntakeSettlementReceipt.class);
    when(receipt.authorizationBinding()).thenReturn(binding);
    when(receipt.targetNamespace()).thenReturn("test");
    when(receipt.canonicalBytes()).thenReturn(completeReceipt.clone());
    when(receipt.digest()).thenReturn(DraftAuthorizationFenceBinding.digest(completeReceipt));

    var response =
        SelectedOwnerIntakeSettlementGrpcCodec.toResponse(
            new SelectedOwnerIntakeSettlementEvidence.Result(request, receipt));

    assertThat(response.getRequest())
        .isEqualTo(SelectedOwnerIntakeSettlementGrpcCodec.toRequest(request));
    assertThat(response.getSettlementReceipt().toByteArray()).containsExactly(completeReceipt);
    assertThat(response.getSettlementReceiptDigest())
        .isEqualTo(DraftAuthorizationFenceBinding.digest(completeReceipt));
  }

  @Test
  void rejectsChangedEchoUnknownResponseAndMalformedFullReceipt() {
    var binding =
        SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(Owner.AUTOMATION_SCRIPTING);
    var request = Request.create("test", binding);
    var base =
        net.firedevops.firemud.account.v1.SelectedOwnerIntakeSettlementResponse.newBuilder()
            .setRequest(SelectedOwnerIntakeSettlementGrpcCodec.toRequest(request))
            .setSettlementReceipt(ByteString.copyFrom(new byte[] {1, 2, 3, 4}))
            .setSettlementReceiptDigest("sha256:" + "0".repeat(64))
            .build();

    var changedEcho =
        base.toBuilder()
            .setRequest(
                base.getRequest().toBuilder().setTransportRequestId(UUID.randomUUID().toString()))
            .build();
    assertThatThrownBy(
            () -> SelectedOwnerIntakeSettlementGrpcCodec.fromResponse(request, changedEcho))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed");

    var unknown =
        base.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertThatThrownBy(() -> SelectedOwnerIntakeSettlementGrpcCodec.fromResponse(request, unknown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown");

    assertThatThrownBy(() -> SelectedOwnerIntakeSettlementGrpcCodec.fromResponse(request, base))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("malformed");
  }

  @Test
  void rejectsCorrelationReuseAndReceiptDigestOrAuthorizationSubstitution() {
    var binding =
        SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(Owner.AUTOMATION_SCRIPTING);
    assertThatThrownBy(() -> new Request(1, "test", binding.operationId(), binding))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("correlation");

    var request = Request.create("test", binding);
    byte[] receiptBytes = {10, 20, 30, 40};
    var badDigest = mock(AccountSelectedOwnerIntakeSettlementReceipt.class);
    when(badDigest.authorizationBinding()).thenReturn(binding);
    when(badDigest.targetNamespace()).thenReturn("test");
    when(badDigest.canonicalBytes()).thenReturn(receiptBytes);
    when(badDigest.digest()).thenReturn("sha256:" + "0".repeat(64));
    assertThatThrownBy(() -> new SelectedOwnerIntakeSettlementEvidence.Result(request, badDigest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("receipt");

    var substitutedBinding =
        SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(Owner.ENTITY_MANAGEMENT);
    var substitution = mock(AccountSelectedOwnerIntakeSettlementReceipt.class);
    when(substitution.authorizationBinding()).thenReturn(substitutedBinding);
    when(substitution.targetNamespace()).thenReturn("test");
    when(substitution.canonicalBytes()).thenReturn(receiptBytes);
    when(substitution.digest()).thenReturn(DraftAuthorizationFenceBinding.digest(receiptBytes));
    assertThatThrownBy(
            () -> new SelectedOwnerIntakeSettlementEvidence.Result(request, substitution))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("original authorization");
  }
}
