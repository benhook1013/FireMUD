package net.firedevops.firemud.entitymanagement;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.test.HttpTestSupport;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Cross-service integration test verifying the service starts alongside the Game Session Service.
 */
@Testcontainers(disabledWithoutDocker = true)
@Disabled("integration environment not configured")
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = EntityManagementServiceApplication.class)
class EntityManagementCrossServiceIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> gameSessionService =
      new GenericContainer<>(
              DockerImageName.parse("ghcr.io/benhook1013/game-session-service:latest"))
          .withExposedPorts(8080);

  @LocalServerPort private int port;

  @Test
  void entityManagementRunsAlongsideGameSessionService() {
    assertThat(postgres.isRunning()).isTrue();
    assertThat(gameSessionService.isRunning()).isTrue();

    String body = HttpTestSupport.getBodyUnchecked("http://localhost:" + port + "/ping");
    assertThat(body).contains("pong");
  }
}
