package net.firedevops.firemud.socialgroups.test;

import java.util.Locale;

/**
 * Stable ordinary Account UUIDs for fixtures only; ordinals are never runtime identity mappings.
 */
public final class AccountUuidFixtures {
  private AccountUuidFixtures() {}

  public static String accountUuid(long fixtureOrdinal) {
    return String.format(Locale.ROOT, "10000000-0000-4000-8000-%012d", fixtureOrdinal);
  }
}
