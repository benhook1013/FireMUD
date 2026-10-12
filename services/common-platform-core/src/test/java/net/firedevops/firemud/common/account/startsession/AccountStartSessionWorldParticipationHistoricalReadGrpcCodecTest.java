package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture.Values;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import org.junit.jupiter.api.Test;

class AccountStartSessionWorldParticipationHistoricalReadGrpcCodecTest {
  private static final String NAMESPACE = "world-runtime";
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID PARTICIPATION_ID = uuid("a8c1e8c8-f237-41b7-918d-ec2281bcac10");
  private static final UUID GAME_SESSION_ATTEMPT_ID = uuid("b9d2f9d9-0438-42c8-829e-fd3392cd9d21");
  private static final UUID GAME_INSTANCE_ID = uuid("c0e30aea-1549-43d9-93af-0e44a3deae32");
  private static final UUID READ_ID = uuid("d1f41bfb-265a-4f3b-8b2a-124cba20ce43");
  private static final String PREPARATION_INPUT =
      "{ \"identity\" : \"retained original input\", \"revision\": 3 }\n";

  @Test
  void roundTripsTheCompleteOriginalTupleAndResolvedHistoricalCoordinates() {
    var request = request();
    var evidence = evidence(request);

    var wireRequest =
        AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.toRequest(request);
    var decodedRequest =
        AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.fromRequest(wireRequest);
    assertThat(decodedRequest).isEqualTo(request);

    var response =
        AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.toResponse(request, evidence);
    assertThat(response.getAllFields()).hasSize(8);
    var decoded =
        AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.fromResponse(
            request, response);
    assertThat(decoded.request()).isEqualTo(request);
    assertThat(decoded.request().originalPostAuthorizationTuple())
        .containsExactly(request.originalPostAuthorizationTuple());
    assertThat(decoded.accountWorldParticipationId()).isEqualTo(PARTICIPATION_ID);
    assertThat(decoded.accountWorldParticipationFence()).isEqualTo(31L);
    assertThat(decoded.gameSessionOwnerAttemptId()).isEqualTo(GAME_SESSION_ATTEMPT_ID);
    assertThat(decoded.gameSessionOwnerFence()).isEqualTo(37L);
    assertThat(decoded.canonicalGameInstanceId()).isEqualTo(GAME_INSTANCE_ID);
    assertThat(decoded.preparationInputJson()).isEqualTo(PREPARATION_INPUT);
    assertThat(decoded.preparationInputDigest()).isEqualTo(digest(PREPARATION_INPUT));
  }

  @Test
  void rejectsUnknownFieldsUnsupportedVersionMalformedReadIdAndIncompleteTuple() {
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
                new AccountStartSessionWorldParticipationHistoricalReadRequest(
                    READ_ID, NAMESPACE, new byte[0]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty or oversized");
    assertThatThrownBy(
            () ->
                AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.fromRequest(
                    wire.toBuilder()
                        .setOriginalPostAuthorizationTuple(com.google.protobuf.ByteString.EMPTY)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty or oversized");
  }

  @Test
  void rejectsOnlyReservationOwnerCollisionAtRequestBoundary() {
    var original = originalTuple(NAMESPACE);
    assertThatThrownBy(
            () ->
                new AccountStartSessionWorldParticipationHistoricalReadRequest(
                    original.reservationOwnerId(), NAMESPACE, original.canonicalBytes()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("distinct from the reservation owner");

    for (UUID requestId :
        new UUID[] {
          original.preAuthorizationTuple().actor().accountId(),
          TENANT,
          uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f")
        }) {
      assertThat(
              new AccountStartSessionWorldParticipationHistoricalReadRequest(
                      requestId, NAMESPACE, original.canonicalBytes())
                  .readRequestId())
          .isEqualTo(requestId);
    }
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
                response.getRequest().toBuilder()
                    .setReadRequestId("0fd3d542-a9f8-4b8f-b1df-79413650126c")
                    .build())
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
                    evidence(
                        new AccountStartSessionWorldParticipationHistoricalReadRequest(
                            READ_ID,
                            NAMESPACE,
                            originalTuple(NAMESPACE, 20L).canonicalBytes()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differs from the exact historical request");
  }

  @Test
  void rejectsOversizedPreparationInputAndTupleAtClosedCarrierBoundary() {
    var request = request();
    String oversized = "x".repeat(WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES + 1);
    assertThatThrownBy(
            () ->
                new AccountStartSessionWorldParticipationHistoricalReadEvidence(
                    request,
                    PARTICIPATION_ID,
                    31L,
                    GAME_SESSION_ATTEMPT_ID,
                    37L,
                    GAME_INSTANCE_ID,
                    oversized,
                    digest(oversized)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("byte limit");
    assertThatThrownBy(
            () ->
                new AccountStartSessionWorldParticipationHistoricalReadRequest(
                    READ_ID,
                    NAMESPACE,
                    new byte
                        [StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES
                            + 1]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty or oversized");
    assertThatThrownBy(
            () ->
                new AccountStartSessionWorldParticipationHistoricalReadEvidence(
                    request,
                    PARTICIPATION_ID,
                    0L,
                    GAME_SESSION_ATTEMPT_ID,
                    37L,
                    GAME_INSTANCE_ID,
                    PREPARATION_INPUT,
                    digest(PREPARATION_INPUT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fence must be positive");
  }

  private static AccountStartSessionWorldParticipationHistoricalReadRequest request() {
    return new AccountStartSessionWorldParticipationHistoricalReadRequest(
        READ_ID, NAMESPACE, originalTuple(NAMESPACE).canonicalBytes());
  }

  private static AccountStartSessionWorldParticipationHistoricalReadEvidence evidence(
      AccountStartSessionWorldParticipationHistoricalReadRequest request) {
    return new AccountStartSessionWorldParticipationHistoricalReadEvidence(
        request,
        PARTICIPATION_ID,
        31L,
        GAME_SESSION_ATTEMPT_ID,
        37L,
        GAME_INSTANCE_ID,
        PREPARATION_INPUT,
        digest(PREPARATION_INPUT));
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple(String namespace) {
    return originalTuple(namespace, 19L);
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple(
      String namespace, long reservationClaimFence) {
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
        reservationClaimFence,
        authorityBundle(preTuple),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    return StartSessionAuthorityEvidenceBundleFixture.canonicalBytes(
        tuple,
        new Values(
            uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f"),
            "sha256:" + "a".repeat(64),
            "17",
            "CURRENT",
            Instant.parse("2020-01-01T00:00:00Z"),
            Instant.parse("2020-01-02T00:00:00Z"),
            1L,
            2L,
            3L,
            4L,
            5L,
            23L,
            uuid("a681bba7-c215-4cf1-a35b-14348912cbdc"),
            "2",
            "3"));
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
