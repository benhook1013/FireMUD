package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionWorldParticipationResponse;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal.Outcome;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Closed selector and response-binding cases using a real canonical World terminal value. */
class AccountStartSessionWorldParticipationSettlementGrpcCodecTest {
  private static final String NAMESPACE = "world-runtime";
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID PARTICIPATION_ID = uuid("a8c1e8c8-f237-41b7-918d-ec2281bcac10");
  private static final UUID GAME_SESSION_ATTEMPT_ID = uuid("b9d2f9d9-0438-42c8-829e-fd3392cd9d21");
  private static final UUID GAME_INSTANCE_ID = uuid("c0e30aea-1549-43d9-93af-0e44a3deae32");
  private static final UUID SETTLEMENT_REQUEST_ID = uuid("d1f41bfb-265a-4f3b-8b2a-124cba20ce43");
  private static final String CONTROL_PLANE_REQUEST_ID = "world-settlement-original-request";
  private static final String PREPARATION_INPUT = "{ \"immutable\": true }";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void roundTripsOnlyExactLookupIdentityAndCompleteCanonicalTerminal() {
    var input = input();
    var request = AccountStartSessionWorldParticipationSettlementGrpcCodec.toRequest(input);
    var decodedRequest =
        AccountStartSessionWorldParticipationSettlementGrpcCodec.fromRequest(request, NAMESPACE);

    assertThat(decodedRequest).isEqualTo(input);
    assertThat(request.getAllFields()).hasSize(5);
    assertThat(request.getSerializedSize())
        .isLessThanOrEqualTo(
            AccountStartSessionWorldParticipationSettlementGrpcCodec.MAX_REQUEST_WIRE_BYTES);
    assertThat(request.getAccountWorldParticipationId(0)).isEqualTo(PARTICIPATION_ID.toString());

    var terminal = terminal(NAMESPACE, PARTICIPATION_ID, 31L, Outcome.COMMITTED);
    var evidence =
        new AccountStartSessionWorldParticipationSettlementEvidence(
            SETTLEMENT_REQUEST_ID, terminal, Instant.parse("2026-10-11T00:00:00.000001Z"));
    var response =
        AccountStartSessionWorldParticipationSettlementGrpcCodec.toResponse(input, evidence);
    var decoded =
        AccountStartSessionWorldParticipationSettlementGrpcCodec.fromResponse(input, response);
    var wireDecoded =
        AccountStartSessionWorldParticipationSettlementGrpcCodec.fromResponseWireBytes(
            input, response.toByteArray());

    assertThat(response.getAllFields()).hasSize(6);
    assertThat(decoded.settlementRequestId()).isEqualTo(SETTLEMENT_REQUEST_ID);
    assertThat(decoded.accountWorldParticipationId()).isEqualTo(PARTICIPATION_ID);
    assertThat(decoded.accountWorldParticipationFence()).isEqualTo(31L);
    assertThat(decoded.terminal().outcome()).isEqualTo(Outcome.COMMITTED);
    assertThat(decoded.terminal().canonicalBytes()).containsExactly(terminal.canonicalBytes());
    assertThat(decoded.settledAt()).isEqualTo(Instant.parse("2026-10-11T00:00:00.000001Z"));
    assertThat(wireDecoded.settlementRequestId()).isEqualTo(decoded.settlementRequestId());
    assertThat(wireDecoded.terminal().canonicalBytes())
        .containsExactly(decoded.terminal().canonicalBytes());
    assertThat(wireDecoded.settledAt()).isEqualTo(decoded.settledAt());
  }

