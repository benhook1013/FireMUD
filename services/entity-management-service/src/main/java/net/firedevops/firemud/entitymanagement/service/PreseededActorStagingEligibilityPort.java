package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence;

/**
 * Account-owned, point-in-time staging predicate read; this is not gameplay admission authority.
 */
@FunctionalInterface
public interface PreseededActorStagingEligibilityPort {
  AccountActorStagingEligibilityEvidence resolvePreseededActorStagingEligibility(
      String canonicalAccountUuid, String canonicalTenantUuid, String assignmentUuid);
}
