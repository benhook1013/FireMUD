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
import org.jooq.DSLContext;
import org.jooq.Record;

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
    retain(request, "ACCEPTED");
  }

  /** Caller has locked Account and verified no exact or conflicting reservation exists. */
  public UUID refuse(Request request) {
    return retain(request, "NO_ACCEPT").get("decision_id", UUID.class);
  }

  private Record retain(Request request, String decision) {
    transaction();
    int inserted =
        dsl.execute(
            "INSERT INTO account_capacity_origin_decisions (tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, mutation_digest, canonical_payload, decision, decision_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_uuid, request_id, admission_id, capacity_rearm_fence) DO NOTHING",
            request.identity().tenantId(),
            request.identity().requestId(),
            request.identity().admissionId(),
            request.capacityRearmFence(),
            request.admissionAttemptId(),
            request.mutationDigest(),
            CapacityAdmissionDigest.preimage(request),
            decision,
            UUID.randomUUID());
    if (inserted != 0 && inserted != 1) throw unavailable();
    Record row = find(request);
    if (row == null) throw unavailable();
    if (!decision.equals(row.get("decision", String.class))) throw conflict();
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
