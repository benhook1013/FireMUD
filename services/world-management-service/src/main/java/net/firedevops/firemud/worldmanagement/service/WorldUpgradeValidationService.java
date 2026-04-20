package net.firedevops.firemud.worldmanagement.service;

import net.firedevops.firemud.worldmanagement.dto.WorldUpgradeValidationResultDto;

public interface WorldUpgradeValidationService {
  WorldUpgradeValidationResultDto validateWorldUpgradeMappings(
      long tenantId, long sourceGameInstanceId, long targetVersionId, String remapSetId);
}
