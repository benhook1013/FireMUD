package net.firedevops.firemud.loggingadmin.service;

import net.firedevops.firemud.loggingadmin.dto.RuntimeOwnershipStatusDto;
import net.firedevops.firemud.loggingadmin.dto.TickRemediationActionDto;
import net.firedevops.firemud.loggingadmin.dto.TickRemediationRequest;

public interface TickRemediationService {
  RuntimeOwnershipStatusDto getRuntimeOwnershipStatus(
      long tenantId, String gameInstanceId, String regionId);

  TickRemediationActionDto pauseTicksForScope(TickRemediationRequest request);

  TickRemediationActionDto resumeTicksForScope(TickRemediationRequest request);
}
