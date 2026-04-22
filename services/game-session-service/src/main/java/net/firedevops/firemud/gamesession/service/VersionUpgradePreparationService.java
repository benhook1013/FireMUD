package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.dto.PreparedVersionUpgradeDto;

public interface VersionUpgradePreparationService {
  PreparedVersionUpgradeDto prepareVersionUpgrade(
      long tenantId, long sourceGameInstanceId, long targetVersionId, String controlPlaneRequestId);

  PreparedVersionUpgradeDto getPreparedVersionUpgrade(long tenantId, String preparationId);

  PreparedVersionUpgradeDto markPreparedVersionUpgradeExecuted(
      long tenantId,
      String preparationId,
      long targetGameInstanceId,
      long executedPointerVersion,
      String executionControlPlaneRequestId);
}
