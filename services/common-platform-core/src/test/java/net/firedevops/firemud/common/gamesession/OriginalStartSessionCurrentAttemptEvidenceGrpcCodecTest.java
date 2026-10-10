package net.firedevops.firemud.common.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import com.google.protobuf.UnknownFieldSet;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OriginalStartSessionCurrentAttemptEvidenceGrpcCodecTest {
  private static final UUID READ_ID = uuid("01111111-1111-4111-8111-111111111111");
  private static final UUID ATTEMPT = uuid("02222222-2222-4222-8222-222222222222");
  private static final UUID MUTATION = uuid("03333333-3333-4333-8333-333333333333");

  @Test
  void roundTripsExactRequestPendingPhaseProjectionAndOriginalExpiry() {
    Request request = request();
    byte[] projection =
        "attached-account-projection".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Result result =
        new Result(request, Instant.parse("2030-01-01T00:00:00.123456789Z"), projection);
    var wireRequest = OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toRequest(request);
    var wireResponse = OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(result);

    assertThat(OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromRequest(wireRequest))
        .isEqualTo(request);
    var decoded =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(request, wireResponse);
    assertThat(decoded.request()).isEqualTo(request);
    assertThat(decoded.phaseState()).isEqualTo("OWNER_EXECUTION_PENDING");
    assertThat(decoded.originalLeaseExpiresAt()).isEqualTo(result.originalLeaseExpiresAt());
    assertThat(decoded.accountRedemptionProjection()).containsExactly(projection);
    assertThat(wireResponse.getAccountRedemptionProjectionDigest())
        .isEqualTo(result.accountRedemptionProjectionDigest());
  }

  @Test
  void rejectsUnknownFieldsUnsupportedSchemaInvalidFenceAndChangedEcho() {
    Request request = request();
    var wire = OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toRequest(request);
    var unknownRequest =
        wire.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertThatThrownBy(
            () -> OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromRequest(unknownRequest))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromRequest(
                    wire.toBuilder().setSchemaVersion(2).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromRequest(
                    wire.toBuilder().setExpectedOwnerFence(0L).build()))
        .isInstanceOf(IllegalArgumentException.class);

    var response =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(
            new Result(request, Instant.parse("2030-01-01T00:00:00Z"), new byte[] {1, 2, 3}));
    var changedEcho =
        response.toBuilder()
            .setRequest(
                wire.toBuilder()
                    .setReadRequestId(uuid("04444444-4444-4444-8444-444444444444").toString()))
            .build();
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                    request, changedEcho))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact read request");
  }

  @Test
  void rejectsWrongPhaseDigestResponseAndTimestampShapes() {
    Request request = request();
    var valid =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(
            new Result(request, Instant.parse("2030-01-01T00:00:00Z"), new byte[] {1, 2, 3}));
    var unknownResponse =
        valid.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                    request, unknownResponse))
        .isInstanceOf(IllegalArgumentException.class);
    var unknownTimestamp =
        valid.toBuilder()
            .setOriginalLeaseExpiresAt(
                valid.getOriginalLeaseExpiresAt().toBuilder()
                    .setUnknownFields(
                        UnknownFieldSet.newBuilder()
                            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                            .build())
                    .build())
            .build();
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                    request, unknownTimestamp))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                    request, valid.toBuilder().setPhaseState("OWNER_EXECUTION_COMMITTED").build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                    request,
                    valid.toBuilder().setAccountRedemptionProjectionDigest("0".repeat(64)).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest");
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                    request,
                    valid.toBuilder()
                        .setOriginalLeaseExpiresAt(
                            Timestamp.newBuilder().setSeconds(253_402_300_800L).setNanos(0).build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timestamp");
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                    request,
                    valid.toBuilder()
                        .setOriginalLeaseExpiresAt(
                            Timestamp.newBuilder()
                                .setSeconds(Instant.parse("2030-01-01T00:00:00Z").getEpochSecond())
                                .setNanos(1_000_000_000)
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
                    request, valid.toBuilder().clearOriginalLeaseExpiresAt().build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Request request() {
    return new Request(
        READ_ID,
        "world-runtime",
        CurrentAttemptTestData.tuple().canonicalBytes(),
        ATTEMPT,
        MUTATION,
        8L);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}

/** Minimal synthetic StartSession tuple fixture shared by the two focused transport tests. */
final class CurrentAttemptTestData {
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID RESERVATION_OWNER = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = uuid("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private CurrentAttemptTestData() {}

  static StartSessionPostAuthorizationExecutionTuple tuple() {
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "current-attempt-test",
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, "world-runtime"),
                new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "canonical StartSession owner attempt"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        "spiffe://firemud/ns/world-runtime/sa/logging-admin-service",
        FINGERPRINT,
        RESERVATION_OWNER,
        19L,
        authorityBundle(pre),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    String tenant = TENANT.toString();
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenant, "targetNamespace", "world-runtime"),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", tenant),
            "accountProjectionEvidence",
            Map.of(
                "sourceType", "ACCOUNT",
                "sourceEvidenceId", digest('a'),
                "sourceEvidenceVersion", "17",
                "projectionStatus", "CURRENT",
                "evaluatedAt", "2026-10-09T00:00:00Z",
                "expiresAt", "2026-10-09T00:05:00Z"),
            "issuanceOperationIdentity",
            Map.of(
                "issuanceOperationId", ISSUANCE_ID.toString(),
                "controlPlaneRequestId", tuple.controlPlaneRequestId(),
                "actionFamilyRequestIdentity",
                    Map.of(
                        "requestIdentityKind",
                        "controlPlaneRequestId",
                        "requestId",
                        tuple.controlPlaneRequestId()),
                "mutationDigest", tuple.mutationDigest()),
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            Map.of(
                "issuerAuthGeneration", 1L,
                "accountAuthorityGeneration", 2L,
                "tenantAuthorityGeneration", Map.of(tenant, 3L),
                "membershipAuthorityGeneration", Map.of(tenant, 4L),
                "privateRealmGrantVersions", List.of()),
            "membershipVersion",
            Map.of(tenant, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            Map.of(
                "evidenceType",
                StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                "actorAccountId",
                ACTOR.toString(),
                "controlUiTokenJti",
                TOKEN_JTI.toString(),
                "role",
                "tenantAdmin",
                "accountGeneration",
                "2",
                "tenantGeneration",
                "3"));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
