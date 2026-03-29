package net.firedevops.firemud.gamesession.command.text;

import java.util.Locale;

/** Supported text commands exposed to Telnet and WebSocket clients. */
public enum TextCommandType {
  LOGIN,
  LOOK,
  SAY,
  MOVE,
  NOOP,
  UNKNOWN;

  public static TextCommandType fromToken(String token) {
    if (token == null || token.isBlank()) {
      return UNKNOWN;
    }

    String normalized = token.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "LOGIN", "LOGON" -> LOGIN;
      case "LOOK" -> LOOK;
      case "SAY", "YELL", "WHISPER" -> SAY;
      case "MOVE", "GO", "NORTH", "SOUTH", "EAST", "WEST" -> MOVE;
      default -> UNKNOWN;
    };
  }
}
