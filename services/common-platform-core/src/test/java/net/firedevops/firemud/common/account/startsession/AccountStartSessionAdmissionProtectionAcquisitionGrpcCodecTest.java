package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AcquireOriginalStartSessionAdmissionProtectionResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AccountStartSessionAdmissionProtectionAcquisitionGrpcCodecTest {
  private static final String NAMESPACE = "world-runtime";
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID MUTATION = uuid("f1a3ab1e-9147-4667-b6c4-6eb5119e8a31");
  private static final UUID ATTEMPT = uuid("ec13cc04-ec15-4eb8-a018-c2c5e8da65f8");
  private static final UUID PARTICIPATION = uuid("47b3be7f-a32f-4e19-8916-8c3b8da07a82");
  private static final UUID HOLD_ID = uuid("0db7344a-1e67-4b95-905a-83dc9c472f0c");
  private static final UUID HOLD_FENCE = uuid("52a14272-f9e4-4f67-97c9-62247b5fbcc1");
  private static final UUID PROTECTION_ID = uuid("6b763f1d-c5bc-4080-b499-d29debc0a7b8");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void roundTripsExactFiveFieldRequestAndCanonicalEvidence() {
    var input = input();
    var evidence = evidence(input);
    var wireRequest =
        AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(input, NAMESPACE);

    var decodedInput =
        AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromRequest(
            wireRequest, NAMESPACE);
    assertThat(decodedInput.originalPostAuthorizationTuple())
        .containsExactly(input.originalPostAuthorizationTuple());
    assertThat(decodedInput.gameSessionOwnerMutationId()).isEqualTo(MUTATION);
    assertThat(decodedInput.gameSessionOwnerAttemptId()).isEqualTo(ATTEMPT);
    assertThat(decodedInput.gameSessionOwnerFence()).isEqualTo(21L);
    assertThat(decodedInput.worldHoldIdentity().canonicalBytes())
        .containsExactly(input.worldHoldIdentity().canonicalBytes());

    var wireResponse =
        AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toResponse(
            input, NAMESPACE, evidence);
    var decodedEvidence =
        AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromResponse(
            input, NAMESPACE, wireResponse);
    assertThat(decodedEvidence.canonicalBytes()).containsExactly(evidence.canonicalBytes());
    assertThat(decodedEvidence.request().accountWorldParticipationId()).isEqualTo(PARTICIPATION);
    assertThat(decodedEvidence.request().accountWorldParticipationFence()).isEqualTo(22L);
  }

  @Test
  void rejectsUnknownFieldsAndSubstitutedRequestOrResponseBindings() {
    var input = input();
    var evidence = evidence(input);
    var wireRequest =
        AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(input, NAMESPACE);
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromRequest(
                    wireRequest.toBuilder().setUnknownFields(unknown()).build(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromRequest(
                    wireRequest.toBuilder().setTargetNamespace("other-runtime").build(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromRequest(
                    wireRequest.toBuilder().setGameSessionOwnerFence(0L).build(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");

    var response =
        AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toResponse(
            input, NAMESPACE, evidence);
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromResponse(
                    input, NAMESPACE, response.toBuilder().setUnknownFields(unknown()).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromResponse(
                    copyInput(uuid("5bc4c35d-eac4-4f9e-a8fc-7ac79aebee23")), NAMESPACE, response))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed the exact original acquisition binding");
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromResponse(
                    input,
                    NAMESPACE,
                    AcquireOriginalStartSessionAdmissionProtectionResponse.newBuilder().build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void inputOwnsItsTupleBytesAndRejectsNamespaceOrTenantSubstitution() {
    var tuple = originalTuple();
    byte[] supplied = tuple.canonicalBytes();
    var input =
        new AccountStartSessionAdmissionProtectionAcquisitionInput(
            supplied, MUTATION, ATTEMPT, 21L, hold(tuple));
    supplied[0] ^= 1;
    assertThat(input.originalPostAuthorizationTuple()).containsExactly(tuple.canonicalBytes());
    byte[] returned = input.originalPostAuthorizationTuple();
    returned[0] ^= 1;
    assertThat(input.originalPostAuthorizationTuple()).containsExactly(tuple.canonicalBytes());
    assertThatThrownBy(() -> input.requireTargetNamespace("other-runtime"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(
                    input, "other-runtime"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static AccountStartSessionAdmissionProtectionAcquisitionInput input() {
    var tuple = originalTuple();
    return new AccountStartSessionAdmissionProtectionAcquisitionInput(
        tuple.canonicalBytes(), MUTATION, ATTEMPT, 21L, hold(tuple));
  }

  private static AccountStartSessionAdmissionProtectionAcquisitionInput copyInput(UUID attempt) {
    var tuple = originalTuple();
    return new AccountStartSessionAdmissionProtectionAcquisitionInput(
        tuple.canonicalBytes(), MUTATION, attempt, 21L, hold(tuple));
  }

  static AccountStartSessionAdmissionProtectionEvidence evidence(
      AccountStartSessionAdmissionProtectionAcquisitionInput input) {
    var tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(input.originalPostAuthorizationTuple());
    var request =
        AccountStartSessionAdmissionProtectionRequest.create(
            input.originalPostAuthorizationTuple(),
            StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
            input.gameSessionOwnerMutationId(),
            input.gameSessionOwnerAttemptId(),
            input.gameSessionOwnerFence(),
            Instant.parse("2026-10-09T10:20:30.456Z"),
            PARTICIPATION,
            22L,
            input.worldHoldIdentity());
    byte[] capture =
        canonical(
            Map.of(
                "schema",
                AccountStartSessionAdmissionProtectionEvidence.SOURCE_CAPTURE_REFERENCE_SCHEMA,
                "controlPlaneRequestId",
                tuple.controlPlaneRequestId(),
                "capturedAt",
                "2026-10-09T10:11:12.123Z",
                "bundleReference",
                Map.of(
                    "bundleVersion",
                    StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                    "sourceVersion",
                    "17",
                    "sourceFence",
                    "23",
                    "linearization",
                    "18446744073709551615"),
                "snapshotSha256",
                "d".repeat(64)));
    return AccountStartSessionAdmissionProtectionEvidence.create(
        request,
        PROTECTION_ID,
        23L,
        capture,
        sha256(capture),
        List.of(
            new SourceEvidence(
                SourceKind.ACCOUNT,
                TENANT.toString(),
                "2",
                "17",
                null,
                null,
                "exact account source".getBytes(StandardCharsets.UTF_8))));
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple() {
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "acquisition-test",
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
                new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "canonical original StartSession admission attempt"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf"),
        19L,
        authorityBundle(preTuple),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion", "17",
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2026-10-09T00:00:00Z",
            "expiresAt", "2026-10-09T00:05:00Z");
    Map<String, Object> operation =
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
            "tenantAuthorityGeneration", Map.of(TENANT.toString(), 3L),
            "membershipAuthorityGeneration", Map.of(TENANT.toString(), 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> issuanceEvidence =
        Map.of(
            "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId", ACTOR.toString(),
            "controlUiTokenJti", "a681bba7-c215-4cf1-a35b-14348912cbdc",
            "role", "tenantAdmin",
            "accountGeneration", "2",
            "tenantGeneration", "3");
    return canonical(
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", TENANT.toString(), "targetNamespace", NAMESPACE),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", TENANT.toString()),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            operation,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(TENANT.toString(), 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            issuanceEvidence));
  }

  private static WorldCanonicalInitialAdmissionHold.HoldIdentity hold(
      StartSessionPostAuthorizationExecutionTuple tuple) {
    return new WorldCanonicalInitialAdmissionHold.HoldIdentity(
        new WorldCanonicalInitialAdmissionHold.Request(
            NAMESPACE,
            TENANT,
            "earth",
            uuid("3916f423-2870-426a-a8aa-5e3f97412613"),
            uuid("54e6094e-11bb-4f4c-93ee-a52f715b530b"),
            "SHARED",
            uuid("a55b2e10-9a24-4adb-adb8-6fc66fe3b8e9"),
            uuid("d7280ec0-5979-4b62-8418-e9f139415184"),
            3L,
            tuple.controlPlaneRequestId(),
            "c".repeat(64),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null),
        HOLD_ID,
        HOLD_FENCE);
  }

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }

  private static byte[] canonical(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
