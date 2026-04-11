package net.firedevops.firemud.gamesession.command.text;

import java.util.Locale;

/** Supported text commands exposed to Telnet and WebSocket clients. */
public enum TextCommandType {
  WORLDS,
  LOGIN,
  LOGOUT,
  PLAY,
  HELP,
  AFK,
  WHO,
  INVENTORY,
  EQUIPMENT,
  CONTAINER,
  GET,
  DROP,
  PUT,
  TAKE,
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
      case "LOGOUT", "LOGOFF", "QUIT" -> LOGOUT;
      case "PLAY" -> PLAY;
      case "HELP" -> HELP;
      case "AFK", "BRB" -> AFK;
      case "WHO" -> WHO;
      case "INVENTORY", "INV", "I" -> INVENTORY;
      case "EQUIPMENT", "EQUIP", "EQ" -> EQUIPMENT;
      case "CONTAINER", "CONT" -> CONTAINER;
      case "GET" -> GET;
      case "DROP" -> DROP;
      case "PUT" -> PUT;
      case "TAKE" -> TAKE;
      case "WEAR" -> WEAR;
      case "REMOVE" -> REMOVE;
      case "LOOK", "L" -> LOOK;
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
