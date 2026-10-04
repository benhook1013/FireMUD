package net.firedevops.firemud.loggingadmin.service;

import java.util.UUID;
import net.firedevops.firemud.loggingadmin.dto.ApplyModerationActionRequest;
import net.firedevops.firemud.loggingadmin.dto.ModerationActionDto;
import net.firedevops.firemud.loggingadmin.dto.ModerationPolicyDecisionDto;

public interface ModerationService {
  ModerationActionDto applyAction(ApplyModerationActionRequest request);

  ModerationPolicyDecisionDto evaluatePolicy(long tenantId, UUID accountId, String scope);
}
