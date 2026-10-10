package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository.PendingProtection;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository.StoredSettlement;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Mocked paging and settlement composition verifies bounded ordering, not SQL or peer proof. */
class AccountStartSessionAdmissionProtectionReconciliationServiceTest {
  private static final UUID FIRST_ID = uuid("d14a9c31-46cc-49f7-9f82-78a945f9c58d");
  private static final UUID SECOND_ID = uuid("18d258b7-2bb9-4bdb-9f5c-dfc84ce9c1e4");
  private static final UUID THIRD_ID = uuid("933a5f0d-d4a7-4fe4-a897-b695a95351c1");
  private static final UUID FOURTH_ID = uuid("a18b1d53-2b8a-4d70-9cef-630170a4f4a2");

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void pageTransactionClosesBeforeSettlementAndOneFailureDoesNotStarveLaterItems() {
    Harness harness = new Harness(2);
    List<Long> attemptedFences = new ArrayList<>();
    Map<Long, StoredSettlement> receipts =
        Map.of(
            90L, receipt(SECOND_ID, 90L),
            80L, receipt(THIRD_ID, 80L),
            70L, receipt(FOURTH_ID, 70L));
    when(harness.repository.findUnsettledPageDescending(null, 2))
        .thenAnswer(
            ignored -> {
              assertWritablePageTransaction();
              return List.of(candidate(FIRST_ID, 100L), candidate(SECOND_ID, 90L));
            });
    when(harness.repository.findUnsettledPageDescending(90L, 2))
        .thenAnswer(
            ignored -> {
              assertWritablePageTransaction();
              return List.of(candidate(THIRD_ID, 80L), candidate(FOURTH_ID, 70L));
            });
    when(harness.settlementService.settle(any(), anyLong()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction();
              long protectionFence = invocation.getArgument(1);
              attemptedFences.add(protectionFence);
              if (protectionFence == 100L) {
                throw new IllegalStateException("terminal proof remains pending");
              }
              return receipts.get(protectionFence);
            });

    var first = harness.service.reconcilePendingBatch();
    var second = harness.service.reconcilePendingBatch();

    assertThat(first.selectedCount()).isEqualTo(2);
    assertThat(first.settledCount()).isEqualTo(1);
    assertThat(first.unresolvedCount()).isEqualTo(1);
    assertThat(second.selectedCount()).isEqualTo(2);
    assertThat(second.settledCount()).isEqualTo(2);
    assertThat(attemptedFences).containsExactly(100L, 90L, 80L, 70L);
    assertThat(harness.transactions.begins).isEqualTo(2);
    assertThat(harness.transactions.commits).isEqualTo(2);
    assertOutsideTransaction();
  }

  @Test
  void emptyEndPageWrapsAndRevisitsAnUnresolvedCandidate() {
    Harness harness = new Harness(2);
    StoredSettlement secondReceipt = receipt(SECOND_ID, 80L);
    when(harness.repository.findUnsettledPageDescending(null, 2))
        .thenReturn(List.of(candidate(FIRST_ID, 90L), candidate(SECOND_ID, 80L)))
        .thenReturn(List.of(candidate(FIRST_ID, 90L)));
    when(harness.repository.findUnsettledPageDescending(80L, 2)).thenReturn(List.of());
    when(harness.settlementService.settle(SECOND_ID, 80L)).thenReturn(secondReceipt);
    when(harness.settlementService.settle(FIRST_ID, 90L))
        .thenThrow(new IllegalStateException("terminal proof remains pending"));

    var first = harness.service.reconcilePendingBatch();
    var endOfSweep = harness.service.reconcilePendingBatch();
    var wrapped = harness.service.reconcilePendingBatch();

    assertThat(first.unresolvedCount()).isEqualTo(1);
    assertThat(endOfSweep.selectedCount()).isZero();
    assertThat(wrapped.selectedCount()).isEqualTo(1);
    assertThat(wrapped.unresolvedCount()).isEqualTo(1);
    InOrder order = inOrder(harness.repository);
    order.verify(harness.repository).findUnsettledPageDescending(null, 2);
    order.verify(harness.repository).findUnsettledPageDescending(80L, 2);
    order.verify(harness.repository).findUnsettledPageDescending(null, 2);
    verify(harness.settlementService, times(2)).settle(FIRST_ID, 90L);
  }

