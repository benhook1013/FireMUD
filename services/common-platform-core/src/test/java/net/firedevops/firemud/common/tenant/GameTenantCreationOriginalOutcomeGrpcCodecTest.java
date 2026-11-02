package net.firedevops.firemud.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Outcome;
import net.firedevops.firemud.gamedesign.v1.OriginalTenantCreationOutcome;
import org.junit.jupiter.api.Test;

class GameTenantCreationOriginalOutcomeGrpcCodecTest {
  private static final String NAMESPACE = "fresh-test";
  private static final UUID READ_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID ACCOUNT_OPERATION_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID ACCOUNT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID CREATION_OPERATION_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID TENANT_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final String CREATION_DIGEST = "sha256:" + "a".repeat(64);
  private static final String ACCOUNT_DIGEST = "sha256:" + "b".repeat(64);
  private static final GameTenantCreationOriginalOutcomeGrpcCodec.ReadRequest READ_REQUEST =
      new GameTenantCreationOriginalOutcomeGrpcCodec.ReadRequest(READ_ID, binding());

  @Test
  void requestRoundTripCarriesFreshReadIdentityAndAllSixTypedBindingFields() {
    var encoded = GameTenantCreationOriginalOutcomeGrpcCodec.toReadRequest(READ_REQUEST);

    assertThat(GameTenantCreationOriginalOutcomeGrpcCodec.fromReadRequest(NAMESPACE, encoded))
        .isEqualTo(READ_REQUEST);
    assertThat(encoded.getDescriptorForType().getFields())
        .extracting(field -> field.getName())
        .containsExactly(
            "schema_version",
            "read_request_id",
            "target_namespace",
            "creation_request_id",
            "account_authorization_operation_id",
            "initiating_account_id",
            "creation_request_digest",
            "account_authorization_digest");
    assertThat(encoded.getSchemaVersion()).isEqualTo(1);
    assertThat(encoded.getReadRequestId()).isEqualTo(READ_ID.toString());
    assertThat(encoded.getAccountAuthorizationOperationId())
        .isEqualTo(ACCOUNT_OPERATION_ID.toString());
  }

  @Test
  void requestDecoderRejectsOpenMalformedAndCrossNamespaceBindings() {
    var valid = GameTenantCreationOriginalOutcomeGrpcCodec.toReadRequest(READ_REQUEST);
    for (var malformed :
        new net.firedevops.firemud.gamedesign.v1.ResolveOriginalTenantCreationOutcomeRequest[] {
          valid.toBuilder().setSchemaVersion(2).build(),
          valid.toBuilder().setReadRequestId("bad-uuid").build(),
          valid.toBuilder().setReadRequestId(new UUID(0L, 0L).toString()).build(),
          valid.toBuilder().setAccountAuthorizationOperationId("bad-uuid").build(),
          valid.toBuilder().setInitiatingAccountId(new UUID(0L, 0L).toString()).build(),
          valid.toBuilder().setAccountAuthorizationDigest("sha256:" + "A".repeat(64)).build(),
          valid.toBuilder().setUnknownFields(unknownField()).build()
        }) {
      assertThatThrownBy(
              () ->
                  GameTenantCreationOriginalOutcomeGrpcCodec.fromReadRequest(NAMESPACE, malformed))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () -> GameTenantCreationOriginalOutcomeGrpcCodec.fromReadRequest("other", valid))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid");
  }

  @Test
  void createdAndNotCreatedResponsesRoundTripWithOnlyCreatedCarryingTheExactV30Receipt() {
    GameTenantCreationOriginalOutcomeEvidence created = created();
    var createdResponse =
        GameTenantCreationOriginalOutcomeGrpcCodec.toReadResponse(READ_REQUEST, created);
    assertThat(createdResponse.getOutcome())
        .isEqualTo(OriginalTenantCreationOutcome.ORIGINAL_TENANT_CREATION_OUTCOME_CREATED);
    assertThat(createdResponse.hasCreatedReceipt()).isTrue();
    assertThat(createdResponse.getEvidenceDigest()).isEqualTo(created.evidenceDigest());
    assertThat(
            GameTenantCreationOriginalOutcomeGrpcCodec.fromReadResponse(
                READ_REQUEST, createdResponse))
        .isEqualTo(created);

    GameTenantCreationOriginalOutcomeEvidence notCreated = notCreated();
    var notCreatedResponse =
        GameTenantCreationOriginalOutcomeGrpcCodec.toReadResponse(READ_REQUEST, notCreated);
    assertThat(notCreatedResponse.getOutcome())
        .isEqualTo(OriginalTenantCreationOutcome.ORIGINAL_TENANT_CREATION_OUTCOME_NOT_CREATED);
    assertThat(notCreatedResponse.hasCreatedReceipt()).isFalse();
    assertThat(
            GameTenantCreationOriginalOutcomeGrpcCodec.fromReadResponse(
                READ_REQUEST, notCreatedResponse))
        .isEqualTo(notCreated);
  }

