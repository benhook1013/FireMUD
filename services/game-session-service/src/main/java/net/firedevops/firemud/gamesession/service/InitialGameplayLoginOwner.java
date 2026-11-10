package net.firedevops.firemud.gamesession.service;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.InitialGameplayLoginTokenWitness;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayLoginAccountClient;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginJournalSnapshot;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered source composition for one credential-bearing original LOGIN invocation. Its outcome
 * is non-admitting; it does not publish LOGIN wire success, bind gameplay, or recover from retained
 * journal evidence.
 */
public final class InitialGameplayLoginOwner {
  private static final long INITIAL_ATTEMPT_FENCE = 1L;

  private final InitialGameplayLoginContextRedisClient contexts;
  private final InitialGameplayLoginCleanupOwner cleanup;
  private final CanonicalGameplayLoginAccountClient account;

  public InitialGameplayLoginOwner(
      InitialGameplayLoginContextRedisClient contexts,
      InitialGameplayLoginCleanupOwner cleanup,
      CanonicalGameplayLoginAccountClient account) {
    this.contexts = Objects.requireNonNull(contexts, "Initial context owner required");
    this.cleanup = Objects.requireNonNull(cleanup, "Original cleanup owner required");
    this.account = Objects.requireNonNull(account, "Canonical Account client required");
  }

  /**
   * Runs only from the current credential presentation. It reserves and independently reads back
   * the original journal coordinates before create-only PENDING, invokes Account as one bounded
   * original invocation (including only its permitted same-deadline retry), records only the
   * verified nonsecret witness, then compare-installs that exact private result. Successful
   * installation transfers the still-live private result to the receiving caller; any uncertain
   * result or custody loss selects exact-original cleanup.
   */
  public Outcome receive(InitialGameplayLoginCredentialPresentation credentials) {
    Objects.requireNonNull(credentials, "Credential custody required");
    var coordinates = newOriginalCoordinates();
    try (credentials) {
      try {
        requireNoTransaction();
        requireCurrent(credentials);
        InitialGameplayLoginJournalSnapshot reserved = cleanup.reserve(coordinates);
        requireCoordinates(coordinates, reserved);
        if (reserved.abandoned() || reserved.verifiedWitness().isPresent()) {
          throw unavailable();
        }

        requireCurrent(credentials);
        Optional<InitialGameplayLoginContextState> pending = contexts.createPending(coordinates);
        if (pending.isEmpty()
            || pending.orElseThrow().state() != InitialGameplayLoginContextState.State.PENDING
            || !coordinates.equals(pending.orElseThrow().coordinates())) {
          return cleanup(coordinates, null, credentials);
        }

        requireCurrent(credentials);
        AuthenticateRequest originalRequest = credentials.createOriginalRequest(coordinates);
        requireExactRequest(coordinates, originalRequest);
        requireCurrent(credentials);

        CanonicalGameplayLoginResult verified =
            account.authenticate(originalRequest, credentials::isCurrent);
        boolean transferred = false;
        try {
          InitialGameplayLoginJournalSnapshot witnessed =
              cleanup.recordVerifiedResult(coordinates, verified);
          requireVerifiedWitness(coordinates, verified, witnessed);
          requireCurrent(credentials);

          Optional<InitialGameplayLoginContextState> installed =
              contexts.install(coordinates, verified);
          if (installed.isEmpty()
              || installed.orElseThrow().state() != InitialGameplayLoginContextState.State.INSTALLED
              || !coordinates.equals(installed.orElseThrow().coordinates())) {
            return cleanup(coordinates, null, credentials);
          }
          if (!credentials.isCurrent()) {
            return cleanup(
                coordinates,
                new InitialGameplayLoginCredentialPresentation.CustodyLostException(),
                credentials);
          }
          Outcome outcome = Outcome.installed(coordinates, verified, cleanup);
          transferred = true;
          return outcome;
        } catch (RuntimeException failure) {
          return cleanup(coordinates, failure, credentials);
        } finally {
          if (!transferred) verified.close();
        }
      } catch (RuntimeException failure) {
        return cleanup(coordinates, failure, credentials);
      }
    }
  }

