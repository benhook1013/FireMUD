package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal.Outcome;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldStartSessionExecutionTerminalResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WorldStartSessionExecutionTerminalReadGrpcCodecTest {
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID RESERVATION_OWNER = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID PARTICIPATION_ID = uuid("a8c1e8c8-f237-41b7-918d-ec2281bcac10");
  private static final UUID GAME_SESSION_ATTEMPT_ID = uuid("b9d2f9d9-0438-42c8-829e-fd3392cd9d21");
  private static final UUID GAME_INSTANCE_ID = uuid("c0e30aea-1549-43d9-93af-0e44a3deae32");
  private static final UUID READ_ID = uuid("d1f41bfb-265a-4f3b-8b2a-124cba20ce43");
  private static final String CONTROL_PLANE_REQUEST_ID = "terminal-read-original-request";
  private static final String NAMESPACE = "world-runtime";
  private static final String PREPARATION_INPUT =
      "{ \"selected\" : \"immutable source\", \"revision\": 3 }\n";
  private static final String WORKLOAD =
      "spiffe://firemud/ns/world-runtime/sa/logging-admin-service";
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void roundTripsFreshReadAndOnlyCanonicalTerminalResult() {
    var request = request();
    var wireRequest = WorldStartSessionExecutionTerminalReadGrpcCodec.toRequest(request);
    var decoded = WorldStartSessionExecutionTerminalReadGrpcCodec.fromRequest(wireRequest);
    assertThat(decoded.readRequestId()).isEqualTo(READ_ID);
    assertThat(decoded.originalPostAuthorizationTuple())
        .containsExactly(request.originalPostAuthorizationTuple());
    assertThat(decoded.accountWorldParticipationFence()).isEqualTo(31L);
    assertThat(decoded.gameSessionOwnerFence()).isEqualTo(37L);
    assertThat(decoded.preparationInputJson()).isEqualTo(PREPARATION_INPUT);

    var terminal = terminal(request, PARTICIPATION_ID, 31L, GAME_SESSION_ATTEMPT_ID, 37L);
    var response = WorldStartSessionExecutionTerminalReadGrpcCodec.toResponse(request, terminal);
    assertThat(response.getAllFields()).hasSize(2);
    var recovered = WorldStartSessionExecutionTerminalReadGrpcCodec.fromResponse(request, response);
    assertThat(recovered.canonicalBytes()).containsExactly(terminal.canonicalBytes());
    assertThat(recovered.outcome()).isEqualTo(Outcome.COMMITTED);
  }

  @Test
  void rejectsUnknownFieldsMalformedIdentityAndMixedNamespaceBeforeUse() {
    var request = request();
    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();

    assertThatThrownBy(
            () ->
                WorldStartSessionExecutionTerminalReadGrpcCodec.fromRequest(
                    WorldStartSessionExecutionTerminalReadGrpcCodec.toRequest(request).toBuilder()
                        .setUnknownFields(unknown)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                WorldStartSessionExecutionTerminalReadGrpcCodec.fromRequest(
                    WorldStartSessionExecutionTerminalReadGrpcCodec.toRequest(request).toBuilder()
                        .setReadRequestId("d1f41bfb-265a-4f3b-8b2a-124cba20ce43 ")
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical non-nil UUID");
    assertThatThrownBy(
            () ->
                WorldStartSessionExecutionTerminalReadGrpcCodec.fromRequest(
                    WorldStartSessionExecutionTerminalReadGrpcCodec.toRequest(request).toBuilder()
                        .setAccountWorldParticipationFence(0L)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
    assertThatThrownBy(
            () ->
                WorldStartSessionExecutionTerminalReadGrpcCodec.fromRequest(
                    WorldStartSessionExecutionTerminalReadGrpcCodec.toRequest(request).toBuilder()
                        .setTargetNamespace("other-runtime")
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differs from the exact original");
  }

  @Test
  void rejectsSubstitutedTerminalIdentityOrReadCorrelation() {
    var request = request();
    var substituted =
        terminal(
            request,
            uuid("e2a52c0c-c71f-4900-b1f2-c78f8b512a63"),
            31L,
            GAME_SESSION_ATTEMPT_ID,
            37L);
    var response =
        ReadWorldStartSessionExecutionTerminalResponse.newBuilder()
            .setReadRequestId(READ_ID.toString())
            .setCanonicalTerminalBytes(
                com.google.protobuf.ByteString.copyFrom(substituted.canonicalBytes()))
            .build();
    assertThatThrownBy(
            () -> WorldStartSessionExecutionTerminalReadGrpcCodec.fromResponse(request, response))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete original StartSession identity");

    var valid = terminal(request, PARTICIPATION_ID, 31L, GAME_SESSION_ATTEMPT_ID, 37L);
    var changedCorrelation =
        ReadWorldStartSessionExecutionTerminalResponse.newBuilder()
            .setReadRequestId("e2a52c0c-c71f-4900-b1f2-c78f8b512a63")
            .setCanonicalTerminalBytes(
                com.google.protobuf.ByteString.copyFrom(valid.canonicalBytes()))
            .build();
    assertThatThrownBy(
            () ->
                WorldStartSessionExecutionTerminalReadGrpcCodec.fromResponse(
                    request, changedCorrelation))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed the exact");

    assertThatThrownBy(
            () ->
                WorldStartSessionExecutionTerminalReadGrpcCodec.fromResponse(
                    request, changedCorrelation.toBuilder().setUnknownFields(unknown()).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
  }

  static WorldStartSessionExecutionTerminalReadRequest request() {
    return new WorldStartSessionExecutionTerminalReadRequest(
        READ_ID,
        NAMESPACE,
        originalTuple().canonicalBytes(),
        PARTICIPATION_ID,
        31L,
        GAME_SESSION_ATTEMPT_ID,
        37L,
        GAME_INSTANCE_ID,
        PREPARATION_INPUT);
  }

  private static WorldStartSessionExecutionTerminal terminal(
      WorldStartSessionExecutionTerminalReadRequest request,
      UUID participationId,
      long participationFence,
      UUID attemptId,
      long attemptFence) {
    return new WorldStartSessionExecutionTerminal(
        request.originalPostAuthorizationTuple(),
        participationId,
        participationFence,
        attemptId,
        attemptFence,
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.controlPlaneRequestId(),
        request.canonicalGameInstanceId(),
        request.preparationInputDigest(),
        request.preparationInputJson(),
        41L,
        Outcome.COMMITTED);
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple() {
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            CONTROL_PLANE_REQUEST_ID,
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
                new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "canonical StartSession owner attempt"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        WORKLOAD,
        FINGERPRINT,
        RESERVATION_OWNER,
        19L,
        bundle(preTuple),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] bundle(StartSessionPreAuthorizationReservationTuple tuple) {
    String tenantId = TENANT.toString();
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion", "17",
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2026-10-09T00:00:00Z",
            "expiresAt", "2026-10-09T00:05:00Z");
    Map<String, Object> identity =
        Map.of(
            "issuanceOperationId", uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f").toString(),
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
    Map<String, Object> evidence =
        Map.of(
            "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId", ACTOR.toString(),
            "controlUiTokenJti", "a681bba7-c215-4cf1-a35b-14348912cbdc",
            "role", "tenantAdmin",
            "accountGeneration", "2",
            "tenantGeneration", "3");
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenantId, "targetNamespace", NAMESPACE),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", tenantId),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            identity,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(tenantId, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            evidence);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
