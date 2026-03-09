package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Cross-service integration test verifying the service starts alongside the Game Logic Service. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = GameSessionCrossServiceIntegrationTest.TestApp.class,
    properties = "game-session.require-authenticated-commands=false")
class GameSessionCrossServiceIntegrationTest {

  private static GenericContainer<?> gameLogicService =
      new GenericContainer<>(DockerImageName.parse("ghcr.io/benhook1013/game-logic-service:latest"))
          .withExposedPorts(8080);

  private static boolean containerStarted;

  @BeforeAll
  static void startContainer() {
    if (DockerClientFactory.instance().isDockerAvailable()) {
      try {
        gameLogicService.start();
        containerStarted = true;
      } catch (Exception e) {
        containerStarted = false;
      }
    }
  }

  @AfterAll
  static void stopContainer() {
    if (containerStarted) {
      gameLogicService.stop();
    }
  }

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @MockitoBean private RedisTemplate<String, Object> redisTemplate;

  @Test
  void gameSessionRunsAlongsideGameLogicService() {
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable() && containerStarted,
        "Required container not available, skipping cross-service test");
    assertThat(gameLogicService.isRunning()).isTrue();

    String body = restTemplate.getForObject("http://localhost:" + port + "/ping", String.class);
    assertThat(body).contains("pong");
  }

  @Configuration
  @EnableAutoConfiguration(
      exclude = {DataSourceAutoConfiguration.class, RedisAutoConfiguration.class})
  @Import({CommonAutoConfiguration.class})
  static class TestApp {}
}
