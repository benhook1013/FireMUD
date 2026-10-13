package net.firedevops.firemud.entitymanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;

class PreseededActorAssignmentServiceTest {
  private static final UUID ASSIGNMENT_UUID =
      UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID ACCOUNT_UUID = UUID.fromString("20000000-0000-4000-8000-000000000002");
  private static final UUID TENANT_UUID = UUID.fromString("30000000-0000-4000-8000-000000000003");
  private static final UUID REALM_UUID = UUID.fromString("40000000-0000-4000-8000-000000000004");
  private static final UUID NAMESPACE_UUID =
      UUID.fromString("50000000-0000-4000-8000-000000000005");
  private static final UUID CHARACTER_UUID =
      UUID.fromString("70000000-0000-4000-8000-000000000007");

  @Test
  void requiresDedicatedRunOwnedActionBeforeCallingAnyOwnerOrStorageBoundary() {
    RunOwnedPreseededAssignmentAuthority runAuthority =
        new RunOwnedPreseededAssignmentAuthority() {
          @Override
          public void requireAuthorized(
              PreseededActorAssignmentRequest request, Action dedicatedAction) {
            assertThat(dedicatedAction).isEqualTo(Action.PRESEEDED_ACTOR_ASSIGNMENT);
            assertThat(request.corePayload().actorKind())
                .isEqualTo(PreseededActorCorePayload.ActorKind.PLAYER);
            throw new IllegalStateException("fixture capability did not grant this action");
          }

          @Override
          public void requireTargetBoundAuthorized(
              PreseededActorAssignmentRequest request,
              PreseededActorAssignmentOwnerEvidence exactTarget,
              Action dedicatedAction) {
            throw new AssertionError("target grant check must follow initial action grant");
          }
        };
    PreseededActorAccountIdentityPort accountClient = mock(PreseededActorAccountIdentityPort.class);
    PreseededActorAssignmentOwnerEvidencePort ownerPort =
        mock(PreseededActorAssignmentOwnerEvidencePort.class);
    CharacterRepository characterRepository = mock(CharacterRepository.class);
    PreseededActorAssignmentService service =
        new PreseededActorAssignmentService(
            runAuthority, accountClient, stagingPort(), ownerPort, characterRepository);

    assertThatThrownBy(() -> service.assign(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("did not grant this action");

    verifyNoInteractions(accountClient, ownerPort, characterRepository);
  }

  @Test
  void bindsDedicatedGrantToExactCurrentTargetAndCorePayloadBeforePersistingActor() {
    RunOwnedPreseededAssignmentAuthority runAuthority =
        mock(RunOwnedPreseededAssignmentAuthority.class);
    PreseededActorAccountIdentityPort accountClient = mock(PreseededActorAccountIdentityPort.class);
    PreseededActorAssignmentOwnerEvidencePort ownerPort =
        mock(PreseededActorAssignmentOwnerEvidencePort.class);
    CharacterRepository characterRepository = mock(CharacterRepository.class);
    PreseededActorAssignmentRequest request = request();
    RuntimeAccountIdentityEvidence accountProof = accountProof();
    PreseededActorAssignmentOwnerEvidence target = ownerEvidence();
    PreseededActorAssignmentResult persisted =
        new PreseededActorAssignmentResult(
            ASSIGNMENT_UUID,
            PreseededActorAssignmentResult.Outcome.ASSIGNED,
            CHARACTER_UUID,
            "a".repeat(64));
    when(accountClient.resolveRuntimeAccountIdentity(
            ACCOUNT_UUID.toString(), ASSIGNMENT_UUID.toString()))
        .thenReturn(accountProof);
    when(ownerPort.resolveCurrentEligibleTarget(request, accountProof, stagingEvidence()))
        .thenReturn(target);
    when(characterRepository.assignPreseededActor(
            eq(request), eq(target), eq(accountProof), any(String.class)))
        .thenReturn(persisted);
    PreseededActorAssignmentService service =
        new PreseededActorAssignmentService(
            runAuthority, accountClient, stagingPort(), ownerPort, characterRepository);

    assertThat(service.assign(request())).isEqualTo(persisted);

    verify(runAuthority)
        .requireAuthorized(
            request, RunOwnedPreseededAssignmentAuthority.Action.PRESEEDED_ACTOR_ASSIGNMENT);
    verify(accountClient)
        .resolveRuntimeAccountIdentity(ACCOUNT_UUID.toString(), ASSIGNMENT_UUID.toString());
    verify(ownerPort).resolveCurrentEligibleTarget(request, accountProof, stagingEvidence());
    verify(runAuthority)
        .requireTargetBoundAuthorized(
            request,
            target,
            RunOwnedPreseededAssignmentAuthority.Action.PRESEEDED_ACTOR_ASSIGNMENT);
    verify(characterRepository)
        .assignPreseededActor(eq(request), eq(target), eq(accountProof), any(String.class));
  }

  @Test
  void changedGrantTargetOrPayloadIsRejectedBeforeRepositoryReplay() {
    RuntimeAccountIdentityEvidence accountProof = accountProof();
    PreseededActorAssignmentRequest request = request();
    PreseededActorAssignmentOwnerEvidence target = ownerEvidence();
    RunOwnedPreseededAssignmentAuthority runAuthority =
        new RunOwnedPreseededAssignmentAuthority() {
          @Override
          public void requireAuthorized(
              PreseededActorAssignmentRequest authorizedRequest, Action dedicatedAction) {
            assertThat(authorizedRequest).isEqualTo(request);
            assertThat(dedicatedAction).isEqualTo(Action.PRESEEDED_ACTOR_ASSIGNMENT);
          }

          @Override
          public void requireTargetBoundAuthorized(
              PreseededActorAssignmentRequest authorizedRequest,
              PreseededActorAssignmentOwnerEvidence currentTarget,
              Action dedicatedAction) {
            assertThat(authorizedRequest).isEqualTo(request);
            assertThat(currentTarget).isEqualTo(target);
            assertThat(dedicatedAction).isEqualTo(Action.PRESEEDED_ACTOR_ASSIGNMENT);
            throw new IllegalStateException("PRESEEDED_ASSIGNMENT_GRANT_INTENT_MISMATCH");
          }
        };
    PreseededActorAccountIdentityPort accountClient = mock(PreseededActorAccountIdentityPort.class);
    PreseededActorAssignmentOwnerEvidencePort ownerPort =
        mock(PreseededActorAssignmentOwnerEvidencePort.class);
    CharacterRepository characterRepository = mock(CharacterRepository.class);
    when(accountClient.resolveRuntimeAccountIdentity(
            ACCOUNT_UUID.toString(), ASSIGNMENT_UUID.toString()))
        .thenReturn(accountProof);
    when(ownerPort.resolveCurrentEligibleTarget(request, accountProof, stagingEvidence()))
        .thenReturn(target);
    PreseededActorAssignmentService service =
        new PreseededActorAssignmentService(
            runAuthority, accountClient, stagingPort(), ownerPort, characterRepository);

    assertThatThrownBy(() -> service.assign(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("PRESEEDED_ASSIGNMENT_GRANT_INTENT_MISMATCH");

    verifyNoInteractions(characterRepository);
  }

  @Test
  void rejectsUnknownAccountProofBeforeReadingAssignmentTarget() {
    RunOwnedPreseededAssignmentAuthority runAuthority =
        mock(RunOwnedPreseededAssignmentAuthority.class);
    PreseededActorAccountIdentityPort accountClient = mock(PreseededActorAccountIdentityPort.class);
    PreseededActorAssignmentOwnerEvidencePort ownerPort =
        mock(PreseededActorAssignmentOwnerEvidencePort.class);
    CharacterRepository characterRepository = mock(CharacterRepository.class);
    when(accountClient.resolveRuntimeAccountIdentity(
            ACCOUNT_UUID.toString(), ASSIGNMENT_UUID.toString()))
        .thenReturn(
            new RuntimeAccountIdentityEvidence(
                1,
                "dev",
                UUID.fromString("80000000-0000-4000-8000-000000000008"),
                ACCOUNT_UUID,
                23L,
                "ACCOUNT_DATABASE_INSERT"));
    PreseededActorAssignmentService service =
        new PreseededActorAssignmentService(
            runAuthority, accountClient, stagingPort(), ownerPort, characterRepository);

    assertThatThrownBy(() -> service.assign(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("PRESEEDED_ASSIGNMENT_ACCOUNT_IDENTITY_PROOF_MISMATCH");

    verify(ownerPort, never()).resolveCurrentEligibleTarget(any(), any(), any());
    verifyNoInteractions(characterRepository);
  }

  @Test
  void deniesIneligibleAccountSnapshotBeforeGameSessionOwnerReadOrAllocation() {
    RunOwnedPreseededAssignmentAuthority runAuthority =
        mock(RunOwnedPreseededAssignmentAuthority.class);
    PreseededActorAccountIdentityPort accountClient = mock(PreseededActorAccountIdentityPort.class);
    PreseededActorStagingEligibilityPort stagingPort =
        mock(PreseededActorStagingEligibilityPort.class);
    PreseededActorAssignmentOwnerEvidencePort ownerPort =
        mock(PreseededActorAssignmentOwnerEvidencePort.class);
    CharacterRepository characterRepository = mock(CharacterRepository.class);
    when(accountClient.resolveRuntimeAccountIdentity(
            ACCOUNT_UUID.toString(), ASSIGNMENT_UUID.toString()))
        .thenReturn(accountProof());
    when(stagingPort.resolvePreseededActorStagingEligibility(
            ACCOUNT_UUID.toString(), TENANT_UUID.toString(), ASSIGNMENT_UUID.toString()))
        .thenReturn(stagingEvidence(false));
    PreseededActorAssignmentService service =
        new PreseededActorAssignmentService(
            runAuthority, accountClient, stagingPort, ownerPort, characterRepository);

    assertThatThrownBy(() -> service.assign(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("PRESEEDED_ASSIGNMENT_ACCOUNT_NOT_CURRENTLY_ELIGIBLE");

    verifyNoInteractions(ownerPort, characterRepository);
  }

  @Test
  void rejectsOwnerEvidenceNotBoundToTheExactAccountStagingSnapshot() {
    RunOwnedPreseededAssignmentAuthority runAuthority =
        mock(RunOwnedPreseededAssignmentAuthority.class);
    PreseededActorAccountIdentityPort accountClient = mock(PreseededActorAccountIdentityPort.class);
    PreseededActorAssignmentOwnerEvidencePort ownerPort =
        mock(PreseededActorAssignmentOwnerEvidencePort.class);
    CharacterRepository characterRepository = mock(CharacterRepository.class);
    RuntimeAccountIdentityEvidence accountProof = accountProof();
    PreseededActorAssignmentRequest request = request();
    PreseededActorAssignmentOwnerEvidence mismatchedTarget =
        ownerEvidenceWithAccountSnapshotDigest("5".repeat(64));
    when(accountClient.resolveRuntimeAccountIdentity(
            ACCOUNT_UUID.toString(), ASSIGNMENT_UUID.toString()))
        .thenReturn(accountProof);
    when(ownerPort.resolveCurrentEligibleTarget(request, accountProof, stagingEvidence()))
        .thenReturn(mismatchedTarget);
    PreseededActorAssignmentService service =
        new PreseededActorAssignmentService(
            runAuthority, accountClient, stagingPort(), ownerPort, characterRepository);

    assertThatThrownBy(() -> service.assign(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("PRESEEDED_ASSIGNMENT_ACCOUNT_STAGING_EVIDENCE_MISMATCH");

    verify(runAuthority, never()).requireTargetBoundAuthorized(any(), any(), any());
    verifyNoInteractions(characterRepository);
  }

  @Test
  void deniesIneligibleOwnerEvidenceBeforeTargetGrantOrRepositoryAllocation() {
    RunOwnedPreseededAssignmentAuthority runAuthority =
        mock(RunOwnedPreseededAssignmentAuthority.class);
    PreseededActorAccountIdentityPort accountClient = mock(PreseededActorAccountIdentityPort.class);
    PreseededActorAssignmentOwnerEvidencePort ownerPort =
        mock(PreseededActorAssignmentOwnerEvidencePort.class);
    CharacterRepository characterRepository = mock(CharacterRepository.class);
    RuntimeAccountIdentityEvidence accountProof = accountProof();
    PreseededActorAssignmentOwnerEvidence ineligible =
        new PreseededActorAssignmentOwnerEvidence(
            ASSIGNMENT_UUID,
            ACCOUNT_UUID,
            PreseededActorAssignmentOwnerEvidence.Eligibility.INELIGIBLE,
            Instant.parse("2026-10-04T00:00:00Z"),
            9L,
            "1".repeat(64),
            TENANT_UUID,
            REALM_UUID,
            "world",
            "realm",
            "game-instance",
            4L,
            UUID.fromString("17000000-0000-4000-8000-000000000017"),
            "2".repeat(64),
            NAMESPACE_UUID,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            PreseededActorAssignmentOwnerEvidence.PublishedEntryPolicy.PRESEEDED_ONLY,
            PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING,
            PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION,
            "3".repeat(64),
            "4".repeat(64),
            "release-bundle/test");
    when(accountClient.resolveRuntimeAccountIdentity(
            ACCOUNT_UUID.toString(), ASSIGNMENT_UUID.toString()))
        .thenReturn(accountProof);
    when(ownerPort.resolveCurrentEligibleTarget(request(), accountProof, stagingEvidence()))
        .thenReturn(ineligible);
    PreseededActorAssignmentService service =
        new PreseededActorAssignmentService(
            runAuthority, accountClient, stagingPort(), ownerPort, characterRepository);

    assertThatThrownBy(() -> service.assign(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("PRESEEDED_ASSIGNMENT_ACCOUNT_NOT_CURRENTLY_ELIGIBLE");

    verify(runAuthority, never()).requireTargetBoundAuthorized(any(), any(), any());
    verify(characterRepository, never()).assignPreseededActor(any(), any(), any(), any());
  }

  @Test
  void deniesAbsentOwnerProofBeforeRepositoryAllocation() {
    RunOwnedPreseededAssignmentAuthority runAuthority =
        mock(RunOwnedPreseededAssignmentAuthority.class);
    PreseededActorAccountIdentityPort accountClient = mock(PreseededActorAccountIdentityPort.class);
    PreseededActorAssignmentOwnerEvidencePort ownerPort =
        mock(PreseededActorAssignmentOwnerEvidencePort.class);
    CharacterRepository characterRepository = mock(CharacterRepository.class);
    RuntimeAccountIdentityEvidence accountProof = accountProof();
    when(accountClient.resolveRuntimeAccountIdentity(
            ACCOUNT_UUID.toString(), ASSIGNMENT_UUID.toString()))
        .thenReturn(accountProof);
    when(ownerPort.resolveCurrentEligibleTarget(request(), accountProof, stagingEvidence()))
        .thenReturn(null);
    PreseededActorAssignmentService service =
        new PreseededActorAssignmentService(
            runAuthority, accountClient, stagingPort(), ownerPort, characterRepository);

    assertThatThrownBy(() -> service.assign(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("PRESEEDED_ASSIGNMENT_OWNER_PROOF_MISMATCH");

    verify(characterRepository, never()).assignPreseededActor(any(), any(), any(), any());
  }

  @Test
  void deniesUnavailableOwnerEvidenceBeforeRepositoryAllocation() {
    RunOwnedPreseededAssignmentAuthority runAuthority =
        mock(RunOwnedPreseededAssignmentAuthority.class);
    PreseededActorAccountIdentityPort accountClient = mock(PreseededActorAccountIdentityPort.class);
    PreseededActorAssignmentOwnerEvidencePort ownerPort =
        mock(PreseededActorAssignmentOwnerEvidencePort.class);
    CharacterRepository characterRepository = mock(CharacterRepository.class);
    RuntimeAccountIdentityEvidence accountProof = accountProof();
    when(accountClient.resolveRuntimeAccountIdentity(
            ACCOUNT_UUID.toString(), ASSIGNMENT_UUID.toString()))
        .thenReturn(accountProof);
    when(ownerPort.resolveCurrentEligibleTarget(request(), accountProof, stagingEvidence()))
        .thenThrow(new IllegalStateException("PRESEEDED_ASSIGNMENT_OWNER_EVIDENCE_UNAVAILABLE"));
    PreseededActorAssignmentService service =
        new PreseededActorAssignmentService(
            runAuthority, accountClient, stagingPort(), ownerPort, characterRepository);

    assertThatThrownBy(() -> service.assign(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("PRESEEDED_ASSIGNMENT_OWNER_EVIDENCE_UNAVAILABLE");

    verifyNoInteractions(characterRepository);
  }

  @Test
  void rejectsEveryChangedExpectedTargetFieldBeforeRepositoryReplay() {
    for (int changedField = 0; changedField < 11; changedField++) {
      RunOwnedPreseededAssignmentAuthority runAuthority =
          mock(RunOwnedPreseededAssignmentAuthority.class);
      PreseededActorAccountIdentityPort accountClient =
          mock(PreseededActorAccountIdentityPort.class);
      PreseededActorAssignmentOwnerEvidencePort ownerPort =
          mock(PreseededActorAssignmentOwnerEvidencePort.class);
      CharacterRepository characterRepository = mock(CharacterRepository.class);
      RuntimeAccountIdentityEvidence accountProof = accountProof();
      PreseededActorAssignmentOwnerEvidence changedTarget = changedTarget(changedField);
      when(accountClient.resolveRuntimeAccountIdentity(
              ACCOUNT_UUID.toString(), ASSIGNMENT_UUID.toString()))
          .thenReturn(accountProof);
      when(ownerPort.resolveCurrentEligibleTarget(request(), accountProof, stagingEvidence()))
          .thenReturn(changedTarget);
      PreseededActorAssignmentService service =
          new PreseededActorAssignmentService(
              runAuthority, accountClient, stagingPort(), ownerPort, characterRepository);

      assertThatThrownBy(() -> service.assign(request()))
          .as("changed expected target field index %s", changedField)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("PRESEEDED_ASSIGNMENT_OWNER_TARGET_MISMATCH");

      verify(runAuthority, never()).requireTargetBoundAuthorized(any(), any(), any());
      verifyNoInteractions(characterRepository);
    }
  }

  @Test
  void exactReplayReturnsStoredActorOnlyAfterGrantAndOwnerChecksForCompleteTuple() {
    RunOwnedPreseededAssignmentAuthority runAuthority =
        mock(RunOwnedPreseededAssignmentAuthority.class);
    PreseededActorAccountIdentityPort accountClient = mock(PreseededActorAccountIdentityPort.class);
    PreseededActorAssignmentOwnerEvidencePort ownerPort =
        mock(PreseededActorAssignmentOwnerEvidencePort.class);
    CharacterRepository characterRepository = mock(CharacterRepository.class);
    PreseededActorAssignmentRequest exactRequest = request();
    RuntimeAccountIdentityEvidence accountProof = accountProof();
    PreseededActorAssignmentOwnerEvidence exactTarget = ownerEvidence();
    PreseededActorAssignmentResult storedReplay =
        new PreseededActorAssignmentResult(
            ASSIGNMENT_UUID,
            PreseededActorAssignmentResult.Outcome.ASSIGNED,
            CHARACTER_UUID,
            "a".repeat(64));
    when(accountClient.resolveRuntimeAccountIdentity(
            ACCOUNT_UUID.toString(), ASSIGNMENT_UUID.toString()))
        .thenReturn(accountProof);
    when(ownerPort.resolveCurrentEligibleTarget(exactRequest, accountProof, stagingEvidence()))
        .thenReturn(exactTarget);
    when(characterRepository.assignPreseededActor(
            eq(exactRequest), eq(exactTarget), eq(accountProof), any(String.class)))
        .thenReturn(storedReplay);
    PreseededActorAssignmentService service =
        new PreseededActorAssignmentService(
            runAuthority, accountClient, stagingPort(), ownerPort, characterRepository);

    assertThat(service.assign(exactRequest)).isEqualTo(storedReplay);
    assertThat(service.assign(exactRequest)).isEqualTo(storedReplay);

    verify(runAuthority, times(2))
        .requireAuthorized(
            exactRequest, RunOwnedPreseededAssignmentAuthority.Action.PRESEEDED_ACTOR_ASSIGNMENT);
    verify(runAuthority, times(2))
        .requireTargetBoundAuthorized(
            exactRequest,
            exactTarget,
            RunOwnedPreseededAssignmentAuthority.Action.PRESEEDED_ACTOR_ASSIGNMENT);
    verify(characterRepository, times(2))
        .assignPreseededActor(
            eq(exactRequest), eq(exactTarget), eq(accountProof), any(String.class));
  }

  @Test
  void intentDigestBindsFullTargetAndPayloadButIgnoresEligibilityRefreshEvidence() {
    PreseededActorAssignmentRequest request = request();
    PreseededActorAssignmentOwnerEvidence baseline = ownerEvidence();
    PreseededActorAssignmentOwnerEvidence reevaluated =
        new PreseededActorAssignmentOwnerEvidence(
            baseline.assignmentUuid(),
            baseline.canonicalAccountUuid(),
            baseline.eligibility(),
            Instant.parse("2026-10-04T00:05:00Z"),
            10L,
            "4".repeat(64),
            baseline.canonicalTenantUuid(),
            baseline.realmUuid(),
            baseline.worldSlug(),
            baseline.realmSlug(),
            "game-instance",
            baseline.catalogRevision(),
            baseline.canonicalVersionUuid(),
            baseline.frozenPolicyDigest(),
            baseline.playableStateNamespaceId(),
            baseline.playableStateScope(),
            baseline.entryPolicy(),
            baseline.accountPurpose(),
            baseline.accountCurrentness(),
            baseline.accountAuthoritySnapshotDigest(),
            baseline.publishedOwnerProofDigest(),
            baseline.publishedReleaseBundleRef());
    PreseededActorAssignmentOwnerEvidence changedCatalog =
        new PreseededActorAssignmentOwnerEvidence(
            baseline.assignmentUuid(),
            baseline.canonicalAccountUuid(),
            baseline.eligibility(),
            baseline.eligibilityEvaluatedAt(),
            baseline.membershipAuthorityGeneration(),
            baseline.eligibilityEvidenceDigest(),
            baseline.canonicalTenantUuid(),
            baseline.realmUuid(),
            baseline.worldSlug(),
            baseline.realmSlug(),
            "game-instance",
            baseline.catalogRevision() + 1,
            baseline.canonicalVersionUuid(),
            baseline.frozenPolicyDigest(),
            baseline.playableStateNamespaceId(),
            baseline.playableStateScope(),
            baseline.entryPolicy(),
            baseline.accountPurpose(),
            baseline.accountCurrentness(),
            baseline.accountAuthoritySnapshotDigest(),
            baseline.publishedOwnerProofDigest(),
            baseline.publishedReleaseBundleRef());
    PreseededActorAssignmentOwnerEvidence changedScope =
        new PreseededActorAssignmentOwnerEvidence(
            baseline.assignmentUuid(),
            baseline.canonicalAccountUuid(),
            baseline.eligibility(),
            baseline.eligibilityEvaluatedAt(),
            baseline.membershipAuthorityGeneration(),
            baseline.eligibilityEvidenceDigest(),
            baseline.canonicalTenantUuid(),
            baseline.realmUuid(),
            baseline.worldSlug(),
            baseline.realmSlug(),
            "game-instance",
            baseline.catalogRevision(),
            baseline.canonicalVersionUuid(),
            baseline.frozenPolicyDigest(),
            baseline.playableStateNamespaceId(),
            PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED,
            baseline.entryPolicy(),
            baseline.accountPurpose(),
            baseline.accountCurrentness(),
            baseline.accountAuthoritySnapshotDigest(),
            baseline.publishedOwnerProofDigest(),
            baseline.publishedReleaseBundleRef());
    PreseededActorAssignmentRequest changedPayload =
        new PreseededActorAssignmentRequest(
            ASSIGNMENT_UUID,
            ACCOUNT_UUID,
            new PreseededActorCorePayload(
                PreseededActorCorePayload.ActorKind.PLAYER, "Changed Actor"),
            request.expectedTarget());
    PreseededActorAssignmentRequest changedAccount =
        new PreseededActorAssignmentRequest(
            ASSIGNMENT_UUID,
            UUID.fromString("80000000-0000-4000-8000-000000000008"),
            request.corePayload(),
            request.expectedTarget());

    String digest = PreseededActorAssignmentIntentDigest.compute(request, baseline);
    assertThat(PreseededActorAssignmentIntentDigest.compute(request, reevaluated))
        .isEqualTo(digest);
    assertThat(PreseededActorAssignmentIntentDigest.compute(request, changedCatalog))
        .isNotEqualTo(digest);
    assertThat(PreseededActorAssignmentIntentDigest.compute(request, changedScope))
        .isNotEqualTo(digest);
    assertThat(PreseededActorAssignmentIntentDigest.compute(request, changedTarget(4)))
        .isNotEqualTo(digest);
    assertThat(PreseededActorAssignmentIntentDigest.compute(request, changedTarget(10)))
        .isNotEqualTo(digest);
    assertThat(PreseededActorAssignmentIntentDigest.compute(changedPayload, baseline))
        .isNotEqualTo(digest);
    assertThat(PreseededActorAssignmentIntentDigest.compute(changedAccount, baseline))
        .isNotEqualTo(digest);
  }

  private static PreseededActorAssignmentRequest request() {
    return new PreseededActorAssignmentRequest(
        ASSIGNMENT_UUID,
        ACCOUNT_UUID,
        new PreseededActorCorePayload(PreseededActorCorePayload.ActorKind.PLAYER, "Assigned Actor"),
        expectedTarget());
  }

  private static PreseededActorAssignmentExpectedTarget expectedTarget() {
    return new PreseededActorAssignmentExpectedTarget(
        TENANT_UUID,
        REALM_UUID,
        "world",
        "realm",
        "game-instance",
        4L,
        UUID.fromString("17000000-0000-4000-8000-000000000017"),
        "2".repeat(64),
        NAMESPACE_UUID,
        "release-bundle/test",
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
  }

  private static PreseededActorAssignmentOwnerEvidence changedTarget(int field) {
    PreseededActorAssignmentOwnerEvidence baseline = ownerEvidence();
    return new PreseededActorAssignmentOwnerEvidence(
        baseline.assignmentUuid(),
        baseline.canonicalAccountUuid(),
        baseline.eligibility(),
        baseline.eligibilityEvaluatedAt(),
        baseline.membershipAuthorityGeneration(),
        baseline.eligibilityEvidenceDigest(),
        field == 0
            ? UUID.fromString("80000000-0000-4000-8000-000000000008")
            : baseline.canonicalTenantUuid(),
        field == 1 ? UUID.fromString("90000000-0000-4000-8000-000000000009") : baseline.realmUuid(),
        field == 2 ? "other-world" : baseline.worldSlug(),
        field == 3 ? "other-realm" : baseline.realmSlug(),
        field == 4 ? "other-game-instance" : baseline.gameInstanceId(),
        field == 5 ? baseline.catalogRevision() + 1 : baseline.catalogRevision(),
        field == 6
            ? UUID.fromString("18000000-0000-4000-8000-000000000018")
            : baseline.canonicalVersionUuid(),
        field == 7 ? "3".repeat(64) : baseline.frozenPolicyDigest(),
        field == 8
            ? UUID.fromString("a0000000-0000-4000-8000-00000000000a")
            : baseline.playableStateNamespaceId(),
        field == 10
            ? PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED
            : baseline.playableStateScope(),
        baseline.entryPolicy(),
        baseline.accountPurpose(),
        baseline.accountCurrentness(),
        baseline.accountAuthoritySnapshotDigest(),
        baseline.publishedOwnerProofDigest(),
        field == 9 ? "release-bundle/changed" : baseline.publishedReleaseBundleRef());
  }

  private static PreseededActorStagingEligibilityPort stagingPort() {
    return (accountUuid, tenantUuid, assignmentUuid) -> {
      assertThat(accountUuid).isEqualTo(ACCOUNT_UUID.toString());
      assertThat(tenantUuid).isEqualTo(TENANT_UUID.toString());
      assertThat(assignmentUuid).isEqualTo(ASSIGNMENT_UUID.toString());
      return stagingEvidence();
    };
  }

  private static AccountActorStagingEligibilityEvidence stagingEvidence() {
    return stagingEvidence(true);
  }

  private static AccountActorStagingEligibilityEvidence stagingEvidence(boolean eligible) {
    return AccountActorStagingEligibilityEvidence.seal(
        "dev",
        ASSIGNMENT_UUID,
        ACCOUNT_UUID,
        TENANT_UUID,
        AccountActorStagingEligibilityEvidence.Purpose.PUBLIC_PRODUCTION_STAGING_ONLY,
        Instant.parse("2026-10-04T00:00:00Z"),
        "ACCOUNT_DATABASE_INSERT",
        eligible ? "ACTIVE" : "DEACTIVATED_PENDING_DELETE",
        eligible ? "ACTIVE" : "INACTIVE",
        eligible,
        "EXPLICIT_JOIN",
        17L,
        9L,
        "FRESH_GAME_DESIGN",
        UUID.fromString("60000000-0000-4000-8000-000000000006"),
        "sha256:" + "a".repeat(64),
        23L,
        UUID.fromString("70000000-0000-4000-8000-000000000007"),
        "sha256:" + "b".repeat(64),
        false);
  }

  private static RuntimeAccountIdentityEvidence accountProof() {
    return new RuntimeAccountIdentityEvidence(
        1, "dev", ASSIGNMENT_UUID, ACCOUNT_UUID, 23L, "ACCOUNT_DATABASE_INSERT");
  }

  private static PreseededActorAssignmentOwnerEvidence ownerEvidence() {
    return new PreseededActorAssignmentOwnerEvidence(
        ASSIGNMENT_UUID,
        ACCOUNT_UUID,
        PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE,
        stagingEvidence().observedAt(),
        stagingEvidence().membershipAuthorityGeneration(),
        stagingEvidence().eligibilityDecisionDigest(),
        TENANT_UUID,
        REALM_UUID,
        "world",
        "realm",
        "game-instance",
        4L,
        UUID.fromString("17000000-0000-4000-8000-000000000017"),
        "2".repeat(64),
        NAMESPACE_UUID,
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        PreseededActorAssignmentOwnerEvidence.PublishedEntryPolicy.PRESEEDED_ONLY,
        PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING,
        PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION,
        stagingEvidence().authoritySnapshotDigest(),
        "4".repeat(64),
        "release-bundle/test");
  }

  private static PreseededActorAssignmentOwnerEvidence ownerEvidenceWithAccountSnapshotDigest(
      String accountSnapshotDigest) {
    PreseededActorAssignmentOwnerEvidence baseline = ownerEvidence();
    return new PreseededActorAssignmentOwnerEvidence(
        baseline.assignmentUuid(),
        baseline.canonicalAccountUuid(),
        baseline.eligibility(),
        baseline.eligibilityEvaluatedAt(),
        baseline.membershipAuthorityGeneration(),
        baseline.eligibilityEvidenceDigest(),
        baseline.canonicalTenantUuid(),
        baseline.realmUuid(),
        baseline.worldSlug(),
        baseline.realmSlug(),
        baseline.gameInstanceId(),
        baseline.catalogRevision(),
        baseline.canonicalVersionUuid(),
        baseline.frozenPolicyDigest(),
        baseline.playableStateNamespaceId(),
        baseline.playableStateScope(),
        baseline.entryPolicy(),
        baseline.accountPurpose(),
        baseline.accountCurrentness(),
        accountSnapshotDigest,
        baseline.publishedOwnerProofDigest(),
        baseline.publishedReleaseBundleRef());
  }
}
