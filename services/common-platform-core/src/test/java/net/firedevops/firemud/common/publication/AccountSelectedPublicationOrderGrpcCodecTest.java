package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AuthorizeSelectedPublicationRequest;
import net.firedevops.firemud.account.v1.AuthorizeSelectedPublicationResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.PublishIntent;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.VisibilityFence;
import org.junit.jupiter.api.Test;

class AccountSelectedPublicationOrderGrpcCodecTest {
  private static final String CREDENTIAL = "original.secret.credential";

  @Test
  void preservesOriginalCredentialAndCanonicalSelectionWithoutEchoingSecret() {
    var binding = AccountPublicationAuthorizationReadGrpcCodecTest.binding();
    var request =
        AccountSelectedPublicationOrderGrpcCodec.Request.create(
            "test", binding.input().selection(), CREDENTIAL);
    var wire = AccountSelectedPublicationOrderGrpcCodec.toRequest(request);
    var decoded = AccountSelectedPublicationOrderGrpcCodec.fromRequest(wire, CREDENTIAL);
    assertThat(decoded.originalCreatorCredential()).isEqualTo(CREDENTIAL);
    assertThat(decoded.originalSelection()).isEqualTo(request.originalSelection());
    assertThat(decoded.requestId()).isEqualTo(request.requestId());
    assertThat(decoded.toString()).doesNotContain(CREDENTIAL);
    assertThat(wire.toString()).doesNotContain(CREDENTIAL);
    var response = AccountSelectedPublicationOrderGrpcCodec.toResponse(request, binding);
    assertThat(response.toString()).doesNotContain(CREDENTIAL);
    assertThat(
            AccountSelectedPublicationOrderGrpcCodec.fromResponse(request, response)
                .canonicalBytes())
        .isEqualTo(binding.canonicalBytes());
    var fresh =
        AccountSelectedPublicationOrderGrpcCodec.Request.create(
            "test", binding.input().selection(), CREDENTIAL);
    assertThat(fresh.requestId()).isNotEqualTo(request.requestId());
    assertThat(
            AccountSelectedPublicationOrderGrpcCodec.fromResponse(
                    fresh, AccountSelectedPublicationOrderGrpcCodec.toResponse(fresh, binding))
                .canonicalBytes())
        .isEqualTo(binding.canonicalBytes());
  }

  @Test
  void rejectsUnknownNoncanonicalOversizedAndMissingRequestFieldsWithoutSecretDiagnostics() {
    var binding = AccountPublicationAuthorizationReadGrpcCodecTest.binding();
    var request =
        AccountSelectedPublicationOrderGrpcCodec.Request.create(
            "test", binding.input().selection(), CREDENTIAL);
    var wire = AccountSelectedPublicationOrderGrpcCodec.toRequest(request);
    for (AuthorizeSelectedPublicationRequest invalid :
        List.of(
            wire.toBuilder().setSchemaVersion(2).build(),
            wire.toBuilder().setTargetNamespace("Test").build(),
            wire.toBuilder().setRequestId("1-1-1-1-1").build(),
            wire.toBuilder().setRequestId(new UUID(0, 0).toString()).build(),
            wire.toBuilder().setRequestId("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA").build(),
            wire.toBuilder().setUnknownFields(unknown()).build(),
            wire.toBuilder().clearOriginalSelection().build(),
            wire.toBuilder().setOriginalSelection(ByteString.copyFrom(new byte[1048577])).build(),
            wire.toBuilder()
                .setOriginalSelection(
                    wire.getOriginalSelection().concat(ByteString.copyFromUtf8(" ")))
                .build())) {
      assertThatThrownBy(
              () -> AccountSelectedPublicationOrderGrpcCodec.fromRequest(invalid, CREDENTIAL))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining(CREDENTIAL)
          .hasNoCause();
    }
    for (String invalid : List.of("", "x".repeat(16385), CREDENTIAL + "\n")) {
      assertThatThrownBy(() -> AccountSelectedPublicationOrderGrpcCodec.fromRequest(wire, invalid))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining(CREDENTIAL)
          .hasNoCause();
    }
  }

  @Test
  void rejectsChangedResponseEchoBindingAndUnknownFields() {
    var binding = AccountPublicationAuthorizationReadGrpcCodecTest.binding();
    var request =
        AccountSelectedPublicationOrderGrpcCodec.Request.create(
            "test", binding.input().selection(), CREDENTIAL);
    var response = AccountSelectedPublicationOrderGrpcCodec.toResponse(request, binding);
    for (var invalid :
        List.of(
            response.toBuilder().setSchemaVersion(2).build(),
            response.toBuilder().setTargetNamespace("other").build(),
            response.toBuilder().setRequestId(UUID.randomUUID().toString()).build(),
            response.toBuilder().clearOriginalSelection().build(),
            response.toBuilder()
                .setTargetNamespace(
                    "x".repeat(AccountSelectedPublicationOrderGrpcCodec.MAX_RESPONSE_MESSAGE_BYTES))
                .build(),
            response.toBuilder().setUnknownFields(unknown()).build(),
            response.toBuilder().clearPublicationAuthorizationBinding().build(),
            response.toBuilder()
                .setPublicationAuthorizationBinding(ByteString.copyFrom(new byte[] {1}))
                .build())) {
      assertThatThrownBy(
              () -> AccountSelectedPublicationOrderGrpcCodec.fromResponse(request, invalid))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining(CREDENTIAL)
          .hasNoCause();
    }
  }

