package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.ApplyModerationActionRequest;
import net.firedevops.firemud.dto.ModerationActionDto;

public interface ModerationService {
  ModerationActionDto applyAction(ApplyModerationActionRequest request);
}
