package net.firedevops.firemud.worldmanagement.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import com.google.protobuf.UnknownFieldSet;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOrigin;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOwnerProofOutcome;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofResponse;
import org.junit.jupiter.api.Test;

class GameSessionCanonicalInitialAdmissionOwnerGrpcCodecTest {
  private static final String NAMESPACE = "world-test";
  private static final String REQUEST_ID = "canonical-admission-42";
  private static final String REQUEST_DIGEST = "a".repeat(64);
  private static final String PROOF_DIGEST = "sha256:" + "b".repeat(64);
  private static final Instant TERMINAL_AT = Instant.parse("2026-10-06T12:34:56.123456Z");

  @Test
  void requestCarriesCompleteIdentityAndPreservesTaggedOptionalPresence() {
    HoldIdentity noPrior = identity(InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    GetCanonicalInitialAdmissionOwnerProofRequest noPriorRequest =
        GameSessionCanonicalInitialAdmissionOwnerGrpcCodec.toRequest(noPrior);

    assertEquals(NAMESPACE, noPriorRequest.getTargetNamespace());
    assertEquals(REQUEST_ID, noPriorRequest.getInitialAdmissionRequestId());
    assertEquals(REQUEST_DIGEST, noPriorRequest.getInitialAdmissionRequestDigest());
    assertEquals("00000000-0000-0000-0000-000000000001", noPriorRequest.getCanonicalTenantId());
    assertEquals("test-world", noPriorRequest.getWorldSlug());
    assertEquals("00000000-0000-0000-0000-000000000002", noPriorRequest.getRealmId());
    assertEquals(
        "00000000-0000-0000-0000-000000000003", noPriorRequest.getPlayableStateNamespaceId());
    assertEquals("SHARED", noPriorRequest.getPlayableStateScope());
    assertEquals(
        "00000000-0000-0000-0000-000000000004", noPriorRequest.getCanonicalGameInstanceId());
    assertEquals("00000000-0000-0000-0000-000000000005", noPriorRequest.getCanonicalVersionId());
    assertEquals(8L, noPriorRequest.getActiveLifecycleEpoch());
    assertEquals(17L, noPriorRequest.getExpectedCatalogRevision());
    assertEquals(
        CanonicalInitialAdmissionOrigin.CANONICAL_INITIAL_ADMISSION_ORIGIN_NO_PRIOR_POINTER,
        noPriorRequest.getOrigin());
    assertFalse(noPriorRequest.hasExpectedPriorPointerVersion());
    assertEquals(noPrior.holdId().toString(), noPriorRequest.getHoldId());
    assertEquals(noPrior.holdFence().toString(), noPriorRequest.getHoldFence());
    assertEquals(noPrior.holdBindingDigest(), noPriorRequest.getHoldBindingDigest());

    HoldIdentity expectedClosed = identity(InitialAdmissionOrigin.EXPECT_CLOSED, 19L);
    GetCanonicalInitialAdmissionOwnerProofRequest expectedClosedRequest =
        GameSessionCanonicalInitialAdmissionOwnerGrpcCodec.toRequest(expectedClosed);
    assertEquals(
        CanonicalInitialAdmissionOrigin.CANONICAL_INITIAL_ADMISSION_ORIGIN_EXPECT_CLOSED,
        expectedClosedRequest.getOrigin());
    assertTrue(expectedClosedRequest.hasExpectedPriorPointerVersion());
    assertEquals(19L, expectedClosedRequest.getExpectedPriorPointerVersion());
  }

  @Test
  void decodesCommittedOwnerProofForBothExactPointerSuccessors() {
    for (var origin : InitialAdmissionOrigin.values()) {
      Long prior = origin == InitialAdmissionOrigin.NO_PRIOR_POINTER ? null : 19L;
      HoldIdentity identity = identity(origin, prior);
      long expectedPointer = origin == InitialAdmissionOrigin.NO_PRIOR_POINTER ? 1L : 20L;
      var proof =
          GameSessionCanonicalInitialAdmissionOwnerGrpcCodec.fromResponse(
              identity,
              GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
              terminalResponse(
                      identity,
                      CanonicalInitialAdmissionOwnerProofOutcome
                          .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_COMMITTED)
                  .setCommittedPointerVersion(expectedPointer)
                  .setAuditEventId(31L)
                  .setProofDigest(PROOF_DIGEST)
                  .build());

      assertEquals(identity, proof.holdIdentity());
      assertEquals(
          GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED, proof.outcome());
      assertEquals(expectedPointer, proof.committedPointerVersion());
      assertEquals(31L, proof.auditEventId());
      assertEquals(PROOF_DIGEST, proof.proofDigest());
      assertFalse(proof.positiveDurableAbort());
      assertEquals(TERMINAL_AT, proof.terminalAt());
    }
  }

  @Test
  void decodesPositiveDurableAbortWithoutPointerOrAuditEvidence() {
    HoldIdentity identity = identity(InitialAdmissionOrigin.EXPECT_CLOSED, 19L);
    var proof =
        GameSessionCanonicalInitialAdmissionOwnerGrpcCodec.fromResponse(
            identity,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED,
            terminalResponse(
                    identity,
                    CanonicalInitialAdmissionOwnerProofOutcome
                        .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_ABORTED)
                .setProofDigest(PROOF_DIGEST)
                .setPositiveDurableAbort(true)
                .build());

    assertEquals(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED, proof.outcome());
    assertNull(proof.committedPointerVersion());
    assertNull(proof.auditEventId());
    assertTrue(proof.positiveDurableAbort());
  }

  @Test
  void decodesExactPendingObservationWithoutTerminalFields() {
    HoldIdentity identity = identity(InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    var response =
        terminalResponse(
                identity,
                CanonicalInitialAdmissionOwnerProofOutcome
                    .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_PENDING)
            .clearTerminalAt()
            .build();

    var proof = GameSessionCanonicalInitialAdmissionOwnerGrpcCodec.fromResponse(identity, response);

    assertEquals(identity, proof.holdIdentity());
    assertEquals(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING, proof.outcome());
    assertNull(proof.committedPointerVersion());
    assertNull(proof.auditEventId());
    assertNull(proof.proofDigest());
    assertNull(proof.terminalAt());
    assertFalse(proof.positiveDurableAbort());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GameSessionCanonicalInitialAdmissionOwnerGrpcCodec.fromResponse(
                identity,
                GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
                response));
  }

  @Test
  void rejectsTerminalFieldsOnPendingObservation() {
    HoldIdentity identity = identity(InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    var pending =
        terminalResponse(
            identity,
            CanonicalInitialAdmissionOwnerProofOutcome
                .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_PENDING);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            GameSessionCanonicalInitialAdmissionOwnerGrpcCodec.fromResponse(
                identity, pending.build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GameSessionCanonicalInitialAdmissionOwnerGrpcCodec.fromResponse(
                identity, pending.clearTerminalAt().setProofDigest(PROOF_DIGEST).build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GameSessionCanonicalInitialAdmissionOwnerGrpcCodec.fromResponse(
                identity, pending.clearTerminalAt().setAuditEventId(31L).build()));
  }

  @Test
  void rejectsChangedOrMissingIdentityEchoAndWrongOptionalPresence() {
    HoldIdentity identity = identity(InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    var outcome =
        CanonicalInitialAdmissionOwnerProofOutcome
            .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_COMMITTED;

    assertInvalid(
        identity, terminalResponse(identity, outcome).setWorldSlug("substituted-world").build());
    assertInvalid(identity, terminalResponse(identity, outcome).clearRealmId().build());
    assertInvalid(
        identity, terminalResponse(identity, outcome).setExpectedPriorPointerVersion(1L).build());

    HoldIdentity expectedClosed = identity(InitialAdmissionOrigin.EXPECT_CLOSED, 19L);
    assertInvalid(
        expectedClosed,
        terminalResponse(expectedClosed, outcome).clearExpectedPriorPointerVersion().build());
  }

  @Test
  void rejectsPendingUnsupportedAndSubstitutedTerminalOutcomes() {
    HoldIdentity identity = identity(InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    assertInvalid(
        identity,
        terminalResponse(
                identity,
                CanonicalInitialAdmissionOwnerProofOutcome
                    .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_PENDING)
            .build());
    assertInvalid(
        identity,
        terminalResponse(
                identity,
                CanonicalInitialAdmissionOwnerProofOutcome
                    .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_PENDING)
            .setOutcomeValue(99)
            .build());
    assertInvalid(
        identity,
        terminalResponse(
                identity,
                CanonicalInitialAdmissionOwnerProofOutcome
                    .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_ABORTED)
            .setProofDigest(PROOF_DIGEST)
            .setPositiveDurableAbort(true)
            .build());
  }

  @Test
  void rejectsInvalidCommitAndAbortSemantics() {
    HoldIdentity identity = identity(InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    var committed =
        CanonicalInitialAdmissionOwnerProofOutcome
            .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_COMMITTED;
    var aborted =
        CanonicalInitialAdmissionOwnerProofOutcome
            .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_ABORTED;

    assertInvalid(
        identity,
        terminalResponse(identity, committed)
            .setAuditEventId(31L)
            .setProofDigest(PROOF_DIGEST)
            .build());
    assertInvalid(
        identity,
        terminalResponse(identity, committed)
            .setCommittedPointerVersion(2L)
            .setAuditEventId(31L)
            .setProofDigest(PROOF_DIGEST)
            .build());
    assertInvalid(
        identity,
        terminalResponse(identity, committed)
            .setCommittedPointerVersion(1L)
            .setAuditEventId(31L)
            .setProofDigest(PROOF_DIGEST)
            .setPositiveDurableAbort(true)
            .build());
    assertInvalid(
        identity,
        terminalResponse(identity, aborted)
            .setCommittedPointerVersion(1L)
            .setProofDigest(PROOF_DIGEST)
            .setPositiveDurableAbort(true)
            .build(),
        GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);
    assertInvalid(
        identity,
        terminalResponse(identity, aborted).setProofDigest(PROOF_DIGEST).build(),
        GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);

    HoldIdentity overflow = identity(InitialAdmissionOrigin.EXPECT_CLOSED, Long.MAX_VALUE);
    assertInvalid(
        overflow,
        terminalResponse(overflow, committed)
            .setCommittedPointerVersion(Long.MIN_VALUE)
            .setAuditEventId(31L)
            .setProofDigest(PROOF_DIGEST)
            .build());
  }

  @Test
  void rejectsUnknownFieldsAndInvalidTimestampRangeOrPrecision() {
    HoldIdentity identity = identity(InitialAdmissionOrigin.NO_PRIOR_POINTER, null);
    var committed =
        CanonicalInitialAdmissionOwnerProofOutcome
            .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_COMMITTED;
    var valid =
        terminalResponse(identity, committed)
            .setCommittedPointerVersion(1L)
            .setAuditEventId(31L)
            .setProofDigest(PROOF_DIGEST);

    assertInvalid(identity, valid.clone().setUnknownFields(unknownField()).build());
    assertInvalid(
        identity,
        valid
            .clone()
            .setTerminalAt(validTimestamp().toBuilder().setUnknownFields(unknownField()))
            .build());
    assertInvalid(
        identity,
        valid.clone().setTerminalAt(Timestamp.newBuilder().setSeconds(253_402_300_800L)).build());
    assertInvalid(
        identity,
        valid
            .clone()
            .setTerminalAt(
                Timestamp.newBuilder().setSeconds(TERMINAL_AT.getEpochSecond()).setNanos(-1))
            .build());
    assertInvalid(
        identity,
        valid
            .clone()
            .setTerminalAt(
                Timestamp.newBuilder()
                    .setSeconds(TERMINAL_AT.getEpochSecond())
                    .setNanos(TERMINAL_AT.getNano() + 1))
            .build());
  }

  private static void assertInvalid(
      HoldIdentity identity, GetCanonicalInitialAdmissionOwnerProofResponse response) {
    assertInvalid(
        identity, response, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
  }

  private static void assertInvalid(
      HoldIdentity identity,
      GetCanonicalInitialAdmissionOwnerProofResponse response,
      GameSessionCanonicalInitialAdmissionOwnerProof.Outcome expectedOutcome) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GameSessionCanonicalInitialAdmissionOwnerGrpcCodec.fromResponse(
                identity, expectedOutcome, response));
  }

  private static HoldIdentity identity(InitialAdmissionOrigin origin, Long priorPointerVersion) {
    Request request =
        new Request(
            NAMESPACE,
            uuid(1),
            "test-world",
            uuid(2),
            uuid(3),
            "SHARED",
            uuid(4),
            uuid(5),
            8L,
            REQUEST_ID,
            REQUEST_DIGEST,
            origin,
            17L,
            priorPointerVersion);
    return new HoldIdentity(request, uuid(6), uuid(7));
  }

  private static GetCanonicalInitialAdmissionOwnerProofResponse.Builder terminalResponse(
      HoldIdentity identity, CanonicalInitialAdmissionOwnerProofOutcome outcome) {
    Request request = identity.request();
    var builder =
        GetCanonicalInitialAdmissionOwnerProofResponse.newBuilder()
            .setTargetNamespace(request.targetNamespace())
            .setInitialAdmissionRequestId(request.initialAdmissionRequestId())
            .setInitialAdmissionRequestDigest(request.initialAdmissionRequestDigest())
            .setCanonicalTenantId(request.canonicalTenantId().toString())
            .setWorldSlug(request.worldSlug())
            .setRealmId(request.realmId().toString())
            .setPlayableStateNamespaceId(request.playableStateNamespaceId().toString())
            .setPlayableStateScope(request.playableStateScope())
            .setCanonicalGameInstanceId(request.canonicalGameInstanceId().toString())
            .setCanonicalVersionId(request.canonicalVersionId().toString())
            .setActiveLifecycleEpoch(request.activeLifecycleEpoch())
            .setExpectedCatalogRevision(request.expectedCatalogRevision())
            .setOrigin(toWireOrigin(request.initialAdmissionOrigin()))
            .setHoldId(identity.holdId().toString())
            .setHoldFence(identity.holdFence().toString())
            .setHoldBindingDigest(identity.holdBindingDigest())
            .setOutcome(outcome)
            .setTerminalAt(validTimestamp());
    if (request.expectedPriorPointerVersion() != null) {
      builder.setExpectedPriorPointerVersion(request.expectedPriorPointerVersion());
    }
    return builder;
  }

  private static CanonicalInitialAdmissionOrigin toWireOrigin(InitialAdmissionOrigin origin) {
    return switch (origin) {
      case NO_PRIOR_POINTER ->
          CanonicalInitialAdmissionOrigin.CANONICAL_INITIAL_ADMISSION_ORIGIN_NO_PRIOR_POINTER;
      case EXPECT_CLOSED ->
          CanonicalInitialAdmissionOrigin.CANONICAL_INITIAL_ADMISSION_ORIGIN_EXPECT_CLOSED;
    };
  }

  private static Timestamp validTimestamp() {
    return Timestamp.newBuilder()
        .setSeconds(TERMINAL_AT.getEpochSecond())
        .setNanos(TERMINAL_AT.getNano())
        .build();
  }

  private static UnknownFieldSet unknownField() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private static UUID uuid(long suffix) {
    return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
  }
}
