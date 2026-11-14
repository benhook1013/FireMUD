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
import org.jooq.DSLContext;
import org.jooq.Record;

/** Immutable Account decision; unique-key arbitration also excludes caller/account aliases. */
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Private transaction collaborator.")
public final class AccountCapacityOriginDecisionRepository {
  private static final String QUOTA_REFUSAL = Failure.CAPACITY_QUOTA_EXCEEDED.name();

  private final DSLContext dsl;

  public AccountCapacityOriginDecisionRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  /** Read only. Missing row is not a refusal. */
  public Record find(Request request) {
    transaction();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_capacity_origin_decisions WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ? AND capacity_rearm_fence = ?",
            request.identity().tenantId(),
            request.identity().requestId(),
            request.identity().admissionId(),
            request.capacityRearmFence());
    if (row != null) requireExact(request, row);
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

  /** Replays an exact durable quota refusal before any mutable eligibility check. */
  public UUID retainedQuotaDenial(Request request) {
    Record row = find(request);
    if (row == null) return null;
    return requireOutcome(row, "NO_ACCEPT", QUOTA_REFUSAL).get("decision_id", UUID.class);
  }

  /** Caller holds the Account and usage locks and found insufficient capacity before any write. */
  public UUID refuseQuota(Request request) {
    return retain(request, "NO_ACCEPT", QUOTA_REFUSAL).get("decision_id", UUID.class);
  }

  private Record retain(Request request, String decision, String refusalCode) {
    Record existing = find(request);
    if (existing != null) return requireOutcome(existing, decision, refusalCode);
    int inserted =
        dsl.execute(
            "INSERT INTO account_capacity_origin_decisions (tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, mutation_digest, canonical_payload, decision, decision_id, refusal_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_uuid, request_id, admission_id, capacity_rearm_fence) DO NOTHING",
            request.identity().tenantId(),
            request.identity().requestId(),
            request.identity().admissionId(),
            request.capacityRearmFence(),
            request.admissionAttemptId(),
            request.mutationDigest(),
            CapacityAdmissionDigest.preimage(request),
            decision,
            UUID.randomUUID(),
            refusalCode);
    if (inserted != 0 && inserted != 1) throw unavailable();
    Record row = find(request);
    if (row == null) throw unavailable();
    return requireOutcome(row, decision, refusalCode);
  }

  private static Record requireOutcome(Record row, String decision, String refusalCode) {
    if (!decision.equals(row.get("decision", String.class))
        || !Objects.equals(refusalCode, row.get("refusal_code", String.class))) {
      throw conflict();
    }
    return row;
  }

  private static void requireExact(Request request, Record row) {
    if (!request.admissionAttemptId().equals(row.get("admission_attempt_id", UUID.class))
        || !request.mutationDigest().equals(row.get("mutation_digest", String.class))
        || !Arrays.equals(
            CapacityAdmissionDigest.preimage(request), row.get("canonical_payload", byte[].class))
        || row.get("decision_id", UUID.class) == null
        || !("ACCEPTED".equals(row.get("decision", String.class))
            || "NO_ACCEPT".equals(row.get("decision", String.class)))) throw conflict();
  }
}