  private Outcome cleanup(
      InitialGameplayLoginAttemptCoordinates coordinates,
      RuntimeException failure,
      InitialGameplayLoginCredentialPresentation credentials) {
    boolean custodyCurrent = credentials.isCurrent();
    InitialGameplayLoginCleanupOwner.CleanupObservation observation;
    try {
      requireNoTransaction();
      // Any successful result has already had one witness-append/readback attempt. Cleanup freezes
      // the exact resulting presence or absence; it never re-appends evidence after ambiguity.
      observation = cleanup.abandonAndRetire(coordinates);
    } catch (RuntimeException unavailable) {
      return Outcome.cleanup(
          coordinates,
          Status.CLEANUP_HELD,
          failureCode(failure, custodyCurrent),
          InitialGameplayLoginCleanupOwner.Status.HELD,
          cleanup);
    }
    Status status =
        switch (observation.status()) {
          case HELD -> Status.CLEANUP_HELD;
          case ACCOUNT_PENDING -> Status.CLEANUP_ACCOUNT_PENDING;
          case ACCOUNT_RETIRED -> Status.CLEANUP_ACCOUNT_RETIRED;
          case ACCOUNT_NO_TOKEN -> Status.CLEANUP_ACCOUNT_NO_TOKEN;
        };
    return Outcome.cleanup(
        coordinates, status, failureCode(failure, custodyCurrent), observation.status(), cleanup);
  }

  private static InitialGameplayLoginAttemptCoordinates newOriginalCoordinates() {
    UUID requestId = UUID.randomUUID();
    UUID callerContextId;
    do {
      callerContextId = UUID.randomUUID();
    } while (requestId.equals(callerContextId));
    return new InitialGameplayLoginAttemptCoordinates(
        requestId, callerContextId, INITIAL_ATTEMPT_FENCE);
  }

  private static void requireExactRequest(
      InitialGameplayLoginAttemptCoordinates coordinates, AuthenticateRequest request) {
    CanonicalGameplayLoginResult.requireOriginalRequest(request);
    if (!coordinates.requestId().toString().equals(request.getRequestId())
        || !coordinates
            .callerContextId()
            .toString()
            .equals(request.getSourceContext().getCallerContextId())) {
      throw unavailable();
    }
  }

  private static void requireVerifiedWitness(
      InitialGameplayLoginAttemptCoordinates coordinates,
      CanonicalGameplayLoginResult verified,
      InitialGameplayLoginJournalSnapshot snapshot) {
    var evidence = verified.evidence();
    var expected =
        InitialGameplayLoginTokenWitness.newBuilder()
            .setTokenJti(evidence.tokenJti().toString())
            .setTokenSha256(evidence.tokenSha256())
            .setTokenGeneration(Long.parseLong(evidence.tokenGeneration()))
            .setIssuanceFence(Long.parseLong(evidence.issuanceFence()))
            .build();
    requireCoordinates(coordinates, snapshot);
    if (snapshot.abandoned() || !snapshot.verifiedWitness().filter(expected::equals).isPresent())
      throw unavailable();
  }

  private static void requireCoordinates(
      InitialGameplayLoginAttemptCoordinates coordinates,
      InitialGameplayLoginJournalSnapshot snapshot) {
    if (snapshot == null || !coordinates.equals(snapshot.coordinates())) throw unavailable();
  }

  private static void requireCurrent(InitialGameplayLoginCredentialPresentation credentials) {
    if (!credentials.isCurrent()) {
      throw new InitialGameplayLoginCredentialPresentation.CustodyLostException();
    }
  }

