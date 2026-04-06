package net.firedevops.firemud.gamesession.command.text;

import java.util.Locale;

/** Supported text commands exposed to Telnet and WebSocket clients. */
public enum TextCommandType {
  WORLDS,
  LOGIN,
  PLAY,
  HELP,
  INVENTORY,
  EQUIPMENT,
  GET,
  DROP,
  WEAR,
  REMOVE,
  LOOK,
  QUICKLOOK,
  SAY,
  WHISPER,
  TELL,
  MOVE,
  NOOP,
  UNKNOWN;

  public static TextCommandType fromToken(String token) {
    if (token == null || token.isBlank()) {
      return UNKNOWN;
    }

    String normalized = token.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "WORLDS" -> WORLDS;
      case "LOGIN", "LOGON" -> LOGIN;
      case "PLAY" -> PLAY;
      case "HELP" -> HELP;
      case "INVENTORY", "INV", "I" -> INVENTORY;
      case "EQUIPMENT", "EQUIP", "EQ" -> EQUIPMENT;
      case "GET" -> GET;
      case "DROP" -> DROP;
      case "WEAR" -> WEAR;
      case "REMOVE" -> REMOVE;
      case "LOOK" -> LOOK;
      case "QUICKLOOK", "QLOOK" -> QUICKLOOK;
      case "SAY" -> SAY;
      case "WHISPER" -> WHISPER;
      case "TELL" -> TELL;
      case "MOVE",
          "GO",
          "NORTH",
          "SOUTH",
          "EAST",
          "WEST",
          "UP",
          "DOWN",
          "N",
          "S",
          "E",
          "W",
          "U",
          "D" ->
          MOVE;
      default -> UNKNOWN;
    };
  }
}
