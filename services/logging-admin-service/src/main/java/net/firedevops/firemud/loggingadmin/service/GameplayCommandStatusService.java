package net.firedevops.firemud.loggingadmin.service;

import net.firedevops.firemud.loggingadmin.dto.GameplayCommandStatusDto;

public interface GameplayCommandStatusService {
  GameplayCommandStatusDto getGameplayCommandStatus(
      long tenantId, long gameInstanceId, String commandId);
}
