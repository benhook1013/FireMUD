package net.firedevops.firemud.loggingadmin.service;

import net.firedevops.firemud.loggingadmin.dto.RuntimeOwnershipStatusDto;

public interface TickRemediationService {
  RuntimeOwnershipStatusDto getRuntimeOwnershipStatus(
      long tenantId, String gameInstanceId, String regionId);
}
