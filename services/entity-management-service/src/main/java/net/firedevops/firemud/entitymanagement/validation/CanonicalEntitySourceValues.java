package net.firedevops.firemud.entitymanagement.validation;

import java.util.UUID;
import java.util.regex.Pattern;

/** Pure canonical value rules shared by passive Entity source boundaries. */
public final class CanonicalEntitySourceValues {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern RAW_SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern PREFIXED_SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

  private CanonicalEntitySourceValues() {}

  public static UUID parseCanonicalNonNilUuid(String value) {
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Canonical non-nil UUID is required", invalid);
    }
    if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Canonical non-nil UUID is required");
    }
    return parsed;
  }

  public static String normalizeSha256ToRawHex(String digest) {
    if (isPrefixedSha256(digest)) {
      return digest.substring("sha256:".length());
    }
    if (isRawSha256(digest)) {
      return digest;
    }
    throw new IllegalArgumentException("Canonical SHA-256 digest is required");
  }

  public static boolean isRawSha256(String digest) {
    return digest != null && RAW_SHA256.matcher(digest).matches();
  }

  public static boolean isPrefixedSha256(String digest) {
    return digest != null && PREFIXED_SHA256.matcher(digest).matches();
  }
}
