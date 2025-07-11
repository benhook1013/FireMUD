package net.firedevops.firemud;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.config.AuthConfig;
import org.junit.jupiter.api.Assumptions;
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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Cross-service integration test verifying Logging Admin Service starts alongside Social Groups
 * Service.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = SocialGroupsCrossServiceIntegrationTest.TestApp.class)
class SocialGroupsCrossServiceIntegrationTest {

  @Container
  static GenericContainer<?> loggingAdminService =
      new GenericContainer<>(
              DockerImageName.parse("ghcr.io/firedevops/logging-admin-service:latest"))
          .withExposedPorts(8080);

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void socialGroupsRunsAlongsideLoggingAdminService() {
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "Docker not available, skipping cross-service test");
    assertThat(loggingAdminService.isRunning()).isTrue();

    String body = restTemplate.getForObject("http://localhost:" + port + "/ping", String.class);
    assertThat(body).contains("pong");
  }

  @Configuration
  @EnableAutoConfiguration(
      exclude = {DataSourceAutoConfiguration.class, RedisAutoConfiguration.class})
  @Import({CommonAutoConfiguration.class, AuthConfig.class})
  static class TestApp {}
}