  @Test
  void responseDecoderRejectsEchoBindingDigestEnumAndNestedReceiptSubstitution() {
    var createdResponse =
        GameTenantCreationOriginalOutcomeGrpcCodec.toReadResponse(READ_REQUEST, created());
    var notCreatedResponse =
        GameTenantCreationOriginalOutcomeGrpcCodec.toReadResponse(READ_REQUEST, notCreated());
    for (var malformed :
        new net.firedevops.firemud.gamedesign.v1.ResolveOriginalTenantCreationOutcomeResponse[] {
          createdResponse.toBuilder()
              .setReadRequestId("77777777-7777-4777-8777-777777777777")
              .build(),
          createdResponse.toBuilder().setTargetNamespace("other").build(),
          createdResponse.toBuilder()
              .setCreationRequestId("77777777-7777-4777-8777-777777777777")
              .build(),
          createdResponse.toBuilder()
              .setAccountAuthorizationOperationId("77777777-7777-4777-8777-777777777777")
              .build(),
          createdResponse.toBuilder()
              .setInitiatingAccountId("77777777-7777-4777-8777-777777777777")
              .build(),
          createdResponse.toBuilder().setCreationRequestDigest("sha256:" + "0".repeat(64)).build(),
          createdResponse.toBuilder()
              .setAccountAuthorizationDigest("sha256:" + "0".repeat(64))
              .build(),
          createdResponse.toBuilder().setEvidenceDigest("sha256:" + "0".repeat(64)).build(),
          createdResponse.toBuilder()
              .setOutcome(
                  OriginalTenantCreationOutcome.ORIGINAL_TENANT_CREATION_OUTCOME_UNSPECIFIED)
              .build(),
          createdResponse.toBuilder().setOutcomeValue(99).build(),
          createdResponse.toBuilder().setUnknownFields(unknownField()).build(),
          createdResponse.toBuilder()
              .setCreatedReceipt(
                  createdResponse.getCreatedReceipt().toBuilder().setUnknownFields(unknownField()))
              .build(),
          createdResponse.toBuilder().clearCreatedReceipt().build(),
          notCreatedResponse.toBuilder()
              .setCreatedReceipt(createdResponse.getCreatedReceipt())
              .build()
        }) {
      assertThatThrownBy(
              () ->
                  GameTenantCreationOriginalOutcomeGrpcCodec.fromReadResponse(
                      READ_REQUEST, malformed))
          .isInstanceOf(IllegalArgumentException.class);
    }

    var substitutedEvidence =
        new GameTenantCreationOriginalOutcomeEvidence(
            1,
            new Binding(
                NAMESPACE,
                REQUEST_ID,
                ACCOUNT_OPERATION_ID,
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                CREATION_DIGEST,
                ACCOUNT_DIGEST),
            Outcome.NOT_CREATED,
            Optional.empty());
    assertThatThrownBy(
            () ->
                GameTenantCreationOriginalOutcomeGrpcCodec.toReadResponse(
                    READ_REQUEST, substitutedEvidence))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact binding");
  }

  private static Binding binding() {
    return new Binding(
        NAMESPACE, REQUEST_ID, ACCOUNT_OPERATION_ID, ACCOUNT_ID, CREATION_DIGEST, ACCOUNT_DIGEST);
  }

  private static GameTenantCreationOriginalOutcomeEvidence created() {
    FreshTenantCreationEvidence receipt =
        new FreshTenantCreationEvidence(
            1,
            NAMESPACE,
            REQUEST_ID,
            CREATION_OPERATION_ID,
            CREATION_DIGEST,
            TENANT_ID,
            91L,
            "fresh-tenant-01",
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                NAMESPACE,
                REQUEST_ID,
                CREATION_OPERATION_ID,
                CREATION_DIGEST,
                TENANT_ID,
                91L,
                "fresh-tenant-01",
                "NEW_GAME_ROW"));
    return new GameTenantCreationOriginalOutcomeEvidence(
        1, binding(), Outcome.CREATED, Optional.of(receipt));
  }

  private static GameTenantCreationOriginalOutcomeEvidence notCreated() {
    return new GameTenantCreationOriginalOutcomeEvidence(
        1, binding(), Outcome.NOT_CREATED, Optional.empty());
  }

  private static UnknownFieldSet unknownField() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }
}
