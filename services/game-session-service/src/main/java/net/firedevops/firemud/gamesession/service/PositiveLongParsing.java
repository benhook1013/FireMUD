package net.firedevops.firemud.gamesession.service;

import java.util.Optional;
import net.firedevops.firemud.common.security.JwtClaims;
import org.springframework.util.StringUtils;

public final class PositiveLongParsing {
  private PositiveLongParsing() {}

  public static ParsedPositiveLong parseOptionalText(String text, String fieldName) {
    if (!StringUtils.hasText(text)) {
      return new ParsedPositiveLong(false, null);
    }
    try {
      return new ParsedPositiveLong(true, JwtClaims.requireLong(text, fieldName, false));
    } catch (IllegalArgumentException ex) {
      return new ParsedPositiveLong(true, null);
    }
  }

  public static Optional<Long> requireOptionalText(String text, String fieldName) {
    if (!StringUtils.hasText(text)) {
      return Optional.empty();
    }
    try {
      return Optional.of(JwtClaims.requireLong(text, fieldName, false));
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException(invalidMessage(ex, fieldName), ex);
    }
  }

  private static String invalidMessage(RuntimeException ex, String fieldName) {
    if (("Invalid claim: " + fieldName).equals(ex.getMessage())) {
      return fieldName + " must be positive";
    }
    return fieldName + " must be numeric";
  }

  public record ParsedPositiveLong(boolean present, Long value) {
    public boolean valid() {
      return value != null;
    }

    public boolean invalid() {
      return present && value == null;
    }

    public Optional<Long> optionalValue() {
      return Optional.ofNullable(value);
    }
  }
}
