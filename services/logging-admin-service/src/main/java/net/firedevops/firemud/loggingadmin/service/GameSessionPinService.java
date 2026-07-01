package net.firedevops.firemud.loggingadmin.service;

import net.firedevops.firemud.loggingadmin.dto.GameSessionPinConvergenceDto;
import net.firedevops.firemud.loggingadmin.dto.PinnedScriptPatchVersionDto;

public interface GameSessionPinService {
  PinnedScriptPatchVersionDto getPinnedScriptPatchVersion(long tenantId, long gameInstanceId);

  GameSessionPinConvergenceDto getGameSessionPinConvergence(long tenantId, long gameInstanceId);
}
