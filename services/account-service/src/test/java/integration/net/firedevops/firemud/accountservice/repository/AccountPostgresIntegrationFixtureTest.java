package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountPostgresIntegrationFixtureTest {
  @Test
  void durablePrimaryConfigurationIsOptInAndRequiresFlushedLocalCommits() {
    assertThat(Arrays.asList(AccountPostgresIntegrationFixture.postgresCommand(false)))
        .containsExactly("postgres", "-c", "fsync=off");
    assertThat(Arrays.asList(AccountPostgresIntegrationFixture.postgresCommand(true)))
        .containsExactly("postgres", "-c", "fsync=on", "-c", "synchronous_commit=on");

    assertThatThrownBy(
            () ->
                AccountPostgresIntegrationFixture.validateDurablePrimarySettings(
                    false, "off", "on"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Durable PostgreSQL primary required");
    assertThatThrownBy(
            () ->
                AccountPostgresIntegrationFixture.validateDurablePrimarySettings(true, "on", "on"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Durable PostgreSQL primary required");
    assertThatThrownBy(
            () ->
                AccountPostgresIntegrationFixture.validateDurablePrimarySettings(
                    false, "on", "off"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Durable PostgreSQL primary required");

    AccountPostgresIntegrationFixture.validateDurablePrimarySettings(false, "on", "on");
  }

  @Test
  void acceptsIpv4AndBracketedIpv6LoopbackUrls() {
    for (String jdbcUrl :
        List.of(
            "jdbc:postgresql://127.0.0.1:55432/postgres",
            "jdbc:postgresql://[::1]:55432/postgres")) {
      assertThat(AccountPostgresIntegrationFixture.validateLoopbackJdbcUrl(jdbcUrl))
          .isEqualTo(jdbcUrl);
    }
  }

  @Test
  void rejectsRemoteCredentialBearingAndOptionBearingUrls() {
    for (String jdbcUrl :
        List.of(
            "jdbc:postgresql://database.example.test:55432/postgres",
            "jdbc:postgresql://user:secret@127.0.0.1:55432/postgres",
            "jdbc:postgresql://127.0.0.1:55432/postgres?options=-csearch_path=public")) {
      assertThatThrownBy(() -> AccountPostgresIntegrationFixture.validateLoopbackJdbcUrl(jdbcUrl))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(AccountPostgresIntegrationFixture.JDBC_URL_ENV);
    }
  }
}
