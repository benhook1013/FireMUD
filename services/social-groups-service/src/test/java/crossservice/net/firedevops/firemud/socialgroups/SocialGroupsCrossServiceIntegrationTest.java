package net.firedevops.firemud.socialgroups;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.exception.NotFoundException;
import net.firedevops.firemud.test.HttpTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Cross-service integration test verifying Logging Admin Service starts alongside Social Groups
 * Service.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = SocialGroupsServiceApplication.class)
class SocialGroupsCrossServiceIntegrationTest {

  static GenericContainer<?> loggingAdminService =
      new GenericContainer<>(
              DockerImageName.parse("ghcr.io/benhook1013/logging-admin-service:latest"))
          .withExposedPorts(8080);

  private static boolean isLoggingAdminImageAvailableLocally() {
    try {
      DockerClientFactory.instance()
          .client()
          .inspectImageCmd("ghcr.io/benhook1013/logging-admin-service:latest")
          .exec();
      return true;
    } catch (NotFoundException e) {
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  @BeforeAll
  static void startContainer() {
    if (!DockerClientFactory.instance().isDockerAvailable()) {
      Assumptions.assumeTrue(false, "Docker not available, skipping cross-service test");
    }
    if (!isLoggingAdminImageAvailableLocally()) {
      Assumptions.assumeTrue(
          false, "Logging Admin image not available locally, skipping cross-service test");
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

  @Test
  void socialGroupsRunsAlongsideLoggingAdminService() {
    assertThat(loggingAdminService.isRunning()).isTrue();

    String body = HttpTestSupport.getBodyUnchecked("http://localhost:" + port + "/ping");
    assertThat(body).contains("pong");
  }
}