  @Test
  void pageReadFailurePreservesCursorAndMakesNoSettlementCalls() {
    Harness harness = new Harness(2);
    Map<Long, StoredSettlement> receipts =
        Map.of(
            90L, receipt(FIRST_ID, 90L),
            80L, receipt(SECOND_ID, 80L),
            70L, receipt(THIRD_ID, 70L),
            60L, receipt(FOURTH_ID, 60L));
    when(harness.repository.findUnsettledPageDescending(null, 2))
        .thenReturn(List.of(candidate(FIRST_ID, 90L), candidate(SECOND_ID, 80L)));
    when(harness.repository.findUnsettledPageDescending(80L, 2))
        .thenThrow(new IllegalStateException("synthetic page read failure"))
        .thenReturn(List.of(candidate(THIRD_ID, 70L), candidate(FOURTH_ID, 60L)));
    when(harness.settlementService.settle(any(), anyLong()))
        .thenAnswer(invocation -> receipts.get(invocation.getArgument(1)));

    harness.service.reconcilePendingBatch();
    assertThatThrownBy(harness.service::reconcilePendingBatch)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("page read failure");
    var afterFailure = harness.service.reconcilePendingBatch();

    assertThat(afterFailure.selectedCount()).isEqualTo(2);
    InOrder order = inOrder(harness.repository);
    order.verify(harness.repository).findUnsettledPageDescending(null, 2);
    order.verify(harness.repository, times(2)).findUnsettledPageDescending(80L, 2);
    verify(harness.settlementService, times(4)).settle(any(), anyLong());
  }

  @Test
  void nullOrMismatchedSettlementReceiptRemainsUnresolved() {
    Harness missing = new Harness(1);
    when(missing.repository.findUnsettledPageDescending(null, 1))
        .thenReturn(List.of(candidate(FIRST_ID, 90L)));
    when(missing.settlementService.settle(FIRST_ID, 90L)).thenReturn(null);

    var missingResult = missing.service.reconcilePendingBatch();

    assertThat(missingResult.settledCount()).isZero();
    assertThat(missingResult.unresolvedCount()).isEqualTo(1);

    Harness changedId = new Harness(1);
    StoredSettlement wrongIdReceipt = receipt(SECOND_ID, 90L);
    when(changedId.repository.findUnsettledPageDescending(null, 1))
        .thenReturn(List.of(candidate(FIRST_ID, 90L)));
    when(changedId.settlementService.settle(FIRST_ID, 90L)).thenReturn(wrongIdReceipt);

    var changedIdResult = changedId.service.reconcilePendingBatch();

    assertThat(changedIdResult.settledCount()).isZero();
    assertThat(changedIdResult.unresolvedCount()).isEqualTo(1);

    Harness changedFence = new Harness(1);
    StoredSettlement wrongFenceReceipt = receipt(FIRST_ID, 91L);
    when(changedFence.repository.findUnsettledPageDescending(null, 1))
        .thenReturn(List.of(candidate(FIRST_ID, 90L)));
    when(changedFence.settlementService.settle(FIRST_ID, 90L)).thenReturn(wrongFenceReceipt);

    var changedFenceResult = changedFence.service.reconcilePendingBatch();

    assertThat(changedFenceResult.settledCount()).isZero();
    assertThat(changedFenceResult.unresolvedCount()).isEqualTo(1);
  }

  @Test
  void pageCommitFailurePreservesCursorAndMakesNoSettlementCalls() {
    Harness harness = new Harness(2);
    harness.transactions.failCommitAt = 2;
    Map<Long, StoredSettlement> receipts =
        Map.of(
            90L, receipt(FIRST_ID, 90L),
            80L, receipt(SECOND_ID, 80L),
            70L, receipt(THIRD_ID, 70L),
            60L, receipt(FOURTH_ID, 60L));
    when(harness.repository.findUnsettledPageDescending(null, 2))
        .thenReturn(List.of(candidate(FIRST_ID, 90L), candidate(SECOND_ID, 80L)));
    when(harness.repository.findUnsettledPageDescending(80L, 2))
        .thenReturn(List.of(candidate(THIRD_ID, 70L), candidate(FOURTH_ID, 60L)));
    when(harness.settlementService.settle(any(), anyLong()))
        .thenAnswer(invocation -> receipts.get(invocation.getArgument(1)));

    harness.service.reconcilePendingBatch();
    assertThatThrownBy(harness.service::reconcilePendingBatch)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("synthetic page commit failure");
    verify(harness.settlementService, times(2)).settle(any(), anyLong());
    var afterFailure = harness.service.reconcilePendingBatch();

    assertThat(afterFailure.selectedCount()).isEqualTo(2);
    InOrder order = inOrder(harness.repository);
    order.verify(harness.repository).findUnsettledPageDescending(null, 2);
    order.verify(harness.repository, times(2)).findUnsettledPageDescending(80L, 2);
    verify(harness.settlementService, times(4)).settle(any(), anyLong());
  }

