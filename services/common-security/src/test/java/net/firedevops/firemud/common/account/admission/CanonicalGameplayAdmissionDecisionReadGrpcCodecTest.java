package net.firedevops.firemud.common.account.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OriginKind;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionCandidate;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionResponse;
import org.junit.jupiter.api.Test;

/** Shape and tuple codec proof only; this fixture grants no admission or Account authority. */
class CanonicalGameplayAdmissionDecisionReadGrpcCodecTest {
  private static final String HIGH = "9007199254740993";
  private static final UUID DECISION_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final UUID OTHER = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID VERSION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID HOLD_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID HOLD_FENCE = UUID.fromString("66666666-6666-4666-8666-666666666666");

  @Test
  void roundTripsCompleteProvisionalReadAndPreservesOriginalLeaseBytes() {
    var lease = lease();
    var request = request(lease);
    var response = response(request, lease, target(lease, "PRESEEDED_ONLY"));

    var evidence = CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromResponse(request, response);

    assertThat(response.getOriginalLeaseEvidenceUtf8(0).toByteArray())
        .containsExactly(request.expectedLeaseEvidenceUtf8());
    assertThat(evidence.originalLeaseEvidenceJson()).isEqualTo(lease.canonicalJson());
    assertThat(evidence.status())
        .isEqualTo(CanonicalGameplayAdmissionDecisionReadGrpcCodec.Status.PROVISIONAL);
    assertThat(evidence.bindingGeneration()).isEqualTo(new BigInteger(HIGH));
    assertThat(evidence.candidate().regionEpoch()).isEqualTo(new BigInteger(HIGH));
    assertThat(evidence.candidate().issuerIndexPartitionCount()).isEqualTo(new BigInteger(HIGH));
    assertThat(evidence.candidate().issuerIndexPartitionCapacity()).isEqualTo(new BigInteger(HIGH));
    assertThat(evidence.targetEvidence().catalogRevision()).isEqualTo(Long.parseLong(HIGH));
    assertThat(evidence.targetEvidence().pointerVersion()).isEqualTo(Long.parseLong(HIGH));
    assertThat(evidence.targetEvidence().activeWorldEpoch()).isEqualTo(Long.parseLong(HIGH));
  }

  @Test
  void rejectsChangedDecisionLeaseCandidateAndOwnerProofTuple() {
    var lease = lease();
    var request = request(lease);
    var response = response(request, lease, target(lease, "PRESEEDED_ONLY"));

    assertRejected(
        request, response.toBuilder().setBindingDecisionUuid(0, OTHER.toString()).build());

    var changedLeaseCarrier = AccountGameplayAdmissionLeaseEvidenceTest.fixture();
    mutableObject(changedLeaseCarrier, "bindingScope").put("pointerVersion", "9007199254740994");
    var changedLease = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changedLeaseCarrier);
    assertRejected(
        request,
        response.toBuilder()
            .setOriginalLeaseEvidenceUtf8(
                0,
                ByteString.copyFrom(changedLease.canonicalJson().getBytes(StandardCharsets.UTF_8)))
            .build());

    assertRejected(
        request,
        response.toBuilder()
            .setCandidate(
                0, response.getCandidate(0).toBuilder().setAccountUuid(0, OTHER.toString()))
            .build());

    var legacyIssuerCandidate =
        response.getCandidate(0).toBuilder().setIssuerId(0, "77777777-7777-4777-8777-777777777777");
    assertThat(legacyIssuerCandidate.getIssuerAuthGeneration(0))
        .isEqualTo(response.getCandidate(0).getIssuerAuthGeneration(0));
    assertRejected(
        request, response.toBuilder().setCandidate(0, legacyIssuerCandidate.build()).build());

