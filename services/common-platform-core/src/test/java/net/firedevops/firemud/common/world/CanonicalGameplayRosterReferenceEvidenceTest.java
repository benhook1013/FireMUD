package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSnapshotReference;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
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

  @Test
  void recoveryFenceStopsDereferenceAndRequiresExactIndependentOwnerIdentity() {
    var original = request();
    var recovery =
        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest
            .newBuilder()
            .setSchemaVersion(1)
            .setOriginalSelection(original)
            .setRecoveryUuid(UUID.randomUUID().toString())
            .build();
    var owner =
        CanonicalGameplayRosterReferenceEvidence.response(original, 1L, UUID.randomUUID(), null)
            .toBuilder()
            .setRecoveryUuid(recovery.getRecoveryUuid())
            .build();
    assertThat(CanonicalGameplayRosterRecoveryEvidence.requireOwnerRecovery(recovery, owner))
        .isEqualTo(owner);
    assertThatThrownBy(() -> CanonicalGameplayRosterReferenceEvidence.requireDereference(owner))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterRecoveryEvidence.requireOwnerRecovery(
                    recovery.toBuilder().setRecoveryUuid(UUID.randomUUID().toString()).build(),
                    owner))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterReferenceEvidence.requireDereference(
                    owner.toBuilder().clearRecoveryUuid().setTerminalOutcome("DENIED").build()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void closedRecoveryEchoAndOptionalOriginalReceiptNeverPermitSubstitution() {
    var original = request();
    var recovery =
        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest
            .newBuilder()
            .setSchemaVersion(1)
            .setOriginalSelection(original)
            .setRecoveryUuid(UUID.randomUUID().toString())
            .build();
    var receipt =
        CanonicalGameplayRosterResponse.newBuilder()
            .setCanonicalAccountUuid(original.getCanonicalAccountUuid())
            .setTarget(CanonicalGameplayRosterReferenceEvidence.target(original))
            .setSnapshotUuid(UUID.randomUUID().toString())
            .setSnapshotDigest("d".repeat(64))
            .build();
    var response =
        CanonicalGameplayRosterRecoveryEvidence.response(
            recovery, UUID.randomUUID(), 1L, UUID.randomUUID(), receipt);
    assertThat(response.getCanonicalRosterReceipt()).isEqualTo(receipt.toByteString());
    assertThat(CanonicalGameplayRosterRecoveryEvidence.requireResponse(recovery, response))
        .isEqualTo(response);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    for (var invalid :
        java.util.List.of(
            response.toBuilder().setOwnerFence(0L).build(),
            response.toBuilder().setAdmissionUuid(new UUID(0L, 0L).toString()).build(),
            response.toBuilder().setUnknownFields(unknown).build(),
            response.toBuilder()
                .setCanonicalRosterReceipt(
                    receipt.toBuilder()
                        .setCanonicalAccountUuid(UUID.randomUUID().toString())
                        .build()
                        .toByteString())
                .build(),
            response.toBuilder()
                .setCanonicalRosterReceipt(
                    receipt.toBuilder()
                        .setTarget(receipt.getTarget().toBuilder().setPointerVersion(99L))
                        .build()
                        .toByteString())
                .build(),
            response.toBuilder()
                .setRequest(recovery.toBuilder().setRecoveryUuid(UUID.randomUUID().toString()))
                .build())) {
      assertThatThrownBy(
              () -> CanonicalGameplayRosterRecoveryEvidence.requireResponse(recovery, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void createdMenuAndSelectedReplayRetainTheDistinctCompleteOriginalCreationReceipt()
      throws Exception {
    var fixture = createdFixture();
    var original = fixture.original();
    var menu =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
            original, 1L, UUID.randomUUID(), fixture.roster());
    assertThat(CanonicalGameplayRosterReferenceEvidence.playerCreatedReceipt(menu))
        .isEqualTo(fixture.roster());
    assertThat(CanonicalGameplayRosterReferenceEvidence.snapshotReceipt(menu))
        .isEqualTo(fixture.roster().getRoster());
    var selected = selectedOriginal(fixture, menu);
    assertThat(CanonicalGameplayRosterReferenceEvidence.requireResponse(original, selected))
        .isEqualTo(selected);
    var replay = GetCanonicalGameplayRosterReferenceResponse.parseFrom(selected.toByteArray());
    assertThat(CanonicalGameplayRosterReferenceEvidence.requireResponse(original, replay))
        .isEqualTo(selected);
    assertThat(
            CanonicalGameplayRosterReferenceEvidence.selectedPlayerCreatedCharacterReceipt(replay))
        .isEqualTo(fixture.selected());
    assertThat(
            CanonicalGameplayRosterReferenceEvidence.selectedPlayerCreatedCharacterReceipt(replay)
                .getTerminal())
        .isEqualTo(fixture.selected().getTerminal());
    assertThatThrownBy(() -> CanonicalGameplayRosterReferenceEvidence.receipt(menu))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> CanonicalGameplayRosterReferenceEvidence.selectedAssignmentReceipt(selected))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterReferenceEvidence.response(
                    original, 1L, UUID.randomUUID(), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterReferenceEvidence.request(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "gameplay",
                    fixture.roster().getRoster().getTarget()))
        .isInstanceOf(IllegalArgumentException.class);
    var foreignOriginal = original.toBuilder().setTargetNamespace("other").build();
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterReferenceEvidence.requireResponse(
                    foreignOriginal, selected.toBuilder().setRequest(foreignOriginal).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload namespace");
    for (var changed :
        List.of(
            selected.toBuilder().setSelectedCharacterUuid(UUID.randomUUID().toString()).build(),
            selected.toBuilder()
                .setCanonicalSelectedPlayerCreatedCharacterReceipt(
                    fixture.selected().toBuilder()
                        .setRequestUuid(UUID.randomUUID().toString())
                        .build()
                        .toByteString())
                .build(),
            selected.toBuilder()
                .setCanonicalSelectedPlayerCreatedCharacterReceipt(
                    fixture.selected().toBuilder()
                        .setCanonicalAccountUuid(UUID.randomUUID().toString())
                        .build()
                        .toByteString())
                .build(),
            selected.toBuilder()
                .setCanonicalSelectedPlayerCreatedCharacterReceipt(
                    fixture.selected().toBuilder()
                        .setSourceIdentity(
                            fixture.selected().getSourceIdentity().toBuilder()
                                .setDescriptorRevisionUuid(UUID.randomUUID().toString()))
                        .build()
                        .toByteString())
                .build())) {
      assertThatThrownBy(
              () -> CanonicalGameplayRosterReferenceEvidence.requireResponse(original, changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void createdSourceBytesAndPolicyBranchesAreClosedAndMutuallyExclusive() {
    var fixture = createdFixture();
    var created = fixture.original();
    var source = CanonicalGameplayRosterReferenceEvidence.playerCreatedSourceIdentity(created);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    for (var changed :
        List.of(
            created.toBuilder().clearCanonicalPlayerCreatedSourceIdentity().build(),
            created.toBuilder()
                .setCanonicalPlayerCreatedSourceIdentity(
                    source.toBuilder().setUnknownFields(unknown).build().toByteString())
                .build(),
            created.toBuilder()
                .setCanonicalPlayerCreatedSourceIdentity(
                    source.toByteString().concat(ByteString.copyFrom(new byte[] {8, 1})))
                .build(),
            created.toBuilder()
                .setCanonicalTarget(
                    CanonicalGameplayRosterReferenceEvidence.target(created).toBuilder()
                        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)
                        .build()
                        .toByteString())
                .build(),
            created.toBuilder()
                .setCanonicalTarget(
                    CanonicalGameplayRosterReferenceEvidence.target(created).toBuilder()
                        .setEntryPolicy(
                            CanonicalGameplayRosterEntryPolicy
                                .CANONICAL_ROSTER_ENTRY_POLICY_AUTO_PROVISIONED)
                        .build()
                        .toByteString())
                .build(),
            request().toBuilder()
                .setCanonicalPlayerCreatedSourceIdentity(source.toByteString())
                .build())) {
      assertThatThrownBy(() -> CanonicalGameplayRosterReferenceEvidence.requireRequest(changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var createdMenu =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
            created, 1L, UUID.randomUUID(), fixture.roster());
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterReferenceEvidence.requireResponse(
                    created,
                    createdMenu.toBuilder()
                        .setCanonicalRosterReceipt(fixture.roster().getRoster().toByteString())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    var preseeded = request();
    var reserved =
        CanonicalGameplayRosterReferenceEvidence.response(preseeded, 1L, UUID.randomUUID(), null);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterReferenceEvidence.requireResponse(
                    preseeded,
                    reserved.toBuilder()
                        .setCanonicalPlayerCreatedRosterReceipt(fixture.roster().toByteString())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterReferenceEvidence.requireResponse(
                    preseeded,
                    reserved.toBuilder()
                        .setCanonicalSelectedPlayerCreatedCharacterReceipt(
                            fixture.selected().toByteString())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
                    preseeded, 1L, UUID.randomUUID(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void largestCreatedSelectorsAndUnicodeReleaseFitUnchangedOriginalAndRecoveryRequestBudgets() {
    var fixture = createdFixture();
    var target =
        CanonicalGameplayRosterReferenceEvidence.target(fixture.original()).toBuilder()
            .setWorldSlug("a".repeat(64))
            .setRealmSlug("b".repeat(64))
            .setCatalogRevision(Long.MAX_VALUE)
            .setPointerVersion(Long.MAX_VALUE)
            .setActiveWorldEpoch(Long.MAX_VALUE)
            .setPublishedReleaseBundleRef("\u0800".repeat(1024))
            .build();
    var largest =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(63),
            target,
            CanonicalGameplayRosterReferenceEvidence.playerCreatedSourceIdentity(
                fixture.original()));
    assertThat(CanonicalGameplayRosterReferenceEvidence.MAX_REQUEST_BYTES).isEqualTo(4096);
    assertThat(
            target.getPublishedReleaseBundleRef().getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .hasSize(3072);
    assertThat(largest.getSerializedSize())
        .isEqualTo(4081)
        .isLessThanOrEqualTo(CanonicalGameplayRosterReferenceEvidence.MAX_REQUEST_BYTES);
    var recovery =
        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest
            .newBuilder()
            .setSchemaVersion(1)
            .setOriginalSelection(largest)
            .setRecoveryUuid(UUID.randomUUID().toString())
            .build();
    assertThat(CanonicalGameplayRosterRecoveryEvidence.requireRequest(recovery).getSerializedSize())
        .isEqualTo(4124)
        .isLessThanOrEqualTo(CanonicalGameplayRosterRecoveryEvidence.MAX_REQUEST_BYTES);
  }

  @Test
  void createdPurposeBudgetsRetainTheFullTerminalAndRejectOversizeWithoutHugeBackingArrays() {
    var fixture = createdFixture();
    var menu =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
            fixture.original(), 1L, UUID.randomUUID(), fixture.roster());
    assertThat(CanonicalGameplayRosterReferenceEvidence.MAX_RECEIPT_BYTES).isEqualTo(64 * 1024);
    assertThat(CanonicalGameplayRosterReferenceEvidence.MAX_SELECTED_ASSIGNMENT_BYTES)
        .isEqualTo(8192);
    assertThat(CanonicalPlayerCreatedRosterEvidence.MAX_TERMINAL_BYTES)
        .isEqualTo(192 * 1024 * 1024);
    assertThat(CanonicalGameplayRosterReferenceEvidence.MAX_SELECTED_PLAYER_CREATED_BYTES)
        .isEqualTo(193 * 1024 * 1024);
    assertThat(CanonicalGameplayRosterReferenceEvidence.MAX_PLAYER_CREATED_RESPONSE_BYTES)
        .isEqualTo(194 * 1024 * 1024);
    var preseeded = request();
    var reserved =
        CanonicalGameplayRosterReferenceEvidence.response(preseeded, 1L, UUID.randomUUID(), null);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterReferenceEvidence.requireResponse(
                    preseeded,
                    reserved.toBuilder()
                        .setCanonicalRosterReceipt(logicalBytes(64 * 1024 + 1))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterReferenceEvidence.selectedAssignmentReceipt(
                    reserved.toBuilder()
                        .setCanonicalSelectedAssignmentReceipt(logicalBytes(8192 + 1))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    int completeSelectedEnvelope =
        CodedOutputStream.computeTagSize(18)
            + CodedOutputStream.computeUInt32SizeNoTag(
                CanonicalGameplayRosterReferenceEvidence.MAX_SELECTED_PLAYER_CREATED_BYTES)
            + CanonicalGameplayRosterReferenceEvidence.MAX_SELECTED_PLAYER_CREATED_BYTES;
    // A full 193 MiB selected envelope, 66 KiB menu, maximal original and bounded input/metadata
    // fit the original-response purpose without constructing a gratuitous 192 MiB terminal.
    assertThat(completeSelectedEnvelope + 66 * 1024 + 4096 + 2048)
        .isLessThanOrEqualTo(
            CanonicalGameplayRosterReferenceEvidence.MAX_PLAYER_CREATED_RESPONSE_BYTES);
    for (var changed :
        List.of(
            menu.toBuilder()
                .setCanonicalPlayerCreatedRosterReceipt(
                    logicalBytes(
                        CanonicalGameplayRosterReferenceEvidence.MAX_PLAYER_CREATED_RECEIPT_BYTES
                            + 1))
                .build(),
            menu.toBuilder()
                .setCanonicalSelectedPlayerCreatedCharacterReceipt(
                    logicalBytes(
                        CanonicalGameplayRosterReferenceEvidence.MAX_SELECTED_PLAYER_CREATED_BYTES
                            + 1))
                .build(),
            menu.toBuilder()
                .setCanonicalSelectedPlayerCreatedCharacterReceipt(
                    logicalBytes(
                        CanonicalGameplayRosterReferenceEvidence.MAX_PLAYER_CREATED_RESPONSE_BYTES
                            + 1))
                .build())) {
      assertThatThrownBy(
              () ->
                  CanonicalGameplayRosterReferenceEvidence.requireResponse(
                      fixture.original(), changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // Rope ByteStrings share their tiny backing payload; over-budget tests reject before parsing.
  static ByteString logicalBytes(int size) {
    ByteString bytes = ByteString.copyFrom(new byte[1024]);
    while (bytes.size() < size) bytes = bytes.concat(bytes);
    return bytes.substring(0, size);
  }

  record CreatedFixture(
      GetCanonicalGameplayRosterReferenceRequest original,
      CanonicalPlayerCreatedRosterResponse roster,
      CanonicalSelectedPlayerCreatedCharacterResponse selected) {}

  static CreatedFixture createdFixture() {
    var selected = CanonicalPlayerCreatedRosterEvidenceTest.selectedFixture();
    var request = selected.request();
    var actors =
        List.of(
            CanonicalGameplayRosterActor.newBuilder()
                .setCharacterUuid(request.getSelectedCharacterUuid())
                .setDisplayName("Persisted created actor")
                .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                .build());
    var digest =
        CanonicalPlayerCreatedRosterEvidence.computeSnapshotDigest(
            request.getCanonicalAccountUuid(),
            request.getExpectedTarget(),
            request.getExpectedSource(),
            actors);
    var snapshot =
        CanonicalGameplayRosterSnapshotReference.newBuilder()
            .setSnapshotUuid(request.getExpectedSnapshot().getSnapshotUuid())
            .setSnapshotDigest(digest)
            .build();
    var roster =
        CanonicalPlayerCreatedRosterResponse.newBuilder()
            .setSourceIdentity(request.getExpectedSource())
            .setRoster(
                CanonicalGameplayRosterResponse.newBuilder()
                    .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
                    .setTarget(request.getExpectedTarget())
                    .setSnapshotUuid(snapshot.getSnapshotUuid())
                    .setSnapshotDigest(digest)
                    .addAllActors(actors))
            .build();
    var original =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedRequest(
            UUID.fromString(request.getRequestUuid()),
            UUID.fromString(request.getCanonicalAccountUuid()),
            UUID.fromString(request.getPlayerExecutionContext().getSessionId()),
            selected
                .response()
                .getTerminal()
                .getOriginalRequest()
                .getOriginal()
                .getTargetNamespace(),
            request.getExpectedTarget(),
            request.getExpectedSource());
    return new CreatedFixture(
        original, roster, selected.response().toBuilder().setSnapshot(snapshot).build());
  }

  static GetCanonicalGameplayRosterReferenceResponse selectedOriginal(
      CreatedFixture fixture, GetCanonicalGameplayRosterReferenceResponse menu) {
    return menu.toBuilder()
        .setSelectionInput(
            net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput.newBuilder()
                .setSchemaVersion(1)
                .setSelector("1"))
        .setSelectedCharacterUuid(fixture.selected().getSelectedCharacterUuid())
        .setCanonicalSelectedPlayerCreatedCharacterReceipt(fixture.selected().toByteString())
        .setTerminalOutcome("SELECTED")
        .build();
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
