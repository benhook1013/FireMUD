package net.firedevops.firemud.entitymanagement.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSelectedAssignmentReference;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSelectedAssignmentRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;

class CanonicalGameplayRosterGrpcAdapterTest {
  @Test
  void parsesCanonicalUuidTargetAndPolicyWithoutUsingCallerIdentityFallback() {
    var parsed = CanonicalGameplayRosterGrpcAdapter.parseRequest(request(target()));

    assertThat(parsed.requestUuid()).isNotNull();
    assertThat(parsed.canonicalAccountUuid()).isNotNull();
    assertThat(parsed.expectedTarget().entryPolicy())
        .isEqualTo(CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY);
    assertThat(parsed.expectedTarget().playableStateScope())
        .isEqualTo(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    assertThat(parsed.expectedTarget().pointerVersion()).isEqualTo(17L);
    assertThat(parsed.expectedTarget().activeWorldEpoch()).isEqualTo(29L);
  }

  @Test
  void responseTargetEchoesExactOwnerCounters() {
    var expected =
        CanonicalGameplayRosterGrpcAdapter.parseRequest(request(target())).expectedTarget();

    var responseTarget = CanonicalGameplayRosterGrpcAdapter.toProto(expected);

    assertThat(responseTarget.getPointerVersion()).isEqualTo(17L);
    assertThat(responseTarget.getActiveWorldEpoch()).isEqualTo(29L);
  }

  @Test
  void rejectsMalformedIdentityAndUnknownTopLevelFieldsBeforeAnyLookup() {
    CanonicalGameplayRosterRequest malformed =
        request(target()).toBuilder().setCanonicalAccountUuid("not-a-uuid").build();
    assertThatThrownBy(() -> CanonicalGameplayRosterGrpcAdapter.parseRequest(malformed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical_account_uuid");

    CanonicalGameplayRosterRequest unknown =
        request(target()).toBuilder().setUnknownFields(unknownField()).build();
    assertThatThrownBy(() -> CanonicalGameplayRosterGrpcAdapter.parseRequest(unknown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown fields");
  }

  @Test
  void rejectsUnknownNestedTargetFieldsAndUnsupportedScopeShape() {
    CanonicalGameplayRosterTarget unknownTarget =
        target().toBuilder().setUnknownFields(unknownField()).build();
    assertThatThrownBy(
            () -> CanonicalGameplayRosterGrpcAdapter.parseRequest(request(unknownTarget)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Complete expected target is required");

    CanonicalGameplayRosterTarget noScope =
        target().toBuilder()
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED)
            .build();
    assertThatThrownBy(() -> CanonicalGameplayRosterGrpcAdapter.parseRequest(request(noScope)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("playable_state_scope is required");

    CanonicalGameplayRosterTarget noCanonicalVersion =
        target().toBuilder().clearCanonicalVersionUuid().build();
    assertThatThrownBy(
            () -> CanonicalGameplayRosterGrpcAdapter.parseRequest(request(noCanonicalVersion)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("canonical_version_uuid is required");

    CanonicalGameplayRosterTarget noPointerVersion =
        target().toBuilder().setPointerVersion(0L).build();
    assertThatThrownBy(
            () -> CanonicalGameplayRosterGrpcAdapter.parseRequest(request(noPointerVersion)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pointerVersion must be positive");

    CanonicalGameplayRosterTarget negativeWorldEpoch =
        target().toBuilder().setActiveWorldEpoch(-1L).build();
    assertThatThrownBy(
            () -> CanonicalGameplayRosterGrpcAdapter.parseRequest(request(negativeWorldEpoch)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("activeWorldEpoch must be positive");

    CanonicalGameplayRosterTarget missingWorldEpoch =
        target().toBuilder().setActiveWorldEpoch(0L).build();
    assertThatThrownBy(
            () -> CanonicalGameplayRosterGrpcAdapter.parseRequest(request(missingWorldEpoch)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("activeWorldEpoch must be positive");
  }

  @Test
  void selectedAssignmentRequestParsesExactAccountActorAndCompleteTarget() {
    CanonicalGameplayRosterSelectedAssignmentRequest request = selectedAssignmentRequest(target());

    var parsed = CanonicalGameplayRosterGrpcAdapter.parseSelectedAssignmentRequest(request);

    assertThat(parsed.requestUuid()).isEqualTo(UUID.fromString(request.getRequestUuid()));
    assertThat(parsed.canonicalAccountUuid())
        .isEqualTo(UUID.fromString(request.getCanonicalAccountUuid()));
    assertThat(parsed.selectedCharacterUuid())
        .isEqualTo(UUID.fromString(request.getSelectedCharacterUuid()));
    assertThat(parsed.expectedTarget().entryPolicy())
        .isEqualTo(CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY);
    assertThat(parsed.expectedTarget().playableStateScope())
        .isEqualTo(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);

    UUID assignmentUuid = UUID.randomUUID();
    String intentDigest = "a".repeat(64);
    var response =
        CanonicalGameplayRosterGrpcAdapter.toSelectedAssignmentResponse(
            new CanonicalGameplayRosterSelectedAssignmentReference(
                parsed.requestUuid(),
                parsed.canonicalAccountUuid(),
                parsed.selectedCharacterUuid(),
                parsed.expectedTarget(),
                assignmentUuid,
                intentDigest));

    assertThat(response.getRequestUuid()).isEqualTo(request.getRequestUuid());
    assertThat(response.getCanonicalAccountUuid()).isEqualTo(request.getCanonicalAccountUuid());
    assertThat(response.getSelectedCharacterUuid()).isEqualTo(request.getSelectedCharacterUuid());
    assertThat(response.getTarget()).isEqualTo(request.getExpectedTarget());
    assertThat(response.getAssignmentUuid()).isEqualTo(assignmentUuid.toString());
    assertThat(response.getIntentDigest()).isEqualTo(intentDigest);
  }

  @Test
  void selectedAssignmentRejectsUnknownFieldsAndMalformedActorIdentityBeforeLookup() {
    CanonicalGameplayRosterSelectedAssignmentRequest unknownRequest =
        selectedAssignmentRequest(target()).toBuilder().setUnknownFields(unknownField()).build();
    assertThatThrownBy(
            () -> CanonicalGameplayRosterGrpcAdapter.parseSelectedAssignmentRequest(unknownRequest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown fields");

    CanonicalGameplayRosterTarget unknownTarget =
        target().toBuilder().setUnknownFields(unknownField()).build();
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterGrpcAdapter.parseSelectedAssignmentRequest(
                    selectedAssignmentRequest(unknownTarget)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Complete expected target is required");

    CanonicalGameplayRosterSelectedAssignmentRequest malformedCharacter =
        selectedAssignmentRequest(target()).toBuilder()
            .setSelectedCharacterUuid("not-a-uuid")
            .build();
    assertThatThrownBy(
            () ->
                CanonicalGameplayRosterGrpcAdapter.parseSelectedAssignmentRequest(
                    malformedCharacter))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("selected_character_uuid");
  }

  private static CanonicalGameplayRosterRequest request(CanonicalGameplayRosterTarget target) {
    return CanonicalGameplayRosterRequest.newBuilder()
        .setRequestUuid(UUID.randomUUID().toString())
        .setCanonicalAccountUuid(UUID.randomUUID().toString())
        .setExpectedTarget(target)
        .build();
  }

  private static CanonicalGameplayRosterSelectedAssignmentRequest selectedAssignmentRequest(
      CanonicalGameplayRosterTarget target) {
    return CanonicalGameplayRosterSelectedAssignmentRequest.newBuilder()
        .setRequestUuid(UUID.randomUUID().toString())
        .setCanonicalAccountUuid(UUID.randomUUID().toString())
        .setSelectedCharacterUuid(UUID.randomUUID().toString())
        .setExpectedTarget(target)
        .build();
  }

  private static CanonicalGameplayRosterTarget target() {
    return CanonicalGameplayRosterTarget.newBuilder()
        .setTenantUuid(UUID.randomUUID().toString())
        .setRealmUuid(UUID.randomUUID().toString())
        .setWorldSlug("world")
        .setRealmSlug("realm")
        .setGameInstanceUuid(UUID.randomUUID().toString())
        .setCatalogRevision(3L)
        .setCanonicalVersionUuid("17000000-0000-4000-8000-000000000017")
        .setPointerVersion(17L)
        .setActiveWorldEpoch(29L)
        .setPublishedPolicyDigest("1".repeat(64))
        .setPublishedReleaseBundleRef("release/test")
        .setAdmissionPointerSnapshotDigest("2".repeat(64))
        .setPublishedOwnerProofDigest("3".repeat(64))
        .setPlayableStateNamespaceUuid(UUID.randomUUID().toString())
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .setEntryPolicy(
            net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy
                .CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
        .build();
  }

  private static UnknownFieldSet unknownField() {
    return UnknownFieldSet.newBuilder()
        .addField(999, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }
}
