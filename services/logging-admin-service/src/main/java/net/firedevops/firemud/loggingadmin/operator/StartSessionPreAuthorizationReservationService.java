package net.firedevops.firemud.loggingadmin.operator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.loggingadmin.repository.StartSessionPreAuthorizationReservationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Durable, bounded Logging/Admin claim lifecycle for the StartSession pre-authorization phase. */
@Service
public final class StartSessionPreAuthorizationReservationService {
  public static final Duration CLAIM_TTL = Duration.ofSeconds(30);

  private final StartSessionPreAuthorizationReservationRepository repository;
  private final Clock clock;

  @Autowired
  public StartSessionPreAuthorizationReservationService(
      StartSessionPreAuthorizationReservationRepository repository) {
    this(repository, Clock.systemUTC());
  }

  StartSessionPreAuthorizationReservationService(
      StartSessionPreAuthorizationReservationRepository repository, Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository is required");
    this.clock = Objects.requireNonNull(clock, "clock is required");
  }

  /**
   * Atomically creates one reservation or returns the exact existing reservation as a duplicate.
   * Only the first caller receives a claim capability; an exact duplicate cannot dispatch work.
   */
  public Acquisition acquire(StartSessionPreAuthorizationReservationTuple tuple) {
    Objects.requireNonNull(tuple, "tuple is required");
    if (!tuple.isAuthorityDerived()) {
      throw new IllegalArgumentException(
          "initial reservation requires an actor derived from the authenticated request context");
    }
    Instant now = clock.instant();
    UUID ownerId = UUID.randomUUID();
    StartSessionPreAuthorizationReservationRepository.AcquireResult result =
        repository.acquire(tuple, ownerId, epochMillis(now), epochMillis(now.plus(CLAIM_TTL)));
    ClaimHandle claim =
        result.created()
            ? new ClaimHandle(
                tuple,
                tuple.mutationDigest(),
                ownerId,
                ownerId,
                result.snapshot().reservationClaimFence(),
                result.snapshot().claimFence(),
                ClaimKind.ORIGINAL)
            : null;
    return new Acquisition(result.snapshot(), claim);
  }

  /** Read-only lookup by the one stable orchestration key. */
  public Optional<Snapshot> find(String controlPlaneRequestId) {
    return repository.find(controlPlaneRequestId);
  }

  /** Read-only exact lookup; a reused key with any tuple difference conflicts. */
  public Optional<Snapshot> findExact(StartSessionPreAuthorizationReservationTuple tuple) {
    Objects.requireNonNull(tuple, "tuple is required");
    return repository.findExact(tuple);
  }

  /**
   * Durably enters AUTHORIZATION_PENDING before the Account call is dispatched by a later
   * integration. Only the first successful transition returns {@code mayDispatch=true}; observing
   * the pending state again never authorizes another issuance call.
   */
  public PendingTransition markAuthorizationPending(ClaimHandle claim) {
    requireClaim(claim);
    if (claim.isRecoveryLookupOnly()) {
      throw new StaleReservationClaimException(claim.controlPlaneRequestId());
    }
    StartSessionPreAuthorizationReservationRepository.TransitionResult result =
        repository.markAuthorizationPending(
            claim.tuple,
            claim.mutationDigest,
            claim.ownerId,
            claim.fence,
            epochMillis(clock.instant()));
    return new PendingTransition(result.snapshot(), result.transitioned());
  }

  /** Renew only the exact current, active claim before its expiry. */
  public Snapshot renew(ClaimHandle claim, Phase expectedPhase, State expectedState) {
    requireClaim(claim);
    Instant now = clock.instant();
    return repository.renew(
        claim.tuple,
        claim.mutationDigest,
        claim.ownerId,
        claim.fence,
        expectedPhase,
        expectedState,
        epochMillis(now),
        epochMillis(now.plus(CLAIM_TTL)));
  }

  /**
   * Advance the current fence after the exact claim expires. Expiry leaves the phase and state
   * unchanged; it is not evidence that Account authorization did not happen.
   */
  public Snapshot expireClaim(ClaimHandle claim, Phase expectedPhase, State expectedState) {
    requireClaim(claim);
    return repository.expireClaim(
        claim.tuple,
        claim.mutationDigest,
        claim.ownerId,
        claim.fence,
        expectedPhase,
        expectedState,
        epochMillis(clock.instant()));
  }

