package net.firedevops.firemud.gamesession.command.text;

import java.time.Clock;
import java.util.Objects;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;

/** Shared validation for caller- and tenant-bound Account runtime authority snapshots. */
final class AccountAuthorityEvidence {
  private AccountAuthorityEvidence() {}

  static boolean hasMatchingAccountAndTenant(
      String accountId, String tenantId, long expectedAccountId, long expectedTenantId) {
    try {
      return Long.parseLong(accountId) == expectedAccountId
          && Long.parseLong(tenantId) == expectedTenantId;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  static boolean hasMatchingTenant(String tenantId, long expectedTenantId) {
    try {
      return Long.parseLong(tenantId) == expectedTenantId;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  static boolean isSafeMembershipSnapshot(
      GetTenantMembershipForRuntimeResponse response,
      long expectedAccountId,
      long expectedTenantId,
      Clock clock) {
    Objects.requireNonNull(response, "response must not be null");
    if (!hasMatchingAccountAndTenant(
            response.getAccountId(), response.getTenantId(), expectedAccountId, expectedTenantId)
        || !AuthorityEvaluationFreshness.isFresh(response.getEvaluatedAt(), clock)) {
      return false;
    }
    if (!response.getMembershipExists()) {
      return "MISSING".equalsIgnoreCase(response.getMembershipLifecycleState())
          && !response.getGameplayAdmissionAllowed()
          && response.getMembershipVersion() == 0L
          && response.getMembershipAuthorityGeneration() == 0L;
    }
    if (response.getMembershipVersion() <= 0L
        || response.getMembershipAuthorityGeneration() <= 0L) {
      return false;
    }
    if (response.getGameplayAdmissionAllowed()) {
      return "ACTIVE".equalsIgnoreCase(response.getMembershipLifecycleState());
    }
    return "ACTIVE".equalsIgnoreCase(response.getMembershipLifecycleState())
        || "INACTIVE".equalsIgnoreCase(response.getMembershipLifecycleState());
  }

  static boolean isActiveMembership(
      GetTenantMembershipForRuntimeResponse response,
      long expectedAccountId,
      long expectedTenantId,
      Clock clock) {
    return response.getMembershipExists()
        && response.getGameplayAdmissionAllowed()
        && "ACTIVE".equalsIgnoreCase(response.getMembershipLifecycleState())
        && isSafeMembershipSnapshot(response, expectedAccountId, expectedTenantId, clock);
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
      long expectedAccountId,
      long expectedTenantId,
      String expectedWorldSlug,
      String expectedRealmSlug,
      Clock clock) {
    return response.getGrantVersion() > 0L
        && hasMatchingAccountAndTenant(
            response.getAccountId(), response.getTenantId(), expectedAccountId, expectedTenantId)
        && expectedWorldSlug.equals(response.getWorldSlug())
        && expectedRealmSlug.equals(response.getRealmSlug())
        && AuthorityEvaluationFreshness.isFresh(response.getEvaluatedAt(), clock);
  }
}
