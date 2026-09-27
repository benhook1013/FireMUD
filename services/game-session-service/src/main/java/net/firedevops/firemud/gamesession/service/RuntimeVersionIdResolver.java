package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.entity.GameInstance;

/** Resolves the immutable owner version ID recorded for a Game Session runtime. */
public final class RuntimeVersionIdResolver {
  private RuntimeVersionIdResolver() {}

  public static Long resolve(GameInstance instance) {
    return resolve(instance.getVersionId(), instance.getRuntimeVersion());
  }

  public static Long resolve(Long versionId, String runtimeVersion) {
    if (versionId != null) {
      return versionId > 0L ? versionId : null;
    }

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