    assertRejected(
        request,
        response.toBuilder()
            .setTargetEvidence(
                0,
                response.getTargetEvidence(0).toBuilder()
                    .setHoldBindingDigest(0, "sha256:" + "f".repeat(64))
                    .build())
            .build());
  }

  @Test
  void keepsIssuerIdAtStringTagElevenAndEmitsOnlyTheAccountIssuer() {
    var wire = CanonicalGameplayAdmissionDecisionReadGrpcCodec.toCandidate(candidate(lease()));
    var issuerField =
        CanonicalGameplayAdmissionCandidate.getDescriptor().findFieldByName("issuer_id");

    assertThat(issuerField.getNumber()).isEqualTo(11);
    assertThat(issuerField.getType())
        .isEqualTo(com.google.protobuf.Descriptors.FieldDescriptor.Type.STRING);
    assertThat(wire.getIssuerIdList()).containsExactly(GameSessionAccountDelegationProfile.ISSUER);
  }

  @Test
  void rejectsMissingDuplicateAndUnknownWireFieldsAtEachClosedLevel() {
    var lease = lease();
    var wireRequest = CanonicalGameplayAdmissionDecisionReadGrpcCodec.toRequest(DECISION_ID, lease);
    var request = CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(wireRequest);
    var response = response(request, lease, target(lease, "PRESEEDED_ONLY"));
    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(
                    wireRequest.toBuilder().clearExpectedLeaseEvidenceUtf8().build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(
                    wireRequest.toBuilder().addSchemaVersion(1).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(
                    wireRequest.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);

    assertRejected(request, response.toBuilder().clearBindingGeneration().build());
    assertRejected(request, response.toBuilder().addBindingGeneration(HIGH).build());
    assertRejected(request, response.toBuilder().setUnknownFields(unknown).build());
    assertRejected(
        request,
        response.toBuilder()
            .setCandidate(0, response.getCandidate(0).toBuilder().clearAccountUuid().build())
            .build());
    assertRejected(
        request,
        response.toBuilder()
            .setCandidate(
                0, response.getCandidate(0).toBuilder().addAccountUuid(OTHER.toString()).build())
            .build());
    assertRejected(
        request,
        response.toBuilder()
            .setCandidate(0, response.getCandidate(0).toBuilder().setUnknownFields(unknown).build())
            .build());
    assertRejected(
        request,
        response.toBuilder()
            .setTargetEvidence(
                0, response.getTargetEvidence(0).toBuilder().setUnknownFields(unknown).build())
            .build());
    assertRejected(
        request,
        response.toBuilder()
            .setTargetEvidence(
                0,
                response.getTargetEvidence(0).toBuilder()
                    .addExpectedPriorPointerVersion("1")
                    .addExpectedPriorPointerVersion("2")
                    .build())
            .build());
  }

  @Test
  void enforcesFiniteRequestAndCompleteResponseBounds() {
    var lease = lease();
    var wireRequest = CanonicalGameplayAdmissionDecisionReadGrpcCodec.toRequest(DECISION_ID, lease);
    var oversizedRequest =
        wireRequest.toBuilder()
            .clearExpectedLeaseEvidenceUtf8()
            .addExpectedLeaseEvidenceUtf8(
                ByteString.copyFrom(
                    new byte
                        [CanonicalGameplayAdmissionDecisionReadGrpcCodec.MAX_REQUEST_WIRE_BYTES]))
            .build();
    assertThatThrownBy(
            () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(oversizedRequest))
        .isInstanceOf(IllegalArgumentException.class);

    var request = CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(wireRequest);
    assertThatThrownBy(() -> response(request, lease, target(lease, "x".repeat(270_000))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertRejected(
      CanonicalGameplayAdmissionDecisionReadGrpcCodec.RequestEvidence request,
      GetCanonicalGameplayAdmissionDecisionResponse response) {
    assertThatThrownBy(
            () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromResponse(request, response))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static GetCanonicalGameplayAdmissionDecisionResponse response(
      CanonicalGameplayAdmissionDecisionReadGrpcCodec.RequestEvidence request,
      AccountGameplayAdmissionLeaseEvidence lease,
      Target target) {
    return CanonicalGameplayAdmissionDecisionReadGrpcCodec.toResponse(
        request, DECISION_ID, lease, new BigInteger(HIGH), null, null, candidate(lease), target);
  }

  private static CanonicalGameplayAdmissionDecisionReadGrpcCodec.RequestEvidence request(
      AccountGameplayAdmissionLeaseEvidence lease) {
    return CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.toRequest(DECISION_ID, lease));
  }

  private static AccountGameplayAdmissionLeaseEvidence lease() {
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(
        AccountGameplayAdmissionLeaseEvidenceTest.fixture());
  }

  private static Candidate candidate(AccountGameplayAdmissionLeaseEvidence lease) {
    Map<?, ?> scope = object(lease.carrier(), "bindingScope");
    Map<?, ?> authority = object(lease.carrier(), "authorityTuple");
    return new Candidate(
        uuid(scope, "accountId"),
        uuid(scope, "tenantId"),
        uuid(scope, "playableStateNamespaceId"),
        uuid(scope, "characterId"),
        (String) scope.get("playableStateScope"),
        uuid(scope, "gameInstanceId"),
        71,
        uuid(scope, "sessionId"),
        uuid(scope, "regionId"),
        new BigInteger((String) scope.get("regionEpoch")),
        GameSessionAccountDelegationProfile.ISSUER,
        new BigInteger((String) authority.get("issuerAuthGeneration")),
        new BigInteger(HIGH),
        new BigInteger(HIGH),
        new BigInteger(HIGH));
  }

  private static Target target(AccountGameplayAdmissionLeaseEvidence lease, String policy) {
    Map<?, ?> carrier = lease.carrier();
    Map<?, ?> scope = object(carrier, "bindingScope");
    UUID tenantId = uuid(scope, "tenantId");
    UUID realmId = uuid(scope, "realmId");
    UUID playableStateNamespaceId = uuid(scope, "playableStateNamespaceId");
    UUID gameInstanceId = uuid(scope, "gameInstanceId");
    long catalogRevision = Long.parseLong((String) scope.get("catalogRevision"));
    long pointerVersion = Long.parseLong((String) scope.get("pointerVersion"));
    long activeWorldEpoch = Long.parseLong(HIGH);
    String requestId = (String) carrier.get("requestId");
    String requestDigest = "c".repeat(64);
    WorldCanonicalInitialAdmissionHold.Request ownerRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            (String) carrier.get("targetNamespace"),
            tenantId,
            (String) scope.get("worldSlug"),
            realmId,
            playableStateNamespaceId,
            (String) scope.get("playableStateScope"),
            gameInstanceId,
            VERSION_ID,
            activeWorldEpoch,
            requestId,
            requestDigest,
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            catalogRevision,
            null);
    return new Target(
        (String) carrier.get("targetNamespace"),
        "tenant",
        tenantId,
        (String) scope.get("worldSlug"),
        "World",
        realmId,
        (String) scope.get("realmSlug"),
        "Realm",
        11,
        71,
        playableStateNamespaceId,
        (String) scope.get("playableStateScope"),
        gameInstanceId,
        VERSION_ID,
        99,
        catalogRevision,
        pointerVersion,
        "d".repeat(64),
        activeWorldEpoch,
        requestId,
        requestDigest,
        OriginKind.NO_PRIOR_POINTER,
        null,
        HOLD_ID,
        HOLD_FENCE,
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(ownerRequest, HOLD_ID, HOLD_FENCE)
            .holdBindingDigest(),
        101,
        "sha256:" + "a".repeat(64),
        OwnerProofOutcome.COMMITTED,
        false,
        Instant.parse("2026-10-25T00:00:00Z"),
        policy);
  }

  private static Map<?, ?> object(Map<?, ?> source, String field) {
    return (Map<?, ?>) source.get(field);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mutableObject(Map<String, Object> source, String field) {
    return (Map<String, Object>) source.get(field);
  }

  private static UUID uuid(Map<?, ?> source, String field) {
    return UUID.fromString((String) source.get(field));
  }
}
