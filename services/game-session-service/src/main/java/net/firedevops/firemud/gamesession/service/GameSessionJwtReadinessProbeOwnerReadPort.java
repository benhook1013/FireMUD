package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerResponse;

/**
 * Authenticated, read-only Account owner lookup; request contains the token hash, never the JWT.
 */
@FunctionalInterface
public interface GameSessionJwtReadinessProbeOwnerReadPort {
  GetCurrentReadinessProbeOwnerResponse readCurrent(GetCurrentReadinessProbeOwnerRequest request);
}
