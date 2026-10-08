package net.firedevops.firemud.common.security;

import java.math.BigInteger;

/** Exact decimal counter parsing at Account JWT inspection boundaries. */
public final class AccountJwtExactValues {
  private AccountJwtExactValues() {}

  /** Parses canonical positive decimal-string authority counters without a BIGINT ceiling. */
  public static BigInteger positiveDecimalCounter(Object value) {
    if (!(value instanceof String text) || !text.matches("[1-9][0-9]*")) throw invalid();
    return new BigInteger(text);
  }

  /** Parses a canonical unsigned decimal string where the owning field permits zero. */
  public static BigInteger nonNegativeDecimalCounter(Object value) {
    if (!(value instanceof String text) || !text.matches("0|[1-9][0-9]*")) throw invalid();
    return new BigInteger(text);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Account JWT counter is not a canonical decimal string");
  }
}
