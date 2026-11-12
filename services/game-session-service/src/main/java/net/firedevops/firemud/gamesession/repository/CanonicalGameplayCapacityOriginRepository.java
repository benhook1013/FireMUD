package net.firedevops.firemud.gamesession.repository;

import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

/** Private receiving storage. Callers supply the existing admitted/budgeted SQL context. */
final class CanonicalGameplayCapacityOriginRepository {
  private static final String TABLE = "game_session_original_new_capacity_origin";
  private final DSLContext sql;

  CanonicalGameplayCapacityOriginRepository(DSLContext admittedSql) {
    this.sql = Objects.requireNonNull(admittedSql);
  }

  /** Persist before Account RPC. A replay cannot replace the selected origin or gain dispatch. */
  Retained prepare(CanonicalGameplayCapacityOrigin origin) {
    return sql.transactionResult(
        configuration -> {
          var tx = DSL.using(configuration);
          lockCurrent(tx, origin, origin.selectedOwnerId(), origin.selectedOwnerFence());
          refuseFrozen(tx, origin);
          Record old = find(tx, origin, true);
          if (old != null) return exact(old, origin);
          if (tx.fetchExists(
              DSL.selectOne()
                  .from("game_session_original_new_capacity_execution")
                  .where(
                      DSL.field("admission_attempt_id").eq(origin.request().admissionAttemptId()))
                  .or(DSL.field("transition_id").eq(origin.transitionId())))) throw unavailable();
          var candidate = origin.candidate();
          Record transition =
              tx.fetchOne(
                  "SELECT status,original_new_candidate_bytes FROM game_session_canonical_binding_transition WHERE transition_id=? FOR UPDATE",
                  origin.transitionId());
          if (transition == null
              || !"PROVISIONAL".equals(transition.get("status", String.class))
              || !Arrays.equals(
                  candidate.canonicalBytes(),
                  transition.get("original_new_candidate_bytes", byte[].class))) {
            throw unavailable();
          }
          var binding = candidate.bindingIdentity();
          var policy =
              net.firedevops.firemud.common.account.capacity.AccountCapacityOriginContinuation
                  .preAccount(origin.request());
          int inserted =
              tx.execute(
                  "INSERT INTO "
                      + TABLE
                      + " (tenant_id,request_id,admission_id,admission_attempt_id,capacity_rearm_fence,transition_id,game_instance_id,region_id,mutation_digest,canonical_request,canonical_candidate,canonical_origin,origin_digest,candidate_digest,tenant_gameplay_tag,selected_owner_id,selected_owner_fence,custody_owner_id,custody_owner_fence,recovery_deadline,recovery_max_observations,state) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,'OPEN')",
                  origin.request().identity().tenantId(),
                  origin.request().identity().requestId(),
                  origin.request().identity().admissionId(),
                  origin.request().admissionAttemptId(),
                  origin.request().capacityRearmFence(),
                  origin.transitionId(),
                  binding.gameInstanceId(),
                  binding.regionId(),
                  origin.request().mutationDigest(),
                  CapacityAdmissionDigest.preimage(origin.request()),
                  candidate.canonicalBytes(),
                  origin.canonicalBytes(),
                  origin.digest(),
                  origin.candidateDigest(),
                  origin.tenantGameplayTag(),
                  origin.selectedOwnerId(),
                  origin.selectedOwnerFence(),
                  origin.selectedOwnerId(),
                  origin.selectedOwnerFence(),
                  policy.deadline().toString(),
                  policy.maxObservations());
          if (inserted != 1) throw unavailable();
          return exact(find(tx, origin, true), origin);
        });
  }

  CanonicalGameplayCapacityOrigin readOrigin(
      net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request request) {
    Record row =
        sql.fetchOne(
            "SELECT * FROM " + TABLE + " WHERE admission_attempt_id=?",
            request.admissionAttemptId());
    if (row == null
        || !Arrays.equals(
            CapacityAdmissionDigest.preimage(request), row.get("canonical_request", byte[].class)))
      throw unavailable();
    var origin =
        new CanonicalGameplayCapacityOrigin(
            request,
            CanonicalGameplayOriginalNewCandidate.parseCanonical(
                row.get("canonical_candidate", byte[].class)),
            row.get("tenant_gameplay_tag", String.class),
            row.get("selected_owner_id", String.class),
            row.get("selected_owner_fence", Long.class));
    exact(row, origin);
    return origin;
  }

  Retained read(CanonicalGameplayCapacityOrigin origin) {
    return exact(find(sql, origin, false), origin);
  }

