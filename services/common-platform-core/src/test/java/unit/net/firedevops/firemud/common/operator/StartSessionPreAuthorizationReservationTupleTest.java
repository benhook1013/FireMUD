package net.firedevops.firemud.common.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StartSessionPreAuthorizationReservationTupleTest {
  private static final UUID TENANT_ID = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR_ID = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID OTHER_ACTOR_ID =
      UUID.fromString("a44b728e-b58f-4fc1-a891-7644baef6ddc");
  private static final UUID OWNER_ACCOUNT_ID =
      UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");

  @Test
  void canonicalBytesMatchThePreAuthorizationReservationTupleV1Encoding() {
    StartSessionPreAuthorizationReservationTuple tuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "tuple-contract-01", ACTOR_ID, action(StartSessionOperatorAction.ClientIp.absent()));
    String expected =
        "{\"actionFamily\":\"StartSession\","
            + "\"actionFamilyRequestIdentity\":{\"requestId\":\"tuple-contract-01\","
            + "\"requestIdentityKind\":\"controlPlaneRequestId\"},"
            + "\"actionFamilySchemaId\":\"firemud.game-session.start-session\","
            + "\"actionFamilySchemaVersion\":\"1\","
            + "\"actor\":{\"accountId\":\"a4f5f4eb-8243-4d42-903a-33495456a622\","
            + "\"kind\":\"HUMAN\"},"
            + "\"auditReason\":\"tuple codec contract\","
            + "\"automationPolicy\":{\"presence\":\"ABSENT\"},"
            + "\"expectedVersion\":{\"presence\":\"ABSENT\"},"
            + "\"mutation\":{\"clientIp\":{\"presence\":\"ABSENT\"}},"
            + "\"scope\":{\"targetNamespace\":\"world-runtime\","
            + "\"tenantId\":\"9f8f06b4-36e5-4d11-9c2a-5adfd7f41531\"},"
            + "\"target\":{\"gameTemplateId\":\"91\","
            + "\"ownerAccountId\":\"36aa9ce5-0ebc-4c14-9f6b-d160edc6059a\"},"
            + "\"targetOwner\":{\"ownerService\":\"game-session-service\"},"
            + "\"tupleSchemaId\":\"preAuthorizationReservationTuple\","
            + "\"tupleSchemaVersion\":\"1\"}";

    assertThat(tuple.canonicalJson().getBytes(StandardCharsets.UTF_8))
        .containsExactly(expected.getBytes(StandardCharsets.UTF_8));
    assertThat(
            StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(expected)
                .canonicalJson()
                .getBytes(StandardCharsets.UTF_8))
        .containsExactly(expected.getBytes(StandardCharsets.UTF_8));
    assertThat(tuple.mutationDigest())
        .isEqualTo(StartSessionOperatorActionCodec.mutationDigest(tuple.action()));
  }

  @Test
  void roundTripsTypedContentsAndSameRequestIdWithDifferentActorIsAConflict() {
    StartSessionOperatorAction action =
        action(StartSessionOperatorAction.ClientIp.of("203.0.113.8"));
    StartSessionPreAuthorizationReservationTuple original =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "tuple-conflict-01", ACTOR_ID, action);
    StartSessionPreAuthorizationReservationTuple rehydrated =
        StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(original.canonicalJson());
    StartSessionPreAuthorizationReservationTuple changedActor =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "tuple-conflict-01", OTHER_ACTOR_ID, action);

    assertThat(rehydrated).isEqualTo(original);
    assertThat(rehydrated.canonicalJson()).isEqualTo(original.canonicalJson());
    assertThat(rehydrated.mutationDigest()).isEqualTo(original.mutationDigest());
    assertThat(changedActor.controlPlaneRequestId()).isEqualTo(original.controlPlaneRequestId());
    assertThat(changedActor).isNotEqualTo(original);
    assertThat(changedActor.canonicalJson()).isNotEqualTo(original.canonicalJson());
    // The action digest excludes reservation identity and actor; it cannot replace tuple
    // comparison.
    assertThat(changedActor.mutationDigest()).isEqualTo(original.mutationDigest());
    assertThat(original.canonicalJson())
        .contains("\"clientIp\":{\"presence\":\"PRESENT\",\"value\":\"203.0.113.8\"}");
  }

  @Test
  void strictDecoderRejectsNoncanonicalAndUnsupportedTupleShapes() {
    StartSessionPreAuthorizationReservationTuple tuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "tuple-strict-01", ACTOR_ID, action(StartSessionOperatorAction.ClientIp.absent()));
    String json = tuple.canonicalJson();
    String withUnknownRootField =
        json.replace(
            "\"tupleSchemaVersion\":\"1\"}", "\"tupleSchemaVersion\":\"1\",\"unexpected\":true}");
    String missingTargetOwner =
        json.replace(",\"targetOwner\":{\"ownerService\":\"game-session-service\"}", "");
    String invalidExpectedVersion =
        json.replace(
            "\"expectedVersion\":{\"presence\":\"ABSENT\"}",
            "\"expectedVersion\":{\"presence\":\"PRESENT\"}");
    String invalidActorKind = json.replace("\"kind\":\"HUMAN\"", "\"kind\":\"WORKLOAD\"");

    assertThatThrownBy(
            () -> StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(" " + json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical JSON");
    assertThatThrownBy(
            () ->
                StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(
                    withUnknownRootField))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(missingTargetOwner))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(
                    invalidExpectedVersion))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expectedVersion");
    assertThatThrownBy(
            () -> StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(invalidActorKind))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("actor must be human");
  }

  @Test
  void strictDecoderRejectsNonObjectJsonRootsAsIllegalArgument() {
    for (String json : List.of("[]", "\"tuple\"", "1", "true", "null")) {
      assertThatThrownBy(() -> StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(json))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static StartSessionOperatorAction action(StartSessionOperatorAction.ClientIp clientIp) {
    return new StartSessionOperatorAction(
        StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
        StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
        new StartSessionOperatorAction.Scope(TENANT_ID, "world-runtime"),
        new StartSessionOperatorAction.Target(91L, OWNER_ACCOUNT_ID),
        StartSessionOperatorAction.ExpectedVersion.ABSENT,
        new StartSessionOperatorAction.Mutation(clientIp),
        "tuple codec contract");
  }
}
