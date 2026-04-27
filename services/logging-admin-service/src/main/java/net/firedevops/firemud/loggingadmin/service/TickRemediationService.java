package net.firedevops.firemud.loggingadmin.service;

import net.firedevops.firemud.loggingadmin.dto.TickRemediationActionDto;
import net.firedevops.firemud.loggingadmin.dto.TickRemediationRequest;

public interface TickRemediationService {
  TickRemediationActionDto pauseTicksForScope(TickRemediationRequest request);

  TickRemediationActionDto resumeTicksForScope(TickRemediationRequest request);
}
