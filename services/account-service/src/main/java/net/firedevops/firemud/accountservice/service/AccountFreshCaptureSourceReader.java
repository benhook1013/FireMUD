package net.firedevops.firemud.accountservice.service;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountFreshCaptureSources;
import net.firedevops.firemud.accountservice.dto.AccountMembershipCaptureSources;
import net.firedevops.firemud.accountservice.repository.AccountGlobalRoleSourceRepository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unwired composition of existing fresh Account role-birth and membership source evidence.
 *
 * <p>The caller owns one writable READ_COMMITTED Account transaction. The membership producer
 * obtains the Account and composite/pair source locks first; the global-role repository then
 * verifies that same transaction and locks the exact birth source. This reader performs no
 * enrollment, initialization, mutation, authorization, or admission.
 */
public final class AccountFreshCaptureSourceReader {
  private final AccountMembershipAuthorityEventProducer membershipAuthorityEventProducer;
  private final AccountGlobalRoleSourceRepository globalRoleSourceRepository;

  public AccountFreshCaptureSourceReader(
      AccountMembershipAuthorityEventProducer membershipAuthorityEventProducer,
      AccountGlobalRoleSourceRepository globalRoleSourceRepository) {
    this.membershipAuthorityEventProducer =
        Objects.requireNonNull(
            membershipAuthorityEventProducer, "membership authority producer is required");
    this.globalRoleSourceRepository =
        Objects.requireNonNull(
            globalRoleSourceRepository, "global-role source repository is required");
  }

  /** Reads the exact already-existing fresh Account source vector and role-birth source. */
  public AccountFreshCaptureSources readExisting(UUID accountUuid, UUID tenantUuid) {
    requireCanonicalUuid(accountUuid, "Account UUID");
    requireCanonicalUuid(tenantUuid, "tenant UUID");
    requireWritableOwnerTransaction();

    AccountMembershipCaptureSources membershipSources =
        membershipAuthorityEventProducer.readExistingRuntimeMembershipCaptureSources(
            accountUuid, tenantUuid);
    AccountGlobalRoleSourceRepository.FreshEmptySource globalRoleSource =
        globalRoleSourceRepository.readFreshEmptySourceForUpdate(accountUuid);

    return new AccountFreshCaptureSources(
        accountUuid, tenantUuid, membershipSources, globalRoleSource);
  }

  private static void requireCanonicalUuid(UUID value, String field) {
    if (value == null || new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID");
    }
  }

  private static void requireWritableOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Fresh Account capture requires a writable caller-owned Account transaction");
    }
  }
}
