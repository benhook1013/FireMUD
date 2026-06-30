package net.firedevops.firemud.automationscripting.service.quota;

import java.util.Optional;

/** Service reserving bounded tenant and cluster capacity for live publish/readiness work. */
public interface ScriptReadinessCapacityService {
  Optional<Reservation> tryReserve(String tenantId, long workItemId);

  void release(Reservation reservation);

  record Reservation(String tenantId, long workItemId, String tenantToken, String clusterToken) {}
}
