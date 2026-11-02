package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ResolveCurrentTenantCreationAuthorizationResponse;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;
import org.junit.jupiter.api.Test;

class CurrentTenantCreationAuthorizationGrpcCodecTest {
  private static final UUID READ_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID CREATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID AUTHORIZATION_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID ACCOUNT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final String CREATION_DIGEST = "sha256:" + "a".repeat(64);
  private static final String AUTHORIZATION_DIGEST = "sha256:" + "b".repeat(64);
  private static final Binding BINDING =
      new Binding(
          "creation-codec-test",
          CREATION_ID,
          AUTHORIZATION_ID,
          ACCOUNT_ID,
          CREATION_DIGEST,
          AUTHORIZATION_DIGEST);

  @Test
  void requestAndCurrentAuthorizationEchoRoundTripExactSixFieldBinding() {
    var request = CurrentTenantCreationAuthorizationGrpcCodec.request(READ_ID, BINDING);
    long validBefore = System.currentTimeMillis() + 60_000L;
    var response =
        CurrentTenantCreationAuthorizationGrpcCodec.response(READ_ID, BINDING, validBefore);

    assertThat(CurrentTenantCreationAuthorizationGrpcCodec.decodeRequest(request))
        .isEqualTo(new CurrentTenantCreationAuthorizationGrpcCodec.Request(READ_ID, BINDING));
    assertThat(
            CurrentTenantCreationAuthorizationGrpcCodec.verifyResponse(READ_ID, BINDING, response))
        .isEqualTo(
            new CurrentTenantCreationAuthorizationGrpcCodec.Authorization(BINDING, validBefore));
  }

  @Test
  void rejectsUnknownFieldsSchemaAndNonCanonicalRequestIdentities() {
    var valid = CurrentTenantCreationAuthorizationGrpcCodec.request(READ_ID, BINDING);
    var unknown =
        valid.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build();

    assertThatThrownBy(() -> CurrentTenantCreationAuthorizationGrpcCodec.decodeRequest(unknown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown");
    assertThatThrownBy(
            () ->
                CurrentTenantCreationAuthorizationGrpcCodec.decodeRequest(
                    valid.toBuilder().setSchemaVersion(2).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CurrentTenantCreationAuthorizationGrpcCodec.decodeRequest(
                    valid.toBuilder()
                        .setReadRequestId("00000000-0000-0000-0000-000000000000")
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CurrentTenantCreationAuthorizationGrpcCodec.decodeRequest(
                    valid.toBuilder().setCreationRequestId("not-a-uuid").build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsChangedBindingReadIdAndMalformedOrExpiredRangeValues() {
    long validBefore = System.currentTimeMillis() + 60_000L;
    var response =
        CurrentTenantCreationAuthorizationGrpcCodec.response(READ_ID, BINDING, validBefore);

    assertThatThrownBy(
            () ->
                CurrentTenantCreationAuthorizationGrpcCodec.verifyResponse(
                    UUID.randomUUID(), BINDING, response))
        .isInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage(
            "Current tenant-creation authorization response changed its exact request");
    assertThatThrownBy(
            () ->
                CurrentTenantCreationAuthorizationGrpcCodec.verifyResponse(
                    READ_ID,
                    new Binding(
                        BINDING.targetNamespace(),
                        BINDING.creationRequestId(),
                        BINDING.accountAuthorizationOperationId(),
                        UUID.fromString("55555555-5555-4555-8555-555555555555"),
                        BINDING.creationRequestDigest(),
                        BINDING.accountAuthorizationDigest()),
                    response))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> CurrentTenantCreationAuthorizationGrpcCodec.response(READ_ID, BINDING, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CurrentTenantCreationAuthorizationGrpcCodec.response(
                    READ_ID, BINDING, Long.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CurrentTenantCreationAuthorizationGrpcCodec.verifyResponse(
                    READ_ID, BINDING, response.toBuilder().setValidBeforeEpochMillis(0).build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnknownFieldsInResponse() {
    var response =
        CurrentTenantCreationAuthorizationGrpcCodec.response(
            READ_ID, BINDING, System.currentTimeMillis() + 60_000L);
    ResolveCurrentTenantCreationAuthorizationResponse unknown =
        response.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build();

    assertThatThrownBy(
            () ->
                CurrentTenantCreationAuthorizationGrpcCodec.verifyResponse(
                    READ_ID, BINDING, unknown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown");
  }
}
