package net.firedevops.firemud.common.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.UnknownFieldSet;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import org.junit.jupiter.api.Test;

class CanonicalGameplayRosterRetirementEvidenceTest {
  private static final UUID ACCOUNT = uuid(1);
  private static final UUID SESSION = uuid(2);
  private static final UUID OPERATION = uuid(3);
  private static final UUID OBSERVATION = uuid(4);
  private static final UUID ADMISSION = uuid(5);
  private static final UUID SNAPSHOT = uuid(6);
  private static final UUID TARGET_FENCE = uuid(7);

  @Test
  void acceptsExactBoundAndUnboundRetirementAcknowledgements() {
    var original = original(OPERATION, target());
    var unbound = acknowledgement(original, retirement(original, null), "", "");
    assertEquals(
        unbound, CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(unbound));

    var roster = roster(original, SNAPSHOT, "a".repeat(64));
    var boundProof = retirement(original, roster);
    var bound = acknowledgement(original, boundProof, SNAPSHOT.toString(), "a".repeat(64));
    assertEquals(bound, CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(bound));
  }

  @Test
  void acceptsDifferentAnchorOperationOnlyForTheExactSharedSnapshotAndFence() {
    var original = original(OPERATION, target());
    var anchorRequest = original(uuid(8), target());
    var anchorProof = retirement(anchorRequest, roster(anchorRequest, SNAPSHOT, "b".repeat(64)));
    var bound = acknowledgement(original, anchorProof, SNAPSHOT.toString(), "b".repeat(64));

    assertEquals(bound, CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(bound));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(
                acknowledgement(original, anchorProof, SNAPSHOT.toString(), "c".repeat(64))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(
                acknowledgement(original, anchorProof, "", "")));
  }

  @Test
  void rejectsChangedAccountTargetPolicyAndSnapshotPair() {
    var original = original(OPERATION, target());
    var exactProof = retirement(original, roster(original, SNAPSHOT, "d".repeat(64)));

    var otherAccount = original.toBuilder().setCanonicalAccountUuid(uuid(9).toString()).build();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(
                acknowledgement(otherAccount, exactProof, SNAPSHOT.toString(), "d".repeat(64))));

