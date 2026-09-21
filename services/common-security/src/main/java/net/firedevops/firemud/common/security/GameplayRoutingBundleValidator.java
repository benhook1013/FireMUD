package net.firedevops.firemud.common.security;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Validates the canonical advisory routing fields shared by gameplay bridge hops. */
public final class GameplayRoutingBundleValidator {
  private static final int MAX_SLUG_UTF8_BYTES = 120;
  private static final Pattern CANONICAL_SLUG_PATTERN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
  private static final Pattern CANONICAL_POINTER_VERSION_PATTERN = Pattern.compile("[1-9][0-9]*");

  private GameplayRoutingBundleValidator() {}

  public static String requireCanonicalSlug(String value, String fieldName) {
    if (value == null
        || value.getBytes(StandardCharsets.UTF_8).length > MAX_SLUG_UTF8_BYTES
        || !CANONICAL_SLUG_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("Malformed routing slug: " + fieldName);
    }
    return value;
  }

  public static String requireCanonicalPointerVersion(String value, String fieldName) {
    if (value == null || !CANONICAL_POINTER_VERSION_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("Malformed routing pointer version: " + fieldName);
    }
    try {
      return Long.toString(Long.parseLong(value));
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("Malformed routing pointer version: " + fieldName, ex);
    }
  }
}
