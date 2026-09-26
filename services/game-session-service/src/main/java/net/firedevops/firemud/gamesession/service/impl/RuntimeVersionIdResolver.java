package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.gamesession.entity.GameInstance;

final class RuntimeVersionIdResolver {
  private RuntimeVersionIdResolver() {}

  static Long resolve(GameInstance instance) {
    Long versionId = instance.getVersionId();
    if (versionId != null) {
      return versionId > 0L ? versionId : null;
    }

    String runtimeVersion = instance.getRuntimeVersion();
    if (runtimeVersion == null || runtimeVersion.isBlank()) {
      return null;
    }
    try {
      long parsed = Long.parseLong(runtimeVersion);
      return parsed > 0L ? parsed : null;
    } catch (NumberFormatException ex) {
      return null;
    }
  }
}
