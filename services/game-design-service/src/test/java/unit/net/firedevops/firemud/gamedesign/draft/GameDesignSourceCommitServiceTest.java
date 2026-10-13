package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderClient;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameDesignSourceCommitServiceTest {
  private final AccountOriginalDraftOrderClient account =
      mock(AccountOriginalDraftOrderClient.class);
  private final DraftCommitCoordinatorRepository coordinator =
      mock(DraftCommitCoordinatorRepository.class);
  private final GameDesignDraftTerminalOutcomeRepository terminal =
      mock(GameDesignDraftTerminalOutcomeRepository.class);
  private final GameDesignSourceRepository sources = mock(GameDesignSourceRepository.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final GameDesignSourceCommitService service =
      new GameDesignSourceCommitService(
          account, coordinator, terminal, sources, transactions, "test");

  @AfterEach
  void clear() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void completeMixedOwnerVectorIsDeniedBeforeAnyCaptureOrLocalClaim() {
    assertThatThrownBy(() -> service.commit(original(true), "creator.credential"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Complete Game Design-only owner vector required");
    verifyNoInteractions(account, coordinator, terminal, sources, transactions);
  }

  @Test
  void historicalWorldParticipationCannotBeDroppedFromAnOtherwiseLocalBinding() {
    var local = original(false);
    var historical =
        new DraftAuthorizationFenceBinding(
            local.operationId(),
            local.requestId(),
            local.commitId(),
            local.fenceId(),
            local.actorAccountId(),
            local.tenantId(),
            local.versionId(),
            local.baseCommitId(),
            local.expectedDraftEpoch(),
            local.gameDesignBinding(),
            local.normalizedInput(),
            local.inputDigest(),
            local.sources());
    assertThatThrownBy(() -> service.commit(historical, "creator.credential"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(account, coordinator, terminal, sources, transactions);
  }

  @Test
  void ambientSqlOrSynchronizationCannotEncloseAccountTransport() {
    var local = original(false);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> service.commit(local, "creator.credential"))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> service.commit(local, "creator.credential"))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(account, coordinator, terminal, sources, transactions);
  }

  @Test
  void ineligibleLocalReservationNeverCallsAccount() {
    var original = original(false);
    doThrow(new DraftCommitCoordinatorRepository.DraftCommitStateConflictException("not DRAFT"))
        .when(coordinator)
        .claim(any(), any());
    assertThatThrownBy(() -> service.commit(original, "creator.credential"))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);
    verifyNoInteractions(account, sources);
  }

  @Test
  void occupiedOrSelectedApplicationSlotNeverCallsAccount() {
    var original = original(false);
    doThrow(new DraftCommitCoordinatorRepository.DraftCommitStateConflictException("slot denied"))
        .when(coordinator)
        .claimApplicationSlot(any());
    assertThatThrownBy(() -> service.commit(original, "creator.credential"))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);
    verifyNoInteractions(account, sources);
  }

  @Test
  void unavailableAccountOrderUsesDurableLocalAbortAfterReservation() {
    var original = original(false);
    var aborted = mock(GameDesignDraftTerminalOutcome.class);
    when(aborted.operation()).thenReturn(new GameDesignDraftTerminalOperation(original));
    when(aborted.result()).thenReturn(GameDesignDraftTerminalOutcome.Result.DEFINITIVELY_ABORTED);
    when(terminal.read(any(GameDesignDraftTerminalOperation.class)))
        .thenReturn(Optional.empty(), Optional.empty(), Optional.of(aborted));
    when(account.claim(any())).thenThrow(io.grpc.Status.UNAVAILABLE.asRuntimeException());
    when(coordinator.abortUnattemptedLocalSource(any())).thenReturn(aborted);
    assertThat(service.commit(original, "creator.credential")).isSameAs(aborted);
    var order = inOrder(coordinator, account);
    order.verify(coordinator).lockVersionTarget(any(DraftCommitBinding.TargetProof.class));
    order.verify(coordinator).claim(any(), any());
    order.verify(coordinator).claimApplicationSlot(any());
    order.verify(account).claim(any());
    order.verify(coordinator).abortUnattemptedLocalSource(any());
    verifyNoInteractions(sources);
  }

  @Test
  void exactAbortedTerminalRetryNeverRecapturesCreatorOrAccount() {
    var original = original(false);
    var operation = new GameDesignDraftTerminalOperation(original);
    var aborted = mock(GameDesignDraftTerminalOutcome.class);
    when(aborted.operation()).thenReturn(operation);
    when(aborted.result()).thenReturn(GameDesignDraftTerminalOutcome.Result.DEFINITIVELY_ABORTED);
    when(terminal.read(any(GameDesignDraftTerminalOperation.class)))
        .thenReturn(Optional.of(aborted));
    assertThat(service.commit(original, null)).isSameAs(aborted);
    verify(terminal).read(any(GameDesignDraftTerminalOperation.class));
    verifyNoInteractions(account, coordinator, sources);
  }

  private static DraftAuthorizationFenceBinding original(boolean includeWorld) {
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "tenant-key", 2, "tenant-key", "NEW_GAME_ROW");
    var binding =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULES",
                    target.canonicalVersionId().toString(),
                    "GAMEPLAY_RULES",
                    "all",
                    "0")));
    if (includeWorld)
      binding =
          DraftCommitBinding.create(
              target,
              binding.requestId(),
              binding.commitId(),
              binding.baseCommitId(),
              List.of(
                  binding.revisions().getFirst(),
                  new DraftCommitBinding.RevisionPayload(
                      "1", UUID.randomUUID(), DraftCommitBinding.Owner.WORLD_MANAGEMENT, "{}")),
              List.of(
                  binding.affectedUnits().getFirst(),
                  new DraftCommitBinding.AffectedUnit(
                      DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                      "WORLD_TEMPLATE",
                      "world",
                      "ROOM_SCOPE",
                      "room",
                      "0")));
    return new DraftAuthorizationFenceBinding(
            UUID.randomUUID(),
            binding.requestId(),
            binding.commitId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            binding.baseCommitId(),
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
