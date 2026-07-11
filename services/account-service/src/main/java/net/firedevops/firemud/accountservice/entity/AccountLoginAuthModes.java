package net.firedevops.firemud.accountservice.entity;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Canonical reader for the persisted primary-login mode set. */
public final class AccountLoginAuthModes {
  public static final String DEFAULT_SERIALIZED = "PASSWORD,EMAIL_OTP";

  private AccountLoginAuthModes() {}

  public static boolean allows(String serializedModes, AccountLoginAuthMode mode) {
    return read(serializedModes).contains(mode);
  }

  public static Set<AccountLoginAuthMode> read(String serializedModes) {
    if (serializedModes == null || serializedModes.isBlank()) {
      return EnumSet.of(AccountLoginAuthMode.PASSWORD);
    }

    EnumSet<AccountLoginAuthMode> modes = EnumSet.noneOf(AccountLoginAuthMode.class);
    for (String token : serializedModes.split(",")) {
      try {
        modes.add(AccountLoginAuthMode.valueOf(token.trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException ex) {
        throw new IllegalStateException("Account has an invalid login authentication mode", ex);
      }
    }
    if (modes.isEmpty()) {
      throw new IllegalStateException("Account must have at least one login authentication mode");
    }
    return Set.copyOf(modes);
  }
}
