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
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import net.firedevops.firemud.common.authoring.WorldOriginalDraftGraphApplyClient;
import net.firedevops.firemud.common.authoring.WorldOriginalDraftGraphApplyEvidence;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.AppliedEpoch;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.CommitSnapshot;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerOutcome;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerState;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerStatus;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.WorkflowState;
import net.firedevops.firemud.gamedesign.publication.AssetSnapshot;
import net.firedevops.firemud.gamedesign.publication.CommandSnapshot;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySnapshot;
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
    verify(coordinator, never()).releaseApplicationSlot(any());
    verifyNoInteractions(world);
    verify(sources, never()).apply(any());
  }

  @Test
  void occupiedSlotDeniesBeforeAccountOrSourceApplication() {
    doThrow(new DraftCommitCoordinatorRepository.DraftCommitStateConflictException("slot denied"))
        .when(coordinator)
        .claimApplicationSlot(any());
    assertThatThrownBy(() -> service.commit(original(), "creator.credential"))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);
    verifyNoInteractions(account, world);
    verify(sources, never()).apply(any());
    verify(coordinator, never()).releaseApplicationSlot(any());
  }

  @Test
  void missingAccountEvidenceDoesNotAuthorizeLocalApplication() {
    assertThatThrownBy(() -> service.commit(original(), "creator.credential"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("authenticated original Account order");
    verifyNoInteractions(world);
    verify(sources, never()).apply(any());
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
    verify(sources).validateInput(any());
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

  @Test
  void changedAccountResponseCannotAuthorizeOwnerEffects() {
    var original = original();
    arrange(original, false);
    var order = mock(AccountOriginalDraftOrderEvidence.class);
    when(order.matches(any())).thenReturn(false);
    when(account.claim(any())).thenReturn(order);
    assertThatThrownBy(() -> service.commit(original, "creator.credential"))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(world);
    verify(sources, never()).apply(any());
    verify(coordinator, never()).releaseApplicationSlot(any());
  }

  @Test
  void localFailureCannotDispatchWorldOrAdvanceVisibility() {
    var original = original();
    arrange(original, false);
    doThrow(new IllegalStateException("source denied")).when(sources).apply(any());
    assertThatThrownBy(() -> service.commit(original, "creator.credential"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("source denied");
    verifyNoInteractions(world);
    verify(coordinator, never()).advanceSourceVisibilityFence(any(), any());
    verify(coordinator, never()).releaseApplicationSlot(any());
  }

  @Test
  void unresolvedLocalApplicationRequiresRecoveryBeforeWorldDispatch() {
    var original = original();
    var states = arrange(original, false);
    states.put(
        Owner.GAME_DESIGN_CONTROL_PLANE,
        state(Owner.GAME_DESIGN_CONTROL_PLANE, OwnerStatus.UNKNOWN,
            unknownOutcome(new GameDesignDraftTerminalOperation(original).gameDesignBinding(),
                Owner.GAME_DESIGN_CONTROL_PLANE)));
    assertThatThrownBy(() -> service.commit(original, "creator.credential"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact recovery");
    verifyNoInteractions(world);
    verify(sources, never()).apply(any());
  }

  @Test
  void unknownWorldApplicationCanRecoverThroughExactApply() {
    var original = original();
    var states = arrange(original, false);
    states.put(Owner.WORLD_MANAGEMENT, state(Owner.WORLD_MANAGEMENT, OwnerStatus.UNKNOWN,
            unknownOutcome(new GameDesignDraftTerminalOperation(original).gameDesignBinding(),
                Owner.WORLD_MANAGEMENT)));
    when(world.apply(any())).thenAnswer(invocation -> successfulWorld(invocation.getArgument(0)));
    assertThat(service.commit(original, "creator.credential").result())
        .isEqualTo(GameDesignDraftTerminalOutcome.Result.COMMITTED);
    verify(world).apply(any());
    verify(coordinator).releaseApplicationSlot(any());
  }

  @Test
  void lostWorldReplyRecoversOriginalResultWithoutReapplyingLocalSource() {
    var original = original();
    arrange(original, false);
    var retained =
        new java.util.concurrent.atomic.AtomicReference<
            WorldOriginalDraftGraphApplyEvidence.Result>();
    when(world.apply(any()))
        .thenAnswer(
            invocation -> {
              var request =
                  (WorldOriginalDraftGraphApplyEvidence.Request) invocation.getArgument(0);
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              if (retained.get() == null) {
                retained.set(successfulWorld(request));
                throw io.grpc.Status.UNAVAILABLE.asRuntimeException();
              }
              assertThat(request).isEqualTo(retained.get().request());
              return retained.get();
            });
    assertThatThrownBy(() -> service.commit(original, "creator.credential"))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
    assertThat(service.commit(original, "creator.credential").result())
        .isEqualTo(GameDesignDraftTerminalOutcome.Result.COMMITTED);
    verify(sources).apply(any());
    verify(world, times(2)).apply(any());
    verify(coordinator).advanceSourceVisibilityFence(any(), any());
  }

  @Test
  void lostFinalCommitReplyRecoversTerminalWithoutReauthorizingOrReapplying() {
    var original = original();
    arrange(original, true);
    var commits = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(
            ignored -> {
              TransactionSynchronizationManager.setActualTransactionActive(false);
              if (commits.incrementAndGet() == 4)
                throw new IllegalStateException("committed reply lost");
              return null;
            })
        .when(transactions)
        .commit(any());
    assertThatThrownBy(() -> service.commit(original, "creator.credential"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("committed reply lost");
    assertThat(service.commit(original, null).result())
        .isEqualTo(GameDesignDraftTerminalOutcome.Result.COMMITTED);
    verify(account).claim(any());
    verify(sources).validateInput(any());
    verify(sources).apply(any());
    verify(coordinator).advanceSourceVisibilityFence(any(), any());
    verifyNoInteractions(world);
  }

  @Test
  void emptyAppliedEpochVectorIsRejectedByCanonicalOutcomeConstructor() {
    var binding = new GameDesignDraftTerminalOperation(original()).gameDesignBinding();
    assertThatThrownBy(() -> new OwnerOutcome(
        Owner.GAME_DESIGN_CONTROL_PLANE, OwnerStatus.APPLIED, binding.commitId(), binding.digest(),
        "mock", new byte[] {1}, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete tuple vector");
    verifyNoInteractions(account, world, coordinator, terminals, sources, transactions);
  }

  @Test
  void omittedOrChangedRetainedOwnerEpochsCannotDispatchOrSynchronize() {
    var original = originalWithTwoLocalScopes();
    var states = arrange(original, true);
    var binding = new GameDesignDraftTerminalOperation(original).gameDesignBinding();
    var expected = outcome(binding, Owner.GAME_DESIGN_CONTROL_PLANE).appliedEpochs();
    var first = expected.getFirst();
    for (var epochs : List.of(
        List.of(first),
        List.of(new AppliedEpoch(first.aggregateType(), first.aggregateId(), first.scopeType(),
            first.scopeId(), "1", "2"), expected.get(1)))) {
      states.put(Owner.GAME_DESIGN_CONTROL_PLANE,
          state(Owner.GAME_DESIGN_CONTROL_PLANE, OwnerStatus.APPLIED,
              new OwnerOutcome(Owner.GAME_DESIGN_CONTROL_PLANE, OwnerStatus.APPLIED,
                  binding.commitId(), binding.digest(), "mock", new byte[] {1}, epochs)));
      assertThatThrownBy(() -> service.commit(original, "creator.credential"))
          .isInstanceOf(IllegalStateException.class);
    }
    verifyNoInteractions(world);
    verify(sources, never()).apply(any());
    verify(coordinator, never()).advanceSourceVisibilityFence(any(), any());
    verify(coordinator, never()).releaseApplicationSlot(any());
  }

  @Test
  void changedTerminalOperationCannotReplay() {
    var outcome = mock(GameDesignDraftTerminalOutcome.class);
    when(outcome.operation()).thenReturn(new GameDesignDraftTerminalOperation(original()));
    when(terminals.read(any(GameDesignDraftTerminalOperation.class)))
        .thenReturn(Optional.of(outcome));
    assertThatThrownBy(() -> service.commit(original(), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("operation conflict");
    verifyNoInteractions(account, world, coordinator, sources);
  }

  private static WorldOriginalDraftGraphApplyEvidence.Result successfulWorld(
      WorldOriginalDraftGraphApplyEvidence.Request request) {
    // Synthetic historical owner bytes validate correlation, never fresh graph authority.
    return new WorldOriginalDraftGraphApplyEvidence.Result(
        request,
        AuthoringFixtures.committedReadback(
            WorldDraftTerminalReadEvidence.Request.create(
                request.targetNamespace(), request.originalAccountBinding())));
  }

  @Test
  void typedScopeRefusalPrecedesReservationsAndRemoteAuthority() {
    doThrow(new IllegalArgumentException("scope denied")).when(sources).validateInput(any());
    assertThatThrownBy(() -> service.commit(original(), "creator.credential"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("scope denied");
    verifyNoInteractions(account, world, coordinator);
    verify(sources, never()).apply(any());
  }

  @Test
  void pureSourceValidatorRejectsUntypedInputWithoutStorageOrTransaction() {
    var sql = mock(org.jooq.DSLContext.class);
    var repository = new GameDesignSourceRepository(sql);
    var binding = new GameDesignDraftTerminalOperation(original()).gameDesignBinding();
    assertThatThrownBy(() -> repository.validateInput(binding))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(sql);
  }

  @Test
  void pureSourceValidatorChecksTheCompleteTypedContainingScope() {
    var sql = mock(org.jooq.DSLContext.class);
    var repository = new GameDesignSourceRepository(sql);
    var base = new GameDesignDraftTerminalOperation(original()).gameDesignBinding();
    var revision =
        new DraftCommitBinding.RevisionPayload(
            "0",
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "{\"schemaVersion\":1,\"revisionKind\":\"COMMAND_DEFINITION\",\"operation\":\"DELETE\",\"commandId\":\"look\"}");
    var properScope =
        new DraftCommitBinding.AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "COMMAND_DEFINITION_SET",
            base.target().canonicalVersionId().toString(),
            "COMMAND_DEFINITION_SET",
            "effective",
            "0");
    var valid =
        DraftCommitBinding.create(
            base.target(),
            base.requestId(),
            base.commitId(),
            base.baseCommitId(),
            List.of(revision),
            List.of(properScope));
    repository.validateInput(valid);
    var incomplete =
        DraftCommitBinding.create(
            base.target(),
            base.requestId(),
            base.commitId(),
            base.baseCommitId(),
            List.of(revision),
            base.affectedUnits(Owner.GAME_DESIGN_CONTROL_PLANE));
    assertThatThrownBy(() -> repository.validateInput(incomplete))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scopes");
    verifyNoInteractions(sql);
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
    var policy = mock(RealmPolicySnapshot.class);
    when(policy.binding()).thenReturn(binding);
    when(synchronizedSources.policy()).thenReturn(policy);
    var asset = mock(AssetSnapshot.class);
    when(asset.binding()).thenReturn(binding);
    when(synchronizedSources.asset()).thenReturn(asset);
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

  private static OwnerOutcome unknownOutcome(DraftCommitBinding binding, Owner owner) {
    return new OwnerOutcome(owner, OwnerStatus.UNKNOWN, binding.commitId(), binding.digest(),
        null, null, List.of());
  }

  private static DraftAuthorizationFenceBinding originalWithTwoLocalScopes() {
    var original = original();
    var binding = new GameDesignDraftTerminalOperation(original).gameDesignBinding();
    var units = new java.util.ArrayList<>(binding.affectedUnits());
    units.add(new DraftCommitBinding.AffectedUnit(Owner.GAME_DESIGN_CONTROL_PLANE,
        "MOCK_SCOPE", binding.target().canonicalVersionId().toString(), "MOCK_SCOPE", "other", "0"));
    var complete = DraftCommitBinding.create(binding.target(), binding.requestId(), binding.commitId(),
        binding.baseCommitId(), binding.revisions(), units);
    return new DraftAuthorizationFenceBinding(original.operationId(), complete.requestId(),
        complete.commitId(), original.fenceId(), original.actorAccountId(), original.tenantId(),
        original.versionId(), original.baseCommitId(), original.expectedDraftEpoch(),
        complete.canonicalBytes(), complete.canonicalBytes(), complete.digest(), original.sources())
        .withRequiredOwners();
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
