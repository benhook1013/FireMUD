package net.firedevops.firemud.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.grpc.ManagedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Health;

class AbstractReloadingBlockingGrpcClientTest {
  @Test
  void failedCertificateReloadMakesWatcherReadinessUnavailable(@TempDir Path directory)
      throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    Path privateKey = Files.writeString(directory.resolve("tls.key"), "private-key-1");
    Path caCertificate = Files.writeString(directory.resolve("ca.crt"), "ca-certificate-1");
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setCertChain(certificate.toString());
    grpc.setPrivateKey(privateKey.toString());
    grpc.setCaCert(caCertificate.toString());
    int unhealthyWatchersBefore =
        ((Number) TlsCertificateWatcher.health().getDetails().get("unhealthyWatchers")).intValue();

    TestClient client =
        new TestClient(new ServiceEndpointsProperties(), grpc, new FailingReloadChannelFactory());
    try {
      client.init();
      Files.writeString(certificate, "certificate-2");

      awaitCondition(
          () -> {
            Health health = TlsCertificateWatcher.health();
            return "OUT_OF_SERVICE".equals(health.getStatus().getCode())
                && ((Number) health.getDetails().get("unhealthyWatchers")).intValue()
                    >= unhealthyWatchersBefore + 1;
          });

      Health health = TlsCertificateWatcher.health();
      assertThat(health.getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
      assertThat(health.getDetails())
          .containsEntry("unhealthyWatchers", unhealthyWatchersBefore + 1);
    } finally {
      client.close();
    }
  }

  private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private static final class FailingReloadChannelFactory extends GrpcChannelFactory {
    private final AtomicInteger buildAttempts = new AtomicInteger();

    @Override
    public ManagedChannel buildChannel(
        String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive)
        throws SSLException {
      if (buildAttempts.incrementAndGet() > 1) {
        throw new SSLException("simulated certificate reload failure");
      }
      return mock(ManagedChannel.class);
    }
  }

  private static final class TestClient
      extends AbstractReloadingBlockingGrpcClient<
          GameDesignServiceGrpc.GameDesignServiceBlockingStub> {
    private TestClient(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties grpc,
        GrpcChannelFactory channelFactory) {
      super(endpoints, grpc, channelFactory, TestClient.class);
    }

    private void init() throws Exception {
      initReloadingClient();
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
      return mock(GameDesignServiceGrpc.GameDesignServiceBlockingStub.class);
    }
  }
}
