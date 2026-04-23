package net.firedevops.firemud.automationscripting.service.quota;

import java.util.Optional;

/** Service reserving isolated dry-run/test execution capacity. */
public interface ScriptDryRunCapacityService {
  Optional<Reservation> tryReserve(String tenantId, long workItemId);

  void release(Reservation reservation);

  record Reservation(String tenantId, long workItemId, String tenantToken, String clusterToken) {}
}
