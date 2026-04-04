package net.firedevops.firemud.gamesession.logging;

import java.util.Locale;

/** Redacts sensitive gameplay command text before it is written to logs. */
public final class GameSessionCommandLogSanitizer {
  private GameSessionCommandLogSanitizer() {}

  public static String sanitize(String command) {
    if (command == null || command.isBlank()) {
      return command;
    }

    String trimmed = command.trim();
    String[] parts = trimmed.split("\\s+", 2);
    String verb = parts[0].toUpperCase(Locale.ROOT);
    return switch (verb) {
      case "LOGIN", "LOGON" -> verb + " [redacted]";
      default -> command;
    };
  }
}
