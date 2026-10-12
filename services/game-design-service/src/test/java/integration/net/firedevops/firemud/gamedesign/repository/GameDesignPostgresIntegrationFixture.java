package integration.net.firedevops.firemud.gamedesign.repository;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Properties;
import net.firedevops.firemud.test.TestContainerImages;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/** Test-only PostgreSQL fixture backed by a disposable Testcontainers database. */
final class GameDesignPostgresIntegrationFixture {
  private final PostgreSQLContainer<?> container;
  private boolean started;

  GameDesignPostgresIntegrationFixture() {
    container = new PostgreSQLContainer<>(TestContainerImages.postgres());
  }

  void start() {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker is unavailable; the PostgreSQL test fixture requires Testcontainers");
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
    if (!started) {
      throw new IllegalStateException("The PostgreSQL test fixture has not started");
    }
    String url = container.getJdbcUrl();
    String username = container.getUsername();
    String password = container.getPassword();

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
}
