package net.firedevops.firemud.accountservice;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.test.HttpTestSupport;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Cross-service test ensuring Account Service starts with Logging Admin Service. */
@Testcontainers(disabledWithoutDocker = true)
@Disabled("integration environment not configured")
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = AccountServiceApplication.class)
class AccountCrossServiceIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @Container
  static GenericContainer<?> loggingAdminService =
      new GenericContainer<>(
              DockerImageName.parse("ghcr.io/benhook1013/logging-admin-service:latest"))
          .withExposedPorts(8080);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("firemud.postgres.host", postgres::getHost);
    registry.add("firemud.postgres.port", () -> postgres.getMappedPort(5432));
    registry.add("firemud.postgres.database", () -> postgres.getDatabaseName());
    registry.add("firemud.postgres.username", postgres::getUsername);
    registry.add("firemud.postgres.password", postgres::getPassword);
    registry.add("firemud.redis.host", redis::getHost);
    registry.add("firemud.redis.port", () -> redis.getMappedPort(6379));
  }

  @LocalServerPort private int port;

  @Test
  void accountServiceRunsAlongsideLoggingAdminService() {
    assertThat(postgres.isRunning()).isTrue();
    assertThat(redis.isRunning()).isTrue();
    assertThat(loggingAdminService.isRunning()).isTrue();

    String body = HttpTestSupport.getBodyUnchecked("http://localhost:" + port + "/ping");
    assertThat(body).contains("pong");
  }
}
