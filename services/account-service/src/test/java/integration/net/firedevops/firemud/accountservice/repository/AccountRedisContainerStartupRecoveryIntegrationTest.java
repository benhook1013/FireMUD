package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.api.exception.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;

class AccountRedisContainerStartupRecoveryIntegrationTest {
  @Test
  void startupRecreatesFailedContainerBeforeFixtureUseAndRemovesBothContainers() throws Exception {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable());
    var container = new FailingStartupContainer(1);
    try (container) {
      container.start();
      assertThat(container.createdIds).hasSize(2).doesNotHaveDuplicates();
      assertRemoved(container.createdIds.getFirst());
      assertThat(container.execInContainer("redis-cli", "PING").getStdout()).contains("PONG");
      assertThat(container.getContainerId()).isEqualTo(container.createdIds.getLast());
    }
    container.createdIds.forEach(
        AccountRedisContainerStartupRecoveryIntegrationTest::assertRemoved);
  }

  @Test
  void persistentStartupFailureStopsAfterTwoAttemptsAndRemovesEveryContainer() {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable());
    var container = new FailingStartupContainer(2);
    try (container) {
      assertThatThrownBy(container::start)
          .isInstanceOf(ContainerLaunchException.class)
          .hasStackTraceContaining("injected mapped-port startup refusal");
      assertThat(container.createdIds).hasSize(2).doesNotHaveDuplicates();
      assertThat(container.getContainerId()).isNull();
    }
    container.createdIds.forEach(
        AccountRedisContainerStartupRecoveryIntegrationTest::assertRemoved);
  }

  private static void assertRemoved(String id) {
    assertThatThrownBy(() -> DockerClientFactory.instance().client().inspectContainerCmd(id).exec())
        .isInstanceOf(NotFoundException.class);
  }

  private static final class FailingStartupContainer
      extends GenericContainer<FailingStartupContainer> {
    private final List<String> createdIds = new ArrayList<>();
    private final int failures;

    private FailingStartupContainer(int failures) {
      super(TestContainerImages.redis());
      this.failures = failures;
      withExposedPorts(6379);
      withStartupAttempts(
          AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.PhysicalRedisFixture
              .STARTUP_ATTEMPTS);
    }

    // A test-owned running container is an identity, not a value of its mutable attempt history.
    @Override
    public boolean equals(Object other) {
      return this == other;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(this);
    }

    @Override
    protected void waitUntilContainerStarted() {
      super.waitUntilContainerStarted();
      createdIds.add(getContainerId());
      if (createdIds.size() <= failures) {
        throw new ContainerLaunchException("injected mapped-port startup refusal");
      }
    }
  }
}
