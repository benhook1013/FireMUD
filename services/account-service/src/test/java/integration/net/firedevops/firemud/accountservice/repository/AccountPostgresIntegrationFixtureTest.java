package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccountPostgresIntegrationFixtureTest {
  @Test
  void durablePrimaryCommandRequiresFlushedLocalCommits() {
    assertThat(AccountPostgresIntegrationFixture.durablePostgresCommand())
        .containsExactly("postgres", "-c", "fsync=on", "-c", "synchronous_commit=on");
  }

  @Test
  void acceptsDurablePrimarySettings() {
    assertThatCode(
            () ->
                AccountPostgresIntegrationFixture.validateDurablePrimarySettings(false, "on", "on"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsRecoveryAndUnflushedCommitSettings() {
    assertRejectedSettings(true, "on", "on");
    assertRejectedSettings(false, "off", "on");
    assertRejectedSettings(false, "on", "off");
  }

  private static void assertRejectedSettings(
      boolean inRecovery, String fsync, String synchronousCommit) {
    assertThatThrownBy(
            () ->
                AccountPostgresIntegrationFixture.validateDurablePrimarySettings(
                    inRecovery, fsync, synchronousCommit))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Durable PostgreSQL primary required");
  }
}
