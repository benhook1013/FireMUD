package net.firedevops.firemud.common.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared helpers for parsing positive numeric request identifiers at service boundaries. */
public final class RequestIdValidation {
  private static final Pattern CANONICAL_RUNTIME_ROOM_ID_PATTERN =
      Pattern.compile("^R-([1-9][0-9]*)$");

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

  public static String requireCanonicalRuntimeRoomId(String value, String fieldName) {
    return canonicalRuntimeRoomIdMatcher(value, fieldName).group(0);
  }

  public static long requireCanonicalRuntimeRoomRowId(String value, String fieldName) {
    return requirePositiveLong(canonicalRuntimeRoomIdMatcher(value, fieldName).group(1), fieldName);
  }

  private static Matcher canonicalRuntimeRoomIdMatcher(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must be specified");
    }
    Matcher matcher = CANONICAL_RUNTIME_ROOM_ID_PATTERN.matcher(value);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(fieldName + " must be a runtime room id like R-1021");
    }
    return matcher;
  }
}
