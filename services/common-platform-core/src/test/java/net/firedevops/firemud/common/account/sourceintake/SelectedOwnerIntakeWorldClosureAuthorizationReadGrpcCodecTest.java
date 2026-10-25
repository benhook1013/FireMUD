package net.firedevops.firemud.common.account.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import net.firedevops.firemud.account.v1.ReadHeldSelectedOwnerWorldClosureAuthorizationRequest;
import net.firedevops.firemud.account.v1.ReadHeldSelectedOwnerWorldClosureAuthorizationResponse;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import org.junit.jupiter.api.Test;

/** Synthetic protobuf integrity cases only; HELD comes from Account's authenticated read. */
class SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodecTest {
  @Test
  void roundTripsClosedOwnerRequestsAndExactHeldEchoes() {
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var binding = SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(owner);
      var request = Request.create("test", binding);
      var wire = SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.toRequest(request);

      assertThat(wire.getSchemaVersion()).isEqualTo(1);
      assertThat(wire.getTargetNamespace()).isEqualTo("test");
      assertThat(wire.getReadRequestId()).isEqualTo(request.readRequestId().toString());
      assertThat(wire.getIntendedReader()).isEqualTo(request.intendedReader());
      assertThat(wire.getClosureReadPurpose()).isEqualTo(request.closureReadPurpose());
      assertThat(wire.getOriginalIntakeAuthorizationBinding().toByteArray())
          .isEqualTo(binding.canonicalBytes());
      assertThat(wire.getIntakeAuthorizationDigest()).isEqualTo(binding.digest());
      var decoded = SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.fromRequest(wire);
      assertThat(decoded.schemaVersion()).isEqualTo(request.schemaVersion());
      assertThat(decoded.targetNamespace()).isEqualTo(request.targetNamespace());
      assertThat(decoded.readRequestId()).isEqualTo(request.readRequestId());
      assertThat(decoded.binding().canonicalBytes()).isEqualTo(binding.canonicalBytes());

      var response =
          SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.toHeldResponse(request);
      assertThat(response.getRequest()).isEqualTo(wire);
      assertThat(response.getHeld()).isTrue();
      assertThat(
              SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.fromResponse(
                  request, response))
          .isEqualTo(new SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence(request));
      assertThat(response.getSerializedSize())
          .isLessThanOrEqualTo(
              SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.MAX_WIRE_BYTES);
    }
  }

  @Test
  void rejectsChangedClosedPurposeReaderNamespaceVersionDigestAndUuid() {
    var request =
        SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.toRequest(
            Request.create(
                "test",
                SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(Owner.ENTITY_MANAGEMENT)));
    for (var changed :
        List.of(
            request.toBuilder().setSchemaVersion(2).build(),
            request.toBuilder().setTargetNamespace("other").build(),
            request.toBuilder().setReadRequestId("not-a-uuid").build(),
            request.toBuilder()
                .setIntendedReader("spiffe://firemud/ns/test/sa/account-service")
                .build(),
            request.toBuilder().setClosureReadPurpose("ENTITY_INTAKE_SOURCE").build(),
            request.toBuilder().setIntakeAuthorizationDigest("sha256:" + "0".repeat(64)).build())) {
      assertThatThrownBy(
              () -> SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.fromRequest(changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsUnknownFieldsAndMalformedOrOversizedBindings() {
    var request =
        SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.toRequest(
            Request.create(
                "test",
                SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(
                    Owner.AUTOMATION_SCRIPTING)));
    var unknown =
        request.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    var malformed =
        request.toBuilder()
            .setOriginalIntakeAuthorizationBinding(ByteString.copyFrom(new byte[] {1, 2, 3}))
            .build();
    var oversizedBinding =
        request.toBuilder()
            .setOriginalIntakeAuthorizationBinding(
                ByteString.copyFrom(
                    new byte[SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES + 1]))
            .build();

    for (var changed : List.of(unknown, malformed, oversizedBinding)) {
      assertThatThrownBy(
              () -> SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.fromRequest(changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsNonHeldSubstitutedAndUnknownResponses() {
    var request =
        Request.create(
            "test",
            SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(Owner.ENTITY_MANAGEMENT));
    var exact = SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.toHeldResponse(request);
    var notHeld = exact.toBuilder().setHeld(false).build();
    var substituted =
        exact.toBuilder()
            .setRequest(
                exact.getRequest().toBuilder()
                    .setReadRequestId("12121212-1212-4212-8212-121212121212"))
            .build();
    var unknown =
        exact.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    var nestedUnknown =
        exact.toBuilder()
            .setRequest(
                exact.getRequest().toBuilder()
                    .setUnknownFields(
                        UnknownFieldSet.newBuilder()
                            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                            .build()))
            .build();
    for (var changed : List.of(notHeld, substituted, unknown, nestedUnknown)) {
      assertThatThrownBy(
              () ->
                  SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.fromResponse(
                      request, changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.fromResponse(
                    request,
                    ReadHeldSelectedOwnerWorldClosureAuthorizationResponse.getDefaultInstance()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMessagesBeyondTheTwentyFourMibBudget() {
    byte[] oversizedBytes =
        new byte[SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.MAX_WIRE_BYTES + 1];
    var oversized =
        ReadHeldSelectedOwnerWorldClosureAuthorizationRequest.newBuilder()
            .setOriginalIntakeAuthorizationBinding(ByteString.copyFrom(oversizedBytes))
            .build();
    assertThat(oversized.getSerializedSize())
        .isGreaterThan(SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.MAX_WIRE_BYTES);
    assertThatThrownBy(
            () -> SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.fromRequest(oversized))
        .isInstanceOf(IllegalArgumentException.class);

    var oversizedResponse =
        ReadHeldSelectedOwnerWorldClosureAuthorizationResponse.newBuilder()
            .setRequest(oversized)
            .setHeld(true)
            .build();
    assertThat(oversizedResponse.getSerializedSize())
        .isGreaterThan(SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.MAX_WIRE_BYTES);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.fromResponse(
                    Request.create(
                        "test",
                        SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(
                            Owner.ENTITY_MANAGEMENT)),
                    oversizedResponse))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