  /**
   * Called within the same transaction that inserts an execution record or retains closure custody.
   */
  static Retained claimInTransaction(
      DSLContext tx,
      CanonicalGameplayCapacityOrigin origin,
      String currentOwner,
      long currentFence,
      CanonicalGameplayCapacityOrigin.State target,
      net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution resolution) {
    if (resolution == null
        || !Arrays.equals(
            CapacityAdmissionDigest.preimage(origin.request()),
            CapacityAdmissionDigest.preimage(resolution.request()))) throw unavailable();
    var status = resolution.status();
    if (status
            == net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution.Status
                .UNKNOWN
        || (target == CanonicalGameplayCapacityOrigin.State.DENIED)
            != (status
                == net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution
                    .Status.NO_ACCEPT_RETAINED)) throw unavailable();
    if (status
            == net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution.Status
                .RESERVATION_RETAINED
        && resolution.reservation().state()
            != net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.State
                .RESERVED) throw unavailable();
    return claimBytesInTransaction(
        tx, origin, currentOwner, currentFence, target, resolution.canonicalBytes());
  }

  static Retained claimDispatchInTransaction(
      DSLContext tx,
      CanonicalGameplayCapacityOrigin origin,
      OriginalNewCapacityExecution execution) {
    if (!Arrays.equals(
            CapacityAdmissionDigest.preimage(origin.request()),
            CapacityAdmissionDigest.preimage(execution.request()))
        || !origin.transitionId().equals(execution.transitionId())
        || !origin.candidateDigest().equals(execution.candidateDigest())
        || !origin.selectedOwnerId().equals(execution.selectedOwnerId())
        || origin.selectedOwnerFence() != execution.selectedOwnerFence()) throw unavailable();
    return claimBytesInTransaction(
        tx,
        origin,
        execution.selectedOwnerId(),
        execution.selectedOwnerFence(),
        CanonicalGameplayCapacityOrigin.State.DISPATCH_CLAIMED,
        execution.canonicalBytes());
  }

  private static Retained claimBytesInTransaction(
      DSLContext tx,
      CanonicalGameplayCapacityOrigin origin,
      String currentOwner,
      long currentFence,
      CanonicalGameplayCapacityOrigin.State target,
      byte[] authenticatedAccountResolution) {
    lockCurrent(tx, origin, currentOwner, currentFence);
    // A frozen capture has stable custody state; the successor resumes unresolved work.
    refuseFrozen(tx, origin);
    var before = exact(find(tx, origin, true), origin);
    if (!before.ownerId().equals(currentOwner) || before.ownerFence() != currentFence)
      throw unavailable();
    CanonicalGameplayCapacityOrigin.claim(before.state(), target);
    if (before.state() == target) {
      if (!Arrays.equals(before.accountResolution(), authenticatedAccountResolution))
        throw unavailable();
      return before;
    }
    String digest = "sha256:" + OriginalNewCapacityExecution.sha256(authenticatedAccountResolution);
    int changed =
        tx.execute(
            "UPDATE "
                + TABLE
                + " SET state=?,account_resolution_bytes=?,account_resolution_digest=? WHERE admission_attempt_id=? AND state='OPEN'",
            target.name(),
            authenticatedAccountResolution,
            digest,
            origin.request().admissionAttemptId());
    if (changed != 1) throw unavailable();
    return exact(find(tx, origin, true), origin);
  }

  net.firedevops.firemud.common.account.capacity.AccountCapacityOriginContinuation.Permit
      admitObservation(
          CanonicalGameplayCapacityOrigin origin,
          java.time.Instant now,
          java.time.Instant callerDeadline) {
    var current = read(origin);
    return sql.transactionResult(
        configuration -> {
          var tx = DSL.using(configuration);
          lockCurrent(tx, origin, current.ownerId(), current.ownerFence());
          refuseFrozen(tx, origin);
          Record row = find(tx, origin, true);
          var retained = exact(row, origin);
          if (!retained.ownerId().equals(current.ownerId())
              || retained.ownerFence() != current.ownerFence()) throw unavailable();
          var policy =
              new net.firedevops.firemud.common.account.capacity.AccountCapacityOriginContinuation
                  .Policy(
                  row.get("recovery_deadline", java.time.OffsetDateTime.class).toInstant(),
                  row.get("recovery_max_observations", Integer.class));
          int consumed = row.get("recovery_observations", Integer.class);
          var admission =
              net.firedevops.firemud.common.account.capacity.AccountCapacityOriginContinuation
                  .admit(policy, consumed, now, callerDeadline)
                  .orElseThrow(CanonicalGameplayCapacityOriginRepository::unavailable);
          if (tx.execute(
                  "UPDATE "
                      + TABLE
                      + " SET recovery_observations=?,last_observation_deadline=?::timestamptz WHERE admission_attempt_id=? AND recovery_observations=?",
                  admission.ordinal(),
                  admission.deadline().toString(),
                  origin.request().admissionAttemptId(),
                  consumed)
              != 1) throw unavailable();
          return new net.firedevops.firemud.common.account.capacity
              .AccountCapacityOriginContinuation.Permit(
              origin.request().mutationDigest(),
              origin.digest(),
              retained.ownerId(),
              retained.ownerFence(),
              admission.ordinal(),
              admission.deadline());
        });
  }

