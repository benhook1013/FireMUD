package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementEvidence;
import net.firedevops.firemud.common.account.capacity.AccountCapacityOriginContinuation;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Evidence;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Identity;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Result;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.State;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Failure;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Account-local primitives only, always in the owning writable transaction. No bean or seed API.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Private owner transaction collaborator.")
public final class AccountTenantCapacityRepository {
  private static final String FIND_RESERVATION =
      "SELECT * FROM account_tenant_capacity_reservations WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?";
  private final DSLContext dsl;

  public AccountTenantCapacityRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  /** Called by the actual Account player evaluator before its final source transaction commits. */
  public void retainPlayerEvaluation(RuntimePlayerAdmissionEntitlementEvidence source) {
    retain(Evidence.player(source));
  }

  private void retain(Evidence evidence) {
    transaction();
    int inserted =
        dsl.execute(
            "INSERT INTO account_tenant_capacity_evaluations "
                + "(evaluation_identity, snapshot_identity, tenant_uuid, caller_workload, owner_service, canonical_evidence, evaluated_at, expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz) ON CONFLICT (evaluation_identity) DO NOTHING",
            evidence.evaluationIdentity(),
            evidence.snapshotIdentity(),
            evidence.tenantId(),
            evidence.callerWorkload(),
            evidence.owner().service(),
            evidence.canonicalBytes(),
            at(evidence.evaluatedAt()),
            at(evidence.expiresAt()));
    if (inserted != 0 && inserted != 1) throw unavailable();
    requireRetained(evidence);
  }

