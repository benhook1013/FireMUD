package net.firedevops.firemud.gamesession.command.text;

/** Canonicalizes the bounded direction vocabulary accepted by text movement commands. */
final class TextCommandDirections {
  private TextCommandDirections() {}

  static String canonicalDirection(String token) {
    if (token == null || token.isBlank()) {
      return "";
    }
    return switch (token.trim().toUpperCase(java.util.Locale.ROOT)) {
      case "N", "NORTH" -> "north";
      case "S", "SOUTH" -> "south";
      case "E", "EAST" -> "east";
      case "W", "WEST" -> "west";
      case "U", "UP" -> "up";
      case "D", "DOWN" -> "down";
      default -> "";
    };
  }
}
