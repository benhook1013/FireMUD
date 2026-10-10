package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AuthorizeSelectedPublicationRequest;
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

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }
}
