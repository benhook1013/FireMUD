package net.firedevops.firemud.common;

import java.util.Locale;

/** Canonicalizes email values shared across account-facing service boundaries. */
public final class EmailCanonicalization {
  private EmailCanonicalization() {}

  public static String normalize(String email) {
    return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
  }
}
