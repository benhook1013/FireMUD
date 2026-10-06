package unit.net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import net.firedevops.firemud.gamesession.command.text.AuthorityEvaluationFreshness;
import org.junit.jupiter.api.Test;

class AuthorityEvaluationFreshnessTest {
  private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void acceptsCurrentAndExactlyFifteenSecondOldEvaluations() {
    assertThat(AuthorityEvaluationFreshness.isFresh(NOW.toString(), CLOCK)).isTrue();
    assertThat(AuthorityEvaluationFreshness.isFresh(NOW.minusSeconds(15).toString(), CLOCK))
        .isTrue();
  }

  @Test
  void rejectsOlderAndFutureEvaluations() {
    assertThat(
            AuthorityEvaluationFreshness.isFresh(
                NOW.minusSeconds(15).minusNanos(1).toString(), CLOCK))
        .isFalse();
    assertThat(AuthorityEvaluationFreshness.isFresh(NOW.plusNanos(1).toString(), CLOCK)).isFalse();
  }

  @Test
  void rejectsMissingBlankAndMalformedEvaluations() {
    assertThat(AuthorityEvaluationFreshness.isFresh(null, CLOCK)).isFalse();
    assertThat(AuthorityEvaluationFreshness.isFresh("   ", CLOCK)).isFalse();
    assertThat(AuthorityEvaluationFreshness.isFresh("not-an-instant", CLOCK)).isFalse();
  }
}
