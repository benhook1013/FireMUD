package net.firedevops.firemud.socialgroups;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.socialgroups.config.AuthConfig;
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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
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

  static GenericContainer<?> loggingAdminService =
      new GenericContainer<>(
              DockerImageName.parse("ghcr.io/benhook1013/logging-admin-service:latest"))
          .withExposedPorts(8080);

  @BeforeAll
  static void startContainer() {
    if (!DockerClientFactory.instance().isDockerAvailable()) {
      Assumptions.assumeTrue(false, "Docker not available, skipping cross-service test");
    }
    try {
      loggingAdminService.start();
    } catch (Exception e) {
      Assumptions.assumeTrue(false, "Unable to start Logging Admin container: " + e.getMessage());
    }
  }

  @AfterAll
  static void stopContainer() {
    if (loggingAdminService.isRunning()) {
      loggingAdminService.stop();
    }
  }

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void socialGroupsRunsAlongsideLoggingAdminService() {
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
