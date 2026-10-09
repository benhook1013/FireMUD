package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderClient;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.WorldOriginalDraftGraphApplyClient;
import net.firedevops.firemud.common.authoring.WorldOriginalDraftGraphApplyEvidence;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.AppliedEpoch;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.CommitSnapshot;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerOutcome;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerState;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerStatus;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.WorkflowState;
import net.firedevops.firemud.gamedesign.publication.CommandSnapshot;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Mock control-flow proof only; typed source, storage and concurrent execution need physical proof.
 */
class GameDesignWorldSourceCommitServiceTest {
  private final AccountOriginalDraftOrderClient account =
      mock(AccountOriginalDraftOrderClient.class);
  private final WorldOriginalDraftGraphApplyClient world =
      mock(WorldOriginalDraftGraphApplyClient.class);
  private final DraftCommitCoordinatorRepository coordinator =
      mock(DraftCommitCoordinatorRepository.class);
  private final GameDesignDraftTerminalOutcomeRepository terminals =
      mock(GameDesignDraftTerminalOutcomeRepository.class);
  private final GameDesignSourceRepository sources = mock(GameDesignSourceRepository.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final GameDesignWorldSourceCommitService service =
      new GameDesignWorldSourceCommitService(
          account, world, coordinator, terminals, sources, transactions, "test");

  @AfterEach
  void clear() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void missingAndExtraOwnersAreDeniedBeforeReservation() {
    for (var owners :
        List.of(
            List.of(Owner.GAME_DESIGN_CONTROL_PLANE),
            List.of(Owner.WORLD_MANAGEMENT),
            List.of(
                Owner.WORLD_MANAGEMENT,
                Owner.ENTITY_MANAGEMENT,
                Owner.GAME_DESIGN_CONTROL_PLANE))) {
      assertThatThrownBy(() -> service.commit(original(owners), "creator.credential"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(account, world, coordinator, terminals, sources, transactions);
  }

  @Test
  void historicalAccountOwnerVectorCannotBeSilentlyReplaced() {
    var current = original(List.of(Owner.GAME_DESIGN_CONTROL_PLANE));
    var historical =
        new DraftAuthorizationFenceBinding(
            current.operationId(),
            current.requestId(),
            current.commitId(),
            current.fenceId(),
            current.actorAccountId(),
            current.tenantId(),
            current.versionId(),
            current.baseCommitId(),
            current.expectedDraftEpoch(),
            current.gameDesignBinding(),
            current.normalizedInput(),
            current.inputDigest(),
            current.sources());
    // Historical v1 requires both Account owners even though this Draft binding is local-only.
    assertThat(historical.requiredOwners())
        .containsExactly(
            DraftAuthorizationFenceBinding.Owner.GAME_DESIGN,
            DraftAuthorizationFenceBinding.Owner.WORLD);
    assertThatThrownBy(() -> service.commit(historical, "creator.credential"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(account, world, coordinator, terminals, sources, transactions);
  }

  @Test
  void ambientTransactionOrSynchronizationDeniesBeforeAnyRemoteCall() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> service.commit(original(), "creator.credential"))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> service.commit(original(), "creator.credential"))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(account, world, coordinator, terminals, sources, transactions);
  }

  @Test
  void accountFailureRetainsReservationWithoutInventingAbort() {
    var original = original();
    when(account.claim(any())).thenThrow(io.grpc.Status.UNAVAILABLE.asRuntimeException());
    assertThatThrownBy(() -> service.commit(original, "creator.credential"))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
    var sequence = inOrder(coordinator, account);
    sequence.verify(coordinator).lockVersionTarget(any());
    sequence.verify(coordinator).claim(any(), any());
    sequence.verify(coordinator).claimApplicationSlot(any());
    sequence.verify(account).claim(any());
    verify(coordinator, never()).abortUnattemptedLocalSource(any());
    verify(coordinator, never()).releaseApplicationSlot(any());
    verifyNoInteractions(sources, world);
  }

  @Test
  void occupiedSlotDeniesBeforeAccountOrSourceApplication() {
    doThrow(new DraftCommitCoordinatorRepository.DraftCommitStateConflictException("slot denied"))
        .when(coordinator)
        .claimApplicationSlot(any());
    assertThatThrownBy(() -> service.commit(original(), "creator.credential"))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);
    verifyNoInteractions(account, world, sources);
    verify(coordinator, never()).releaseApplicationSlot(any());
  }

  @Test
  void missingAccountEvidenceDoesNotAuthorizeLocalApplication() {
    assertThatThrownBy(() -> service.commit(original(), "creator.credential"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("authenticated original Account order");
    verifyNoInteractions(world, sources);
    verify(coordinator, never()).abortUnattemptedLocalSource(any());
    verify(coordinator, never()).releaseApplicationSlot(any());
  }

  @Test
  void worldFailureKeepsLocalAppliedAndRetryDoesNotReapplyLocalSource() {
    var original = original();
    var state = arrange(original, false);
    when(world.apply(any()))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              throw io.grpc.Status.UNAVAILABLE.asRuntimeException();
            });
    for (int retry = 0; retry < 2; retry++) {
      assertThatThrownBy(() -> service.commit(original, "creator.credential"))
          .isInstanceOf(io.grpc.StatusRuntimeException.class);
    }
    assertThat(state.get(Owner.GAME_DESIGN_CONTROL_PLANE).status()).isEqualTo(OwnerStatus.APPLIED);
    assertThat(state.get(Owner.WORLD_MANAGEMENT).status()).isEqualTo(OwnerStatus.IN_PROGRESS);
    verify(sources).apply(any());
    verify(world, times(2)).apply(any());
    verify(coordinator, never()).advanceSourceVisibilityFence(any(), any());
    verify(coordinator, never()).releaseApplicationSlot(any());
    verify(coordinator, never()).abortUnattemptedLocalSource(any());
    var sequence = inOrder(sources, coordinator, world);
    sequence.verify(sources).apply(any());
    sequence
        .verify(coordinator)
        .markOwnerInProgress(any(), org.mockito.ArgumentMatchers.eq(Owner.WORLD_MANAGEMENT));
    sequence.verify(world).apply(any());
  }

  @Test
  void substitutedWorldRequestCannotAdvanceVisibility() {
    var original = original();
    arrange(original, false);
    var substituted = mock(WorldOriginalDraftGraphApplyEvidence.Result.class);
    when(substituted.request())
        .thenReturn(
            WorldOriginalDraftGraphApplyEvidence.Request.create(
                "test", original().canonicalBytes()));
    when(world.apply(any())).thenReturn(substituted);
    assertThatThrownBy(() -> service.commit(original, "creator.credential"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete original request");
    verify(coordinator, never()).recordOwnerOutcome(any(), any());
    verify(coordinator, never()).advanceSourceVisibilityFence(any(), any());
    verify(coordinator, never()).releaseApplicationSlot(any());
  }

  @Test
  void malformedWorldCommittedBytesAreRevalidatedEvenFromInjectedClient() {
    var original = original();
    arrange(original, false);
    var response = mock(WorldOriginalDraftGraphApplyEvidence.Result.class);
    when(response.request())
        .thenReturn(
            WorldOriginalDraftGraphApplyEvidence.Request.create("test", original.canonicalBytes()));
    when(response.ownerReadback())
        .thenReturn(
            new DraftAuthorizationFenceBinding.OwnerReadback(
                DraftAuthorizationFenceBinding.Owner.WORLD,
                DraftAuthorizationFenceBinding.Outcome.COMMITTED,
                original.operationId(),
                original.commitId(),
                original.fenceId(),
                original.inputDigest(),
                original.canonicalBytes(),
                new byte[] {1}));
    when(world.apply(any())).thenReturn(response);
    assertThatThrownBy(() -> service.commit(original, "creator.credential"))
        .isInstanceOf(IllegalArgumentException.class);
    verify(coordinator, never()).recordOwnerOutcome(any(), any());
    verify(coordinator, never()).releaseApplicationSlot(any());
  }

  @Test
  void retainedWorldOutcomeCompletesFullFenceAndFinalRetrySkipsAuthorityAndWrites() {
    var original = original();
    arrange(original, true);
    var first = service.commit(original, "creator.credential");
    assertThat(first.result()).isEqualTo(GameDesignDraftTerminalOutcome.Result.COMMITTED);
    assertThat(service.commit(original, null)).isSameAs(first);
    verify(account).claim(any());
    verify(sources).apply(any());
    verify(coordinator).recordOwnerOutcome(any(), any());
    verify(coordinator).advanceSourceVisibilityFence(any(), any());
    verify(coordinator).releaseApplicationSlot(any());
    verifyNoInteractions(world);
  }

  @Test
  void exactAbortedTerminalIsReturnedBeforeCreatorOrRemoteAuthority() {
    var original = original();
    var outcome = mock(GameDesignDraftTerminalOutcome.class);
    when(outcome.operation()).thenReturn(new GameDesignDraftTerminalOperation(original));
    when(outcome.result()).thenReturn(GameDesignDraftTerminalOutcome.Result.DEFINITIVELY_ABORTED);
    when(terminals.read(any(GameDesignDraftTerminalOperation.class)))
        .thenReturn(Optional.of(outcome));
    assertThat(service.commit(original, null)).isSameAs(outcome);
    verifyNoInteractions(account, world, coordinator, sources);
  }

  @Test
  void committedTerminalWithoutSynchronizedSourceBackingCannotReplaySuccess() {
    var original = original();
    var outcome = mock(GameDesignDraftTerminalOutcome.class);
    when(outcome.operation()).thenReturn(new GameDesignDraftTerminalOperation(original));
    when(outcome.result()).thenReturn(GameDesignDraftTerminalOutcome.Result.COMMITTED);
    when(terminals.read(any(GameDesignDraftTerminalOperation.class)))
        .thenReturn(Optional.of(outcome));
    assertThatThrownBy(() -> service.commit(original, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Synchronized source readback unavailable");
    verifyNoInteractions(account, world, coordinator);
  }

  private EnumMap<Owner, OwnerState> arrange(
      DraftAuthorizationFenceBinding original, boolean worldApplied) {
    var binding = new GameDesignDraftTerminalOperation(original).gameDesignBinding();
    var states = new EnumMap<Owner, OwnerState>(Owner.class);
    states.put(
        Owner.GAME_DESIGN_CONTROL_PLANE,
        state(Owner.GAME_DESIGN_CONTROL_PLANE, OwnerStatus.NOT_ATTEMPTED, null));
    var worldOutcome = outcome(binding, Owner.WORLD_MANAGEMENT);
    states.put(
        Owner.WORLD_MANAGEMENT,
        state(
            Owner.WORLD_MANAGEMENT,
            worldApplied ? OwnerStatus.APPLIED : OwnerStatus.NOT_ATTEMPTED,
            worldApplied ? worldOutcome : null));
    var completed = new AtomicBoolean();
    var terminal = mock(GameDesignDraftTerminalOutcome.class);
    when(terminal.operation()).thenReturn(new GameDesignDraftTerminalOperation(original));
    when(terminal.result()).thenReturn(GameDesignDraftTerminalOutcome.Result.COMMITTED);
    when(terminals.read(any(GameDesignDraftTerminalOperation.class)))
        .thenAnswer(ignored -> completed.get() ? Optional.of(terminal) : Optional.empty());
    when(coordinator.claim(any(), any())).thenAnswer(ignored -> snapshot(binding, states));
    when(coordinator.read(any(), any()))
        .thenAnswer(ignored -> Optional.of(snapshot(binding, states)));
    when(coordinator.markOwnerInProgress(any(), any()))
        .thenAnswer(
            invocation -> {
              Owner owner = invocation.getArgument(1);
              var updated = state(owner, OwnerStatus.IN_PROGRESS, null);
              states.put(owner, updated);
              return updated;
            });
    when(sources.apply(any()))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              var local = outcome(binding, Owner.GAME_DESIGN_CONTROL_PLANE);
              states.put(
                  Owner.GAME_DESIGN_CONTROL_PLANE, state(local.owner(), local.status(), local));
              var applied = mock(GameDesignSourceRepository.Application.class);
              when(applied.ownerOutcome()).thenReturn(local);
              return applied;
            });
    when(coordinator.recordOwnerOutcome(any(), any()))
        .thenAnswer(
            invocation -> {
              OwnerOutcome actual = invocation.getArgument(1);
              var retained = state(actual.owner(), actual.status(), actual);
              states.put(actual.owner(), retained);
              return retained;
            });
    when(coordinator.advanceSourceVisibilityFence(any(), any()))
        .thenAnswer(
            ignored -> {
              assertThat(states.values()).allMatch(value -> value.status() == OwnerStatus.APPLIED);
              completed.set(true);
              return null;
            });
    var synchronizedSources = mock(GameDesignSourceRepository.SynchronizedSources.class);
    var command = mock(CommandSnapshot.class);
    when(command.binding()).thenReturn(binding);
    when(synchronizedSources.command()).thenReturn(command);
    when(sources.readSynchronized(any(), any())).thenReturn(Optional.of(synchronizedSources));
    var order = mock(AccountOriginalDraftOrderEvidence.class);
    when(order.matches(any())).thenReturn(true);
    when(account.claim(any()))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return order;
            });
    when(transactions.getTransaction(any()))
        .thenAnswer(
            ignored -> {
              TransactionSynchronizationManager.setActualTransactionActive(true);
              return mock(TransactionStatus.class);
            });
    doAnswer(
            ignored -> {
              TransactionSynchronizationManager.setActualTransactionActive(false);
              return null;
            })
        .when(transactions)
        .commit(any());
    doAnswer(
            ignored -> {
              TransactionSynchronizationManager.setActualTransactionActive(false);
              return null;
            })
        .when(transactions)
        .rollback(any());
    return states;
  }

  private static CommitSnapshot snapshot(
      DraftCommitBinding binding, EnumMap<Owner, OwnerState> states) {
    var now = OffsetDateTime.now();
    return new CommitSnapshot(binding, WorkflowState.APPLYING, now, now, states);
  }

  private static OwnerState state(Owner owner, OwnerStatus status, OwnerOutcome outcome) {
    var now = OffsetDateTime.now();
    return new OwnerState(owner, status, Optional.ofNullable(outcome), now, now);
  }

  private static OwnerOutcome outcome(DraftCommitBinding binding, Owner owner) {
    return new OwnerOutcome(
        owner,
        OwnerStatus.APPLIED,
        binding.commitId(),
        binding.digest(),
        "mock-owner-result",
        new byte[] {1},
        binding.affectedUnits(owner).stream()
            .map(
                unit ->
                    new AppliedEpoch(
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch(),
                        "1"))
            .toList());
  }

  private static DraftAuthorizationFenceBinding original() {
    return original(List.of(Owner.GAME_DESIGN_CONTROL_PLANE, Owner.WORLD_MANAGEMENT));
  }

  private static DraftAuthorizationFenceBinding original(List<Owner> owners) {
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "tenant-key", 2, "tenant-key", "NEW_GAME_ROW");
    var revisions = new java.util.ArrayList<DraftCommitBinding.RevisionPayload>();
    var units = new java.util.ArrayList<DraftCommitBinding.AffectedUnit>();
    for (var owner : owners) {
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(revisions.size()), UUID.randomUUID(), owner, "{}"));
      units.add(
          new DraftCommitBinding.AffectedUnit(
              owner,
              "MOCK_SCOPE",
              target.canonicalVersionId().toString(),
              "MOCK_SCOPE",
              "mock",
              "0"));
    }
    var binding =
        DraftCommitBinding.create(
            target, UUID.randomUUID(), UUID.randomUUID(), "base", revisions, units);
    return new DraftAuthorizationFenceBinding(
            UUID.randomUUID(),
            binding.requestId(),
            binding.commitId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            "base",
            "0",
            binding.canonicalBytes(),
            binding.canonicalBytes(),
            binding.digest(),
            List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                    UUID.randomUUID().toString(),
                    "1",
                    "1",
                    "account",
                    "1",
                    new byte[] {1})))
        .withRequiredOwners();
  }
}
