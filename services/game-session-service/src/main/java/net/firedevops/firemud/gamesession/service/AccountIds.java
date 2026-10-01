package net.firedevops.firemud.gamesession.service;

import java.util.UUID;

/** Validation for canonical Account logical identifiers carried by Game Session. */
public final class AccountIds {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private AccountIds() {}

  public static boolean isCanonicalNonNilUuid(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    try {
      UUID parsed = UUID.fromString(value);
      return !NIL_UUID.equals(parsed) && parsed.toString().equals(value);
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }
}
