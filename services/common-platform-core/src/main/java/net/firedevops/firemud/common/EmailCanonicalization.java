package net.firedevops.firemud.common;

import java.util.Locale;

/** Canonicalizes email values shared across account-facing service boundaries. */
public final class EmailCanonicalization {
  private EmailCanonicalization() {}

  public static String normalize(String email) {
    if (email == null) {
      throw new IllegalArgumentException("email must not be null");
    }
    String trimmed = email.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("email must not be blank");
    }
    return trimmed.toLowerCase(Locale.ROOT);
  }
}
