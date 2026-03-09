package net.firedevops.firemud.loggingadmin.service;

import net.firedevops.firemud.loggingadmin.dto.ApplyModerationActionRequest;
import net.firedevops.firemud.loggingadmin.dto.ModerationActionDto;

public interface ModerationService {
  ModerationActionDto applyAction(ApplyModerationActionRequest request);
}
