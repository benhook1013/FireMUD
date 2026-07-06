package net.firedevops.firemud.common.security;

/** Shared helpers for parsing positive numeric request identifiers at service boundaries. */
public final class RequestIdValidation {
  private RequestIdValidation() {}

  public static long requirePositiveLong(String value, String fieldName) {
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0L) {
        throw new IllegalArgumentException(fieldName + " must be positive");
      }
      return parsed;
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(fieldName + " must be numeric", ex);
    }
  }
}