  @Test
  void roundTripsMaximumCanonicalSelectionWithLargerBoundedBinding() {
    var base = AccountPublicationAuthorizationReadGrpcCodecTest.binding();
    var selectionWithoutNotes = withNotes(base.input().selection(), "");
    int notesBytes =
        AccountSelectedPublicationOrderGrpcCodec.MAX_SELECTION_BYTES
            - selectionWithoutNotes.canonicalBytes().length;
    var selection = withNotes(base.input().selection(), "n".repeat(notesBytes));
    var request =
        AccountSelectedPublicationOrderGrpcCodec.Request.create("test", selection, CREDENTIAL);
    var binding =
        new AccountPublicationAuthorizationBinding(
            base.operationId(),
            base.fenceId(),
            new AccountPublicationAuthorizationBinding.PreallocationInput(
                base.input().actorAccountId(), selection),
            base.sources());

    assertThat(request.originalSelection())
        .hasSize(AccountSelectedPublicationOrderGrpcCodec.MAX_SELECTION_BYTES);
    assertThat(binding.canonicalBytes())
        .hasSizeGreaterThan(request.originalSelection().length)
        .hasSizeLessThanOrEqualTo(AccountSelectedPublicationOrderGrpcCodec.MAX_BINDING_BYTES);
    var response = AccountSelectedPublicationOrderGrpcCodec.toResponse(request, binding);
    assertThat(response.getSerializedSize())
        .isLessThanOrEqualTo(AccountSelectedPublicationOrderGrpcCodec.MAX_RESPONSE_MESSAGE_BYTES);
    assertThat(
            AccountSelectedPublicationOrderGrpcCodec.fromResponse(request, response)
                .canonicalBytes())
        .containsExactly(binding.canonicalBytes());

    var oversizedSelection =
        withNotes(
            base.input().selection(),
            "n".repeat(AccountSelectedPublicationOrderGrpcCodec.MAX_SELECTION_BYTES));
    assertThat(oversizedSelection.canonicalBytes())
        .hasSizeGreaterThan(AccountSelectedPublicationOrderGrpcCodec.MAX_SELECTION_BYTES);
    assertThatThrownBy(
            () ->
                AccountSelectedPublicationOrderGrpcCodec.Request.create(
                    "test", oversizedSelection, CREDENTIAL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasNoCause();
  }

  @Test
  void rejectsOversizedBindingBeforeProducerAndConsumerDecode() {
    var base = AccountPublicationAuthorizationReadGrpcCodecTest.binding();
    var request =
        AccountSelectedPublicationOrderGrpcCodec.Request.create(
            "test", base.input().selection(), CREDENTIAL);
    var source = base.sources().get(0);
    var oversizedSource =
        new SourceEvidence(
            source.kind(),
            source.scopeId(),
            source.generation(),
            source.sourceVersion(),
            source.checkpointStream(),
            source.checkpointSequence(),
            new byte[AccountSelectedPublicationOrderGrpcCodec.MAX_BINDING_BYTES + 1]);
    var oversizedBinding =
        new AccountPublicationAuthorizationBinding(
            base.operationId(), base.fenceId(), base.input(), List.of(oversizedSource));

    assertThat(oversizedBinding.canonicalBytes())
        .hasSizeGreaterThan(AccountSelectedPublicationOrderGrpcCodec.MAX_BINDING_BYTES);
    assertThatThrownBy(
            () -> AccountSelectedPublicationOrderGrpcCodec.toResponse(request, oversizedBinding))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid selected-publication order transport evidence")
        .hasNoCause();

    var wire =
        AuthorizeSelectedPublicationResponse.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setRequestId(request.requestId().toString())
            .setOriginalSelection(ByteString.copyFrom(request.originalSelection()))
            .setPublicationAuthorizationBinding(
                ByteString.copyFrom(oversizedBinding.canonicalBytes()))
            .build();
    assertThat(wire.getSerializedSize())
        .isLessThanOrEqualTo(AccountSelectedPublicationOrderGrpcCodec.MAX_RESPONSE_MESSAGE_BYTES);
    assertThatThrownBy(() -> AccountSelectedPublicationOrderGrpcCodec.fromResponse(request, wire))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid selected-publication order transport evidence")
        .hasNoCause();
  }

  private static AuthoredDraftPublishSelectionBinding withNotes(
      AuthoredDraftPublishSelectionBinding selection, String notes) {
    var intent = selection.intent();
    return AuthoredDraftPublishSelectionBinding.capture(
        new PublishIntent(
            intent.canonicalTenantId(),
            intent.canonicalVersionId(),
            intent.publishRequestId(),
            intent.expectedVersionStateEpoch(),
            notes,
            intent.selectedCommitRequestId(),
            intent.selectedCommitId(),
            intent.selectedCommitDigest()),
        selection.target(),
        selection.selectedCommit(),
        new VisibilityFence(
            selection.target(),
            selection.fenceRequestId(),
            selection.fenceCommitId(),
            selection.fenceInputDigest(),
            selection.fenceResultVectorJson(),
            OffsetDateTime.parse(selection.fenceCreatedAt())));
  }

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }
}
