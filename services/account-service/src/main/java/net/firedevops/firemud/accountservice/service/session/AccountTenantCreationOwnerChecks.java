package net.firedevops.firemud.accountservice.service.session;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService.AcceptedSignerKey;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionRepository.Admitted;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository.OriginalIntent;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationOwnerSourceCapture.Captured;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Shared retained-original checks within the existing tenant-creation owner transactions. */
final class AccountTenantCreationOwnerChecks {
  private AccountTenantCreationOwnerChecks() {}

  static String originalKid(byte[] signerReceipt, Supplier<IllegalStateException> denied) {
    Object kid = AccountControlUiIssuanceRepository.object(signerReceipt).get("kid");
    if (!(kid instanceof String text) || text.isBlank()) throw denied.get();
    return text;
  }

  static LockedCurrent captureCurrent(
      AccountTenantCreationOwnerSourceCapture capture,
      AccountTenantCreationSourceOrderRepository orders,
      OriginalIntent intent,
      Admitted admitted,
      AccountControlUiIssuanceRepository.Stored issuance,
      AccountTenantCreationSourceOrderBinding originalHolder,
      CapturedEnvironmentBoundary environment,
      AccountRecentReauthenticationEvidence recent,
      AcceptedSignerKey accepted,
      Supplier<IllegalStateException> denied) {
    Captured actual = capture.captureLocked(intent, admitted, environment, recent);
    AccountControlUiActorService.requireAcceptedSignerKey(issuance, accepted);
    if (!Arrays.equals(actual.originalClaims(), issuance.claims)
        || !Arrays.equals(actual.originalSignerReceipt(), issuance.signerReceipt)
        || !issuance.operationId.equals(actual.issuanceOperationId())
        || !issuance.jti.equals(actual.tokenJti())
        || !issuance.tokenHash.equals(actual.tokenHash())) throw denied.get();
    var locked = orders.lockOriginalForCompletionLocked(intent, admitted, actual.sources());
    if (!Arrays.equals(locked.canonicalBytes(), originalHolder.canonicalBytes()))
      throw denied.get();
    return new LockedCurrent(actual, locked);
  }

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

  record LockedCurrent(Captured captured, AccountTenantCreationSourceOrderBinding lockedHolder) {}
}