    var otherTarget = original(uuid(10), target(9));
    var otherTargetProof = retirement(otherTarget, roster(otherTarget, SNAPSHOT, "e".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(
                acknowledgement(original, otherTargetProof, SNAPSHOT.toString(), "e".repeat(64))));

    var policyTarget =
        target().toBuilder()
            .setEntryPolicy(
                CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED)
            .build();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(
                acknowledgement(
                    original(uuid(11), policyTarget),
                    exactProof,
                    SNAPSHOT.toString(),
                    "d".repeat(64))));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(
                acknowledgement(original, exactProof, SNAPSHOT.toString(), "")));
  }

  @Test
  void rejectsNilNonceUnknownNestedEvidenceAndOversizedValues() {
    var original = original(OPERATION, target());
    var proof = retirement(original, roster(original, SNAPSHOT, "f".repeat(64)));
    var valid = acknowledgement(original, proof, SNAPSHOT.toString(), "f".repeat(64));
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(
                valid.toBuilder().setAdmissionUuid(new UUID(0L, 0L).toString()).build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(
                valid.toBuilder()
                    .setOriginalSelection(original.toBuilder().setUnknownFields(unknown))
                    .build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(
                valid.toBuilder()
                    .setObservedRetirement(proof.toBuilder().setUnknownFields(unknown))
                    .build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(
                valid.toBuilder().setSnapshotDigest("a".repeat(128 * 1024)).build()));
  }

  @Test
  void acknowledgementAndConfirmationBindExactRequestHashesAndHorizons() throws Exception {
    var original = original(OPERATION, target());
    var request = acknowledgement(original, retirement(original, null), "", "");
    var response = CanonicalGameplayRosterRetirementEvidence.response(request, uuid(12), 20_000L);
    assertEquals(
        HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(request.toByteArray())),
        response.getRequestDigest());
    assertEquals(64, response.getRequestDigest().length());
    assertTrue(response.getMinimumRetentionMillis() >= request.getMinimumRetentionMillis());
    assertEquals(
        response,
        CanonicalGameplayRosterRetirementEvidence.requireAcknowledgementResponse(
            request, response));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalGameplayRosterRetirementEvidence.requireAcknowledgementResponse(
                request, response.toBuilder().setRequestDigest("0".repeat(64)).build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalGameplayRosterRetirementEvidence.requireAcknowledgementResponse(
                request, response.toBuilder().setRequestDigest("a".repeat(4096)).build()));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalGameplayRosterRetirementEvidence.response(request, uuid(12), 1L));

    var confirmation = CanonicalGameplayRosterRetirementEvidence.confirmation(request, response);
    var confirmationResponse =
        CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmation);
    assertEquals(
        confirmationResponse,
        CanonicalGameplayRosterRetirementEvidence.requireConfirmationResponse(
            confirmation, confirmationResponse));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalGameplayRosterRetirementEvidence.requireConfirmationResponse(
                confirmation,
                confirmationResponse.toBuilder().setRequestDigest("0".repeat(64)).build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalGameplayRosterRetirementEvidence.requireConfirmation(
                confirmation.toBuilder()
                    .setAcknowledgement(response.toBuilder().setRequestDigest("0".repeat(64)))
                    .build()));
  }

  private static CanonicalGameplayRosterRetirementAcknowledgementRequest acknowledgement(
      GetCanonicalGameplayRosterReferenceRequest original,
      GetCanonicalGameplayRosterReferenceResponse observed,
      String snapshotUuid,
      String snapshotDigest) {
    return CanonicalGameplayRosterRetirementAcknowledgementRequest.newBuilder()
        .setSchemaVersion(1)
        .setOriginalSelection(original)
        .setObservedRetirement(observed)
        .setObservationUuid(OBSERVATION.toString())
        .setAdmissionUuid(ADMISSION.toString())
        .setSnapshotUuid(snapshotUuid)
        .setSnapshotDigest(snapshotDigest)
        .setMinimumRetentionMillis(10_000L)
        .build();
  }

  private static GetCanonicalGameplayRosterReferenceResponse retirement(
      GetCanonicalGameplayRosterReferenceRequest original, CanonicalGameplayRosterResponse roster) {
    var response =
        CanonicalGameplayRosterReferenceEvidence.response(original, 17L, TARGET_FENCE, roster)
            .toBuilder()
            .setState(
                CanonicalGameplayRosterReferenceState.CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED)
            .setTerminalOutcome("DENIED")
            .setRetirementUuid(uuid(18).toString())
            .setSupersedingPointerDigest("9".repeat(64))
            .setSupersedingPointerVersion(12L)
            .setSupersedingCatalogRevision(7L)
            .setSupersedingWorldEpoch(13L)
            .build();
    return CanonicalGameplayRosterReferenceEvidence.sealRetirement(response);
  }

  private static CanonicalGameplayRosterResponse roster(
      GetCanonicalGameplayRosterReferenceRequest request, UUID snapshot, String snapshotDigest) {
    return CanonicalGameplayRosterResponse.newBuilder()
        .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
        .setTarget(CanonicalGameplayRosterReferenceEvidence.target(request))
        .setSnapshotUuid(snapshot.toString())
        .setSnapshotDigest(snapshotDigest)
        .build();
  }

  private static GetCanonicalGameplayRosterReferenceRequest original(
      UUID operation, CanonicalGameplayRosterTarget target) {
    return CanonicalGameplayRosterReferenceEvidence.request(
        operation, ACCOUNT, SESSION, "gameplay", target);
  }

  private static CanonicalGameplayRosterTarget target() {
    return target(0);
  }

  private static CanonicalGameplayRosterTarget target(int variant) {
    return CanonicalGameplayRosterTarget.newBuilder()
        .setTenantUuid(uuid(20).toString())
        .setRealmUuid(uuid(21).toString())
        .setWorldSlug("earth")
        .setRealmSlug("main")
        .setGameInstanceUuid(uuid(22).toString())
        .setCatalogRevision(7L)
        .setPointerVersion(variant == 0 ? 11L : 10L)
        .setActiveWorldEpoch(13L)
        .setCanonicalVersionUuid(uuid(23).toString())
        .setPublishedPolicyDigest("a".repeat(64))
        .setPublishedReleaseBundleRef("release:exact")
        .setAdmissionPointerSnapshotDigest("b".repeat(64))
        .setPublishedOwnerProofDigest("c".repeat(64))
        .setPlayableStateNamespaceUuid(uuid(24).toString())
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .setEntryPolicy(
            CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
        .build();
  }

  private static UUID uuid(long value) {
    return new UUID(0L, value);
  }
}
