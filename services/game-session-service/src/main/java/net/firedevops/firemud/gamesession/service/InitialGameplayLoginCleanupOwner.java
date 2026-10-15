package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayLoginAccountClient;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginJournalSnapshot;
import net.firedevops.firemud.gamesession.repository.InitialGameplayLoginCoordinationJournalRepository;
import net.firedevops.firemud.gamesession.repository.InitialGameplayLoginCoordinationJournalRepository.JournalConflictException;
import net.firedevops.firemud.gamesession.repository.InitialGameplayLoginCoordinationJournalRepository.JournalUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered original LOGIN cleanup orchestration. No Authenticate dispatch/replay, credential
 * reconstruction, live context installation, admission or wire success is offered. Ordinary secret
 * loss is cleanup-only; all retained originals remain lifecycle owned without an expiry policy.
 */
public final class InitialGameplayLoginCleanupOwner {
  private static final Logger LOG = LoggerFactory.getLogger(InitialGameplayLoginCleanupOwner.class);

  private final InitialGameplayLoginCoordinationJournalRepository journal;
  private final InitialGameplayLoginAttemptClosure closure;
  private final CanonicalGameplayLoginAccountClient account;
  private final TransactionTemplate transaction;

  public InitialGameplayLoginCleanupOwner(
      InitialGameplayLoginCoordinationJournalRepository journal,
      InitialGameplayLoginAttemptClosure closure,
      CanonicalGameplayLoginAccountClient account,
      PlatformTransactionManager transactionManager) {
    this.journal = Objects.requireNonNull(journal, "journal");
    this.closure = Objects.requireNonNull(closure, "closure");
    this.account = Objects.requireNonNull(account, "account");
    transaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Commit and independently read back coordinates before a future credential dispatcher could use
   * them. This parcel has no such dispatcher: the snapshot is not permission to Authenticate.
   */
  public InitialGameplayLoginJournalSnapshot reserve(
      InitialGameplayLoginAttemptCoordinates coordinates) {
    requireNoTransaction();
    var reserved = transaction.execute(status -> journal.reserve(coordinates));
    requireSnapshot(coordinates, reserved);
    return committed(coordinates);
  }

  /**
   * Keeps only the minimal verified original witness, including late evidence after abandonment.
   */
  public InitialGameplayLoginJournalSnapshot recordVerifiedResult(
      InitialGameplayLoginAttemptCoordinates coordinates, CanonicalGameplayLoginResult result) {
    requireNoTransaction();
    var written = transaction.execute(status -> journal.recordVerifiedResult(coordinates, result));
    requireSnapshot(coordinates, written);
    var readback = committed(coordinates);
    if (!readback.verifiedWitness().equals(written.verifiedWitness())) {
      throw new JournalUnavailableException();
    }
    return readback;
  }

  /**
   * Irreversibly abandon, exclude original late installers, then replay exact Account retirement. A
   * retained terminal receipt never skips closure or the new Account call. HELD/PENDING preserve
   * the original coordinates and earlier receipt, with no claim that a marker is currently present.
   */
  public CleanupObservation abandonAndRetire(InitialGameplayLoginAttemptCoordinates coordinates) {
    Objects.requireNonNull(coordinates, "coordinates");
    requireNoTransaction();
    Optional<InitialGameplayLoginDisposalResult> retainedReceipt = Optional.empty();
    try {
      var abandoned = transaction.execute(status -> journal.abandon(coordinates));
      requireSnapshot(coordinates, abandoned);
      var readback = committed(coordinates);
      retainedReceipt = readback.retainedReceipt();
      if (!readback.abandoned()) {
        throw new JournalUnavailableException();
      }
      Optional<InitialGameplayLoginDisposalResult> previous = retainedReceipt;
      return closure
          .withAcknowledgedClosure(
              coordinates,
              acknowledged -> retireWithinQualifiedClosure(coordinates, acknowledged, previous))
          .orElseGet(() -> held(coordinates, previous));
    } catch (RuntimeException unavailableOrConflicting) {
      // No potentially sensitive transport/SQL/parser diagnostics become retained evidence.
      String reason =
          unavailableOrConflicting instanceof JournalConflictException ? "CONFLICT" : "UNAVAILABLE";
      LOG.warn("Initial LOGIN cleanup operation=ABANDON_AND_RETIRE reason={}", reason);
      return held(coordinates, retainedReceipt);
    }
  }

  private CleanupObservation retireWithinQualifiedClosure(
      InitialGameplayLoginAttemptCoordinates coordinates,
      InitialGameplayLoginAttemptClosure.AcknowledgedClosure acknowledged,
      Optional<InitialGameplayLoginDisposalResult> previous) {
    requireNoTransaction();
    if (acknowledged == null || !coordinates.equals(acknowledged.coordinates())) {
      throw new JournalConflictException();
    }
    var frozen = transaction.execute(status -> journal.freezeRetirementRequest(coordinates));
    requireSnapshot(coordinates, frozen);
    var readback = committed(coordinates);
    var request = readback.frozenRetirementRequest().orElseThrow(JournalUnavailableException::new);
    if (!readback.abandoned()
        || !frozen.frozenRetirementRequest().filter(request::equals).isPresent()) {
      throw new JournalUnavailableException();
    }
    // Closure qualification is still owned by the adapter; no SQL transaction spans Account.
    requireNoTransaction();
    var receipt = account.retireInitialGameplayLogin(request);
    requireReceipt(coordinates, readback.retainedReceipt(), receipt);
    var written =
        transaction.execute(status -> journal.recordDisposalReceipt(coordinates, receipt));
    requireSnapshot(coordinates, written);
    var retained = committed(coordinates);
    if (!retained.retainedReceipt().filter(receipt::equals).isPresent()) {
      throw new JournalUnavailableException();
    }
    return new CleanupObservation(
        coordinates,
        switch (receipt.disposition()) {
          case PENDING -> Status.ACCOUNT_PENDING;
          case RETIRED -> Status.ACCOUNT_RETIRED;
          case NO_TOKEN -> Status.ACCOUNT_NO_TOKEN;
        },
        previous,
        Optional.of(receipt));
  }

  private InitialGameplayLoginJournalSnapshot committed(
      InitialGameplayLoginAttemptCoordinates coordinates) {
    requireNoTransaction();
    var snapshot = journal.readCommitted(coordinates).orElseThrow(JournalUnavailableException::new);
    requireSnapshot(coordinates, snapshot);
    return snapshot;
  }

  private static void requireSnapshot(
      InitialGameplayLoginAttemptCoordinates coordinates,
      InitialGameplayLoginJournalSnapshot snapshot) {
    if (snapshot == null || !coordinates.equals(snapshot.coordinates())) {
      throw new JournalUnavailableException();
    }
  }

  private static void requireReceipt(
      InitialGameplayLoginAttemptCoordinates coordinates,
      Optional<InitialGameplayLoginDisposalResult> previous,
      InitialGameplayLoginDisposalResult receipt) {
    if (receipt == null
        || !coordinates.requestId().equals(receipt.requestId())
        || !coordinates.callerContextId().equals(receipt.callerContextId())) {
      throw new JournalConflictException();
    }
    previous.ifPresent(
        value -> {
          if (!value.disposalOperationId().equals(receipt.disposalOperationId())
              || value.disposition() != InitialGameplayLoginDisposalResult.Disposition.PENDING
                  && !value.equals(receipt)) {
            throw new JournalConflictException();
          }
        });
  }

  private static void requireNoTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new JournalUnavailableException();
    }
  }

  private static CleanupObservation held(
      InitialGameplayLoginAttemptCoordinates coordinates,
      Optional<InitialGameplayLoginDisposalResult> previous) {
    return new CleanupObservation(coordinates, Status.HELD, previous, Optional.empty());
  }

  public enum Status {
    HELD,
    ACCOUNT_PENDING,
    ACCOUNT_RETIRED,
    ACCOUNT_NO_TOKEN
  }

  /**
   * Historical receipt and this call's acknowledged observation remain separate and non-admitting.
   */
  public record CleanupObservation(
      InitialGameplayLoginAttemptCoordinates coordinates,
      Status status,
      Optional<InitialGameplayLoginDisposalResult> priorRetainedReceipt,
      Optional<InitialGameplayLoginDisposalResult> currentCallReceipt) {
    public CleanupObservation {
      Objects.requireNonNull(coordinates, "coordinates");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(priorRetainedReceipt, "priorRetainedReceipt");
      Objects.requireNonNull(currentCallReceipt, "currentCallReceipt");
    }
  }
}