  public void requireRetained(Evidence evidence) {
    transaction();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_tenant_capacity_evaluations WHERE evaluation_identity = ? FOR SHARE",
            evidence.evaluationIdentity());
    if (row == null
        || !evidence.tenantId().equals(row.get("tenant_uuid", UUID.class))
        || !evidence.snapshotIdentity().equals(row.get("snapshot_identity", String.class))
        || !evidence.callerWorkload().equals(row.get("caller_workload", String.class))
        || !evidence.owner().service().equals(row.get("owner_service", String.class))
        || !Arrays.equals(evidence.canonicalBytes(), row.get("canonical_evidence", byte[].class))
        || !evidence
            .evaluatedAt()
            .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
            .equals(time(row, "evaluated_at"))
        || !evidence
            .expiresAt()
            .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
            .equals(time(row, "expires_at"))) throw unavailable();
  }

  public Record find(Identity id) {
    return find(id, FIND_RESERVATION + " FOR UPDATE");
  }

  /** Exact replay reads the transaction snapshot without taking a mutation lock. */
  public Record findForReplay(Identity id) {
    return find(id, FIND_RESERVATION);
  }

  private Record find(Identity id, String query) {
    transaction();
    return dsl.fetchOne(query, id.tenantId(), id.requestId(), id.admissionId());
  }

  public Record usage(Request request) {
    transaction();
    Record usage =
        dsl.fetchOne(
            "SELECT * FROM account_tenant_capacity_usage WHERE tenant_uuid = ? AND owner_service = ? FOR UPDATE",
            request.identity().tenantId(),
            request.context().owner().service());
    if (usage == null) throw unavailable();
    return usage;
  }

  public Result exact(Request request, Record row) {
    if (!request.mutationDigest().equals(row.get("mutation_digest", String.class))
        || !request.admissionAttemptId().equals(row.get("admission_attempt_id", UUID.class))
        || request.capacityRearmFence() != number(row, "capacity_rearm_fence")
        || !Arrays.equals(
            CapacityAdmissionDigest.preimage(request), row.get("canonical_payload", byte[].class))
        || !request.evidence().callerWorkload().equals(row.get("caller_workload", String.class)))
      throw conflict();
    return result(row);
  }

  public void requirePriorHistory(Request prior, Request next) {
    transaction();
    if (!prior.identity().equals(next.identity())
        || prior.admissionAttemptId().equals(next.admissionAttemptId())
        || next.capacityRearmFence() != Math.incrementExact(prior.capacityRearmFence()))
      throw conflict();
    Record history =
        dsl.fetchOne(
            "SELECT * FROM account_tenant_capacity_attempt_history WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ? AND admission_attempt_id = ? FOR SHARE",
            prior.identity().tenantId(),
            prior.identity().requestId(),
            prior.identity().admissionId(),
            prior.admissionAttemptId());
    if (history == null
        || number(history, "capacity_rearm_fence") != prior.capacityRearmFence()
        || !prior.mutationDigest().equals(history.get("mutation_digest", String.class))
        || !Arrays.equals(
            CapacityAdmissionDigest.preimage(prior), history.get("canonical_payload", byte[].class))
        || history.get("owner_readback", byte[].class) == null) throw conflict();
  }

  /** Fresh database time, not the transaction-start timestamp or an injected application clock. */
  public void requireFresh(Evidence evidence) {
    transaction();
    requireCurrentLifetime(evidence, databaseNow(dsl));
  }

  /**
   * Reads PostgreSQL wall time for evidence creation/freshness checks across application clocks.
   */
  public static Instant databaseNow(DSLContext exactDsl) {
    return time(
        Objects.requireNonNull(exactDsl).fetchSingle("SELECT clock_timestamp() AS observed_at"),
        "observed_at");
  }

  public Result create(Request request, Record usage, Instant expiresAt) {
    return createIfQuotaAvailable(request, usage, expiresAt)
        .orElseThrow(() -> new CapacityException(Failure.CAPACITY_QUOTA_EXCEEDED));
  }

  /** Retains acceptance only after the locked quota check, in the same reservation transaction. */
  public Optional<Result> createIfQuotaAvailable(Request request, Record usage, Instant expiresAt) {
    requireFresh(request.evidence());
    long delta = Objects.requireNonNull(request.capacityDelta());
    long occupied = Math.addExact(number(usage, "held_units"), number(usage, "used_units"));
    if (delta > request.evidence().quota() - occupied) return Optional.empty();
    new AccountCapacityOriginDecisionRepository(dsl).accept(request);
    long held = Math.addExact(number(usage, "held_units"), delta);
    updateUsage(request, usage, held, number(usage, "used_units"));
    int created =
        dsl.execute(
            "INSERT INTO account_tenant_capacity_reservations "
                + "(tenant_uuid, request_id, admission_id, reservation_id, owner_service, caller_workload, admission_attempt_id, capacity_rearm_fence, reservation_fence, mutation_digest, canonical_payload, evaluation_identity, capacity_delta, expires_at, reconcile_deadline, state, execution_status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, 'RESERVED', 'OWNER_EXECUTION_PENDING')",
            request.identity().tenantId(),
            request.identity().requestId(),
            request.identity().admissionId(),
            UUID.randomUUID(),
            request.context().owner().service(),
            request.evidence().callerWorkload(),
            request.admissionAttemptId(),
            request.capacityRearmFence(),
            request.mutationDigest(),
            CapacityAdmissionDigest.preimage(request),
            request.evidence().evaluationIdentity(),
            delta,
            at(expiresAt),
            at(AccountCapacityOriginContinuation.reconcileDeadline(expiresAt)));
    if (created != 1) throw unavailable();
    return Optional.of(exact(request, Objects.requireNonNull(find(request.identity()))));
  }

  public static void requireCurrentLifetime(Evidence evidence, Instant now) {
    // PostgreSQL stores microseconds. Floor the bound, never round an expiry forward.
    if (now.isBefore(evidence.evaluatedAt())
        || !now.isBefore(evidence.expiresAt().truncatedTo(ChronoUnit.MICROS))) {
      throw new CapacityException(Failure.CAPACITY_ADMISSION_STALE);
    }
  }

  public void updateUsage(Request request, Record before, long held, long used) {
    transaction();
    if (held < 0 || used < 0 || Math.addExact(held, used) < 0) throw unavailable();
    long revision = number(before, "usage_revision");
    int updated =
        dsl.execute(
            "UPDATE account_tenant_capacity_usage SET held_units = ?, used_units = ?, usage_revision = ? WHERE tenant_uuid = ? AND owner_service = ? AND usage_revision = ?",
            held,
            used,
            Math.incrementExact(revision),
            request.identity().tenantId(),
            request.context().owner().service(),
            revision);
    if (updated != 1) throw unavailable();
  }

  public Result result(Record row) {
    transaction();
    Record evaluation =
        dsl.fetchOne(
            "SELECT snapshot_identity FROM account_tenant_capacity_evaluations WHERE evaluation_identity = ?",
            row.get("evaluation_identity", String.class));
    if (evaluation == null) throw unavailable();
    return new Result(
        new Identity(
            row.get("tenant_uuid", UUID.class),
            row.get("request_id", UUID.class),
            row.get("admission_id", UUID.class)),
        row.get("admission_attempt_id", UUID.class),
        number(row, "capacity_rearm_fence"),
        row.get("mutation_digest", String.class),
        row.get("reservation_id", UUID.class),
        number(row, "reservation_fence"),
        time(row, "expires_at"),
        State.valueOf(row.get("state", String.class)),
        evaluation.get("snapshot_identity", String.class),
        row.get("evaluation_identity", String.class));
  }

  public static long number(Record row, String name) {
    Long value = row.get(name, Long.class);
    if (value == null || value < 0) throw unavailable();
    return value;
  }

  public static Instant time(Record row, String name) {
    OffsetDateTime value = row.get(name, OffsetDateTime.class);
    if (value == null) throw unavailable();
    return value.toInstant();
  }

  public static OffsetDateTime at(Instant instant) {
    return instant.truncatedTo(java.time.temporal.ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
  }

  public static void transaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw unavailable();
  }

  public static CapacityException conflict() {
    return new CapacityException(Failure.IDEMPOTENCY_CONFLICT);
  }

  public static CapacityException unavailable() {
    return new CapacityException(Failure.ENTITLEMENT_UNAVAILABLE);
  }

  public static final class CapacityException extends IllegalStateException {
    private final Failure code;
    private final UUID durableDenialId;

    public CapacityException(Failure code) {
      super(code.name());
      this.code = code;
      this.durableDenialId = null;
    }

    public CapacityException(Failure code, UUID durableDenialId) {
      super(code.name());
      if (code != Failure.CAPACITY_QUOTA_EXCEEDED)
        throw new IllegalArgumentException("Only a quota refusal can carry a durable denial");
      this.code = code;
      this.durableDenialId = TenantCapacityAdmission.uuid(durableDenialId);
    }

    public Failure code() {
      return code;
    }

    public UUID durableDenialId() {
      return durableDenialId;
    }
  }
}
