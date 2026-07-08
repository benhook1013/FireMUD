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

  public static Long parseOptionalPositiveLong(String value, String fieldName) {
    return value == null || value.isBlank() ? null : requirePositiveLong(value, fieldName);
  }

  public static long requirePositiveLong(Long value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    if (value <= 0L) {
      throw new IllegalArgumentException(fieldName + " must be positive");
    }
    return value;
  }

  public static int requirePositiveInt(String value, String fieldName) {
    long parsed = requirePositiveLong(value, fieldName);
    if (parsed > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(fieldName + " must fit in a 32-bit integer");
    }
    return (int) parsed;
  }
}
