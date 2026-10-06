package unit.net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.authordraft.AccountWorldDraftTerminalReconciliationService;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Ordering;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Synthetic unit collaborators prove phase composition, not peer authentication or SQL atomicity.
 */
class AccountWorldDraftTerminalReconciliationServiceTest {
  private static final String NAMESPACE = "firemud-test";

  @Test
  void unknownReadsOutsideOwnerTransactionsAndWritesNoParticipantEvidence() {
    Fixture f = fixture(Ordering.COMMIT_ORDER);
    when(f.client().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(f);
              var request = (WorldDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              assertThat(request.originalAccountBinding())
                  .containsExactly(f.binding().canonicalBytes());
              assertThat(request.targetNamespace()).isEqualTo(NAMESPACE);
              assertThat(request.readRequestId()).isNotEqualTo(f.binding().operationId());
              return new WorldDraftTerminalReadEvidence(request, Optional.empty());
            });
    assertThat(f.service().reconcileWorld(f.binding().operationId())).contains(Settlement.PENDING);
    assertThat(f.manager().commits).isEqualTo(2);
    verify(f.repository(), never()).recordOwnerReadback(any(), any());
  }

  @Test
  void genuineWorldAbortRemainsPendingWithoutGameDesignAndExactRetryDoesNotCallPeer() {
    Fixture f = fixture(Ordering.COMMIT_ORDER);
    when(f.client().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(f);
              return new WorldDraftTerminalReadEvidence(
                  invocation.getArgument(0), Optional.of(abort(f.binding())));
            });
    assertThat(f.service().reconcileWorld(f.binding().operationId())).contains(Settlement.PENDING);
    verify(f.repository()).recordOwnerReadback(eq(f.binding()), any());
    verify(f.repository(), never())
        .recordOwnerReadback(any(), argThat(value -> value.owner() == Owner.GAME_DESIGN));
    assertThat(f.stored().get().owner()).isEqualTo(Owner.WORLD);
    assertThat(f.service().reconcileWorld(f.binding().operationId())).contains(Settlement.PENDING);
    verify(f.client()).read(any());
  }

  @Test
  void missingReservedExistingAndTerminalOperationsDoNotCallPeer() {
    Fixture missing = fixture(Ordering.COMMIT_ORDER);
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                  .isFalse();
              return Optional.empty();
            })
        .when(missing.repository())
        .readOriginalBinding(any());
    assertThat(missing.service().reconcileWorld(missing.binding().operationId())).isEmpty();
    verifyNoInteractions(missing.client());
    Fixture reserved = fixture(Ordering.RESERVED);
    assertThat(reserved.service().reconcileWorld(reserved.binding().operationId()))
        .contains(Settlement.PENDING);
    verifyNoInteractions(reserved.client());
    Fixture existing = fixture(Ordering.REVOKE_ORDER);
    existing.stored().set(abort(existing.binding()));
    assertThat(existing.service().reconcileWorld(existing.binding().operationId()))
        .contains(Settlement.PENDING);
    verifyNoInteractions(existing.client());
    Fixture terminal = fixture(Ordering.COMMIT_ORDER);
    when(terminal.repository().readSettlement(any())).thenReturn(Settlement.FAILED_NONPUBLICATION);
    assertThat(terminal.service().reconcileWorld(terminal.binding().operationId()))
        .contains(Settlement.FAILED_NONPUBLICATION);
    verifyNoInteractions(terminal.client());
  }

  @Test
  void wrongReturnedSchemaNamespaceRequestAndBindingFailBeforeOwnerMutation() {
    for (int field = 0; field < 4; field++) {
      Fixture f = fixture(Ordering.COMMIT_ORDER);
      int wrong = field;
      when(f.client().read(any()))
          .thenAnswer(
              invocation -> {
                var expected = (WorldDraftTerminalReadEvidence.Request) invocation.getArgument(0);
                var request = mock(WorldDraftTerminalReadEvidence.Request.class);
                when(request.schemaVersion()).thenReturn(wrong == 0 ? 2 : expected.schemaVersion());
                when(request.targetNamespace())
                    .thenReturn(wrong == 1 ? "other-namespace" : NAMESPACE);
                when(request.readRequestId())
                    .thenReturn(wrong == 2 ? UUID.randomUUID() : expected.readRequestId());
                when(request.originalAccountBinding())
                    .thenReturn(
                        wrong == 3
                            ? binding().canonicalBytes()
                            : expected.originalAccountBinding());
                var response = mock(WorldDraftTerminalReadEvidence.class);
                when(response.request()).thenReturn(request);
                when(response.ownerReadback()).thenReturn(Optional.empty());
                return response;
              });
      assertThatThrownBy(() -> f.service().reconcileWorld(f.binding().operationId()))
          .isInstanceOf(IllegalArgumentException.class);
      verify(f.repository(), never()).recordOwnerReadback(any(), any());
      assertThat(f.manager().commits).isEqualTo(1);
    }
  }

  @Test
  void existingGameDesignCorruptionDeniesEvenWhenWorldResultAlreadyExists() {
    Fixture f = fixture(Ordering.COMMIT_ORDER);
    f.stored().set(abort(f.binding()));
    when(f.repository().readOwnerResult(any(), eq(Owner.GAME_DESIGN)))
        .thenThrow(new IllegalArgumentException("synthetic strict persisted-readback rejection"));
    assertThatThrownBy(() -> f.service().reconcileWorld(f.binding().operationId()))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(f.client());
    verify(f.repository(), never()).recordOwnerReadback(any(), any());
  }

  @Test
  void concurrentExactWorldReadbackRetainsTheSameOriginalBytes() {
    Fixture f = fixture(Ordering.REVOKE_ORDER);
    var original = abort(f.binding());
    when(f.client().read(any()))
        .thenAnswer(
            invocation -> {
              f.stored().set(original);
              return new WorldDraftTerminalReadEvidence(
                  invocation.getArgument(0), Optional.of(original));
            });
    assertThat(f.service().reconcileWorld(f.binding().operationId())).contains(Settlement.PENDING);
    verify(f.repository()).recordOwnerReadback(eq(f.binding()), eq(original));
    assertThat(f.stored().get().canonicalBytes()).containsExactly(original.canonicalBytes());
  }

  @Test
  void wrongOwnerOutcomeAndOriginalBindingFailBeforeMutation() {
    for (int field = 0; field < 3; field++) {
      Fixture f = fixture(Ordering.COMMIT_ORDER);
      var b = field == 2 ? binding() : f.binding();
      var wrong =
          new OwnerReadback(
              field == 0 ? Owner.GAME_DESIGN : Owner.WORLD,
              field == 1 ? Outcome.COMMITTED : Outcome.DEFINITIVELY_ABORTED,
              b.operationId(),
              b.commitId(),
              b.fenceId(),
              b.inputDigest(),
              b.canonicalBytes(),
              new byte[] {1});
      when(f.client().read(any()))
          .thenAnswer(
              invocation -> {
                var response = mock(WorldDraftTerminalReadEvidence.class);
                when(response.request()).thenReturn(invocation.getArgument(0));
                when(response.ownerReadback()).thenReturn(Optional.of(wrong));
                return response;
              });
      assertThatThrownBy(() -> f.service().reconcileWorld(f.binding().operationId()))
          .isInstanceOf(IllegalArgumentException.class);
      verify(f.repository(), never()).recordOwnerReadback(any(), any());
    }
  }

  @Test
  void transportFailurePreservesOriginalAndAmbientTransactionIsRejected() {
    Fixture f = fixture(Ordering.COMMIT_ORDER);
    when(f.client().read(any())).thenThrow(new IllegalStateException("synthetic unavailable peer"));
    assertThatThrownBy(() -> f.service().reconcileWorld(f.binding().operationId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("synthetic unavailable peer");
    verify(f.repository(), never()).recordOwnerReadback(any(), any());
    Fixture ambient = fixture(Ordering.COMMIT_ORDER);
    var ambientTransaction = new TransactionTemplate(ambient.manager());
    ambientTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ambientTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    ambientTransaction.execute(
        status -> {
          assertThatThrownBy(
                  () -> ambient.service().reconcileWorld(ambient.binding().operationId()))
              .isInstanceOf(IllegalStateException.class);
          verifyNoInteractions(ambient.repository(), ambient.client());
          return null;
        });
  }

  @Test
  void disappearedOrChangedOriginalDuringPeerCallIsDeniedAndRollsBackRecoveryTransaction() {
    for (boolean absent : List.of(false, true)) {
      Fixture f = fixture(Ordering.COMMIT_ORDER);
      var b = f.binding();
      var changed =
          new DraftAuthorizationFenceBinding(
              b.operationId(),
              b.requestId(),
              b.commitId(),
              b.fenceId(),
              b.actorAccountId(),
              b.tenantId(),
              b.versionId(),
              b.baseCommitId(),
              "1",
              b.gameDesignBinding(),
              b.normalizedInput(),
              b.inputDigest(),
              b.sources());
      assertThat(changed.operationId()).isEqualTo(b.operationId());
      assertThat(changed.canonicalBytes()).isNotEqualTo(b.canonicalBytes());
      when(f.client().read(any()))
          .thenAnswer(
              invocation -> {
                doAnswer(
                        originalRead -> {
                          assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                              .isTrue();
                          assertThat(
                                  TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                              .isFalse();
                          return absent ? Optional.empty() : Optional.of(changed);
                        })
                    .when(f.repository())
                    .readOriginalBinding(any());
                return new WorldDraftTerminalReadEvidence(
                    invocation.getArgument(0), Optional.of(abort(f.binding())));
              });
      assertThatThrownBy(() -> f.service().reconcileWorld(f.binding().operationId()))
          .isInstanceOf(IllegalStateException.class);
      verify(f.repository(), never()).recordOwnerReadback(any(), any());
      assertThat(f.manager().rollbacks).isEqualTo(1);
    }
  }

  @Test
  void unknownResponseCanObserveConcurrentGenuineSettlementWithoutInventingResults() {
    Fixture f = fixture(Ordering.COMMIT_ORDER);
    when(f.client().read(any()))
        .thenAnswer(
            invocation -> {
              f.stored().set(abort(f.binding()));
              when(f.repository().readSettlement(any()))
                  .thenReturn(Settlement.FAILED_NONPUBLICATION);
              return new WorldDraftTerminalReadEvidence(
                  invocation.getArgument(0), Optional.empty());
            });
    assertThat(f.service().reconcileWorld(f.binding().operationId()))
        .contains(Settlement.FAILED_NONPUBLICATION);
    verify(f.repository(), never()).recordOwnerReadback(any(), any());
  }

  private static Fixture fixture(Ordering ordering) {
    var repository = mock(DraftAuthorizationFenceRepository.class);
    var client = mock(WorldDraftTerminalReadClient.class);
    var manager = new TestTransactionManager();
    var binding = binding();
    var stored = new AtomicReference<OwnerReadback>();
    when(repository.readOriginalBinding(any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                  .isFalse();
              return Optional.of(binding);
            });
    when(repository.read(any()))
        .thenReturn(
            new DraftAuthorizationFenceRepository.FenceSnapshot(
                ordering,
                binding.canonicalBytes(),
                OffsetDateTime.parse("2026-10-06T00:00:00Z"),
                ordering == Ordering.RESERVED
                    ? null
                    : OffsetDateTime.parse("2026-10-06T00:00:01Z")));
    when(repository.readOwnerResult(any(), any()))
        .thenAnswer(
            invocation ->
                invocation.getArgument(1) == Owner.WORLD && stored.get() != null
                    ? Optional.of(
                        new DraftAuthorizationFenceRepository.OwnerResultSnapshot(
                            stored.get().outcome(),
                            stored.get().canonicalBytes(),
                            OffsetDateTime.now()))
                    : Optional.empty());
    when(repository.readSettlement(any())).thenReturn(Settlement.PENDING);
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              stored.set(invocation.getArgument(1));
              return null;
            })
        .when(repository)
        .recordOwnerReadback(any(), any());
    return new Fixture(
        repository,
        client,
        manager,
        binding,
        stored,
        new AccountWorldDraftTerminalReconciliationService(repository, manager, client, NAMESPACE));
  }

  private static void assertOutsideTransaction(Fixture f) {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    assertThat(f.manager().commits).isEqualTo(1);
  }

  private static OwnerReadback abort(DraftAuthorizationFenceBinding b) {
    return new OwnerReadback(
        Owner.WORLD,
        Outcome.DEFINITIVELY_ABORTED,
        b.operationId(),
        b.commitId(),
        b.fenceId(),
        b.inputDigest(),
        b.canonicalBytes(),
        new byte[] {1});
  }

  private static DraftAuthorizationFenceBinding binding() {
    UUID account = UUID.randomUUID();
    var gameDesign =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "tenant-key",
                2,
                "tenant-key",
                "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0", UUID.randomUUID(), DraftCommitBinding.Owner.WORLD_MANAGEMENT, "payload")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "region",
                    "region-1",
                    "aggregate",
                    "region-1",
                    "0")));
    return new DraftAuthorizationFenceBinding(
        UUID.randomUUID(),
        gameDesign.requestId(),
        gameDesign.commitId(),
        UUID.randomUUID(),
        account,
        gameDesign.target().canonicalTenantId(),
        gameDesign.target().canonicalVersionId(),
        "base",
        "0",
        gameDesign.canonicalBytes(),
        gameDesign.canonicalBytes(),
        gameDesign.digest(),
        List.of(
            new SourceEvidence(
                SourceKind.ACCOUNT, account.toString(), "1", "1", "stream", "0", new byte[] {1})));
  }

  private record Fixture(
      DraftAuthorizationFenceRepository repository,
      WorldDraftTerminalReadClient client,
      TestTransactionManager manager,
      DraftAuthorizationFenceBinding binding,
      AtomicReference<OwnerReadback> stored,
      AccountWorldDraftTerminalReconciliationService service) {}

  private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
    private int commits;
    private int rollbacks;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
      return false;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      assertThat(definition.getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
      assertThat(definition.getPropagationBehavior())
          .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      assertThat(definition.isReadOnly()).isFalse();
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++;
    }
  }
}
