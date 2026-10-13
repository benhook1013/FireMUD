package net.firedevops.firemud.common.account.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeAuthorizationProducerResponse;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationProducerEvidence.Request;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import org.junit.jupiter.api.Test;

class SelectedOwnerIntakeAuthorizationProducerProtoCodecTest {
  @Test
  void roundTripsExactCanonicalSelectionAndCompleteOwnerAuthorization() {
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var binding = SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(owner);
      var request =
          new Request(
              1,
              "test",
              UUID.randomUUID(),
              binding.intakeRequestId(),
              owner,
              binding.selected().canonicalBytes());
      var wireRequest = SelectedOwnerIntakeAuthorizationProducerProtoCodec.toRequest(request);

      assertThat(SelectedOwnerIntakeAuthorizationProducerProtoCodec.fromRequest(wireRequest))
          .isEqualTo(request);
      assertThat(wireRequest.getSelectedDraftBinding().toByteArray())
          .containsExactly(binding.selected().canonicalBytes());
      var result =
          new SelectedOwnerIntakeAuthorizationProducerEvidence.Result(
              request, binding, binding.digest());
      var response = SelectedOwnerIntakeAuthorizationProducerProtoCodec.toResponse(result);
      assertThat(response.getRequest()).isEqualTo(wireRequest);
      assertThat(response.getIntakeAuthorizationBinding().toByteArray())
          .containsExactly(binding.canonicalBytes());
      assertThat(response.getIntakeAuthorizationDigest()).isEqualTo(binding.digest());

      var decoded =
          SelectedOwnerIntakeAuthorizationProducerProtoCodec.fromResponse(request, response);
      assertThat(decoded.request()).isEqualTo(request);
      assertThat(decoded.binding().canonicalBytes()).containsExactly(binding.canonicalBytes());
      assertThat(decoded.digest()).isEqualTo(binding.digest());
    }
  }

  @Test
  void rejectsChangedTransportNamespaceIntakeOwnerAndSelectedDraftEchoes() {
    var binding = SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(Owner.ENTITY_MANAGEMENT);
    var request = request(Owner.ENTITY_MANAGEMENT, binding);
    var response = response(request, binding);
    var exactEcho = response.getRequest();
    var changed =
        List.of(
            exactEcho.toBuilder().setTransportRequestId(UUID.randomUUID().toString()).build(),
            exactEcho.toBuilder().setTargetNamespace("other").build(),
            exactEcho.toBuilder().setIntakeRequestId(UUID.randomUUID().toString()).build(),
            exactEcho.toBuilder()
                .setOwner(
                    net.firedevops.firemud.account.v1.SelectedOwnerIntakeAuthorizationProducerOwner
                        .SELECTED_OWNER_INTAKE_AUTHORIZATION_PRODUCER_OWNER_AUTOMATION_SCRIPTING)
                .build(),
            exactEcho.toBuilder().setSelectedDraftBinding(ByteString.copyFromUtf8("{}")).build());

    for (var echo : changed) {
      assertThatThrownBy(
              () ->
                  SelectedOwnerIntakeAuthorizationProducerProtoCodec.fromResponse(
                      request, response.toBuilder().setRequest(echo).build()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void acceptsDifferentAccountSourceCaptureButRejectsMismatchedOwnerAndDigest() {
    var entity = SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(Owner.ENTITY_MANAGEMENT);
    var automation =
        SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(Owner.AUTOMATION_SCRIPTING);
    var request = request(Owner.ENTITY_MANAGEMENT, entity);
    var response = response(request, entity);

    var changedSources = new java.util.ArrayList<>(entity.sources());
    var source = changedSources.get(0);
    changedSources.set(
        0,
        new SourceEvidence(
            source.kind(),
            source.scopeId(),
            source.generation(),
            source.sourceVersion(),
            source.checkpointStream(),
            source.checkpointSequence(),
            new byte[] {9, 8, 7}));
    var changedCompleteBinding =
        new SelectedOwnerIntakeAuthorizationBinding(entity.content(), changedSources);
    var changedAccountSourceResponse =
        response.toBuilder()
            .setIntakeAuthorizationBinding(
                ByteString.copyFrom(changedCompleteBinding.canonicalBytes()))
            .setIntakeAuthorizationDigest(changedCompleteBinding.digest())
            .build();
    var accepted =
        SelectedOwnerIntakeAuthorizationProducerProtoCodec.fromResponse(
            request, changedAccountSourceResponse);
    assertThat(accepted.binding().canonicalBytes())
        .containsExactly(changedCompleteBinding.canonicalBytes());

    var mismatchedOwnerBinding =
        response.toBuilder()
            .setIntakeAuthorizationBinding(ByteString.copyFrom(automation.canonicalBytes()))
            .setIntakeAuthorizationDigest(automation.digest())
            .build();
    var wrongDigest =
        response.toBuilder().setIntakeAuthorizationDigest("sha256:" + "0".repeat(64)).build();

    for (var changed : List.of(mismatchedOwnerBinding, wrongDigest)) {
      assertThatThrownBy(
              () ->
                  SelectedOwnerIntakeAuthorizationProducerProtoCodec.fromResponse(request, changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsUnknownDefaultMalformedAndOversizedRequestValues() {
    var binding = SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(Owner.ENTITY_MANAGEMENT);
    var request = request(Owner.ENTITY_MANAGEMENT, binding);
    var wire = SelectedOwnerIntakeAuthorizationProducerProtoCodec.toRequest(request);
    var unknown =
        wire.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    var nestedUnknown =
        SelectedOwnerIntakeAuthorizationProducerProtoCodec.toResponse(
                new SelectedOwnerIntakeAuthorizationProducerEvidence.Result(
                    request, binding, binding.digest()))
            .toBuilder()
            .setRequest(
                wire.toBuilder()
                    .setUnknownFields(
                        UnknownFieldSet.newBuilder()
                            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                            .build())
                    .build())
            .build();
    var malformed =
        wire.toBuilder().setSelectedDraftBinding(ByteString.copyFrom(new byte[] {1, 2, 3})).build();
    var unspecifiedOwner = wire.toBuilder().clearOwner().build();
    var unknownOwner = wire.toBuilder().setOwnerValue(99).build();
    var oversizedSelection =
        wire.toBuilder()
            .setSelectedDraftBinding(
                ByteString.copyFrom(
                    new byte
                        [SelectedOwnerIntakeAuthorizationProducerEvidence.MAX_SELECTION_BYTES + 1]))
            .build();

    for (var changed :
        List.of(unknown, malformed, unspecifiedOwner, unknownOwner, oversizedSelection)) {
      assertThatThrownBy(
              () -> SelectedOwnerIntakeAuthorizationProducerProtoCodec.fromRequest(changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var unknownResponse =
        SelectedOwnerIntakeAuthorizationProducerProtoCodec.toResponse(
                new SelectedOwnerIntakeAuthorizationProducerEvidence.Result(
                    request, binding, binding.digest()))
            .toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeAuthorizationProducerProtoCodec.fromResponse(
                    request, nestedUnknown))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeAuthorizationProducerProtoCodec.fromResponse(
                    request, unknownResponse))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMalformedNoncanonicalOrOversizedAuthorizationBinding() {
    var binding =
        SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(Owner.AUTOMATION_SCRIPTING);
    var request = request(Owner.AUTOMATION_SCRIPTING, binding);
    var exact = response(request, binding);
    var malformed =
        exact.toBuilder()
            .setIntakeAuthorizationBinding(ByteString.copyFrom(new byte[] {1, 2, 3}))
            .build();
    var oversized =
        exact.toBuilder()
            .setIntakeAuthorizationBinding(
                ByteString.copyFrom(
                    new byte[SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES + 1]))
            .build();
    var missing = SelectedOwnerIntakeAuthorizationProducerResponse.getDefaultInstance();

    for (var changed : List.of(malformed, oversized, missing)) {
      assertThatThrownBy(
              () ->
                  SelectedOwnerIntakeAuthorizationProducerProtoCodec.fromResponse(request, changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsResponsesBeyondTheTwentyFourMibWireBudget() {
    var binding = SelectedOwnerIntakeAuthorizationReadEvidenceTest.binding(Owner.ENTITY_MANAGEMENT);
    var request = request(Owner.ENTITY_MANAGEMENT, binding);
    var oversizedEcho =
        SelectedOwnerIntakeAuthorizationProducerProtoCodec.toRequest(request).toBuilder()
            .setSelectedDraftBinding(
                ByteString.copyFrom(
                    new byte[SelectedOwnerIntakeAuthorizationProducerProtoCodec.MAX_WIRE_BYTES]))
            .build();
    var oversized = response(request, binding).toBuilder().setRequest(oversizedEcho).build();

    assertThat(oversized.getSerializedSize())
        .isGreaterThan(SelectedOwnerIntakeAuthorizationProducerProtoCodec.MAX_WIRE_BYTES);
    assertThatThrownBy(
            () ->
                SelectedOwnerIntakeAuthorizationProducerProtoCodec.fromResponse(request, oversized))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Request request(Owner owner, SelectedOwnerIntakeAuthorizationBinding binding) {
    return new Request(
        1,
        "test",
        UUID.randomUUID(),
        binding.intakeRequestId(),
        owner,
        binding.selected().canonicalBytes());
  }

  private static SelectedOwnerIntakeAuthorizationProducerResponse response(
      Request request, SelectedOwnerIntakeAuthorizationBinding binding) {
    return SelectedOwnerIntakeAuthorizationProducerProtoCodec.toResponse(
        new SelectedOwnerIntakeAuthorizationProducerEvidence.Result(
            request, binding, binding.digest()));
  }
}
