package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.dto.InstanceCutoverCompatibilityDto;

public interface InstanceCutoverCompatibilityService {
  InstanceCutoverCompatibilityDto validateInstanceCutoverCompatibility(
      long tenantId, long sourceGameInstanceId, long targetVersionId);
}
