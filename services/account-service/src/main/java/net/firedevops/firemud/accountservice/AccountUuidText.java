package net.firedevops.firemud.accountservice;

import java.util.UUID;

/** Canonical non-nil UUID text parsing at Account's local transport boundaries. */
public final class AccountUuidText {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private AccountUuidText() {}

  public static UUID parseOrNull(String value) {
    if (value == null) {
      return null;
    }
    try {
      UUID parsed = UUID.fromString(value);
      return !NIL_UUID.equals(parsed) && parsed.toString().equals(value) ? parsed : null;
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }
}
