package net.firedevops.firemud.gamesession.repository;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Failure;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Refusal;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;

final class CanonicalGameplayCapacityEvidenceBudget {
  private CanonicalGameplayCapacityEvidenceBudget() {}

  static Refusal precharge(
      DSLContext tx,
      Request request,
      String candidateDigest,
      CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner selected,
      String tag,
      Clock clock) {
    var identity = request.identity();
    lockBudget(tx, request, tag);
    var prior = exactCharge(tx, request, candidateDigest, selected, tag);
    if (prior != null) return retainedDenial(prior);
    // The tenant budget lock serializes precharges. Retained and active charges both bind
    // the original Account origin and rearm fence; a new attempt cannot reserve that slot.
    if (tx.fetchOne(
            "SELECT 1 FROM game_session_capacity_evidence_charge"
                + " WHERE tenant_id=? AND request_id=? AND admission_id=? AND capacity_rearm_fence=?"
                + " LIMIT 1",
            identity.tenantId(),
            identity.requestId(),
            identity.admissionId(),
            request.capacityRearmFence())
        != null) throw unavailable();
    // Match Account's durable timestamp precision. Historical exact charges above don't use
    // current evidence, and a stale uncharged request cannot consume a new slot.
    var now = clock.instant();
    if (now.isBefore(request.evidence().evaluatedAt())
        || !now.isBefore(request.evidence().expiresAt().truncatedTo(ChronoUnit.MICROS)))
      throw unavailable();
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

  record RetainedCharge(Refusal accountDenial) {}

  static RetainedCharge requireExistingCharge(
      DSLContext tx,
      Request request,
      String candidateDigest,
      CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner selected,
      String tag) {
    var charge = exactCharge(tx, request, candidateDigest, selected, tag);
    if (charge == null) throw unavailable();
    return new RetainedCharge(retainedDenial(charge));
  }

  static void settleDenied(
      DSLContext tx,
      Request request,
      String candidateDigest,
      CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner selected,
      String tag,
      Refusal denial) {
    if (denial == null
        || !denial.code().supportsDurableDenial()
        || denial.durableDenialId() == null) throw unavailable();
    lockBudget(tx, request, tag);
    var charge = exactCharge(tx, request, candidateDigest, selected, tag);
    if (charge == null) throw unavailable();
    var retained = retainedDenial(charge);
    if (retained != null) {
      if (!retained.equals(denial)) throw unavailable();
      return;
    }
    int settled =
        tx.execute(
            "UPDATE game_session_capacity_evidence_charge"
                + " SET account_denial_id=?, account_denial_kind=?"
                + " WHERE admission_attempt_id=? AND account_denial_id IS NULL",
            denial.durableDenialId(),
            denial.code().name(),
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

  private static Refusal retainedDenial(Record charge) {
    UUID denialId = charge.get("account_denial_id", UUID.class);
    if (denialId == null) return null;
    String kind = charge.get("account_denial_kind", String.class);
    // Only quota denials existed before the typed settlement migration.
    var code = kind == null ? Failure.CAPACITY_QUOTA_EXCEEDED : Failure.valueOf(kind);
    return new Refusal(code, denialId);
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

  private static Record exactCharge(
      DSLContext tx,
      Request request,
      String candidateDigest,
      CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner selected,
      String tag) {
    var identity = request.identity();
    var prior =
        tx.fetchOne(
            "SELECT c.tenant_id,c.request_id,c.admission_id,c.capacity_rearm_fence,c.mutation_digest,"
                + " c.candidate_digest,c.selected_owner_id,c.selected_owner_fence,t.tenant_gameplay_tag,"
                + " c.account_denial_id,c.account_denial_kind"
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
