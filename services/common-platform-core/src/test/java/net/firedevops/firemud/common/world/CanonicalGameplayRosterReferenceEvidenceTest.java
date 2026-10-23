package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import org.junit.jupiter.api.Test;

class CanonicalGameplayRosterReferenceEvidenceTest {
  @Test
  void bindsReservedAndRecordedEvidenceToTheExactOriginalRequest() {
    var request = request();
    var reserved =
        CanonicalGameplayRosterReferenceEvidence.response(
            request, 1L, java.util.UUID.fromString("12121212-1212-4212-8212-121212121212"), null);
    assertThat(reserved.getCanonicalRosterReceipt()).isEmpty();
    var receipt =
        CanonicalGameplayRosterResponse.newBuilder()
            .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
            .setSnapshotUuid(UUID.randomUUID().toString())
            .setSnapshotDigest("d".repeat(64))
            .setTarget(CanonicalGameplayRosterReferenceEvidence.target(request))
            .build();
    var recorded =
        CanonicalGameplayRosterReferenceEvidence.response(
            request,
            1L,
            java.util.UUID.fromString("12121212-1212-4212-8212-121212121212"),
            receipt);
    assertThat(CanonicalGameplayRosterReferenceEvidence.receipt(recorded)).isEqualTo(receipt);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterReferenceEvidence.requireResponse(
                    request.toBuilder().setSessionUuid(UUID.randomUUID().toString()).build(),
                    recorded))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterReferenceEvidence.requireResponse(
                    request.toBuilder().setTargetNamespace("foreign").build(), recorded))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unknownStatesFieldsAndChangedOriginalBindingsCannotBecomeReleaseEvidence() {
    var request = request();
    var reserved =
        CanonicalGameplayRosterReferenceEvidence.response(
            request, 1L, java.util.UUID.fromString("12121212-1212-4212-8212-121212121212"), null);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterReferenceEvidence.requireResponse(
                    request, reserved.toBuilder().setStateValue(77).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterReferenceEvidence.requireRequest(
                    request.toBuilder().setOperationUuid(new UUID(0L, 0L).toString()).build()))
        .isInstanceOf(IllegalArgumentException.class);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterReferenceEvidence.requireRequest(
                    request.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);
    var target =
        CanonicalGameplayRosterReferenceEvidence.target(request).toBuilder()
            .setUnknownFields(unknown)
            .build();
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterReferenceEvidence.requireRequest(
                    request.toBuilder().setCanonicalTarget(target.toByteString()).build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void positiveRetirementRequiresTheCompleteOriginalBindingAndStrictlyNewerOwnerPointer() {
    var request = request();
    var target = CanonicalGameplayRosterReferenceEvidence.target(request);
    var receipt =
        CanonicalGameplayRosterResponse.newBuilder()
            .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
            .setTarget(target)
            .setSnapshotUuid(UUID.randomUUID().toString())
            .setSnapshotDigest("d".repeat(64))
            .build();
    var retired =
        CanonicalGameplayRosterReferenceEvidence.sealRetirement(
            CanonicalGameplayRosterReferenceEvidence.response(
                    request, 1L, UUID.randomUUID(), receipt)
                .toBuilder()
                .setTerminalOutcome("DENIED")
                .setState(
                    net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState
                        .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED)
                .setRetirementUuid(UUID.randomUUID().toString())
                .setSupersedingPointerDigest("f".repeat(64))
                .setSupersedingPointerVersion(target.getPointerVersion() + 1L)
                .setSupersedingCatalogRevision(target.getCatalogRevision())
                .setSupersedingWorldEpoch(target.getActiveWorldEpoch())
                .build());
    assertThat(CanonicalGameplayRosterReferenceEvidence.requireRetirement(retired))
        .isEqualTo(retired);
    assertThatThrownBy(() -> CanonicalGameplayRosterReferenceEvidence.requireDereference(retired))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterReferenceEvidence.sealRetirement(
                    retired.toBuilder()
                        .setSupersedingPointerVersion(target.getPointerVersion())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterReferenceEvidence.requireResponse(
                    request.toBuilder()
                        .setCanonicalAccountUuid(UUID.randomUUID().toString())
                        .build(),
                    retired))
        .isInstanceOf(IllegalArgumentException.class);
    var replayClosed =
        retired.toBuilder()
            .setState(
                net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState
                    .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_REPLAY_CLOSED)
            .clearRetirementUuid()
            .clearRetirementProofDigest()
            .clearSupersedingPointerDigest()
            .clearSupersedingPointerVersion()
            .clearSupersedingCatalogRevision()
            .clearSupersedingWorldEpoch()
            .build();
    assertThatThrownBy(
            () -> CanonicalGameplayRosterReferenceEvidence.requireRetirement(replayClosed))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static GetCanonicalGameplayRosterReferenceRequest request() {
    var target =
        CanonicalGameplayRosterTarget.newBuilder()
            .setTenantUuid(UUID.randomUUID().toString())
            .setRealmUuid(UUID.randomUUID().toString())
            .setGameInstanceUuid(UUID.randomUUID().toString())
            .setCanonicalVersionUuid(UUID.randomUUID().toString())
            .setPlayableStateNamespaceUuid(UUID.randomUUID().toString())
            .setWorldSlug("earth")
            .setRealmSlug("main")
            .setCatalogRevision(7L)
            .setPointerVersion(11L)
            .setActiveWorldEpoch(13L)
            .setPublishedPolicyDigest("a".repeat(64))
            .setPublishedOwnerProofDigest("b".repeat(64))
            .setAdmissionPointerSnapshotDigest("c".repeat(64))
            .setPublishedReleaseBundleRef("release:exact")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setEntryPolicy(
                CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
            .build();
    return CanonicalGameplayRosterReferenceEvidence.request(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "gameplay", target);
  }
}
