package net.firedevops.firemud.gamesession.service;

import java.util.Optional;
import net.firedevops.firemud.common.security.JwtClaims;

public final class SessionIdParsing {
  private SessionIdParsing() {}

  public static ParsedSessionId parse(String sessionIdText) {
    try {
      return new ParsedSessionId(JwtClaims.requireLong(sessionIdText, "sessionId", false), null);
    } catch (RuntimeException ex) {
      return new ParsedSessionId(null, invalidSessionMessage(ex));
    }
  }

  public static long require(String sessionIdText) {
    ParsedSessionId parsed = parse(sessionIdText);
    if (parsed.valid()) {
      return parsed.value();
    }
    throw new IllegalArgumentException(parsed.errorMessage());
  }

  private static String invalidSessionMessage(RuntimeException ex) {
    if ("Invalid claim: sessionId".equals(ex.getMessage())) {
      return "sessionId must be positive";
    }
    return "sessionId must be numeric";
  }

  public record ParsedSessionId(Long value, String errorMessage) {
    public boolean valid() {
      return value != null;
    }

    public Optional<Long> optionalValue() {
      return Optional.ofNullable(value);
    }
  }
}
