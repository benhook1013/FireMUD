package integration.net.firedevops.firemud.accountservice.repository;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.util.Properties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/** Test-only PostgreSQL fixture with a strict loopback external-database option. */
public final class AccountPostgresIntegrationFixture {
  public static final String JDBC_URL_ENV = "FIREMUD_ACCOUNT_SIGNER_TEST_POSTGRES_URL";

  private final PostgreSQLContainer<?> container;
  private final String externalJdbcUrl;
  private final boolean requireDurablePrimary;
  private boolean started;

  public AccountPostgresIntegrationFixture() {
    this(false);
  }

  public AccountPostgresIntegrationFixture(boolean requireDurablePrimary) {
    this.requireDurablePrimary = requireDurablePrimary;
    String configuredUrl = System.getenv(JDBC_URL_ENV);
    if (configuredUrl != null && !configuredUrl.isBlank()) {
      externalJdbcUrl = validateLoopbackJdbcUrl(configuredUrl);
      container = null;
    } else {
      externalJdbcUrl = null;
      container =
          new PostgreSQLContainer<>("postgres:16-alpine")
              .withCommand(postgresCommand(requireDurablePrimary));
    }
  }

  public void start() {
    if (container == null) {
      if (requireDurablePrimary) {
        verifyDurablePrimary();
      }
      return;
    }
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker is unavailable; set " + JDBC_URL_ENV + " to an isolated loopback PostgreSQL URL");
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
    String url;
    String username;
    String password;
    if (container == null) {
      url = externalJdbcUrl;
      username = "postgres";
      password = "";
    } else {
      if (!started) {
        throw new IllegalStateException("The PostgreSQL test fixture has not started");
      }
      url = container.getJdbcUrl();
      username = container.getUsername();
      password = container.getPassword();
    }
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
    if (container == null) {
      // The explicit loopback option is carried through an SSH tunnel, whose remote endpoint
      // is a Unix-domain PostgreSQL socket. Avoid PgJDBC's optional SSL/GSS negotiation against
      // that local forwarding endpoint; Docker-backed fixture behavior remains unchanged.
      connectionProperties.setProperty("sslmode", "disable");
      connectionProperties.setProperty("gssEncMode", "disable");
    }
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

  static String[] postgresCommand(boolean requireDurablePrimary) {
    if (requireDurablePrimary) {
      return new String[] {"postgres", "-c", "fsync=on", "-c", "synchronous_commit=on"};
    }
    return new String[] {"postgres", "-c", "fsync=off"};
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

  static String validateLoopbackJdbcUrl(String jdbcUrl) {
    final URI uri;
    try {
      if (!jdbcUrl.startsWith("jdbc:")) {
        throw new IllegalArgumentException();
      }
      uri = URI.create(jdbcUrl.substring("jdbc:".length()));
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          JDBC_URL_ENV + " must use a credential-free loopback PostgreSQL URL without options");
    }
    String host = uri.getHost();
    boolean loopbackHost = "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
    if (!"postgresql".equals(uri.getScheme())
        || !loopbackHost
        || uri.getPort() < 1
        || uri.getPort() > 65_535
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || !"/postgres".equals(uri.getRawPath())) {
      throw new IllegalArgumentException(
          JDBC_URL_ENV + " must use a credential-free loopback PostgreSQL URL without options");
    }
    return jdbcUrl;
  }
}
