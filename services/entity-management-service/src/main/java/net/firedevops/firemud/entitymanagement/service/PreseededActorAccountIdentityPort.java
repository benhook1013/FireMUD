package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;

/** Account-owned canonical UUID provenance read used by the protected actor-assignment path. */
@FunctionalInterface
public interface PreseededActorAccountIdentityPort {
  RuntimeAccountIdentityEvidence resolveRuntimeAccountIdentity(
      String canonicalAccountUuid, String assignmentUuid);
}
