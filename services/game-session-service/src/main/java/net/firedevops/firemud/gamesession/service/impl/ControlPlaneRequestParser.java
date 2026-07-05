package net.firedevops.firemud.gamesession.service.impl;

final class ControlPlaneRequestParser {
  private ControlPlaneRequestParser() {}

  static long parsePositiveLong(String text, String fieldName) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    try {
      long value = Long.parseLong(text);
      if (value <= 0) {
        throw new IllegalArgumentException(fieldName + " must be positive");
      }
      return value;
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(fieldName + " must be a number");
    }
  }
}
