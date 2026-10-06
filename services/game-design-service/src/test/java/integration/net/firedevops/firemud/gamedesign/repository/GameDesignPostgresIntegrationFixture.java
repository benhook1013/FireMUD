package integration.net.firedevops.firemud.gamedesign.repository;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.util.Properties;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Test-only PostgreSQL fixture with an explicit loopback-only external database option. */
final class GameDesignPostgresIntegrationFixture {
  static final String JDBC_URL_ENV = "FIREMUD_GAME_DESIGN_TEST_POSTGRES_JDBC_URL";
  static final String USERNAME_ENV = "FIREMUD_GAME_DESIGN_TEST_POSTGRES_USERNAME";
  static final String PASSWORD_ENV = "FIREMUD_GAME_DESIGN_TEST_POSTGRES_PASSWORD";

  private final PostgreSQLContainer<?> container;
  private final String externalJdbcUrl;
  private final String externalUsername;
  private final String externalPassword;
  private boolean started;

  GameDesignPostgresIntegrationFixture() {
    String configuredUrl = System.getenv(JDBC_URL_ENV);
    if (configuredUrl != null && !configuredUrl.isBlank()) {
      externalJdbcUrl = validateLoopbackJdbcUrl(configuredUrl);
      externalUsername = requiredEnvironmentValue(USERNAME_ENV);
      externalPassword = System.getenv().getOrDefault(PASSWORD_ENV, "");
      container = null;
    } else {
      if (System.getenv().containsKey(USERNAME_ENV) || System.getenv().containsKey(PASSWORD_ENV)) {
        throw new IllegalStateException(
            USERNAME_ENV + " and " + PASSWORD_ENV + " require " + JDBC_URL_ENV);
      }
      externalJdbcUrl = null;
      externalUsername = null;
      externalPassword = null;
      container = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    }
  }

  void start() {
    if (container == null) {
      return;
    }
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker is unavailable; set " + JDBC_URL_ENV + " to an isolated loopback PostgreSQL URI");
    container.start();
    started = true;
  }

  void stop() {
    if (started) {
      container.stop();
      started = false;
    }
  }

  DriverManagerDataSource dataSource() {
    String url;
    String username;
    String password;
    if (container == null) {
      url = externalJdbcUrl;
      username = externalUsername;
      password = externalPassword;
    } else {
      if (!started) {
        throw new IllegalStateException("The PostgreSQL test fixture has not started");
      }
      url = container.getJdbcUrl();
      username = container.getUsername();
      password = container.getPassword();
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

  private static String validateLoopbackJdbcUrl(String jdbcUrl) {
    if (!jdbcUrl.startsWith("jdbc:")) {
      throw new IllegalArgumentException("External PostgreSQL URL must use the JDBC scheme");
    }
    URI uri;
    try {
      uri = URI.create(jdbcUrl.substring("jdbc:".length()));
    } catch (IllegalArgumentException ignored) {
      throw new IllegalArgumentException("External PostgreSQL URL is malformed");
    }
    String host = uri.getHost();
    boolean loopbackHost = "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
    if (!"postgresql".equals(uri.getScheme())
        || !loopbackHost
        || uri.getPort() < 1
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || uri.getRawPath() == null
        || !uri.getRawPath().matches("/[A-Za-z0-9_-]+")) {
      throw new IllegalArgumentException(
          JDBC_URL_ENV
              + " must be a credential-free loopback PostgreSQL URL without query options");
    }
    return jdbcUrl;
  }

  private static String requiredEnvironmentValue(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required when " + JDBC_URL_ENV + " is set");
    }
    return value;
  }
}