  /**
   * Acquire one fresh recovery claim only for an expired AUTHORIZATION_PENDING reservation. The
   * returned claim is suitable only for a future read-only Account recovery lookup; it cannot
   * initiate issuance or authorize owner forwarding. Original reservation owner/fence evidence is
   * preserved in the row and snapshot.
   */
  public Optional<RecoveryAcquisition> acquireAuthorizationRecoveryClaim(
      StartSessionPreAuthorizationReservationTuple tuple) {
    Objects.requireNonNull(tuple, "tuple is required");
    Instant now = clock.instant();
    UUID recoveryOwnerId = UUID.randomUUID();
    Optional<StartSessionPreAuthorizationReservationRepository.RecoveryClaimResult> acquired =
        repository.acquireRecoveryClaim(
            tuple, recoveryOwnerId, epochMillis(now), epochMillis(now.plus(CLAIM_TTL)));
    return acquired.map(
        result ->
            new RecoveryAcquisition(
                result.snapshot(),
                new ClaimHandle(
                    tuple,
                    tuple.mutationDigest(),
                    result.reservationOwnerId(),
                    recoveryOwnerId,
                    result.snapshot().reservationClaimFence(),
                    result.snapshot().claimFence(),
                    ClaimKind.RECOVERY_LOOKUP_ONLY)));
  }

  private static void requireClaim(ClaimHandle claim) {
    Objects.requireNonNull(claim, "claim is required");
  }

  private static long epochMillis(Instant instant) {
    long millis = instant.toEpochMilli();
    if (millis <= 0) {
      throw new IllegalArgumentException("reservation time must be after the Unix epoch");
    }
    return millis;
  }

  public enum Phase {
    ACCOUNT_AUTHORIZATION
  }

  public enum State {
    RESERVED,
    AUTHORIZATION_PENDING
  }

  public enum ClaimState {
    ACTIVE,
    EXPIRED
  }

  private enum ClaimKind {
    ORIGINAL,
    RECOVERY_LOOKUP_ONLY
  }

  public record Snapshot(
      StartSessionPreAuthorizationReservationTuple tuple,
      String mutationDigest,
      Phase phase,
      State state,
      long reservationClaimFence,
      long claimFence,
      long claimExpiresAtEpochMillis,
      ClaimState claimState) {
    public Snapshot {
      Objects.requireNonNull(tuple, "tuple is required");
      Objects.requireNonNull(mutationDigest, "mutationDigest is required");
      Objects.requireNonNull(phase, "phase is required");
      Objects.requireNonNull(state, "state is required");
      Objects.requireNonNull(claimState, "claimState is required");
      if (reservationClaimFence != 1 || claimFence < reservationClaimFence) {
        throw new IllegalArgumentException("reservation claim fences are invalid");
      }
      if (claimExpiresAtEpochMillis <= 0) {
        throw new IllegalArgumentException("claim expiry must be positive");
      }
      if (!tuple.mutationDigest().equals(mutationDigest)) {
        throw new IllegalArgumentException("stored mutationDigest does not match the typed tuple");
      }
    }
  }

  /** Opaque capability returned only to the caller that acquired a new claim. */
  public static final class ClaimHandle {
    private final StartSessionPreAuthorizationReservationTuple tuple;
    private final String mutationDigest;
    private final UUID reservationOwnerId;
    private final UUID ownerId;
    private final long reservationFence;
    private final long fence;
    private final ClaimKind kind;

    private ClaimHandle(
        StartSessionPreAuthorizationReservationTuple tuple,
        String mutationDigest,
        UUID reservationOwnerId,
        UUID ownerId,
        long reservationFence,
        long fence,
        ClaimKind kind) {
      this.tuple = Objects.requireNonNull(tuple, "tuple is required");
      this.mutationDigest = Objects.requireNonNull(mutationDigest, "mutationDigest is required");
      this.reservationOwnerId =
          Objects.requireNonNull(reservationOwnerId, "reservationOwnerId is required");
      this.ownerId = Objects.requireNonNull(ownerId, "ownerId is required");
      this.reservationFence = reservationFence;
      this.fence = fence;
      this.kind = Objects.requireNonNull(kind, "claim kind is required");
      if (reservationOwnerId.equals(new UUID(0L, 0L))
          || ownerId.equals(new UUID(0L, 0L))
          || reservationFence <= 0
          || fence < reservationFence) {
        throw new IllegalArgumentException("reservation claim fences are invalid");
      }
    }