  private static void requireNoTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
  }

  private static Optional<String> failureCode(RuntimeException failure, boolean custodyCurrent) {
    if (failure instanceof CanonicalGameplayLoginResult.CredentialDecisionException decision) {
      return Optional.of(decision.code());
    }
    if (failure instanceof io.grpc.StatusRuntimeException transport) {
      if (!custodyCurrent && transport.getStatus().getCode() == io.grpc.Status.Code.CANCELLED) {
        return Optional.of("CUSTODY_LOST");
      }
      return Optional.of(transport.getStatus().getCode().name());
    }
    if (failure instanceof InitialGameplayLoginCredentialPresentation.CustodyLostException) {
      return Optional.of("CUSTODY_LOST");
    }
    return failure == null ? Optional.empty() : Optional.of("LOGIN_UNAVAILABLE");
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Initial credential LOGIN owner is unavailable");
  }

  public enum Status {
    INSTALLED_NON_ADMITTING,
    CLEANUP_HELD,
    CLEANUP_ACCOUNT_PENDING,
    CLEANUP_ACCOUNT_RETIRED,
    CLEANUP_ACCOUNT_NO_TOKEN
  }

  /**
   * Original identity and lifecycle classification, plus one transferable private result only on
   * the non-admitting installed outcome. The receiver owns a transferred result and must invoke the
   * exact-original cleanup callback only at actual pre-admission custody loss; closing an
   * untransferred outcome wipes its retained result and attempts that cleanup immediately.
   */
  public static final class Outcome implements AutoCloseable {
    private final InitialGameplayLoginAttemptCoordinates coordinates;
    private final Status status;
    private final Optional<String> failureCode;
    private final InitialGameplayLoginCleanupOwner.Status cleanupStatus;
    private final InitialGameplayLoginCleanupOwner cleanupOwner;
    private CanonicalGameplayLoginResult privateResult;
    private CanonicalGameplayLoginResult transferredResult;
    private final Object lifecycleMutex = new Object();
    private boolean resultTransferred;
    private final Set<Thread> borrowers = new HashSet<>();
    private Thread drainingThread;
    private boolean closed;
    private Optional<InitialGameplayLoginCleanupOwner.CleanupObservation> custodyLossCleanup =
        Optional.empty();

    private Outcome(
        InitialGameplayLoginAttemptCoordinates coordinates,
        Status status,
        Optional<String> failureCode,
        InitialGameplayLoginCleanupOwner.Status cleanupStatus,
        CanonicalGameplayLoginResult privateResult,
        InitialGameplayLoginCleanupOwner cleanupOwner) {
      this.coordinates = Objects.requireNonNull(coordinates, "Original coordinates required");
      this.status = Objects.requireNonNull(status, "Outcome status required");
      this.failureCode = Objects.requireNonNull(failureCode, "Failure code option required");
      this.cleanupStatus = cleanupStatus;
      this.privateResult = privateResult;
      this.cleanupOwner = Objects.requireNonNull(cleanupOwner, "Original cleanup owner required");
      if ((status == Status.INSTALLED_NON_ADMITTING)
          != (cleanupStatus == null && privateResult != null)) {
        throw new IllegalArgumentException("Initial LOGIN outcome classification is invalid");
      }
    }

    private static Outcome installed(
        InitialGameplayLoginAttemptCoordinates coordinates,
        CanonicalGameplayLoginResult privateResult,
        InitialGameplayLoginCleanupOwner cleanupOwner) {
      return new Outcome(
          coordinates,
          Status.INSTALLED_NON_ADMITTING,
          Optional.empty(),
          null,
          privateResult,
          cleanupOwner);
    }

    private static Outcome cleanup(
        InitialGameplayLoginAttemptCoordinates coordinates,
        Status status,
        Optional<String> failureCode,
        InitialGameplayLoginCleanupOwner.Status cleanupStatus,
        InitialGameplayLoginCleanupOwner cleanupOwner) {
      return new Outcome(coordinates, status, failureCode, cleanupStatus, null, cleanupOwner);
    }

    public InitialGameplayLoginAttemptCoordinates coordinates() {
      return coordinates;
    }

    public Status status() {
      return status;
    }

    public Optional<String> failureCode() {
      return failureCode;
    }

    public InitialGameplayLoginCleanupOwner.Status cleanupStatus() {
      return cleanupStatus;
    }

    /**
     * Borrows the identical installed result while this outcome retains ownership. Transfer,
     * closure and retirement first block new borrows and drain existing callbacks. Neither owner
     * nor result monitors cover callback work or cleanup I/O. Reentrant borrowing and lifecycle
     * mutation are refused. This process-local guard supplies no transport or admission authority.
     */
    public <T> T withOwnedPrivateResult(Function<CanonicalGameplayLoginResult, T> action) {
      Objects.requireNonNull(action, "Private result action required");
      Thread borrower = Thread.currentThread();
      CanonicalGameplayLoginResult result;
      synchronized (lifecycleMutex) {
        if (closed
            || drainingThread != null
            || resultTransferred
            || privateResult == null
            || !borrowers.add(borrower)) {
          throw new IllegalStateException("Owned initial LOGIN result is unavailable");
        }
        result = privateResult;
      }
      try {
        return result.withLivePrivateResult(action);
      } finally {
        synchronized (lifecycleMutex) {
          borrowers.remove(borrower);
          lifecycleMutex.notifyAll();
        }
      }
    }

    private void beginDrain() {
      CanonicalGameplayLoginResult retained;
      synchronized (lifecycleMutex) {
        if (borrowers.contains(Thread.currentThread())
            || drainingThread == Thread.currentThread()) {
          throw new IllegalStateException("Initial LOGIN result is borrowed");
        }
        retained = privateResult != null ? privateResult : transferredResult;
      }
      // A direct result callback must not wait behind a drain that is itself waiting for its pin.
      // Check before any mutation, outside the Outcome monitor, including transferred custody.
      if (retained != null) retained.requireNotBorrowedByCurrentThread();
      boolean interrupted = false;
      synchronized (lifecycleMutex) {
        while (drainingThread != null) {
          try {
            lifecycleMutex.wait();
          } catch (InterruptedException interruption) {
            interrupted = true;
          }
        }
        drainingThread = Thread.currentThread();
        while (!borrowers.isEmpty()) {
          try {
            lifecycleMutex.wait();
          } catch (InterruptedException interruption) {
            interrupted = true;
          }
        }
      }
      if (interrupted) Thread.currentThread().interrupt();
    }

    private void endDrain() {
      synchronized (lifecycleMutex) {
        drainingThread = null;
        lifecycleMutex.notifyAll();
      }
    }

    /** Transfers the verified private result exactly once after draining owned borrows. */
    public Optional<CanonicalGameplayLoginResult> takePrivateResult() {
      beginDrain();
      try {
        synchronized (lifecycleMutex) {
          if (closed) throw new IllegalStateException("Initial LOGIN outcome is closed");
          if (status != Status.INSTALLED_NON_ADMITTING) return Optional.empty();
          if (resultTransferred)
            throw new IllegalStateException("Initial LOGIN result was transferred");
          resultTransferred = true;
          CanonicalGameplayLoginResult result = privateResult;
          privateResult = null;
          transferredResult = result;
          return Optional.of(result);
        }
      } finally {
        endDrain();
      }
    }

    /**
     * Called only at actual original pre-admission custody loss, including after transfer or a HELD
     * cleanup observation. It drains owned callbacks, erases the exact result and retries cleanup
     * against the exact original coordinates. It is not a rebind or reset authority.
     */
    public InitialGameplayLoginCleanupOwner.CleanupObservation abandonOriginalAfterCustodyLoss() {
      beginDrain();
      try {
        if (status != Status.INSTALLED_NON_ADMITTING) {
          throw new IllegalStateException("Only an installed original LOGIN can be abandoned");
        }
        eraseRetainedResults(true);
        return recordOriginalCleanup();
      } finally {
        endDrain();
      }
    }

    private void eraseRetainedResults(boolean includeTransferred) {
      CanonicalGameplayLoginResult retained;
      synchronized (lifecycleMutex) {
        closed = true;
        retained =
            privateResult != null ? privateResult : includeTransferred ? transferredResult : null;
      }
      // Draining excludes new callbacks; result.close also drains direct result borrowers.
      if (retained != null) retained.close();
      synchronized (lifecycleMutex) {
        privateResult = null;
        if (includeTransferred) transferredResult = null;
      }
    }

    private InitialGameplayLoginCleanupOwner.CleanupObservation recordOriginalCleanup() {
      var observation = attemptOriginalCleanup();
      synchronized (lifecycleMutex) {
        custodyLossCleanup = Optional.of(observation);
      }
      return observation;
    }

    public Optional<InitialGameplayLoginCleanupOwner.CleanupObservation> custodyLossCleanup() {
      synchronized (lifecycleMutex) {
        return custodyLossCleanup;
      }
    }

    /**
     * Closing untransferred custody drains and wipes its result, then attempts exact-original
     * cleanup. Transfer leaves cleanup to the explicit custody-loss callback. A held observation
     * remains retryable through that callback; cleanup never runs under a lifecycle monitor.
     */
    @Override
    public void close() {
      beginDrain();
      try {
        boolean retained;
        synchronized (lifecycleMutex) {
          retained = privateResult != null;
        }
        eraseRetainedResults(false);
        if (retained) recordOriginalCleanup();
      } finally {
        endDrain();
      }
    }

    private InitialGameplayLoginCleanupOwner.CleanupObservation attemptOriginalCleanup() {
      try {
        var observation = cleanupOwner.abandonAndRetire(coordinates);
        if (observation != null && coordinates.equals(observation.coordinates())) {
          return observation;
        }
      } catch (RuntimeException unavailable) {
        // Preserve the exact original obligation as held; no cleanup failure becomes authority.
      }
      return new InitialGameplayLoginCleanupOwner.CleanupObservation(
          coordinates,
          InitialGameplayLoginCleanupOwner.Status.HELD,
          Optional.<InitialGameplayLoginDisposalResult>empty(),
          Optional.<InitialGameplayLoginDisposalResult>empty());
    }

    @Override
    public String toString() {
      return "InitialGameplayLoginOutcome[non-admitting,redacted]";
    }
  }
}
