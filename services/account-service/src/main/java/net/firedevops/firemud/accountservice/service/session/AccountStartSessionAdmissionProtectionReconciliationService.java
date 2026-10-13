package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository.PendingProtection;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered, bounded Account-local composition for retrying historical protection settlement.
 *
 * <p>Each invocation selects one short page in its own writable READ COMMITTED transaction, then
 * closes that transaction before the existing settlement composition performs authenticated Game
 * Session reads and immutable settlement/readback. The in-memory cursor advances across the entire
 * selected page, including unresolved items; another invocation eventually wraps to the newest
 * candidate. Separate service instances may select the same candidate, relying on exact immutable
 * settlement idempotency.
 */
public final class AccountStartSessionAdmissionProtectionReconciliationService {
  private final AccountStartSessionAdmissionProtectionRepository repository;
  private final AccountStartSessionAdmissionProtectionSettlementService settlementService;
  private final TransactionTemplate pageTransaction;
  private final int pageSize;
  private final AtomicBoolean invocationActive = new AtomicBoolean();

  /** Null begins a sweep from the newest positive protection fence. */
  private Long beforeProtectionFence;

  public AccountStartSessionAdmissionProtectionReconciliationService(
      AccountStartSessionAdmissionProtectionRepository repository,
      AccountStartSessionAdmissionProtectionSettlementService settlementService,
      PlatformTransactionManager transactionManager,
      int pageSize) {
    this.repository =
        Objects.requireNonNull(repository, "admission protection repository required");
    this.settlementService =
        Objects.requireNonNull(
            settlementService, "admission protection settlement service required");
    if (pageSize <= 0
        || pageSize > AccountStartSessionAdmissionProtectionRepository.MAX_PENDING_SWEEP_SIZE) {
      throw new IllegalArgumentException(
          "Bounded positive admission protection page size required");
    }
    this.pageSize = pageSize;
    pageTransaction =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "Account transaction manager required"));
    pageTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    pageTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    pageTransaction.setReadOnly(false);
  }

  /**
   * Selects and attempts at most one bounded page. Per-item runtime failures remain unresolved and
   * do not prevent later candidates in the page from being attempted.
   *
   * <p>A simultaneous invocation on this instance returns an empty skipped result. Page read or
   * commit failures propagate before cursor movement or any settlement call.
   */
  public BatchResult reconcilePendingBatch() {
    if (!invocationActive.compareAndSet(false, true)) return BatchResult.skipped();
    try {
      requireNoAuthenticatedEndUser();
      requireNoAmbientTransaction();

      Long pageCursor = beforeProtectionFence;
      List<PendingProtection> candidates =
          Objects.requireNonNull(
              pageTransaction.execute(
                  ignored -> repository.findUnsettledPageDescending(pageCursor, pageSize)),
              "Account pending protection page returned no result");
      candidates = List.copyOf(candidates);
      if (candidates.size() > pageSize) {
        throw new IllegalStateException("Account pending protection page exceeded its bound");
      }

      advanceCursor(candidates);
      int settledCount = 0;
      int unresolvedCount = 0;
      for (PendingProtection candidate : candidates) {
        try {
          var receipt =
              settlementService.settle(candidate.protectionId(), candidate.protectionFence());
          if (receipt == null
              || !candidate.protectionId().equals(receipt.protectionId())
              || candidate.protectionFence() != receipt.protectionFence()) {
            unresolvedCount++;
            continue;
          }
          settledCount++;
        } catch (RuntimeException unresolved) {
          unresolvedCount++;
        }
      }
      return new BatchResult(candidates.size(), settledCount, unresolvedCount, false);
    } finally {
      invocationActive.set(false);
    }
  }

  private void advanceCursor(List<PendingProtection> candidates) {
    if (candidates.isEmpty() || candidates.size() < pageSize) {
      beforeProtectionFence = null;
      return;
    }
    beforeProtectionFence = candidates.get(candidates.size() - 1).protectionFence();
  }

  private static void requireNoAuthenticatedEndUser() {
    if (SessionContext.hasAuthenticatedCallerContext()) {
      throw Status.PERMISSION_DENIED
          .withDescription("Internal Account historical recovery rejects end-user context")
          .asRuntimeException();
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Account historical recovery requires an independent page transaction")
          .asRuntimeException();
    }
  }

  /** Bounded outcome for one invocation; unresolved candidates remain protected and retryable. */
  public record BatchResult(
      int selectedCount, int settledCount, int unresolvedCount, boolean skippedBecauseActive) {
    public BatchResult {
      if (selectedCount < 0
          || settledCount < 0
          || unresolvedCount < 0
          || settledCount + unresolvedCount != selectedCount
          || (skippedBecauseActive && selectedCount != 0)) {
        throw new IllegalArgumentException("Invalid Account recovery batch result");
      }
    }

    private static BatchResult skipped() {
      return new BatchResult(0, 0, 0, true);
    }
  }
}
