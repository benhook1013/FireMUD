package net.firedevops.firemud.loggingadmin.repository;

import static net.firedevops.firemud.loggingadmin.jooq.tables.StartSessionPreAuthorizationReservations.START_SESSION_PRE_AUTHORIZATION_RESERVATIONS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.loggingadmin.jooq.tables.StartSessionPreAuthorizationReservations;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimState;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Phase;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Snapshot;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.State;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationTuple;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** jOOQ persistence for the single-key StartSession ADR 0048 reservation phase. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class StartSessionPreAuthorizationReservationRepository {
  private static final StartSessionPreAuthorizationReservations TABLE =
      START_SESSION_PRE_AUTHORIZATION_RESERVATIONS;

  private final DSLContext dsl;

  public StartSessionPreAuthorizationReservationRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public AcquireResult acquire(
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID ownerId,
      long nowEpochMillis,
      long expiresAtEpochMillis) {
    requireBoundedClaim(ownerId, nowEpochMillis, expiresAtEpochMillis);
    if (!tuple.isAuthorityDerived()) {
      throw new IllegalArgumentException(
          "initial reservation requires an authority-derived StartSession tuple");
    }
    String tupleJson = tuple.canonicalJson();
    String mutationDigest = tuple.mutationDigest();
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          int inserted =
              tx.insertInto(TABLE)
                  .set(TABLE.CONTROL_PLANE_REQUEST_ID, tuple.controlPlaneRequestId())
                  .set(TABLE.PRE_AUTHORIZATION_TUPLE_JSON, tupleJson)
                  .set(TABLE.MUTATION_DIGEST, mutationDigest)
                  .set(TABLE.PHASE, Phase.ACCOUNT_AUTHORIZATION.name())
                  .set(TABLE.STATE, State.RESERVED.name())
                  .set(TABLE.RESERVATION_OWNER_ID, ownerId)
                  .set(TABLE.RESERVATION_CLAIM_FENCE, 1L)
                  .set(TABLE.CLAIM_OWNER_ID, ownerId)
                  .set(TABLE.CLAIM_FENCE, 1L)
                  .set(TABLE.CLAIM_EXPIRES_AT_EPOCH_MS, expiresAtEpochMillis)
                  .set(TABLE.CLAIM_STATE, ClaimState.ACTIVE.name())
                  .set(TABLE.CREATED_AT_EPOCH_MS, nowEpochMillis)
                  .set(TABLE.UPDATED_AT_EPOCH_MS, nowEpochMillis)
                  .onConflict(TABLE.CONTROL_PLANE_REQUEST_ID)
                  .doNothing()
                  .execute();
          Record row = lockByRequestId(tx, tuple.controlPlaneRequestId());
          if (row == null) {
            throw new IllegalStateException("reservation row was not visible after acquire");
          }
          Snapshot snapshot = toSnapshot(row);
          requireExactTuple(row, tuple, mutationDigest);
          return new AcquireResult(snapshot, inserted == 1);
        });
  }

  public Optional<Snapshot> find(String controlPlaneRequestId) {
    StartSessionPreAuthorizationReservationTuple.requireCanonicalControlPlaneRequestId(
        controlPlaneRequestId);
    return dsl.selectFrom(TABLE)
        .where(TABLE.CONTROL_PLANE_REQUEST_ID.eq(controlPlaneRequestId))
        .fetchOptional(this::toSnapshot);
  }

  public Optional<Snapshot> findExact(StartSessionPreAuthorizationReservationTuple tuple) {
    return find(tuple.controlPlaneRequestId())
        .map(
            snapshot -> {
              if (!snapshot.tuple().canonicalJson().equals(tuple.canonicalJson())
                  || !snapshot.mutationDigest().equals(tuple.mutationDigest())) {
                throw new StartSessionPreAuthorizationReservationService
                    .IdempotencyConflictException(tuple.controlPlaneRequestId());
              }
              return snapshot;
            });
  }

  public TransitionResult markAuthorizationPending(
      StartSessionPreAuthorizationReservationTuple tuple,
      String mutationDigest,
      UUID ownerId,
      long claimFence,
      long nowEpochMillis) {
    requireClaimOwner(ownerId, claimFence, nowEpochMillis);
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          int updated =
              tx.update(TABLE)
                  .set(TABLE.STATE, State.AUTHORIZATION_PENDING.name())
                  .set(TABLE.UPDATED_AT_EPOCH_MS, nowEpochMillis)
                  .where(TABLE.CONTROL_PLANE_REQUEST_ID.eq(tuple.controlPlaneRequestId()))
                  .and(TABLE.PRE_AUTHORIZATION_TUPLE_JSON.eq(tuple.canonicalJson()))
                  .and(TABLE.MUTATION_DIGEST.eq(mutationDigest))
                  .and(TABLE.PHASE.eq(Phase.ACCOUNT_AUTHORIZATION.name()))
                  .and(TABLE.STATE.eq(State.RESERVED.name()))
                  .and(TABLE.RESERVATION_OWNER_ID.eq(ownerId))
                  .and(TABLE.RESERVATION_CLAIM_FENCE.eq(1L))
                  .and(TABLE.CLAIM_OWNER_ID.eq(ownerId))
                  .and(TABLE.CLAIM_FENCE.eq(claimFence))
                  .and(TABLE.CLAIM_STATE.eq(ClaimState.ACTIVE.name()))
                  .and(TABLE.CLAIM_EXPIRES_AT_EPOCH_MS.gt(nowEpochMillis))
                  .execute();
          Record row = lockByRequestId(tx, tuple.controlPlaneRequestId());
          if (row == null) {
            throw new StartSessionPreAuthorizationReservationService.StaleReservationClaimException(
                tuple.controlPlaneRequestId());
          }
          Snapshot snapshot = toSnapshot(row);
          requireExactTuple(row, tuple, mutationDigest);
          if (updated == 1) {
            return new TransitionResult(snapshot, true);
          }
          if (isCurrentClaim(row, ownerId, claimFence, nowEpochMillis)
              && snapshot.phase() == Phase.ACCOUNT_AUTHORIZATION
              && snapshot.state() == State.AUTHORIZATION_PENDING) {
            return new TransitionResult(snapshot, false);
          }
          throw new StartSessionPreAuthorizationReservationService.StaleReservationClaimException(
              tuple.controlPlaneRequestId());
        });
  }

  public Snapshot renew(
      StartSessionPreAuthorizationReservationTuple tuple,
      String mutationDigest,
      UUID ownerId,
      long claimFence,
      Phase expectedPhase,
      State expectedState,
      long nowEpochMillis,
      long expiresAtEpochMillis) {
    requireClaimOwner(ownerId, claimFence, nowEpochMillis);
    Objects.requireNonNull(expectedPhase, "expectedPhase is required");
    Objects.requireNonNull(expectedState, "expectedState is required");
    requireBoundedClaim(ownerId, nowEpochMillis, expiresAtEpochMillis);
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          int updated =
              tx.update(TABLE)
                  .set(TABLE.CLAIM_EXPIRES_AT_EPOCH_MS, expiresAtEpochMillis)
                  .set(TABLE.UPDATED_AT_EPOCH_MS, nowEpochMillis)
                  .where(TABLE.CONTROL_PLANE_REQUEST_ID.eq(tuple.controlPlaneRequestId()))
                  .and(TABLE.PRE_AUTHORIZATION_TUPLE_JSON.eq(tuple.canonicalJson()))
                  .and(TABLE.MUTATION_DIGEST.eq(mutationDigest))
                  .and(TABLE.PHASE.eq(expectedPhase.name()))
                  .and(TABLE.STATE.eq(expectedState.name()))
                  .and(TABLE.CLAIM_OWNER_ID.eq(ownerId))
                  .and(TABLE.CLAIM_FENCE.eq(claimFence))
                  .and(TABLE.CLAIM_STATE.eq(ClaimState.ACTIVE.name()))
                  .and(TABLE.CLAIM_EXPIRES_AT_EPOCH_MS.gt(nowEpochMillis))
                  .execute();
          Record row = lockByRequestId(tx, tuple.controlPlaneRequestId());
          if (row == null) {
            throw new StartSessionPreAuthorizationReservationService.StaleReservationClaimException(
                tuple.controlPlaneRequestId());
          }
          Snapshot snapshot = toSnapshot(row);
          requireExactTuple(row, tuple, mutationDigest);
          if (updated != 1) {
            throw new StartSessionPreAuthorizationReservationService.StaleReservationClaimException(
                tuple.controlPlaneRequestId());
          }
          return snapshot;
        });
  }

  public Snapshot expireClaim(
      StartSessionPreAuthorizationReservationTuple tuple,
      String mutationDigest,
      UUID ownerId,
      long claimFence,
      Phase expectedPhase,
      State expectedState,
      long nowEpochMillis) {
    requireClaimOwner(ownerId, claimFence, nowEpochMillis);
    Objects.requireNonNull(expectedPhase, "expectedPhase is required");
    Objects.requireNonNull(expectedState, "expectedState is required");
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          int updated =
              tx.update(TABLE)
                  .setNull(TABLE.CLAIM_OWNER_ID)
                  .set(TABLE.CLAIM_FENCE, TABLE.CLAIM_FENCE.add(1L))
                  .set(TABLE.CLAIM_STATE, ClaimState.EXPIRED.name())
                  .set(TABLE.UPDATED_AT_EPOCH_MS, nowEpochMillis)
                  .where(TABLE.CONTROL_PLANE_REQUEST_ID.eq(tuple.controlPlaneRequestId()))
                  .and(TABLE.PRE_AUTHORIZATION_TUPLE_JSON.eq(tuple.canonicalJson()))
                  .and(TABLE.MUTATION_DIGEST.eq(mutationDigest))
                  .and(TABLE.PHASE.eq(expectedPhase.name()))
                  .and(TABLE.STATE.eq(expectedState.name()))
                  .and(TABLE.CLAIM_OWNER_ID.eq(ownerId))
                  .and(TABLE.CLAIM_FENCE.eq(claimFence))
                  .and(TABLE.CLAIM_STATE.eq(ClaimState.ACTIVE.name()))
                  .and(TABLE.CLAIM_EXPIRES_AT_EPOCH_MS.le(nowEpochMillis))
                  .execute();
          Record row = lockByRequestId(tx, tuple.controlPlaneRequestId());
          if (row == null) {
            throw new StartSessionPreAuthorizationReservationService.StaleReservationClaimException(
                tuple.controlPlaneRequestId());
          }
          Snapshot snapshot = toSnapshot(row);
          requireExactTuple(row, tuple, mutationDigest);
          if (updated == 1) {
            return snapshot;
          }
          if (isCurrentClaim(row, ownerId, claimFence, nowEpochMillis)) {
            throw new StartSessionPreAuthorizationReservationService
                .ReservationClaimNotExpiredException(tuple.controlPlaneRequestId());
          }
          throw new StartSessionPreAuthorizationReservationService.StaleReservationClaimException(
              tuple.controlPlaneRequestId());
        });
  }

  public Optional<RecoveryClaimResult> acquireRecoveryClaim(
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID recoveryOwnerId,
      long nowEpochMillis,
      long expiresAtEpochMillis) {
    requireBoundedClaim(recoveryOwnerId, nowEpochMillis, expiresAtEpochMillis);
    String mutationDigest = tuple.mutationDigest();
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          Record row = lockByRequestId(tx, tuple.controlPlaneRequestId());
          if (row == null) {
            return Optional.empty();
          }
          Snapshot snapshot = toSnapshot(row);
          requireExactTuple(row, tuple, mutationDigest);
          if (snapshot.phase() != Phase.ACCOUNT_AUTHORIZATION
              || snapshot.state() != State.AUTHORIZATION_PENDING) {
            return Optional.empty();
          }
          UUID reservationOwnerId = row.get(TABLE.RESERVATION_OWNER_ID);
          String previousClaimState = row.get(TABLE.CLAIM_STATE);
          long previousFence = row.get(TABLE.CLAIM_FENCE);
          long previousExpiry = row.get(TABLE.CLAIM_EXPIRES_AT_EPOCH_MS);
          if (!ClaimState.EXPIRED.name().equals(previousClaimState)
              && !(ClaimState.ACTIVE.name().equals(previousClaimState)
                  && previousExpiry <= nowEpochMillis)) {
            return Optional.empty();
          }
          long recoveryFence = Math.addExact(previousFence, 1L);
          int updated =
              tx.update(TABLE)
                  .set(TABLE.CLAIM_OWNER_ID, recoveryOwnerId)
                  .set(TABLE.CLAIM_FENCE, recoveryFence)
                  .set(TABLE.CLAIM_EXPIRES_AT_EPOCH_MS, expiresAtEpochMillis)
                  .set(TABLE.CLAIM_STATE, ClaimState.ACTIVE.name())
                  .set(TABLE.UPDATED_AT_EPOCH_MS, nowEpochMillis)
                  .where(TABLE.CONTROL_PLANE_REQUEST_ID.eq(tuple.controlPlaneRequestId()))
                  .and(TABLE.PRE_AUTHORIZATION_TUPLE_JSON.eq(tuple.canonicalJson()))
                  .and(TABLE.MUTATION_DIGEST.eq(mutationDigest))
                  .and(TABLE.PHASE.eq(Phase.ACCOUNT_AUTHORIZATION.name()))
                  .and(TABLE.STATE.eq(State.AUTHORIZATION_PENDING.name()))
                  .and(TABLE.CLAIM_FENCE.eq(previousFence))
                  .and(TABLE.CLAIM_STATE.eq(previousClaimState))
                  .and(
                      ClaimState.EXPIRED.name().equals(previousClaimState)
                          ? DSL.trueCondition()
                          : TABLE.CLAIM_EXPIRES_AT_EPOCH_MS.le(nowEpochMillis))
                  .execute();
          if (updated != 1) {
            return Optional.empty();
          }
          Record updatedRow = lockByRequestId(tx, tuple.controlPlaneRequestId());
          if (updatedRow == null) {
            throw new IllegalStateException("recovery claim row disappeared after update");
          }
          return Optional.of(new RecoveryClaimResult(toSnapshot(updatedRow), reservationOwnerId));
        });
  }

  private Record lockByRequestId(DSLContext context, String controlPlaneRequestId) {
    return context
        .selectFrom(TABLE)
        .where(TABLE.CONTROL_PLANE_REQUEST_ID.eq(controlPlaneRequestId))
        .forUpdate()
        .fetchOne();
  }

  private void requireBoundedClaim(UUID ownerId, long nowEpochMillis, long expiresAtEpochMillis) {
    requireClaimOwner(ownerId, 1L, nowEpochMillis);
    if (expiresAtEpochMillis <= nowEpochMillis
        || expiresAtEpochMillis - nowEpochMillis
            > StartSessionPreAuthorizationReservationService.CLAIM_TTL.toMillis()) {
      throw new IllegalArgumentException("reservation claim must have a bounded positive expiry");
    }
  }

  private void requireClaimOwner(UUID ownerId, long claimFence, long nowEpochMillis) {
    if (ownerId == null
        || ownerId.equals(new UUID(0L, 0L))
        || claimFence <= 0L
        || nowEpochMillis <= 0L) {
      throw new IllegalArgumentException("reservation claim identity and time must be positive");
    }
  }

  private Snapshot toSnapshot(Record row) {
    String requestId = row.get(TABLE.CONTROL_PLANE_REQUEST_ID);
    String tupleJson = row.get(TABLE.PRE_AUTHORIZATION_TUPLE_JSON);
    String storedDigest = row.get(TABLE.MUTATION_DIGEST);
    StartSessionPreAuthorizationReservationTuple tuple;
    try {
      tuple = StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(tupleJson);
    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "stored StartSession pre-authorization tuple is malformed for " + requestId, exception);
    }
    if (!requestId.equals(tuple.controlPlaneRequestId())) {
      throw new IllegalStateException(
          "stored StartSession tuple key does not match reservation key " + requestId);
    }
    String recomputedDigest = tuple.mutationDigest();
    if (!recomputedDigest.equals(storedDigest)) {
      throw new IllegalStateException(
          "stored StartSession mutationDigest does not match its full tuple for " + requestId);
    }
    Phase phase = enumValue(Phase.class, row.get(TABLE.PHASE), "phase", requestId);
    State state = enumValue(State.class, row.get(TABLE.STATE), "state", requestId);
    ClaimState claimState =
        enumValue(ClaimState.class, row.get(TABLE.CLAIM_STATE), "claimState", requestId);
    UUID reservationOwnerId = row.get(TABLE.RESERVATION_OWNER_ID);
    UUID currentClaimOwnerId = row.get(TABLE.CLAIM_OWNER_ID);
    long originalFence = row.get(TABLE.RESERVATION_CLAIM_FENCE);
    long currentFence = row.get(TABLE.CLAIM_FENCE);
    long createdAt = row.get(TABLE.CREATED_AT_EPOCH_MS);
    long updatedAt = row.get(TABLE.UPDATED_AT_EPOCH_MS);
    if (phase != Phase.ACCOUNT_AUTHORIZATION
        || (state != State.RESERVED && state != State.AUTHORIZATION_PENDING)
        || reservationOwnerId == null
        || reservationOwnerId.equals(new UUID(0L, 0L))
        || originalFence != 1L
        || currentFence < originalFence
        || createdAt <= 0L
        || updatedAt <= 0L
        || (claimState == ClaimState.ACTIVE
            && (currentClaimOwnerId == null || currentClaimOwnerId.equals(new UUID(0L, 0L))))
        || (claimState == ClaimState.EXPIRED
            && (currentClaimOwnerId != null || currentFence <= originalFence))) {
      throw new IllegalStateException("stored StartSession reservation lifecycle is malformed");
    }
    return new Snapshot(
        tuple,
        storedDigest,
        phase,
        state,
        originalFence,
        currentFence,
        row.get(TABLE.CLAIM_EXPIRES_AT_EPOCH_MS),
        claimState);
  }

  private void requireExactTuple(
      Record row, StartSessionPreAuthorizationReservationTuple tuple, String mutationDigest) {
    if (!tuple.controlPlaneRequestId().equals(row.get(TABLE.CONTROL_PLANE_REQUEST_ID))
        || !tuple.canonicalJson().equals(row.get(TABLE.PRE_AUTHORIZATION_TUPLE_JSON))
        || !tuple.mutationDigest().equals(mutationDigest)
        || !mutationDigest.equals(row.get(TABLE.MUTATION_DIGEST))) {
      throw new StartSessionPreAuthorizationReservationService.IdempotencyConflictException(
          tuple.controlPlaneRequestId());
    }
  }

  private boolean isCurrentClaim(Record row, UUID ownerId, long claimFence, long nowEpochMillis) {
    return ownerId.equals(row.get(TABLE.CLAIM_OWNER_ID))
        && claimFence == row.get(TABLE.CLAIM_FENCE)
        && ClaimState.ACTIVE.name().equals(row.get(TABLE.CLAIM_STATE))
        && row.get(TABLE.CLAIM_EXPIRES_AT_EPOCH_MS) > nowEpochMillis;
  }

  private <T extends Enum<T>> T enumValue(
      Class<T> enumClass, String value, String fieldName, String requestId) {
    try {
      return Enum.valueOf(enumClass, value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "stored StartSession reservation " + fieldName + " is invalid for " + requestId,
          exception);
    }
  }

  public record AcquireResult(Snapshot snapshot, boolean created) {}

  public record TransitionResult(Snapshot snapshot, boolean transitioned) {}

  public record RecoveryClaimResult(Snapshot snapshot, UUID reservationOwnerId) {}
}
