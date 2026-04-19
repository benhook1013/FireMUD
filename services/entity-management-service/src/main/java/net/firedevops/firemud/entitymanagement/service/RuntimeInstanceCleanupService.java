package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.dto.RuntimeInstanceCleanupResultDto;

public interface RuntimeInstanceCleanupService {
  RuntimeInstanceCleanupResultDto cleanupRuntimeInstance(
      Long tenantId, String gameInstanceId, String terminationRequestId);
}
