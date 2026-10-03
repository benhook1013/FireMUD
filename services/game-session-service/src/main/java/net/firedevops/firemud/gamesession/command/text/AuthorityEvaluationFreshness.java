package net.firedevops.firemud.gamesession.command.text;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** Shared freshness policy for Account authority evaluations used by text commands. */
public final class AuthorityEvaluationFreshness {
  private static final Duration MAX_AGE = Duration.ofSeconds(15);

  private AuthorityEvaluationFreshness() {}

  public static boolean isFresh(String evaluatedAt, Clock clock) {
    Objects.requireNonNull(clock, "clock must not be null");
    if (evaluatedAt == null || evaluatedAt.isBlank()) {
      return false;
    }
    try {
      Instant evaluated = Instant.parse(evaluatedAt);
      Instant now = clock.instant();
      return !evaluated.isAfter(now) && !evaluated.isBefore(now.minus(MAX_AGE));
    } catch (DateTimeParseException ex) {
      return false;
    }
  }
}
