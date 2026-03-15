package net.firedevops.firemud.worldmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Cross-service integration test verifying the service starts alongside the Game Design Service.
 */
@Testcontainers(disabledWithoutDocker = true)
@Disabled("integration environment not configured")
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = WorldManagementServiceApplication.class)
class WorldManagementCrossServiceIntegrationTest {

  @Container
  static GenericContainer<?> gameDesignService =
      new GenericContainer<>(
              DockerImageName.parse("ghcr.io/benhook1013/game-design-service:latest"))
          .withExposedPorts(8080);

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void worldManagementRunsAlongsideGameDesignService() {
    assertThat(gameDesignService.isRunning()).isTrue();

    String body = restTemplate.getForObject("http://localhost:" + port + "/ping", String.class);
    assertThat(body).contains("pong");
  }
}
