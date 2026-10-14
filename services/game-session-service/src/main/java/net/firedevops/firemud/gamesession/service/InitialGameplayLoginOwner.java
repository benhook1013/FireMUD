package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
          return cleanup(coordinates, null);
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
            return cleanup(coordinates, null);
          }
          if (!credentials.isCurrent()) {
            return cleanup(
                coordinates, new InitialGameplayLoginCredentialPresentation.CustodyLostException());
          }
          Outcome outcome = Outcome.installed(coordinates, verified, cleanup);
          transferred = true;
          return outcome;
        } catch (RuntimeException failure) {
          return cleanup(coordinates, failure);
        } finally {
          if (!transferred) verified.close();
        }
      } catch (RuntimeException failure) {
        return cleanup(coordinates, failure);
      }
    }
  }

  private Outcome cleanup(
      InitialGameplayLoginAttemptCoordinates coordinates, RuntimeException failure) {
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
          failureCode(failure),
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
        coordinates, status, failureCode(failure), observation.status(), cleanup);
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

  private static Optional<String> failureCode(RuntimeException failure) {
    if (failure instanceof CanonicalGameplayLoginResult.CredentialDecisionException decision) {
      return Optional.of(decision.code());
    }
    if (failure instanceof io.grpc.StatusRuntimeException transport) {
      if (transport.getStatus().getCode() == io.grpc.Status.Code.CANCELLED) {
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

    /** Transfers the verified private result exactly once to its receiving caller. */
    public Optional<CanonicalGameplayLoginResult> takePrivateResult() {
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
    }

    /**
     * Called only when the original pre-admission LOGIN custody is actually lost. This is not a
     * retained-edge rebind or post-admission disconnect signal. It wipes this owner's retained
     * result, then asks the existing owner to clean the exact original coordinates.
     */
    public InitialGameplayLoginCleanupOwner.CleanupObservation abandonOriginalAfterCustodyLoss() {
      synchronized (lifecycleMutex) {
        if (status != Status.INSTALLED_NON_ADMITTING) {
          throw new IllegalStateException("Only an installed original LOGIN can be abandoned");
        }
        if (privateResult != null) {
          privateResult.close();
          privateResult = null;
        }
        if (transferredResult != null) {
          transferredResult.close();
          transferredResult = null;
        }
        closed = true;
        custodyLossCleanup = Optional.of(attemptOriginalCleanup());
        return custodyLossCleanup.orElseThrow();
      }
    }

    public Optional<InitialGameplayLoginCleanupOwner.CleanupObservation> custodyLossCleanup() {
      synchronized (lifecycleMutex) {
        return custodyLossCleanup;
      }
    }

    /**
     * Wipes and cleans an untransferred installed result because abandoning this owner before
     * transfer loses the original private custody. After transfer, cleanup belongs to the caller
     * and requires its explicit pre-admission custody-loss callback.
     */
    @Override
    public void close() {
      synchronized (lifecycleMutex) {
        if (!closed) {
          closed = true;
          if (privateResult != null) {
            privateResult.close();
            privateResult = null;
            custodyLossCleanup = Optional.of(attemptOriginalCleanup());
          }
        }
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