  static boolean requireObservationInTransaction(
      DSLContext tx,
      CanonicalGameplayCapacityOrigin origin,
      net.firedevops.firemud.common.account.capacity.AccountCapacityOriginContinuation.Permit
          permit,
      java.time.Instant now,
      net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution resolution) {
    if (permit == null
        || !now.isBefore(permit.deadline())
        || !origin.digest().equals(permit.originDigest())
        || !origin.request().mutationDigest().equals(permit.requestDigest())) throw unavailable();
    lockCurrent(tx, origin, permit.custodyOwner(), permit.custodyFence());
    refuseFrozen(tx, origin);
    Record row = find(tx, origin, true);
    var retained = exact(row, origin);
    if (!retained.ownerId().equals(permit.custodyOwner())
        || retained.ownerFence() != permit.custodyFence()
        || row.get("recovery_observations", Integer.class) != permit.ordinal()
        || !row.get("last_observation_deadline", java.time.OffsetDateTime.class)
            .toInstant()
            .equals(permit.deadline())) throw unavailable();
    if (resolution.status()
        == net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution.Status
            .RESERVATION_RETAINED) {
      var policy = resolution.continuationPolicy();
      // Adopt the ORIGINAL retained reservation policy, never a new now-relative window.
      // Count and admitted observation identity do not change or replenish on discovery.
      boolean adopted = row.get("reservation_policy_applied", Boolean.class);
      if (adopted) {
        if (!row.get("recovery_deadline", java.time.OffsetDateTime.class)
                .toInstant()
                .equals(policy.deadline())
            || row.get("recovery_max_observations", Integer.class) != policy.maxObservations())
          throw unavailable();
      } else if (tx.execute(
              "UPDATE "
                  + TABLE
                  + " SET recovery_deadline=?::timestamptz,recovery_max_observations=?,reservation_policy_applied=true WHERE admission_attempt_id=? AND reservation_policy_applied=false",
              policy.deadline().toString(),
              policy.maxObservations(),
              origin.request().admissionAttemptId())
          != 1) throw unavailable();
      if (!now.isBefore(policy.deadline()) || permit.ordinal() > policy.maxObservations())
        return false;
    }
    return true;
  }

  private static void lockCurrent(
      DSLContext tx, CanonicalGameplayCapacityOrigin origin, String owner, long fence) {
    CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(tx);
    var b = origin.candidate().bindingIdentity();
    Record runtime =
        tx.fetchOne(
            "SELECT owner_instance_id,executor_fence FROM game_session_region_runtime_status WHERE tenant_id=? AND game_instance_id=? AND region_id=? FOR UPDATE",
            b.tenantId(),
            b.gameInstanceId(),
            b.regionId());
    if (runtime == null
        || !owner.equals(runtime.get("owner_instance_id", String.class))
        || fence != runtime.get("executor_fence", Long.class)) throw unavailable();
  }

  private static void refuseFrozen(DSLContext tx, CanonicalGameplayCapacityOrigin origin) {
    var b = origin.candidate().bindingIdentity();
    if (tx.fetchExists(
        DSL.selectOne()
            .from("game_session_region_owner_transfer")
            .where(DSL.field("tenant_id").eq(b.tenantId()))
            .and(DSL.field("game_instance_id").eq(b.gameInstanceId()))
            .and(DSL.field("region_id").eq(b.regionId()))
            .and(DSL.field("state").eq("FROZEN")))) throw unavailable();
  }

  private static Record find(DSLContext tx, CanonicalGameplayCapacityOrigin origin, boolean lock) {
    return tx.fetchOne(
        "SELECT * FROM " + TABLE + " WHERE admission_attempt_id=?" + (lock ? " FOR UPDATE" : ""),
        origin.request().admissionAttemptId());
  }

  private static Retained exact(Record row, CanonicalGameplayCapacityOrigin origin) {
    if (row == null
        || !origin.request().mutationDigest().equals(row.get("mutation_digest", String.class))
        || !origin.digest().equals(row.get("origin_digest", String.class))
        || !Arrays.equals(origin.canonicalBytes(), row.get("canonical_origin", byte[].class))
        || !Arrays.equals(
            CapacityAdmissionDigest.preimage(origin.request()),
            row.get("canonical_request", byte[].class))
        || !Arrays.equals(
            origin.candidate().canonicalBytes(), row.get("canonical_candidate", byte[].class)))
      throw unavailable();
    byte[] resolution = row.get("account_resolution_bytes", byte[].class);
    String digest = row.get("account_resolution_digest", String.class);
    if ((resolution == null) != (digest == null)
        || (resolution != null
            && !("sha256:" + OriginalNewCapacityExecution.sha256(resolution)).equals(digest)))
      throw unavailable();
    return new Retained(
        CanonicalGameplayCapacityOrigin.State.valueOf(row.get("state", String.class)),
        row.get("custody_owner_id", String.class),
        row.get("custody_owner_fence", Long.class),
        resolution);
  }

  record Retained(
      CanonicalGameplayCapacityOrigin.State state,
      String ownerId,
      long ownerFence,
      byte[] accountResolution) {
    Retained {
      accountResolution = accountResolution == null ? null : accountResolution.clone();
    }

    @Override
    public byte[] accountResolution() {
      return accountResolution == null ? null : accountResolution.clone();
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Exact capacity origin custody unavailable");
  }
}
