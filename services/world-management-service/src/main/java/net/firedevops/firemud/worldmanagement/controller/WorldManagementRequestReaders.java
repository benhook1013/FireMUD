package net.firedevops.firemud.worldmanagement.controller;

import net.firedevops.firemud.common.security.RequestIdValidation;

final class WorldManagementRequestReaders {
  private WorldManagementRequestReaders() {}

  static long requireTenantId(String tenantId) {
    return RequestIdValidation.requirePositiveLong(tenantId, "tenantId");
  }

  static long requireRegionId(String regionId) {
    return RequestIdValidation.requirePositiveLong(regionId, "id");
  }
}