    public String controlPlaneRequestId() {
      return tuple.controlPlaneRequestId();
    }

    public long claimFence() {
      return fence;
    }

    public StartSessionPreAuthorizationReservationTuple tuple() {
      return tuple;
    }

    public String mutationDigest() {
      return mutationDigest;
    }

    /** Original tuple owner/fence and current fenced claim, kept distinct during recovery. */
    public ClaimEvidence claimEvidence() {
      return new ClaimEvidence(reservationOwnerId, reservationFence, ownerId, fence);
    }

    public boolean isRecoveryLookupOnly() {
      return kind == ClaimKind.RECOVERY_LOOKUP_ONLY;
    }
  }

  public record Acquisition(Snapshot snapshot, ClaimHandle claim) {
    public Acquisition {
      Objects.requireNonNull(snapshot, "snapshot is required");
      if (claim != null
          && (claim.kind != ClaimKind.ORIGINAL
              || snapshot.phase() != Phase.ACCOUNT_AUTHORIZATION
              || snapshot.state() != State.RESERVED
              || snapshot.claimState() != ClaimState.ACTIVE
              || snapshot.claimFence() != claim.fence
              || !snapshot.tuple().canonicalJson().equals(claim.tuple.canonicalJson())
              || !snapshot.mutationDigest().equals(claim.mutationDigest))) {
        throw new IllegalArgumentException("initial claim does not match its reservation");
      }
    }

    public boolean newlyAcquired() {
      return claim != null;
    }
  }

  public static final class PendingTransition {
    private final Snapshot snapshot;
    private final boolean mayDispatchAccountAuthorization;

    private PendingTransition(Snapshot snapshot, boolean mayDispatchAccountAuthorization) {
      this.snapshot = Objects.requireNonNull(snapshot, "snapshot is required");
      this.mayDispatchAccountAuthorization = mayDispatchAccountAuthorization;
      if (snapshot.state() != State.AUTHORIZATION_PENDING && mayDispatchAccountAuthorization) {
        throw new IllegalArgumentException("dispatch requires durable AUTHORIZATION_PENDING state");
      }
    }

    public Snapshot snapshot() {
      return snapshot;
    }

    public boolean mayDispatchAccountAuthorization() {
      return mayDispatchAccountAuthorization;
    }
  }

  public record RecoveryAcquisition(Snapshot snapshot, ClaimHandle claim) {
    public RecoveryAcquisition {
      Objects.requireNonNull(snapshot, "snapshot is required");
      Objects.requireNonNull(claim, "claim is required");
      if (snapshot.phase() != Phase.ACCOUNT_AUTHORIZATION
          || snapshot.state() != State.AUTHORIZATION_PENDING
          || snapshot.claimState() != ClaimState.ACTIVE
          || !claim.isRecoveryLookupOnly()
          || snapshot.claimFence() != claim.fence
          || !snapshot.tuple().canonicalJson().equals(claim.tuple.canonicalJson())
          || !snapshot.mutationDigest().equals(claim.mutationDigest)) {
        throw new IllegalArgumentException(
            "recovery claim is not read-only authorization recovery");
      }
    }
  }

  public record ClaimEvidence(
      UUID reservationOwnerId,
      long reservationClaimFence,
      UUID currentClaimOwnerId,
      long currentClaimFence) {
    public ClaimEvidence {
      Objects.requireNonNull(reservationOwnerId, "reservationOwnerId is required");
      Objects.requireNonNull(currentClaimOwnerId, "currentClaimOwnerId is required");
      if (reservationClaimFence != 1 || currentClaimFence < reservationClaimFence) {
        throw new IllegalArgumentException("reservation claim evidence is invalid");
      }
    }
  }

  public static class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String controlPlaneRequestId) {
      super(
          "controlPlaneRequestId is already bound to a different StartSession tuple: "
              + controlPlaneRequestId);
    }
  }

  public static class StaleReservationClaimException extends RuntimeException {
    public StaleReservationClaimException(String controlPlaneRequestId) {
      super("StartSession reservation claim is stale: " + controlPlaneRequestId);
    }
  }

  public static class ReservationClaimNotExpiredException extends RuntimeException {
    public ReservationClaimNotExpiredException(String controlPlaneRequestId) {
      super("StartSession reservation claim has not expired: " + controlPlaneRequestId);
    }
  }
}
