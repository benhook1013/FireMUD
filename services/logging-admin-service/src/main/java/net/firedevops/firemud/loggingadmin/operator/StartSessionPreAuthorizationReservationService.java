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
  private final MutationCapability mutationCapability;

  @Autowired
  public StartSessionPreAuthorizationReservationService(
      StartSessionPreAuthorizationReservationRepository repository) {
    this(repository, Clock.systemUTC(), null);
  }

  StartSessionPreAuthorizationReservationService(
      StartSessionPreAuthorizationReservationRepository repository, Clock clock) {
    this(repository, clock, null);
  }

  private StartSessionPreAuthorizationReservationService(
      StartSessionPreAuthorizationReservationRepository repository,
      Clock clock,
      MutationCapability mutationCapability) {
    this.repository = Objects.requireNonNull(repository, "repository is required");
    this.clock = Objects.requireNonNull(clock, "clock is required");
    this.mutationCapability = mutationCapability;
  }

  /**
   * Atomically creates one reservation or returns the exact existing reservation as a duplicate.
   * Only the first caller receives a claim capability; an exact duplicate cannot dispatch work.
   */
  public Acquisition acquire(StartSessionPreAuthorizationReservationTuple tuple) {
    requireMutationCapability();
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
                ClaimPurpose.ORIGINAL)
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
   * Reads one exact active Account evidence claim. Issue evidence is available only after the
   * reservation is pending and only for an original or reserved-state recovery claim. Recovery
   * evidence is read-only and may inspect the pending or already-authorized original response.
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
    if (purpose == ReadPurpose.ISSUE && currentClaimFence < reservationClaimFence) {
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
            purpose,
            epochMillis(clock.instant()));
    if (read.isEmpty()) {
      return Optional.empty();
    }
    StartSessionPreAuthorizationReservationRepository.CurrentClaimResult result =
        read.orElseThrow();
    long observedAtEpochMillis = epochMillis(clock.instant());
    Snapshot snapshot = result.snapshot();
    boolean allowedState =
        purpose == ReadPurpose.ISSUE
            ? snapshot.state() == State.AUTHORIZATION_PENDING
            : snapshot.state() == State.AUTHORIZATION_PENDING
                || snapshot.state() == State.AUTHORIZED;
    if (snapshot.phase() != Phase.ACCOUNT_AUTHORIZATION
        || !allowedState
        || snapshot.claimState() != ClaimState.ACTIVE
        || snapshot.reservationClaimFence() != reservationClaimFence
        || snapshot.claimFence() != currentClaimFence
        || !snapshot.tuple().canonicalJson().equals(tuple.canonicalJson())
        || !snapshot.mutationDigest().equals(tuple.mutationDigest())
        || !result.reservationOwnerId().equals(reservationOwnerId)
        || !result.currentClaimOwnerId().equals(currentClaimOwnerId)
        || !claimPurposeAllowsRead(snapshot, purpose, reservationOwnerId, currentClaimOwnerId)
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
    requireMutationCapability();
    requireClaim(claim);
    if (!isIssueClaimPurpose(claim.purpose)) {
      throw new StaleReservationClaimException(claim.controlPlaneRequestId());
    }
    StartSessionPreAuthorizationReservationRepository.TransitionResult result =
        repository.markAuthorizationPending(
            claim.tuple,
            claim.mutationDigest,
            claim.reservationOwnerId,
            claim.reservationFence,
            claim.ownerId,
            claim.fence,
            epochMillis(clock.instant()));
    return new PendingTransition(result.snapshot(), result.transitioned());
  }

  /** Renew only the exact current, active claim before its expiry. */
  public Snapshot renew(ClaimHandle claim, Phase expectedPhase, State expectedState) {
    requireMutationCapability();
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
    requireMutationCapability();
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
   * Acquire one fresh state-scoped recovery claim after expiry. A RESERVED claim can only finish
   * the first pending transition and issue once; a pending claim can only recover Account's
   * original response; an authorized claim can only look up that same response and record the
   * original bounded owner handoff. Original reservation owner/fence evidence remains immutable.
   */
  public Optional<RecoveryAcquisition> acquireAuthorizationRecoveryClaim(
      StartSessionPreAuthorizationReservationTuple tuple) {
    requireMutationCapability();
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
                    result.snapshot().claimPurpose())));
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
    requireMutationCapability();
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
    requireMutationCapability();
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

  public enum ClaimPurpose {
    ORIGINAL,
    RESERVED_RECOVERY_ISSUE,
    AUTHORIZATION_RECOVERY,
    AUTHORIZED_RESPONSE_RECOVERY
  }

  public record Snapshot(
      StartSessionPreAuthorizationReservationTuple tuple,
      String mutationDigest,
      Phase phase,
      State state,
      long reservationClaimFence,
      long claimFence,
      long claimExpiresAtEpochMillis,
      ClaimState claimState,
      ClaimPurpose claimPurpose) {
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
      if (claimState == ClaimState.EXPIRED
          ? claimPurpose != null
          : !purposeAllowedForState(state, claimPurpose)) {
        throw new IllegalArgumentException("reservation claim purpose and state are inconsistent");
      }
      if (claimState == ClaimState.ACTIVE
          && ((claimPurpose == ClaimPurpose.ORIGINAL && claimFence != reservationClaimFence)
              || (claimPurpose != ClaimPurpose.ORIGINAL && claimFence <= reservationClaimFence))) {
        throw new IllegalArgumentException("reservation claim purpose and fence are inconsistent");
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

  private void requireMutationCapability() {
    if (mutationCapability == null) {
      throw new UnsupportedOperationException(
          "StartSession reservation mutations are disabled for production construction");
    }
  }

  private static final class MutationCapability {
    private MutationCapability() {}
  }

  private static boolean purposeAllowedForState(State state, ClaimPurpose purpose) {
    if (purpose == null) {
      return false;
    }
    return switch (state) {
      case RESERVED ->
          purpose == ClaimPurpose.ORIGINAL || purpose == ClaimPurpose.RESERVED_RECOVERY_ISSUE;
      case AUTHORIZATION_PENDING ->
          purpose == ClaimPurpose.ORIGINAL
              || purpose == ClaimPurpose.RESERVED_RECOVERY_ISSUE
              || purpose == ClaimPurpose.AUTHORIZATION_RECOVERY;
      case AUTHORIZED, OWNER_EXECUTION_PENDING ->
          purpose == ClaimPurpose.ORIGINAL
              || purpose == ClaimPurpose.RESERVED_RECOVERY_ISSUE
              || purpose == ClaimPurpose.AUTHORIZATION_RECOVERY
              || purpose == ClaimPurpose.AUTHORIZED_RESPONSE_RECOVERY;
    };
  }

  private static boolean isIssueClaimPurpose(ClaimPurpose purpose) {
    return purpose == ClaimPurpose.ORIGINAL || purpose == ClaimPurpose.RESERVED_RECOVERY_ISSUE;
  }

  private static boolean claimPurposeAllowsRead(
      Snapshot snapshot, ReadPurpose purpose, UUID reservationOwnerId, UUID currentClaimOwnerId) {
    if (purpose == ReadPurpose.ISSUE) {
      return snapshot.state() == State.AUTHORIZATION_PENDING
          && ((snapshot.claimPurpose() == ClaimPurpose.ORIGINAL
                  && reservationOwnerId.equals(currentClaimOwnerId)
                  && snapshot.claimFence() == snapshot.reservationClaimFence())
              || (snapshot.claimPurpose() == ClaimPurpose.RESERVED_RECOVERY_ISSUE
                  && !reservationOwnerId.equals(currentClaimOwnerId)
                  && snapshot.claimFence() > snapshot.reservationClaimFence()));
    }
    return !reservationOwnerId.equals(currentClaimOwnerId)
        && snapshot.claimFence() > snapshot.reservationClaimFence()
        && (snapshot.state() == State.AUTHORIZATION_PENDING
                && snapshot.claimPurpose() == ClaimPurpose.AUTHORIZATION_RECOVERY
            || snapshot.state() == State.AUTHORIZED
                && (snapshot.claimPurpose() == ClaimPurpose.AUTHORIZATION_RECOVERY
                    || snapshot.claimPurpose() == ClaimPurpose.AUTHORIZED_RESPONSE_RECOVERY));
  }

  /** Opaque capability returned only to the caller that acquired a new claim. */
  public static final class ClaimHandle {
    private final StartSessionPreAuthorizationReservationTuple tuple;
    private final String mutationDigest;
    private final UUID reservationOwnerId;
    private final UUID ownerId;
    private final long reservationFence;
    private final long fence;
    private final ClaimPurpose purpose;

    private ClaimHandle(
        StartSessionPreAuthorizationReservationTuple tuple,
        String mutationDigest,
        UUID reservationOwnerId,
        UUID ownerId,
        long reservationFence,
        long fence,
        ClaimPurpose purpose) {
      this.tuple = Objects.requireNonNull(tuple, "tuple is required");
      this.mutationDigest = Objects.requireNonNull(mutationDigest, "mutationDigest is required");
      this.reservationOwnerId =
          Objects.requireNonNull(reservationOwnerId, "reservationOwnerId is required");
      this.ownerId = Objects.requireNonNull(ownerId, "ownerId is required");
      this.reservationFence = reservationFence;
      this.fence = fence;
      this.purpose = Objects.requireNonNull(purpose, "claim purpose is required");
      if (reservationOwnerId.equals(new UUID(0L, 0L))
          || ownerId.equals(new UUID(0L, 0L))
          || reservationFence <= 0
          || fence < reservationFence
          || (purpose == ClaimPurpose.ORIGINAL
              && (!reservationOwnerId.equals(ownerId) || fence != reservationFence))
          || (purpose != ClaimPurpose.ORIGINAL
              && (reservationOwnerId.equals(ownerId) || fence <= reservationFence))) {
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
      return purpose == ClaimPurpose.AUTHORIZATION_RECOVERY
          || purpose == ClaimPurpose.AUTHORIZED_RESPONSE_RECOVERY;
    }

    public ClaimPurpose purpose() {
      return purpose;
    }
  }

  public record Acquisition(Snapshot snapshot, ClaimHandle claim) {
    public Acquisition {
      Objects.requireNonNull(snapshot, "snapshot is required");
      if (claim != null
          && (claim.purpose != ClaimPurpose.ORIGINAL
              || snapshot.claimPurpose() != ClaimPurpose.ORIGINAL
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
          || snapshot.state() == State.OWNER_EXECUTION_PENDING
          || snapshot.claimState() != ClaimState.ACTIVE
          || claim.purpose == ClaimPurpose.ORIGINAL
          || snapshot.claimPurpose() != claim.purpose
          || snapshot.claimFence() != claim.fence
          || !snapshot.tuple().canonicalJson().equals(claim.tuple.canonicalJson())
          || !snapshot.mutationDigest().equals(claim.mutationDigest)) {
        throw new IllegalArgumentException("recovery claim is not a valid state-scoped claim");
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
