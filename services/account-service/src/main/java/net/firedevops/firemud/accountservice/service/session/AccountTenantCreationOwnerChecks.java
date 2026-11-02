package net.firedevops.firemud.accountservice.service.session;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Shared retained-original checks within the existing tenant-creation owner transactions. */
final class AccountTenantCreationOwnerChecks {
  private AccountTenantCreationOwnerChecks() {}

  static AccountControlUiIssuanceRepository.Stored readOriginalIssuance(
      DSLContext dsl,
      AccountControlUiIssuanceRepository issuances,
      AccountTenantCreationAdmissionBinding admitted,
      UUID initiatingAccountId,
      Supplier<IllegalStateException> denied) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ?",
            admitted.issuanceOperationId());
    if (row == null) throw denied.get();
    var issuance = issuances.decodeStored(row);
    if (!admitted.issuanceOperationId().equals(issuance.operationId)
        || !initiatingAccountId.equals(issuance.accountId)
        || !admitted.tokenHash().equals(issuance.tokenHash)) throw denied.get();
    return issuance;
  }

  static Instant currentValidBefore(
      AccountTenantCreationAdmissionBinding admitted,
      AccountTenantCreationOwnerSourceCapture.Captured captured) {
    Instant deadline = Instant.ofEpochMilli(admitted.operationDeadlineEpochMillis());
    var recent = captured.reauthentication();
    Instant recentDeadline =
        recent.issuedAt().plusSeconds(recent.recentAgeSeconds() - recent.maxClockSkewSeconds());
    if (recentDeadline.isBefore(deadline)) deadline = recentDeadline;
    Instant termsDeadline = captured.currentness().terms().validUntil();
    if (termsDeadline != null && termsDeadline.isBefore(deadline)) deadline = termsDeadline;
    return deadline;
  }
}
