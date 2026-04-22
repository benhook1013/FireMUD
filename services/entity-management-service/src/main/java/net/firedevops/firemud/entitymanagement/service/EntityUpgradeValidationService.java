package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.dto.EntityUpgradeValidationResultDto;

public interface EntityUpgradeValidationService {
  EntityUpgradeValidationResultDto validateEntityUpgradeMappings(
      long tenantId, long sourceGameInstanceId, long targetVersionId, String remapSetId);
}
