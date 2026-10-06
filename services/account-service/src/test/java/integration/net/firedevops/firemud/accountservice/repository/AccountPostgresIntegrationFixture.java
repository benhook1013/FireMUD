package integration.net.firedevops.firemud.accountservice.repository;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.util.Properties;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/** Test-only PostgreSQL fixture with a strict loopback external-database option. */
public final class AccountPostgresIntegrationFixture {
  public static final String JDBC_URL_ENV = "FIREMUD_ACCOUNT_SIGNER_TEST_POSTGRES_URL";

  private final PostgreSQLContainer<?> container;
  private final String externalJdbcUrl;
  private boolean started;

  public AccountPostgresIntegrationFixture() {
    String configuredUrl = System.getenv(JDBC_URL_ENV);
    if (configuredUrl != null && !configuredUrl.isBlank()) {
      externalJdbcUrl = validateLoopbackJdbcUrl(configuredUrl);
      container = null;
    } else {
      externalJdbcUrl = null;
      container = new PostgreSQLContainer<>("postgres:16-alpine");
    }
  }

  public void start() {
    if (container == null) {
      return;
    }
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker is unavailable; set " + JDBC_URL_ENV + " to an isolated loopback PostgreSQL URL");
    container.start();
    started = true;
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
