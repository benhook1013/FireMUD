package net.firedevops.firemud.loggingadmin.operator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.loggingadmin.repository.StartSessionPreAuthorizationReservationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Durable, bounded Logging/Admin claim lifecycle for StartSession authorization and handoff. */
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
   * Reads one exact active AUTHORIZATION_PENDING claim for Account. This is a read snapshot only;
   * it does not renew, transfer, or otherwise mutate the reservation.
   */
  public Optional<CurrentClaimEvidence> readCurrentClaimEvidence(
      String controlPlaneRequestId,
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID reservationOwnerId,
      long reservationClaimFence,
      UUID currentClaimOwnerId,
      long currentClaimFence,
      ReadPurpose purpose) {
    if (controlPlaneRequestId == null) {
      throw new IllegalArgumentException("controlPlaneRequestId is required");
    }
    String canonicalControlPlaneRequestId =
        StartSessionPreAuthorizationReservationTuple.requireCanonicalControlPlaneRequestId(
            controlPlaneRequestId);
    Objects.requireNonNull(tuple, "tuple is required");
    Objects.requireNonNull(reservationOwnerId, "reservationOwnerId is required");
    Objects.requireNonNull(currentClaimOwnerId, "currentClaimOwnerId is required");
    Objects.requireNonNull(purpose, "purpose is required");
    if (!canonicalControlPlaneRequestId.equals(tuple.controlPlaneRequestId())
        || reservationOwnerId.equals(new UUID(0L, 0L))
        || currentClaimOwnerId.equals(new UUID(0L, 0L))
        || reservationClaimFence <= 0L
        || currentClaimFence <= 0L) {
      throw new IllegalArgumentException("exact reservation claim identity is required");
    }
    if (purpose == ReadPurpose.ISSUE
        && (!reservationOwnerId.equals(currentClaimOwnerId)
            || reservationClaimFence != currentClaimFence)) {
      throw new StaleReservationClaimException(tuple.controlPlaneRequestId());
    }
    if (purpose == ReadPurpose.RECOVER
        && (reservationOwnerId.equals(currentClaimOwnerId)
            || currentClaimFence <= reservationClaimFence)) {
      throw new StaleReservationClaimException(tuple.controlPlaneRequestId());
    }

    Optional<StartSessionPreAuthorizationReservationRepository.CurrentClaimResult> read =
        repository.readCurrentClaim(
            tuple,
            reservationOwnerId,
            reservationClaimFence,
            currentClaimOwnerId,
            currentClaimFence,
            epochMillis(clock.instant()));
    if (read.isEmpty()) {
      return Optional.empty();
    }
    StartSessionPreAuthorizationReservationRepository.CurrentClaimResult result =
        read.orElseThrow();
    long observedAtEpochMillis = epochMillis(clock.instant());
    Snapshot snapshot = result.snapshot();
    if (snapshot.phase() != Phase.ACCOUNT_AUTHORIZATION
        || snapshot.state() != State.AUTHORIZATION_PENDING
        || snapshot.claimState() != ClaimState.ACTIVE
        || snapshot.reservationClaimFence() != reservationClaimFence
        || snapshot.claimFence() != currentClaimFence
        || !snapshot.tuple().canonicalJson().equals(tuple.canonicalJson())
        || !snapshot.mutationDigest().equals(tuple.mutationDigest())
        || !result.reservationOwnerId().equals(reservationOwnerId)
        || !result.currentClaimOwnerId().equals(currentClaimOwnerId)
        || snapshot.claimExpiresAtEpochMillis() <= observedAtEpochMillis) {
      throw new StaleReservationClaimException(tuple.controlPlaneRequestId());
    }
    return Optional.of(
        new CurrentClaimEvidence(
            snapshot,
            result.reservationOwnerId(),
            result.currentClaimOwnerId(),
            purpose,
            observedAtEpochMillis));
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
   * Acquire one fresh recovery claim only for an expired AUTHORIZATION_PENDING reservation. It
   * cannot initiate issuance. If the exact original Account response is recovered, the current
   * claim may durably enrich this same row and then record its one owner handoff. Original
   * reservation owner/fence evidence remains distinct from the fresh recovery claim.
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

  /**
   * Enriches the exact live authorization-pending reservation with the original response returned
   * by the authenticated Account client. Callers must not derive this value from caller assertions;
   * the shared tuple validates structure but does not establish Account authority or source
   * freshness. Only its canonical post-authorization tuple is persisted; the opaque reference is
   * not.
   */
  public AuthorizationCompletion completeAuthorization(
      ClaimHandle claim, StartSessionPostAuthorizationExecutionTuple postAuthorizationTuple) {
    requirePostAuthorizationBinding(claim, postAuthorizationTuple);
    long now = epochMillis(clock.instant());
    if (StartSessionAuthorityEvidenceBundle.decode(
                postAuthorizationTuple.authorityEvidenceBundleBytes())
            .expiresAt()
            .toEpochMilli()
        <= now) {
      throw new StaleReservationClaimException(claim.controlPlaneRequestId());
    }
    StartSessionPreAuthorizationReservationRepository.AuthorizationTransitionResult result =
        repository.completeAuthorization(
            claim.tuple,
            claim.mutationDigest,
            claim.reservationOwnerId,
            claim.reservationFence,
            claim.ownerId,
            claim.fence,
            postAuthorizationTuple.canonicalJson(),
            now);
    return new AuthorizationCompletion(result.snapshot(), result.transitioned());
  }

  /**
   * Persists the exact owner handoff before any external forward call. Only the first successful
   * exact transition returns {@code mayDispatch=true}; duplicates only observe the pending row.
   */
  public OwnerExecutionTransition beginOwnerExecution(
      ClaimHandle claim,
      StartSessionPostAuthorizationExecutionTuple postAuthorizationTuple,
      OwnerExecutionHandoff handoff) {
    requirePostAuthorizationBinding(claim, postAuthorizationTuple);
    Objects.requireNonNull(handoff, "owner execution handoff is required");
    long now = epochMillis(clock.instant());
    if (StartSessionAuthorityEvidenceBundle.decode(
                postAuthorizationTuple.authorityEvidenceBundleBytes())
            .expiresAt()
            .toEpochMilli()
        <= now) {
      throw new StaleReservationClaimException(claim.controlPlaneRequestId());
    }
    StartSessionPreAuthorizationReservationRepository.OwnerExecutionTransitionResult result =
        repository.beginOwnerExecution(
            claim.tuple,
            claim.mutationDigest,
            claim.reservationOwnerId,
            claim.reservationFence,
            claim.ownerId,
            claim.fence,
            postAuthorizationTuple.canonicalJson(),
            handoff.handoffId(),
            now);
    return new OwnerExecutionTransition(result.snapshot(), result.mayDispatch());
  }

  /**
   * Read-only exact post-authorization snapshot; it does not revalidate authority or claim
   * freshness and is not a commit-spanning compare-and-set proof.
   */
  public Optional<AuthorizedSnapshot> findAuthorizedExact(
      StartSessionPreAuthorizationReservationTuple tuple) {
    Objects.requireNonNull(tuple, "tuple is required");
    return repository.findAuthorizedExact(tuple);
  }

  private static void requirePostAuthorizationBinding(
      ClaimHandle claim, StartSessionPostAuthorizationExecutionTuple postAuthorizationTuple) {
    requireClaim(claim);
    Objects.requireNonNull(postAuthorizationTuple, "post-authorization tuple is required");
    if (!claim
            .tuple
            .canonicalJson()
            .equals(postAuthorizationTuple.preAuthorizationTuple().canonicalJson())
        || !claim.mutationDigest.equals(postAuthorizationTuple.mutationDigest())
        || !claim.reservationOwnerId.equals(postAuthorizationTuple.reservationOwnerId())
        || claim.reservationFence != postAuthorizationTuple.reservationClaimFence()) {
      throw new IdempotencyConflictException(claim.controlPlaneRequestId());
    }
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
    ACCOUNT_AUTHORIZATION,
    OWNER_EXECUTION
  }

  public enum State {
    RESERVED,
    AUTHORIZATION_PENDING,
    AUTHORIZED,
    OWNER_EXECUTION_PENDING
  }

  public enum ClaimState {
    ACTIVE,
    EXPIRED
  }

  public enum ReadPurpose {
    ISSUE,
    RECOVER
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
      if ((phase == Phase.ACCOUNT_AUTHORIZATION && state == State.OWNER_EXECUTION_PENDING)
          || (phase == Phase.OWNER_EXECUTION && state != State.OWNER_EXECUTION_PENDING)) {
        throw new IllegalArgumentException("reservation phase and state are inconsistent");
      }
    }
  }

  /** Exact immutable post-authorization state; no raw or encrypted opaque reference is included. */
  public record AuthorizedSnapshot(
      Snapshot reservationSnapshot,
      StartSessionPostAuthorizationExecutionTuple postAuthorizationTuple,
      UUID reservationOwnerId,
      UUID currentClaimOwnerId,
      OwnerExecutionHandoff handoff) {
    public AuthorizedSnapshot {
      Objects.requireNonNull(reservationSnapshot, "reservationSnapshot is required");
      Objects.requireNonNull(postAuthorizationTuple, "postAuthorizationTuple is required");
      requireNonNil(reservationOwnerId, "reservationOwnerId");
      if (reservationSnapshot.claimState() == ClaimState.ACTIVE) {
        requireNonNil(currentClaimOwnerId, "currentClaimOwnerId");
      } else if (currentClaimOwnerId != null) {
        throw new IllegalArgumentException(
            "expired reservation cannot retain a current claim owner");
      }
      if (!reservationSnapshot
              .tuple()
              .canonicalJson()
              .equals(postAuthorizationTuple.preAuthorizationTuple().canonicalJson())
          || !reservationOwnerId.equals(postAuthorizationTuple.reservationOwnerId())
          || reservationSnapshot.reservationClaimFence()
              != postAuthorizationTuple.reservationClaimFence()
          || (reservationSnapshot.phase() == Phase.ACCOUNT_AUTHORIZATION
              && reservationSnapshot.state() == State.AUTHORIZED
              && handoff != null)
          || (reservationSnapshot.phase() == Phase.OWNER_EXECUTION
              && reservationSnapshot.state() == State.OWNER_EXECUTION_PENDING
              && handoff == null)
          || (reservationSnapshot.state() != State.AUTHORIZED
              && reservationSnapshot.state() != State.OWNER_EXECUTION_PENDING)) {
        throw new IllegalArgumentException("post-authorization snapshot is inconsistent");
      }
    }
  }

  /** Immutable handoff identity written before the owner allocates its attempt and fence. */
  public record OwnerExecutionHandoff(UUID handoffId) {
    public OwnerExecutionHandoff {
      requireNonNil(handoffId, "handoffId");
    }
  }

  public record AuthorizationCompletion(AuthorizedSnapshot snapshot, boolean transitioned) {
    public AuthorizationCompletion {
      Objects.requireNonNull(snapshot, "snapshot is required");
    }
  }

  public record OwnerExecutionTransition(AuthorizedSnapshot snapshot, boolean mayDispatch) {
    public OwnerExecutionTransition {
      Objects.requireNonNull(snapshot, "snapshot is required");
      if (mayDispatch
          && (snapshot.reservationSnapshot().phase() != Phase.OWNER_EXECUTION
              || snapshot.reservationSnapshot().state() != State.OWNER_EXECUTION_PENDING
              || snapshot.handoff() == null)) {
        throw new IllegalArgumentException("dispatch requires the durable exact owner handoff");
      }
    }
  }

  private static void requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field + " is required");
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(field + " must not be nil");
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

  public record CurrentClaimEvidence(
      Snapshot snapshot,
      UUID reservationOwnerId,
      UUID currentClaimOwnerId,
      ReadPurpose purpose,
      long observedAtEpochMillis) {
    public CurrentClaimEvidence {
      Objects.requireNonNull(snapshot, "snapshot is required");
      Objects.requireNonNull(reservationOwnerId, "reservationOwnerId is required");
      Objects.requireNonNull(currentClaimOwnerId, "currentClaimOwnerId is required");
      Objects.requireNonNull(purpose, "purpose is required");
      if (observedAtEpochMillis <= 0L
          || snapshot.claimExpiresAtEpochMillis() <= observedAtEpochMillis) {
        throw new IllegalArgumentException("current claim evidence must be fresh and unexpired");
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