  @Test
  void rejectsDuplicateUnknownChangedAndNoncanonicalRequestFields() {
    var input = input();
    var request = AccountStartSessionWorldParticipationSettlementGrpcCodec.toRequest(input);

    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationSettlementGrpcCodec.fromRequest(
                    request.toBuilder().addAccountWorldParticipationFence("32").build(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationSettlementGrpcCodec.fromRequest(
                    request.toBuilder().setTargetNamespace(0, "another-runtime").build(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationSettlementGrpcCodec.fromRequest(
                    request.toBuilder().setAccountWorldParticipationFence(0, "031").build(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationSettlementGrpcCodec.fromRequest(
                    request.toBuilder()
                        .setAccountWorldParticipationFence(0, "9223372036854775808")
                        .build(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationSettlementGrpcCodec.fromRequest(
                    request.toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                                .build())
                        .build(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationSettlementGrpcCodec.fromRequest(
                    request.toBuilder()
                        .setSettlementRequestId(0, "00000000-0000-0000-0000-000000000000")
                        .build(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical non-nil UUID");

    byte[] noncanonicalWire =
        java.util.Arrays.copyOf(
            AccountStartSessionWorldParticipationSettlementGrpcCodec.toRequest(input).toByteArray(),
            request.getSerializedSize() + 1);
    noncanonicalWire[noncanonicalWire.length - 1] = 0;
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationSettlementGrpcCodec.fromRequestWireBytes(
                    noncanonicalWire, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);

    byte[] outOfOrderWire =
        concatenate(
            request.toBuilder()
                .clear()
                .addAllAccountWorldParticipationFence(
                    request.getAccountWorldParticipationFenceList())
                .build()
                .toByteArray(),
            request.toBuilder().clearAccountWorldParticipationFence().build().toByteArray());
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationSettlementGrpcCodec.fromRequestWireBytes(
                    outOfOrderWire, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not canonical");
  }

  @Test
  void responseMustEchoLookupIdentityAndBindTerminalToParticipationAndNamespace() {
    var input = input();
    var terminal = terminal(NAMESPACE, PARTICIPATION_ID, 31L, Outcome.ABORTED);
    var valid = response(input, terminal, "2026-10-11T00:00:00.000001Z");

    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationSettlementGrpcCodec.fromResponse(
                    input,
                    valid.toBuilder()
                        .setSettlementRequestId(
                            0, uuid("e2a52c0c-c71f-4900-b1f2-c78f8b512a63").toString())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("identity");
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationSettlementGrpcCodec.fromResponse(
                    input,
                    valid.toBuilder().setSettledAt(0, "2026-10-11T00:00:00.000000001Z").build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("microseconds");
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationSettlementGrpcCodec.fromResponse(
                    input,
                    valid.toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationSettlementGrpcCodec.fromResponse(
                    input,
                    response(
                        input,
                        terminal(
                            NAMESPACE,
                            uuid("e2a52c0c-c71f-4900-b1f2-c78f8b512a63"),
                            31L,
                            Outcome.COMMITTED),
                        "2026-10-11T00:00:00.000001Z")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact Account participation");
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationSettlementGrpcCodec.fromResponse(
                    input,
                    response(
                        input,
                        terminal(NAMESPACE, PARTICIPATION_ID, 32L, Outcome.COMMITTED),
                        "2026-10-11T00:00:00.000001Z")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact Account participation");
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationSettlementGrpcCodec.fromResponse(
                    input,
                    response(
                        input,
                        terminal("another-runtime", PARTICIPATION_ID, 31L, Outcome.COMMITTED),
                        "2026-10-11T00:00:00.000001Z")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact Account participation");
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationSettlementGrpcCodec.fromResponse(
                    input,
                    valid.toBuilder()
                        .setTerminalEvidenceCanonicalBytes(0, ByteString.copyFromUtf8("{}"))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void responseWireBytesMustUseCanonicalFieldOrder() {
    var input = input();
    var response =
        response(
            input,
            terminal(NAMESPACE, PARTICIPATION_ID, 31L, Outcome.ABORTED),
            "2026-10-11T00:00:00.000001Z");
    byte[] outOfOrderWire =
        concatenate(
            response.toBuilder()
                .clear()
                .addAllTerminalEvidenceCanonicalBytes(
                    response.getTerminalEvidenceCanonicalBytesList())
                .build()
                .toByteArray(),
            response.toBuilder().clearTerminalEvidenceCanonicalBytes().build().toByteArray());

    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationSettlementGrpcCodec.fromResponseWireBytes(
                    input, outOfOrderWire))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not canonical");
  }

  @Test
  void rejectsIncompleteEvidenceAndTimestampPrecisionLoss() {
    assertThatThrownBy(
            () ->
                new AccountStartSessionWorldParticipationSettlementEvidence(
                    SETTLEMENT_REQUEST_ID,
                    terminal(NAMESPACE, PARTICIPATION_ID, 31L, Outcome.COMMITTED),
                    Instant.parse("2026-10-11T00:00:00.000000001Z")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("preserve PostgreSQL microseconds");

    var input = input();
    var incomplete =
        SettleOriginalStartSessionWorldParticipationResponse.newBuilder()
            .addSchemaVersion(1)
            .addSettlementRequestId(SETTLEMENT_REQUEST_ID.toString())
            .addAccountWorldParticipationId(PARTICIPATION_ID.toString())
            .addAccountWorldParticipationFence("31")
            .addSettledAt("2026-10-11T00:00:00.000001Z")
            .addTerminalEvidenceCanonicalBytes(ByteString.EMPTY)
            .build();
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationSettlementGrpcCodec.fromResponse(
                    input, incomplete))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AccountStartSessionWorldParticipationSettlementInput input() {
    return new AccountStartSessionWorldParticipationSettlementInput(
        NAMESPACE, SETTLEMENT_REQUEST_ID, PARTICIPATION_ID, 31L);
  }

  private static SettleOriginalStartSessionWorldParticipationResponse response(
      AccountStartSessionWorldParticipationSettlementInput input,
      WorldStartSessionExecutionTerminal terminal,
      String settledAt) {
    return SettleOriginalStartSessionWorldParticipationResponse.newBuilder()
        .addSchemaVersion(1)
        .addSettlementRequestId(input.settlementRequestId().toString())
        .addAccountWorldParticipationId(input.accountWorldParticipationId().toString())
        .addAccountWorldParticipationFence(Long.toString(input.accountWorldParticipationFence()))
        .addSettledAt(settledAt)
        .addTerminalEvidenceCanonicalBytes(ByteString.copyFrom(terminal.canonicalBytes()))
        .build();
  }

  private static WorldStartSessionExecutionTerminal terminal(
      String namespace, UUID participationId, long participationFence, Outcome outcome) {
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple(namespace);
    return new WorldStartSessionExecutionTerminal(
        tuple.canonicalBytes(),
        participationId,
        participationFence,
        GAME_SESSION_ATTEMPT_ID,
        37L,
        namespace,
        TENANT,
        CONTROL_PLANE_REQUEST_ID,
        GAME_INSTANCE_ID,
        digest(PREPARATION_INPUT),
        PREPARATION_INPUT,
        41L,
        outcome);
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple(String namespace) {
    UUID actor = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
    UUID targetOwner = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
    UUID reservationOwner = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            CONTROL_PLANE_REQUEST_ID,
            actor,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, namespace),
                new StartSessionOperatorAction.Target(91L, targetOwner),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "synthetic World participation settlement codec test"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        reservationOwner,
        19L,
        authorityBundle(preTuple),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    String actorId = tuple.actor().accountId().toString();
    String tenantId = tuple.action().scope().tenantId().toString();
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion", "17",
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2026-10-09T00:00:00Z",
            "expiresAt", "2026-10-09T00:05:00Z");
    Map<String, Object> issuanceIdentity =
        Map.of(
            "issuanceOperationId", "f5d044bd-7e5f-4e2d-9859-9025cbdcc60f",
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
            "mutationDigest", tuple.mutationDigest());
    Map<String, Object> authority =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 2L,
            "tenantAuthorityGeneration", Map.of(tenantId, 3L),
            "membershipAuthorityGeneration", Map.of(tenantId, 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> humanEvidence =
        Map.of(
            "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId", actorId,
            "controlUiTokenJti", "a681bba7-c215-4cf1-a35b-14348912cbdc",
            "role", "tenantAdmin",
            "accountGeneration", "2",
            "tenantGeneration", "3");
    Map<String, Object> bundle =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope",
                Map.of(
                    "tenantId",
                    tenantId,
                    "targetNamespace",
                    tuple.action().scope().targetNamespace()),
                "actionFamily",
                tuple.actionFamily(),
                "applicableAccountId",
                actorId,
                "applicableTenantId",
                tenantId),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            issuanceIdentity,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(tenantId, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            humanEvidence);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(bundle));
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String digest(String value) {
    try {
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static byte[] concatenate(byte[] first, byte[] second) {
    byte[] result = java.util.Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }
}
