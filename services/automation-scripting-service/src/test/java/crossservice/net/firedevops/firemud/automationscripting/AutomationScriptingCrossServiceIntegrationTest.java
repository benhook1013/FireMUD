package net.firedevops.firemud.automationscripting;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import org.junit.jupiter.api.Disabled;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Cross-service integration test verifying Game Session Service starts alongside Automation
 * Scripting Service.
 */
@Testcontainers
@Disabled("integration environment not configured")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = AutomationScriptingCrossServiceIntegrationTest.TestApp.class)
class AutomationScriptingCrossServiceIntegrationTest {

  @Container
  static GenericContainer<?> gameSessionService =
      new GenericContainer<>(
              DockerImageName.parse("ghcr.io/benhook1013/game-session-service:latest"))
          .withExposedPorts(8080);

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void automationScriptingRunsAlongsideGameSessionService() {
    assertThat(gameSessionService.isRunning()).isTrue();

    String body = restTemplate.getForObject("http://localhost:" + port + "/ping", String.class);
    assertThat(body).contains("pong");
  }

  @Configuration
  @EnableAutoConfiguration(
      exclude = {DataSourceAutoConfiguration.class, RedisAutoConfiguration.class})
  @Import(CommonAutoConfiguration.class)
  static class TestApp {}
}
