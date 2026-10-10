package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AccountStartSessionWorldParticipationHistoricalReadGrpcCodecTest {
  private static final String NAMESPACE = "world-runtime";
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID PARTICIPATION_ID = uuid("a8c1e8c8-f237-41b7-918d-ec2281bcac10");
  private static final UUID GAME_SESSION_ATTEMPT_ID = uuid("b9d2f9d9-0438-42c8-829e-fd3392cd9d21");
  private static final UUID GAME_INSTANCE_ID = uuid("c0e30aea-1549-43d9-93af-0e44a3deae32");
  private static final UUID READ_ID = uuid("d1f41bfb-265a-4f3b-8b2a-124cba20ce43");
  private static final String PREPARATION_INPUT =
      "{ \"identity\" : \"retained original input\", \"revision\": 3 }\n";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void roundTripsOnlyTheExactHistoricalRequestAndAccountRetainedFields() {
    var request = request();
    var evidence = evidence(request);

    var wireRequest =
        AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.toRequest(request);
    var decodedRequest =
        AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.fromRequest(wireRequest);
    assertThat(decodedRequest).isEqualTo(request);

    var response =
        AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.toResponse(request, evidence);
    assertThat(response.getAllFields()).hasSize(7);
    var decoded =
        AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.fromResponse(
            request, response);
    assertThat(decoded.request()).isEqualTo(request);
    assertThat(decoded.originalPostAuthorizationTuple())
        .containsExactly(evidence.originalPostAuthorizationTuple());
    assertThat(decoded.gameSessionOwnerAttemptId()).isEqualTo(GAME_SESSION_ATTEMPT_ID);
    assertThat(decoded.gameSessionOwnerFence()).isEqualTo(37L);
    assertThat(decoded.canonicalGameInstanceId()).isEqualTo(GAME_INSTANCE_ID);
    assertThat(decoded.preparationInputJson()).isEqualTo(PREPARATION_INPUT);
    assertThat(decoded.preparationInputDigest()).isEqualTo(digest(PREPARATION_INPUT));
  }

  @Test
  void rejectsUnknownFieldsUnsupportedVersionMalformedIdsAndNonpositiveFences() {
    var request = request();
    UnknownFieldSet unknown = unknown();
    var wire = AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.toRequest(request);

    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.fromRequest(
                    wire.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.fromRequest(
                    wire.toBuilder().setSchemaVersion(2).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported");
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.fromRequest(
                    wire.toBuilder().setReadRequestId(READ_ID + " ").build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical non-nil UUID");
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.fromRequest(
                    wire.toBuilder().setAccountWorldParticipationFence(0L).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
    assertThatThrownBy(
            () ->
                new AccountStartSessionWorldParticipationHistoricalReadRequest(
                    READ_ID, NAMESPACE, READ_ID, 31L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("distinct");
  }

  @Test
  void rejectsChangedRequestEchoUnknownResponseAndInconsistentRetainedIdentity() {
    var request = request();
    var response =
        AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.toResponse(
            request, evidence(request));

    var changedEcho =
        response.toBuilder()
            .setRequest(
                response.getRequest().toBuilder().setTargetNamespace("other-runtime").build())
            .build();
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.fromResponse(
                    request, changedEcho))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed the exact");
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.fromResponse(
                    request, response.toBuilder().setUnknownFields(unknown()).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");

    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setPreparationInputDigest("sha256:" + "0".repeat(64))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differs from the exact retained");
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.toResponse(
                    request,
                    new AccountStartSessionWorldParticipationHistoricalReadEvidence(
                        request,
                        originalTuple("other-runtime").canonicalBytes(),
                        GAME_SESSION_ATTEMPT_ID,
                        37L,
                        GAME_INSTANCE_ID,
                        PREPARATION_INPUT,
                        digest(PREPARATION_INPUT))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differs from the exact original");
  }

  @Test
  void rejectsOversizedPreparationInputAndTupleAtClosedCarrierBoundary() {
    var request = request();
    String oversized = "x".repeat(WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES + 1);
    assertThatThrownBy(
            () ->
                new AccountStartSessionWorldParticipationHistoricalReadEvidence(
                    request,
                    originalTuple(NAMESPACE).canonicalBytes(),
                    GAME_SESSION_ATTEMPT_ID,
                    37L,
                    GAME_INSTANCE_ID,
                    oversized,
                    digest(oversized)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("byte limit");
    assertThatThrownBy(
            () ->
                new AccountStartSessionWorldParticipationHistoricalReadEvidence(
                    request,
                    new byte
                        [net.firedevops.firemud.common.operator
                                .StartSessionPostAuthorizationExecutionTuple
                                .MAX_CANONICAL_TUPLE_BYTES
                            + 1],
                    GAME_SESSION_ATTEMPT_ID,
                    37L,
                    GAME_INSTANCE_ID,
                    PREPARATION_INPUT,
                    digest(PREPARATION_INPUT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("oversized");
  }

  private static AccountStartSessionWorldParticipationHistoricalReadRequest request() {
    return new AccountStartSessionWorldParticipationHistoricalReadRequest(
        READ_ID, NAMESPACE, PARTICIPATION_ID, 31L);
  }

  private static AccountStartSessionWorldParticipationHistoricalReadEvidence evidence(
      AccountStartSessionWorldParticipationHistoricalReadRequest request) {
    return new AccountStartSessionWorldParticipationHistoricalReadEvidence(
        request,
        originalTuple(NAMESPACE).canonicalBytes(),
        GAME_SESSION_ATTEMPT_ID,
        37L,
        GAME_INSTANCE_ID,
        PREPARATION_INPUT,
        digest(PREPARATION_INPUT));
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple(String namespace) {
    UUID actor = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
    UUID targetOwner = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
    UUID reservationOwner = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
    String requestId = "historical-participation-original-request";
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            requestId,
            actor,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, namespace),
                new StartSessionOperatorAction.Target(91L, targetOwner),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "synthetic historical Account evidence carrier test"));
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
            "evaluatedAt", "2020-01-01T00:00:00Z",
            "expiresAt", "2020-01-02T00:00:00Z");
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

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
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
}
