package net.firedevops.firemud.gamesession.service;

import java.util.Locale;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;

/** Shared source and text normalization for Automation gameplay command fences. */
public final class GameplayCommandSourceCoherence {
  private GameplayCommandSourceCoherence() {}

  public static boolean isLocalAutomation(GameplayCommand command) {
    return command != null
        && isLocalAutomation(command.getSourceType(), command.getRemoteFollowupId());
  }

  public static boolean isLocalAutomation(String sourceType, String remoteFollowupId) {
    return "AUTOMATION".equals(normalizeSourceType(sourceType))
        && normalizeText(remoteFollowupId).isEmpty();
  }

  public static String normalizeSourceType(String value) {
    return normalizeText(value).toUpperCase(Locale.ROOT);
  }

  public static String normalizeText(String value) {
    return value == null ? "" : value.trim();
  }
}
