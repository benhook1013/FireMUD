package net.firedevops.firemud.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

class TlsReloadHealthTest {
  @Test
  void noWatcherIsHealthyForClientAndServer() {
    TestReloadingClient client = new TestReloadingClient();
    GrpcServerTlsReloader server = newServerReloader();

    assertThat(client.health().getStatus()).isEqualTo(Status.UP);
    assertThat(client.health().getDetails())
        .containsEntry("tlsReload", "disabled_or_not_configured");
    assertThat(server.health().getStatus()).isEqualTo(Status.UP);
    assertThat(server.health().getDetails())
        .containsEntry("tlsReload", "disabled_or_not_configured");
  }

  @Test
  void healthyWatcherIsReportedForClientAndServer(@TempDir Path directory) throws Exception {
    TlsCertificateWatcher watcher = watcher(directory, 3);
    try {
      TestReloadingClient client = new TestReloadingClient();
      GrpcServerTlsReloader server = newServerReloader();
      setWatcher(client, watcher);
      setWatcher(server, watcher);

      assertHealthy(client);
      assertHealthy(server);
      assertThat(client.health().getDetails())
          .containsEntry("watcherRunning", true)
          .containsEntry("allRequiredRegistrations", true);
      assertThat(server.health().getDetails())
          .containsEntry("watcherRunning", true)
          .containsEntry("allRequiredRegistrations", true);
    } finally {
      watcher.close();
    }
  }

  @Test
  void incompleteWatcherIsOutOfServiceForClientAndServer(@TempDir Path directory) throws Exception {
    TlsCertificateWatcher watcher = watcher(directory, 2);
    try {
      TestReloadingClient client = new TestReloadingClient();
      GrpcServerTlsReloader server = newServerReloader();
      setWatcher(client, watcher);
      setWatcher(server, watcher);

      Path retiredDirectory = directory.resolve("tls-0");
      Files.delete(retiredDirectory.resolve("tls.crt"));
      Files.delete(retiredDirectory);
      await(() -> !watcher.hasAllRequiredRegistrations());

      assertThat(watcher.isRunning()).isTrue();
      assertOutOfService(client, true);
      assertOutOfService(server, true);
    } finally {
      watcher.close();
    }
  }

  @Test
  void stoppedWatcherIsOutOfServiceForClientAndServer(@TempDir Path directory) throws Exception {
    TlsCertificateWatcher watcher = watcher(directory, 1);
    try {
      TestReloadingClient client = new TestReloadingClient();
      GrpcServerTlsReloader server = newServerReloader();
      setWatcher(client, watcher);
      setWatcher(server, watcher);

      Path watchedDirectory = directory.resolve("tls-0");
      Files.delete(watchedDirectory.resolve("tls.crt"));
      Files.delete(watchedDirectory);
      await(() -> !watcher.isRunning());

      assertOutOfService(client, false);
      assertOutOfService(server, false);
    } finally {
      watcher.close();
    }
  }

  private static TlsCertificateWatcher watcher(Path parent, int directoryCount) throws IOException {
    List<Path> files = new ArrayList<>();
    for (int index = 0; index < directoryCount; index++) {
      Path directory = Files.createDirectory(parent.resolve("tls-" + index));
      files.add(Files.writeString(directory.resolve("tls.crt"), "certificate"));
    }
    return TlsCertificateWatcher.createAndStart(files, () -> {});
  }

  private static void setWatcher(Object owner, TlsCertificateWatcher watcher)
      throws ReflectiveOperationException {
    Field field =
        owner instanceof AbstractReloadingBlockingGrpcClient
            ? AbstractReloadingBlockingGrpcClient.class.getDeclaredField("watcher")
            : owner.getClass().getDeclaredField("watcher");
    field.setAccessible(true);
    field.set(owner, watcher);
  }

  private static GrpcServerTlsReloader newServerReloader() {
    return new GrpcServerTlsReloader(Mockito.mock(GrpcServerLifecycle.class));
  }

  private static void assertHealthy(HealthIndicator indicator) {
    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    assertThat(indicator.health().getDetails()).containsEntry("tlsReload", "watching");
  }

  private static void assertOutOfService(HealthIndicator indicator, boolean running) {
    Health health = indicator.health();
    assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    assertThat(health.getDetails())
        .containsEntry("tlsReload", "watcher_unhealthy")
        .containsEntry("watcherRunning", running)
        .containsEntry("allRequiredRegistrations", false);
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10L);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private static final class TestReloadingClient
      extends AbstractReloadingBlockingGrpcClient<
          GameDesignServiceGrpc.GameDesignServiceBlockingStub> {
    private TestReloadingClient() {
      super(
          new ServiceEndpointsProperties(),
          new CommonGrpcClientProperties(),
          new GrpcChannelFactory(),
          TestReloadingClient.class);
    }

    @Override
    protected String configuredTarget(ServiceEndpointsProperties endpoints) {
      return null;
    }

    @Override
    protected String defaultTarget() {
      return "localhost:6565";
    }

    @Override
    protected GameDesignServiceGrpc.GameDesignServiceBlockingStub buildStub(
        ManagedChannel channel) {
      return GameDesignServiceGrpc.newBlockingStub(channel);
    }
  }
}
