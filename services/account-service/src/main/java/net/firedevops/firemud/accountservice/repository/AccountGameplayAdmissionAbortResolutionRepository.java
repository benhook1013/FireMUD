package net.firedevops.firemud.accountservice.repository;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionAbortResolution;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * Owner-internal append-only evidence; authentication and reverse-owner proof belong to the owner.
 */
final class AccountGameplayAdmissionAbortResolutionRepository {
  private static final String RESOLUTIONS = "account_gameplay_admission_abort_resolutions";
  private final DSLContext dsl;

  AccountGameplayAdmissionAbortResolutionRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  Optional<AccountGameplayAdmissionAbortResolution> readExact(
      AccountGameplayAdmissionLeaseEvidence lease, UUID cleanupId, UUID decisionId) {
    requireOriginalAbort(lease, cleanupId);
    Record row =
        dsl.fetchOne("SELECT * FROM " + RESOLUTIONS + " WHERE request_id = ?", requestId(lease));
    if (row == null) return Optional.empty();
    var result = decode(row);
    if (!result.originalLeaseId().equals(leaseId(lease))
        || !result.originalLeaseFence().equals(lease.leaseFence())
        || !result.originalLeaseSha256().equals(lease.sha256())
        || !result.orphanCleanupId().equals(cleanupId)
        || !result.bindingDecisionId().equals(decisionId)) {
      throw new IllegalStateException("Abort resolution changed the original exact linkage");
    }
    return Optional.of(result);
  }

  AccountGameplayAdmissionAbortResolution recordOrReplay(
      AccountGameplayAdmissionLeaseEvidence lease, UUID cleanupId, UUID decisionId) {
    var retained = readExact(lease, cleanupId, decisionId);
    if (retained.isPresent()) return retained.orElseThrow();
    var resolution =
        new AccountGameplayAdmissionAbortResolution(
            UUID.randomUUID(),
            leaseId(lease),
            lease.leaseFence(),
            lease.sha256(),
            cleanupId,
            decisionId);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + RESOLUTIONS
                + " (request_id, resolution_id, original_lease_id, original_lease_fence,"
                + " original_lease_sha256, orphan_cleanup_id, binding_decision_id)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)",
            requestId(lease),
            resolution.resolutionId(),
            resolution.originalLeaseId(),
            resolution.originalLeaseFence().longValueExact(),
            resolution.originalLeaseSha256(),
            resolution.orphanCleanupId(),
            resolution.bindingDecisionId());
    if (inserted != 1) throw new IllegalStateException("Abort resolution insert unavailable");
    var stored = readExact(lease, cleanupId, decisionId).orElseThrow();
    if (!stored.equals(resolution))
      throw new IllegalStateException("Abort resolution readback changed");
    return stored;
  }

  private void requireOriginalAbort(AccountGameplayAdmissionLeaseEvidence lease, UUID cleanupId) {
    // readExact enforces writable SERIALIZABLE and locks Account before the operation row.
    AccountGameplayAdmissionLeaseOperation original =
        new AccountGameplayAdmissionLeaseRepository(dsl).readExact(lease).orElseThrow();
    if (original.state() != State.ABORTED
        || original.bindingDecisionId() != null
        || !Objects.equals(original.orphanCleanupId(), cleanupId)) {
      throw new IllegalStateException("Exact retained unknown-decision Account abort required");
    }
  }

  private static AccountGameplayAdmissionAbortResolution decode(Record row) {
    return new AccountGameplayAdmissionAbortResolution(
        row.get("resolution_id", UUID.class),
        row.get("original_lease_id", UUID.class),
        BigInteger.valueOf(row.get("original_lease_fence", Long.class)),
        row.get("original_lease_sha256", String.class),
        row.get("orphan_cleanup_id", UUID.class),
        row.get("binding_decision_id", UUID.class));
  }

  private static UUID requestId(AccountGameplayAdmissionLeaseEvidence lease) {
    return UUID.fromString((String) lease.carrier().get("requestId"));
  }

  private static UUID leaseId(AccountGameplayAdmissionLeaseEvidence lease) {
    return UUID.fromString((String) lease.carrier().get("leaseId"));
  }
}
