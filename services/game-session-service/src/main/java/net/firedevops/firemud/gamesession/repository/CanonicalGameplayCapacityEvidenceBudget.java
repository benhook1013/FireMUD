package net.firedevops.firemud.gamesession.repository;

import java.util.UUID;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;

final class CanonicalGameplayCapacityEvidenceBudget {
  private CanonicalGameplayCapacityEvidenceBudget() {}

  static UUID precharge(
      DSLContext tx,
      Request request,
      String candidateDigest,
      CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner selected,
      String tag) {
    var identity = request.identity();
    lockBudget(tx, request, tag);
    var prior = exactCharge(tx, request, candidateDigest, selected, tag);
    if (prior != null) return prior.get("account_denial_id", UUID.class);
    try {
      int inserted =
          tx.execute(
              "INSERT INTO game_session_capacity_evidence_charge (admission_attempt_id,tenant_id,"
                  + " request_id,admission_id,capacity_rearm_fence,mutation_digest,candidate_digest,"
                  + " selected_owner_id,selected_owner_fence,reserved_binding_bytes)"
                  + " VALUES (?,?,?,?,?,?,?,?,?,1048576)",
              request.admissionAttemptId(),
              identity.tenantId(),
              identity.requestId(),
              identity.admissionId(),
              request.capacityRearmFence(),
              request.mutationDigest(),
              candidateDigest,
              selected.instanceId(),
              selected.fence());
      if (inserted != 1) throw unavailable();
      return null;
    } catch (DataAccessException failure) {
      if ("23514".equals(failure.sqlState()) || "23505".equals(failure.sqlState()))
        throw unavailable();
      throw failure;
    }
  }

  static void settleDenied(
      DSLContext tx,
      Request request,
      String candidateDigest,
      CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner selected,
      String tag,
      UUID accountDenialId) {
    if (accountDenialId == null) throw unavailable();
    lockBudget(tx, request, tag);
    var charge = exactCharge(tx, request, candidateDigest, selected, tag);
    if (charge == null) throw unavailable();
    var retained = charge.get("account_denial_id", UUID.class);
    if (retained != null) {
      if (!retained.equals(accountDenialId)) throw unavailable();
      return;
    }
    int settled =
        tx.execute(
            "UPDATE game_session_capacity_evidence_charge SET account_denial_id=?"
                + " WHERE admission_attempt_id=? AND account_denial_id IS NULL",
            accountDenialId,
            request.admissionAttemptId());
    if (settled != 1) throw unavailable();
  }

  static void requireCharge(DSLContext tx, OriginalNewCapacityExecution execution, String tag) {
    var charge =
        exactCharge(
            tx,
            execution.request(),
            execution.candidateDigest(),
            new CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner(
                execution.selectedOwnerId(), execution.selectedOwnerFence()),
            tag);
    if (charge == null || charge.get("account_denial_id", UUID.class) != null) throw unavailable();
  }

  private static void lockBudget(DSLContext tx, Request request, String tag) {
    if (tx.fetchOne(
            "SELECT 1 FROM game_session_capacity_evidence_budget b"
                + " JOIN game_session_canonical_tenant_gameplay_tag t USING (tenant_id)"
                + " WHERE b.tenant_id=? AND t.tenant_gameplay_tag=? FOR UPDATE OF b",
            request.identity().tenantId(),
            tag)
        == null) throw unavailable();
  }

  private static org.jooq.Record exactCharge(
      DSLContext tx,
      Request request,
      String candidateDigest,
      CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner selected,
      String tag) {
    var identity = request.identity();
    var prior =
        tx.fetchOne(
            "SELECT c.tenant_id,c.request_id,c.admission_id,c.capacity_rearm_fence,c.mutation_digest,"
                + " c.candidate_digest,c.selected_owner_id,c.selected_owner_fence,t.tenant_gameplay_tag,c.account_denial_id"
                + " FROM game_session_capacity_evidence_charge c"
                + " JOIN game_session_canonical_tenant_gameplay_tag t USING (tenant_id)"
                + " WHERE c.admission_attempt_id=?",
            request.admissionAttemptId());
    if (prior == null) return null;
    if (!identity.tenantId().equals(prior.get(0))
        || !identity.requestId().equals(prior.get(1))
        || !identity.admissionId().equals(prior.get(2))
        || !Long.valueOf(request.capacityRearmFence()).equals(prior.get(3))
        || !request.mutationDigest().equals(prior.get(4))
        || !candidateDigest.equals(prior.get(5))
        || !selected.instanceId().equals(prior.get(6))
        || !Long.valueOf(selected.fence()).equals(prior.get(7))
        || !tag.equals(prior.get(8))) throw unavailable();
    return prior;
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Original NEW capacity evidence budget is unavailable");
  }
}