  @Test
  void sameInstanceConcurrentInvocationIsSkippedWhileOnePageIsBeingSettled() throws Exception {
    Harness harness = new Harness(1);
    CountDownLatch settlementEntered = new CountDownLatch(1);
    CountDownLatch releaseSettlement = new CountDownLatch(1);
    AtomicInteger activeSettlements = new AtomicInteger();
    AtomicInteger maximumActiveSettlements = new AtomicInteger();
    StoredSettlement receipt = receipt(FIRST_ID, 90L);
    when(harness.repository.findUnsettledPageDescending(null, 1))
        .thenReturn(List.of(candidate(FIRST_ID, 90L)));
    when(harness.settlementService.settle(FIRST_ID, 90L))
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              int active = activeSettlements.incrementAndGet();
              maximumActiveSettlements.accumulateAndGet(active, Math::max);
              settlementEntered.countDown();
              try {
                if (!releaseSettlement.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("test did not release settlement call");
                }
                return receipt;
              } finally {
                activeSettlements.decrementAndGet();
              }
            });
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<AccountStartSessionAdmissionProtectionReconciliationService.BatchResult> first =
          executor.submit(harness.service::reconcilePendingBatch);
      assertThat(settlementEntered.await(5, TimeUnit.SECONDS)).isTrue();

      var overlapping = harness.service.reconcilePendingBatch();

      assertThat(overlapping.skippedBecauseActive()).isTrue();
      releaseSettlement.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS).settledCount()).isEqualTo(1);
      assertThat(maximumActiveSettlements.get()).isEqualTo(1);
      verify(harness.repository, times(1)).findUnsettledPageDescending(null, 1);
      verify(harness.settlementService, times(1)).settle(FIRST_ID, 90L);
    } finally {
      releaseSettlement.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void endUserAndAmbientTransactionAreRejectedBeforePageSelection() {
    Harness harness = new Harness(2);
    SessionContext.setContext("123", List.of(), Map.of());

    assertStatus(Status.Code.PERMISSION_DENIED, harness.service::reconcilePendingBatch);
    SessionContext.clear();

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertStatus(Status.Code.FAILED_PRECONDITION, harness.service::reconcilePendingBatch);
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertStatus(Status.Code.FAILED_PRECONDITION, harness.service::reconcilePendingBatch);

    verifyNoInteractions(harness.repository, harness.settlementService);
    assertThat(harness.transactions.begins).isZero();
  }

  @Test
  void constructorRequiresAServiceBoundedPageSize() {
    assertThatThrownBy(() -> new Harness(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Bounded positive");
    assertThatThrownBy(
            () ->
                new AccountStartSessionAdmissionProtectionReconciliationService(
                    mock(AccountStartSessionAdmissionProtectionRepository.class),
                    mock(AccountStartSessionAdmissionProtectionSettlementService.class),
                    new PageTransactions(),
                    AccountStartSessionAdmissionProtectionRepository.MAX_PENDING_SWEEP_SIZE + 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Bounded positive");
  }

  private static void assertWritablePageTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
        .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
  }

  private static void assertOutsideTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }

  private static PendingProtection candidate(UUID id, long fence) {
    return new PendingProtection(id, fence);
  }

  private static StoredSettlement receipt(UUID id, long fence) {
    StoredSettlement receipt = mock(StoredSettlement.class);
    when(receipt.protectionId()).thenReturn(id);
    when(receipt.protectionFence()).thenReturn(fence);
    return receipt;
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static void assertStatus(Status.Code code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(RuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable((Throwable) failure).getCode()).isEqualTo(code));
  }

  private static final class Harness {
    final AccountStartSessionAdmissionProtectionRepository repository =
        mock(AccountStartSessionAdmissionProtectionRepository.class);
    final AccountStartSessionAdmissionProtectionSettlementService settlementService =
        mock(AccountStartSessionAdmissionProtectionSettlementService.class);
    final PageTransactions transactions = new PageTransactions();
    final AccountStartSessionAdmissionProtectionReconciliationService service;

    Harness(int pageSize) {
      service =
          new AccountStartSessionAdmissionProtectionReconciliationService(
              repository, settlementService, transactions, pageSize);
    }
  }

  private static final class PageTransactions extends AbstractPlatformTransactionManager {
    private static final long serialVersionUID = 1L;
    int begins;
    int commits;
    int rollbacks;
    int failCommitAt = -1;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(definition.getPropagationBehavior())
          .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      assertThat(definition.getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
      assertThat(definition.isReadOnly()).isFalse();
      begins++;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      assertWritablePageTransaction();
      commits++;
      if (commits == failCommitAt) {
        throw new IllegalStateException("synthetic page commit failure");
      }
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++;
    }
  }
}
