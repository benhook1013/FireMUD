package net.firedevops.firemud.worldmanagement.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldFinalizationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldRepository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit, unregistered bounded restart reconciliation for canonical World admission holds.
 *
 * <p>This class has no Spring component or scheduling annotation. Its caller chooses when to invoke
 * the next page; each page advances its in-memory keyset cursor before processing rows so one
 * unavailable or malformed owner proof cannot starve later candidates.
 */
public final class WorldCanonicalInitialAdmissionHoldReconciler {
  public static final int DEFAULT_PAGE_SIZE = 64;

  private final WorldCanonicalInitialAdmissionHoldRepository holdRepository;
  private final WorldCanonicalInitialAdmissionHoldFinalizationService finalizationService;
  private final String targetNamespace;
  private final AtomicReference<WorldCanonicalInitialAdmissionHoldRepository.ReconciliationCursor>
      cursor = new AtomicReference<>();

  public WorldCanonicalInitialAdmissionHoldReconciler(
      WorldCanonicalInitialAdmissionHoldRepository holdRepository,
      WorldCanonicalInitialAdmissionHoldFinalizationService finalizationService,
      String targetNamespace) {
    this.holdRepository = Objects.requireNonNull(holdRepository, "holdRepository");
    this.finalizationService = Objects.requireNonNull(finalizationService, "finalizationService");
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Target namespace must be one canonical DNS label");
    }
    this.targetNamespace = targetNamespace;
  }

  /**
   * Processes at most one bounded page. Invoke again while {@code endOfPass} is false; an empty
   * page resets the keyset so later invocations begin a new fair pass.
   */
  public PageResult reconcileNextPage() {
    return reconcileNextPage(DEFAULT_PAGE_SIZE);
  }

  /** Same as {@link #reconcileNextPage()}, with a caller-selected but capped page size. */
  public PageResult reconcileNextPage(int requestedPageSize) {
    requireNoAmbientTransaction();
    int pageSize = Math.max(1, Math.min(requestedPageSize, 256));
    WorldCanonicalInitialAdmissionHoldRepository.ReconciliationCursor after = cursor.get();
    List<WorldCanonicalInitialAdmissionHoldRepository.ReconciliationCandidateReference> page =
        holdRepository.findReconciliationCandidates(targetNamespace, after, pageSize);
    if (page.isEmpty()) {
      cursor.compareAndSet(after, null);
      return new PageResult(0, 0, 0, List.of(), List.of(), true);
    }

    advanceCursor(page.getLast().cursor());
    int terminalized = 0;
    int pending = 0;
    List<UUID> unresolved = new ArrayList<>();
    List<UUID> reconciliationRequired = new ArrayList<>();
    for (WorldCanonicalInitialAdmissionHoldRepository.ReconciliationCandidateReference ref : page) {
      try {
        Optional<WorldCanonicalInitialAdmissionHoldRepository.ReconciliationCandidate> candidate =
            holdRepository.readReconciliationCandidate(targetNamespace, ref.holdId());
        if (candidate.isEmpty()) continue;

        WorldCanonicalInitialAdmissionHoldRepository.ReconciliationCandidate exact =
            candidate.orElseThrow();
        if (exact.status()
            == WorldCanonicalInitialAdmissionHoldState.HoldStatus.RECONCILIATION_REQUIRED) {
          reconciliationRequired.add(ref.holdId());
          continue;
        }
        if (exact.status() != WorldCanonicalInitialAdmissionHoldState.HoldStatus.PENDING) {
          unresolved.add(ref.holdId());
          continue;
        }

        Optional<GameSessionCanonicalInitialAdmissionOwnerProof> result =
            finalizationService.observeAndFinalizeHold(exact.identity());
        if (result.isPresent()) terminalized++;
        else pending++;
      } catch (RuntimeException unavailableOrInconsistent) {
        unresolved.add(ref.holdId());
      }
    }
    return new PageResult(
        page.size(), terminalized, pending, unresolved, reconciliationRequired, false);
  }

  private void advanceCursor(
      WorldCanonicalInitialAdmissionHoldRepository.ReconciliationCursor next) {
    cursor.updateAndGet(current -> current == null || compare(next, current) > 0 ? next : current);
  }

  private static int compare(
      WorldCanonicalInitialAdmissionHoldRepository.ReconciliationCursor left,
      WorldCanonicalInitialAdmissionHoldRepository.ReconciliationCursor right) {
    int updated = left.updatedAt().compareTo(right.updatedAt());
    if (updated != 0) return updated;
    int mostSignificant =
        Long.compareUnsigned(
            left.holdId().getMostSignificantBits(), right.holdId().getMostSignificantBits());
    return mostSignificant != 0
        ? mostSignificant
        : Long.compareUnsigned(
            left.holdId().getLeastSignificantBits(), right.holdId().getLeastSignificantBits());
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "World canonical initial-admission reconciliation must start outside an ambient transaction");
    }
  }

  /** Counts and row keys only; uncertainty is reported without fabricating a hold transition. */
  public record PageResult(
      int scanned,
      int terminalized,
      int stillPending,
      List<UUID> unresolvedHoldIds,
      List<UUID> reconciliationRequiredHoldIds,
      boolean endOfPass) {
    public PageResult {
      unresolvedHoldIds = List.copyOf(unresolvedHoldIds);
      reconciliationRequiredHoldIds = List.copyOf(reconciliationRequiredHoldIds);
    }
  }
}
