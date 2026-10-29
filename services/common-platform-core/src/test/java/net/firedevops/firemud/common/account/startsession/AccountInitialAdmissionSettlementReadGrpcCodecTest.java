package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof.Outcome;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Complete controlled values only; no Account issuance or Game Session custody is established. */
class AccountInitialAdmissionSettlementReadGrpcCodecTest {
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID GAME_SESSION_MUTATION = uuid("f1a3ab1e-9147-4667-b6c4-6eb5119e8a31");
  private static final UUID GAME_SESSION_ATTEMPT = uuid("ec13cc04-ec15-4eb8-a018-c2c5e8da65f8");
  private static final UUID ACCOUNT_PARTICIPATION = uuid("47b3be7f-a32f-4e19-8916-8c3b8da07a82");
  private static final UUID WORLD_HOLD = uuid("0db7344a-1e67-4b95-905a-83dc9c472f0c");
  private static final UUID WORLD_HOLD_FENCE = uuid("52a14272-f9e4-4f67-97c9-62247b5fbcc1");
  private static final Instant ORIGINAL_LEASE_EXPIRY = Instant.parse("2026-10-09T10:20:30.456789Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void roundTripsExactCompleteProtectionAndBothTerminalOutcomes() {
    for (Outcome outcome : List.of(Outcome.COMMITTED, Outcome.ABORTED)) {
      var evidence = evidence(outcome, false);
      var input = evidence.input();
      var request =
          AccountInitialAdmissionSettlementReadGrpcCodec.toRequest(input, "world-runtime");
      var response = AccountInitialAdmissionSettlementReadGrpcCodec.toResponse(evidence);
      assertThat(response.getSchemaVersionList()).containsExactly(1);
      assertThat(
              AccountInitialAdmissionSettlementReadGrpcCodec.fromRequestWireBytes(
                      request.toByteArray(), "world-runtime")
                  .protection()
                  .canonicalBytes())
          .containsExactly(input.protection().canonicalBytes());
      var decoded =
          AccountInitialAdmissionSettlementReadGrpcCodec.fromResponseWireBytes(
              input, response.toByteArray());
      assertThat(decoded.protection().canonicalBytes())
          .containsExactly(evidence.protection().canonicalBytes());
      assertThat(
              GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
                  decoded.terminal()))
          .containsExactly(
              GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
                  evidence.terminal()));
      assertThat(decoded.readRequestId()).isEqualTo(input.readRequestId());
    }
  }

  @Test
  void rejectsNonterminalAndChangedCompleteHoldDespiteSameOriginalProtection() {
    var evidence = evidence(Outcome.COMMITTED, false);
    var hold = evidence.terminal().holdIdentity();
    var pending =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            hold, Outcome.PENDING, null, null, null, false, null);
    assertThatThrownBy(
            () ->
                new AccountInitialAdmissionSettlementReadEvidence(
                    evidence.readRequestId(), evidence.protection(), pending))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Nonterminal");
    var changed =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            hold.request(), UUID.randomUUID(), hold.holdFence());
    var differentHold =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            changed, Outcome.ABORTED, null, null, "sha256:" + "a".repeat(64), true, Instant.EPOCH);
    assertThatThrownBy(
            () ->
                new AccountInitialAdmissionSettlementReadEvidence(
                    evidence.readRequestId(), evidence.protection(), differentHold))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete protected hold");
    assertThatThrownBy(
            () ->
                new AccountInitialAdmissionSettlementReadInput(
                    new UUID(0L, 0L), evidence.protection()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesMissingDuplicateUnknownAndSubstitutedRequestFields() {
    var input = evidence(Outcome.COMMITTED, false).input();
    var request = AccountInitialAdmissionSettlementReadGrpcCodec.toRequest(input, "world-runtime");
    var invalid =
        List.of(
            request.toBuilder().clearSchemaVersion().build(),
            request.toBuilder().addSchemaVersion(1).build(),
            request.toBuilder().clearTargetNamespace().build(),
            request.toBuilder().addTargetNamespace("world-runtime").build(),
            request.toBuilder().setTargetNamespace(0, "other").build(),
            request.toBuilder().clearReadRequestId().build(),
            request.toBuilder().addReadRequestId(input.readRequestId().toString()).build(),
            request.toBuilder().setReadRequestId(0, "00000000-0000-0000-0000-000000000000").build(),
            request.toBuilder().clearAccountProtectionEvidence().build(),
            request.toBuilder()
                .addAccountProtectionEvidence(request.getAccountProtectionEvidence(0))
                .build(),
            request.toBuilder().setAccountProtectionEvidence(0, ByteString.EMPTY).build(),
            request.toBuilder().setUnknownFields(unknown()).build());
    for (var value : invalid) {
      assertThatThrownBy(
              () ->
                  AccountInitialAdmissionSettlementReadGrpcCodec.fromRequest(
                      value, "world-runtime"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () -> AccountInitialAdmissionSettlementReadGrpcCodec.fromRequest(request, "other"))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] duplicateWire = new byte[request.getSerializedSize() * 2];
    System.arraycopy(request.toByteArray(), 0, duplicateWire, 0, request.getSerializedSize());
    System.arraycopy(
        request.toByteArray(),
        0,
        duplicateWire,
        request.getSerializedSize(),
        request.getSerializedSize());
    assertThatThrownBy(
            () ->
                AccountInitialAdmissionSettlementReadGrpcCodec.fromRequestWireBytes(
                    duplicateWire, "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesMissingDuplicateUnknownAndSubstitutedResponseFields() {
    var evidence = evidence(Outcome.ABORTED, false);
    var response = AccountInitialAdmissionSettlementReadGrpcCodec.toResponse(evidence);
    var changed = evidence(Outcome.ABORTED, false);
    var invalid =
        List.of(
            response.toBuilder().clearSchemaVersion().build(),
            response.toBuilder().addSchemaVersion(1).build(),
            response.toBuilder().setSchemaVersion(0, 2).build(),
            response.toBuilder().clearReadRequestId().build(),
            response.toBuilder().addReadRequestId(evidence.readRequestId().toString()).build(),
            response.toBuilder().setReadRequestId(0, UUID.randomUUID().toString()).build(),
            response.toBuilder().clearAccountProtectionEvidence().build(),
            response.toBuilder()
                .addAccountProtectionEvidence(response.getAccountProtectionEvidence(0))
                .build(),
            response.toBuilder()
                .setAccountProtectionEvidence(
                    0, ByteString.copyFrom(changed.protection().canonicalBytes()))
                .build(),
            response.toBuilder().clearCanonicalGameSessionOwnerProof().build(),
            response.toBuilder()
                .addCanonicalGameSessionOwnerProof(response.getCanonicalGameSessionOwnerProof(0))
                .build(),
            response.toBuilder().setCanonicalGameSessionOwnerProof(0, ByteString.EMPTY).build(),
            response.toBuilder()
                .setCanonicalGameSessionOwnerProof(0, ByteString.copyFrom(new byte[66_049]))
                .build(),
            response.toBuilder().setUnknownFields(unknown()).build());
    for (var value : invalid) {
      assertThatThrownBy(
              () ->
                  AccountInitialAdmissionSettlementReadGrpcCodec.fromResponse(
                      evidence.input(), value))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void refusesNoncanonicalNestedEvidenceMalformedFramingAndPurposeOverflow() {
    var evidence = evidence(Outcome.COMMITTED, false);
    var request =
        AccountInitialAdmissionSettlementReadGrpcCodec.toRequest(evidence.input(), "world-runtime");
    String canonical = new String(evidence.protection().canonicalBytes(), StandardCharsets.UTF_8);
    for (String invalid :
        List.of(
            canonical + " ",
            canonical + "{}",
            canonical.replaceFirst("\\{", "{\"schema\":\"duplicate\","))) {
      var changed =
          request.toBuilder()
              .setAccountProtectionEvidence(0, ByteString.copyFromUtf8(invalid))
              .build();
      assertThatThrownBy(
              () ->
                  AccountInitialAdmissionSettlementReadGrpcCodec.fromRequest(
                      changed, "world-runtime"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var response = AccountInitialAdmissionSettlementReadGrpcCodec.toResponse(evidence);
    byte[] proof = response.getCanonicalGameSessionOwnerProof(0).toByteArray();
    byte[] trailing = Arrays.copyOf(proof, proof.length + 1);
    assertThatThrownBy(
            () ->
                AccountInitialAdmissionSettlementReadGrpcCodec.fromResponse(
                    evidence.input(),
                    response.toBuilder()
                        .setCanonicalGameSessionOwnerProof(0, ByteString.copyFrom(trailing))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountInitialAdmissionSettlementReadGrpcCodec.fromRequest(
                    request.toBuilder()
                        .setAccountProtectionEvidence(
                            0,
                            ByteString.copyFrom(
                                new byte
                                    [AccountStartSessionAdmissionProtectionEvidence
                                            .MAX_CANONICAL_BYTES
                                        + 1]))
                        .build(),
                    "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountInitialAdmissionSettlementReadGrpcCodec.fromResponseWireBytes(
                    evidence.input(),
                    new byte
                        [AccountInitialAdmissionSettlementReadGrpcCodec.MAX_RESPONSE_WIRE_BYTES
                            + 1]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static AccountInitialAdmissionSettlementReadEvidence evidence(Outcome outcome, boolean large) {
    var tuple = originalTuple("admission-settlement-controlled");
    byte[] reference = captureReference(tuple, "17", "23", "18446744073709551615");
    var sources = new ArrayList<SourceEvidence>();
    if (large) {
      for (int index = 0; index < 32; index++) {
        sources.add(
            new SourceEvidence(
                SourceKind.ISSUER,
                "https://issuer-" + index + ".example",
                null,
                "17",
                null,
                null,
                new byte[100 * 1024]));
      }
    } else {
      sources.add(source("controlled-source"));
    }
    var protection =
        AccountStartSessionAdmissionProtectionEvidence.create(
            request(tuple), UUID.randomUUID(), 3L, reference, sha256(reference), sources);
    var terminal =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            protection.request().worldAdmissionHoldIdentity(),
            outcome,
            outcome == Outcome.COMMITTED ? 1L : null,
            outcome == Outcome.COMMITTED ? 7L : null,
            "sha256:" + "a".repeat(64),
            outcome == Outcome.ABORTED,
            Instant.parse("2026-10-24T01:02:03Z"));
    return new AccountInitialAdmissionSettlementReadEvidence(
        UUID.randomUUID(), protection, terminal);
  }

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private static AccountStartSessionAdmissionProtectionRequest request(
      StartSessionPostAuthorizationExecutionTuple tuple) {
    return AccountStartSessionAdmissionProtectionRequest.create(
        tuple.canonicalBytes(),
        StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
        GAME_SESSION_MUTATION,
        GAME_SESSION_ATTEMPT,
        Long.MAX_VALUE,
        ORIGINAL_LEASE_EXPIRY,
        ACCOUNT_PARTICIPATION,
        Long.MAX_VALUE,
        hold(tuple));
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple(String requestId) {
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            requestId,
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, "world-runtime"),
                new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "canonical original StartSession admission attempt"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        "spiffe://firemud/ns/world-runtime/sa/logging-admin-service",
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
    String tenant = TENANT.toString();
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
            "tenantAuthorityGeneration", Map.of(tenant, 3L),
            "membershipAuthorityGeneration", Map.of(tenant, 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> issuanceEvidence =
        Map.of(
            "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId", ACTOR.toString(),
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
                "scope", Map.of("tenantId", tenant, "targetNamespace", "world-runtime"),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", tenant),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            operation,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(tenant, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            issuanceEvidence);
    return canonical(bundle);
  }

  private static WorldCanonicalInitialAdmissionHold.HoldIdentity hold(
      StartSessionPostAuthorizationExecutionTuple tuple) {
    return hold(tuple, TENANT, "world-runtime");
  }

  private static WorldCanonicalInitialAdmissionHold.HoldIdentity hold(
      StartSessionPostAuthorizationExecutionTuple tuple, UUID tenant, String namespace) {
    return new WorldCanonicalInitialAdmissionHold.HoldIdentity(
        new WorldCanonicalInitialAdmissionHold.Request(
            namespace,
            tenant,
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
        WORLD_HOLD,
        WORLD_HOLD_FENCE);
  }

  private static byte[] captureReference(
      StartSessionPostAuthorizationExecutionTuple tuple,
      String sourceVersion,
      String sourceFence,
      String linearization) {
    return canonical(
        Map.of(
            "schema",
                AccountStartSessionAdmissionProtectionEvidence.SOURCE_CAPTURE_REFERENCE_SCHEMA,
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "capturedAt", "2026-10-09T10:11:12.123Z",
            "bundleReference",
                Map.of(
                    "bundleVersion", StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                    "sourceVersion", sourceVersion,
                    "sourceFence", sourceFence,
                    "linearization", linearization),
            "snapshotSha256", "d".repeat(64)));
  }

  private static SourceEvidence source(String exactEvidence) {
    return new SourceEvidence(
        SourceKind.ACCOUNT,
        TENANT.toString(),
        "2",
        "17",
        null,
        null,
        exactEvidence.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] canonical(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
