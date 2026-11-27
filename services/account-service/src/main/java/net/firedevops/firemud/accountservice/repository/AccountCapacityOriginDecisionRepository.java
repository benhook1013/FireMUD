package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.conflict;
import static net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.transaction;
import static net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.unavailable;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Failure;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Refusal;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Immutable Account decision; unique-key arbitration also excludes caller/account aliases. */
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Private transaction collaborator.")
public final class AccountCapacityOriginDecisionRepository {
  private final DSLContext dsl;

  public AccountCapacityOriginDecisionRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  /** Read only. Missing row is not a refusal. */
  public Record find(Request request) {
    transaction();
    return findExact(request);
  }

  private Record findExact(Request request) {
    Record row = findRow(request);
    if (row != null) requireExact(request, row, CapacityAdmissionDigest.preimage(request));
    return row;
  }

  /**
   * Must commit atomically with reservation creation/rearm, otherwise the transaction rolls back.
   */
  public void accept(Request request) {
    retain(request, "ACCEPTED", null);
  }

  /** Caller has locked Account and verified no exact or conflicting reservation exists. */
  public UUID refuse(Request request) {
    return retain(request, "NO_ACCEPT", null).get("decision_id", UUID.class);
  }

  /** Replays an exact durable refusal before any mutable eligibility check. */
  public Refusal retainedAdmissionDenial(Request request) {
    return admissionDenial(find(request));
  }

  /** Historical recovery SELECT, never part of the mutation transaction. */
  public Refusal readRetainedAdmissionDenial(Request request) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw unavailable();
    return admissionDenial(findExact(request));
  }

  private static Refusal admissionDenial(Record row) {
    if (row == null) return null;
    if (!"NO_ACCEPT".equals(row.get("decision", String.class))) throw conflict();
    String code = row.get("refusal_code", String.class);
    if (Failure.CAPACITY_QUOTA_EXCEEDED.name().equals(code))
      return new Refusal(Failure.CAPACITY_QUOTA_EXCEEDED, row.get("decision_id", UUID.class));
    if (Failure.CAPACITY_EVALUATION_EXPIRED.name().equals(code))
      return new Refusal(Failure.CAPACITY_EVALUATION_EXPIRED, row.get("decision_id", UUID.class));
    throw conflict();
  }

  /** Caller proved the typed refusal in the owning transaction before any reservation mutation. */
  public Refusal refuse(Request request, Failure cause) {
    if (!cause.supportsDurableDenial()) throw new IllegalArgumentException("Invalid denial cause");
    return admissionDenial(retain(request, "NO_ACCEPT", cause.name()));
  }

  private Record retain(Request request, String decision, String refusalCode) {
    transaction();
    byte[] canonicalPayload = CapacityAdmissionDigest.preimage(request);
    Record existing = find(request, canonicalPayload);
    if (existing != null) return requireOutcome(existing, decision, refusalCode);
    requireFreshAttemptBinding(request, canonicalPayload);
    int inserted =
        dsl.execute(
            "INSERT INTO account_capacity_origin_decisions (tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, mutation_digest, canonical_payload, decision, decision_id, refusal_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            request.identity().tenantId(),
            request.identity().requestId(),
            request.identity().admissionId(),
            request.capacityRearmFence(),
            request.admissionAttemptId(),
            request.mutationDigest(),
            canonicalPayload,
            decision,
            UUID.randomUUID(),
            refusalCode);
    if (inserted != 0 && inserted != 1) throw unavailable();
    Record row = find(request, canonicalPayload);
    if (row == null) {
      requireFreshAttemptBinding(request, canonicalPayload);
      throw unavailable();
    }
    return requireOutcome(row, decision, refusalCode);
  }

  /** SERIALIZABLE caller: include pre-origin reservations and retained rearm history. */
  public void requireFreshAttemptBinding(Request request) {
    requireFreshAttemptBinding(request, CapacityAdmissionDigest.preimage(request));
  }

  private void requireFreshAttemptBinding(Request request, byte[] canonicalPayload) {
    Record conflict =
        dsl.fetchOne(
            "SELECT 1 FROM ("
                + "SELECT tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, mutation_digest, canonical_payload FROM account_capacity_origin_decisions "
                + "UNION ALL SELECT tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, mutation_digest, canonical_payload FROM account_tenant_capacity_reservations "
                + "UNION ALL SELECT tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, mutation_digest, canonical_payload FROM account_tenant_capacity_attempt_history"
                + ") retained_attempt WHERE admission_attempt_id = ? AND (tenant_uuid <> ? OR request_id <> ? OR admission_id <> ? OR capacity_rearm_fence <> ? OR mutation_digest <> ? OR canonical_payload <> ?) LIMIT 1",
            request.admissionAttemptId(),
            request.identity().tenantId(),
            request.identity().requestId(),
            request.identity().admissionId(),
            request.capacityRearmFence(),
            request.mutationDigest(),
            canonicalPayload);
    if (conflict != null) throw conflict();
  }

  private Record find(Request request, byte[] canonicalPayload) {
    Record row = findRow(request);
    if (row != null) requireExact(request, row, canonicalPayload);
    return row;
  }

  private Record findRow(Request request) {
    return dsl.fetchOne(
        "SELECT * FROM account_capacity_origin_decisions WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ? AND capacity_rearm_fence = ?",
        request.identity().tenantId(),
        request.identity().requestId(),
        request.identity().admissionId(),
        request.capacityRearmFence());
  }

  private static Record requireOutcome(Record row, String decision, String refusalCode) {
    if (!decision.equals(row.get("decision", String.class))
        || !Objects.equals(refusalCode, row.get("refusal_code", String.class))) {
      throw conflict();
    }
    return row;
  }

  private static void requireExact(Request request, Record row, byte[] canonicalPayload) {
    if (!request.admissionAttemptId().equals(row.get("admission_attempt_id", UUID.class))
        || !request.mutationDigest().equals(row.get("mutation_digest", String.class))
        || !Arrays.equals(canonicalPayload, row.get("canonical_payload", byte[].class))
        || row.get("decision_id", UUID.class) == null
        || !("ACCEPTED".equals(row.get("decision", String.class))
            || "NO_ACCEPT".equals(row.get("decision", String.class)))) throw conflict();
  }
}
