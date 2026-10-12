package integration.net.firedevops.firemud.accountservice.repository;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Properties;
import net.firedevops.firemud.test.TestContainerImages;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/** Test-only PostgreSQL fixture backed by a disposable Testcontainers database. */
public final class AccountPostgresIntegrationFixture {
  private final PostgreSQLContainer<?> container;
  private final boolean requireDurablePrimary;
  private boolean started;

  public AccountPostgresIntegrationFixture() {
    this(false);
  }

  public AccountPostgresIntegrationFixture(boolean requireDurablePrimary) {
    this.requireDurablePrimary = requireDurablePrimary;
    PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(TestContainerImages.postgres());
    if (requireDurablePrimary) {
      postgres = postgres.withCommand(durablePostgresCommand());
    }
    container = postgres;
  }

  public void start() {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker is unavailable; the PostgreSQL test fixture requires Testcontainers");
    container.start();
    started = true;
    if (requireDurablePrimary) {
      try {
        verifyDurablePrimary();
      } catch (RuntimeException unsuitable) {
        stop();
        throw unsuitable;
      }
    }
  }

  public void stop() {
    if (started) {
      container.stop();
      started = false;
    }
  }

  public DriverManagerDataSource dataSource() {
    return dataSource(null);
  }

  public DriverManagerDataSource dataSource(String schema) {
    if (!started) {
      throw new IllegalStateException("The PostgreSQL test fixture has not started");
    }
    String url = container.getJdbcUrl();
    String username = container.getUsername();
    String password = container.getPassword();
    if (schema != null) {
      if (!schema.matches("[a-z][a-z0-9_]{0,62}")) {
        throw new IllegalArgumentException("Generated test PostgreSQL schema name is invalid");
      }
      url += (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(url);
    dataSource.setUsername(username);
    dataSource.setPassword(password);
    Properties connectionProperties = new Properties();
    connectionProperties.setProperty("connectTimeout", "10");
    connectionProperties.setProperty("socketTimeout", "60");
    dataSource.setConnectionProperties(connectionProperties);
    return dataSource;
  }

  /** Reads and validates the live PostgreSQL settings required by durable commit confirmation. */
  public DurablePrimarySettings verifyDurablePrimary() {
    DurablePrimarySettings settings =
        new JdbcTemplate(dataSource())
            .queryForObject(
                "SELECT pg_is_in_recovery(), current_setting('fsync'), "
                    + "current_setting('synchronous_commit')",
                (result, rowNumber) ->
                    new DurablePrimarySettings(
                        result.getBoolean(1), result.getString(2), result.getString(3)));
    if (settings == null) {
      throw new IllegalStateException("Durable PostgreSQL primary settings are unavailable");
    }
    validateDurablePrimarySettings(
        settings.inRecovery(), settings.fsync(), settings.synchronousCommit());
    return settings;
  }

  static String[] durablePostgresCommand() {
    return new String[] {"postgres", "-c", "fsync=on", "-c", "synchronous_commit=on"};
  }

  static void validateDurablePrimarySettings(
      boolean inRecovery, String fsync, String synchronousCommit) {
    if (inRecovery || !"on".equals(fsync) || !"on".equals(synchronousCommit)) {
      throw new IllegalStateException(
          "Durable PostgreSQL primary required (pg_is_in_recovery=false, fsync=on, "
              + "synchronous_commit=on); observed pg_is_in_recovery="
              + inRecovery
              + ", fsync="
              + fsync
              + ", synchronous_commit="
              + synchronousCommit);
    }
  }

  public record DurablePrimarySettings(
      boolean inRecovery, String fsync, String synchronousCommit) {}
}
