package net.firedevops.firemud.gamesession.command.text;

import java.time.Clock;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.gamesession.service.AccountIds;

/** Shared validation for caller- and tenant-bound Account runtime authority snapshots. */
final class AccountAuthorityEvidence {
  private AccountAuthorityEvidence() {}

  static boolean hasMatchingAccountAndTenant(
      String accountId, String tenantId, String expectedAccountUuid, long expectedTenantId) {
    return AccountIds.isCanonicalNonNilUuid(accountId)
        && AccountIds.isCanonicalNonNilUuid(expectedAccountUuid)
        && expectedAccountUuid.equals(accountId)
        && hasMatchingTenant(tenantId, expectedTenantId);
  }

  static boolean hasMatchingTenant(String tenantId, long expectedTenantId) {
    try {
      return Long.parseLong(tenantId) == expectedTenantId;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  static boolean isValidEntitlement(
      GetTenantEntitlementsForRuntimeResponse response, long expectedTenantId, Clock clock) {
    return hasMatchingTenant(response.getTenantId(), expectedTenantId)
        && response.getEntitlementVersion() > 0L
        && response.getTenantBillingSequence() > 0L
        && AuthorityEvaluationFreshness.isFresh(response.getEvaluatedAt(), clock);
  }

  static boolean isValidRealmAccessGrant(
      GetRealmAccessGrantForRuntimeResponse response,
      String expectedAccountUuid,
      long expectedTenantId,
      String expectedWorldSlug,
      String expectedRealmSlug,
      Clock clock) {
    return response.getGrantVersion() > 0L
        && hasMatchingAccountAndTenant(
            response.getAccountId(), response.getTenantId(), expectedAccountUuid, expectedTenantId)
        && expectedWorldSlug.equals(response.getWorldSlug())
        && expectedRealmSlug.equals(response.getRealmSlug())
        && AuthorityEvaluationFreshness.isFresh(response.getEvaluatedAt(), clock);
  }
}
