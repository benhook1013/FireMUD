package net.firedevops.firemud.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
  void certificateChangeDuringInitialChannelBuildTriggersReload(@TempDir Path directory)
      throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    Path privateKey = Files.writeString(directory.resolve("tls.key"), "private-key-1");
    Path caCertificate = Files.writeString(directory.resolve("ca.crt"), "ca-certificate-1");
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setCertChain(certificate.toString());
    grpc.setPrivateKey(privateKey.toString());
    grpc.setCaCert(caCertificate.toString());
    CertificateChangingChannelFactory factory = new CertificateChangingChannelFactory(certificate);
    TestClient client = new TestClient(new ServiceEndpointsProperties(), grpc, factory);
    try {
      client.init();

      awaitCondition(() -> factory.buildAttempts.get() >= 2);

      assertThat(factory.observedCertificates).hasSizeGreaterThanOrEqualTo(2);
      assertThat(factory.observedCertificates.get(0)).isEqualTo("certificate-1");
      assertThat(factory.observedCertificates.get(1)).isEqualTo("certificate-2");
    } finally {
      client.close();
    }
  }

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
      assertThat(((Number) health.getDetails().get("unhealthyWatchers")).intValue())
          .isGreaterThanOrEqualTo(unhealthyWatchersBefore + 1);
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

  private static final class CertificateChangingChannelFactory extends GrpcChannelFactory {
    private final Path certificate;
    private final AtomicInteger buildAttempts = new AtomicInteger();
    private final List<String> observedCertificates = new CopyOnWriteArrayList<>();

    private CertificateChangingChannelFactory(Path certificate) {
      this.certificate = certificate;
    }

    @Override
    public ManagedChannel buildChannel(
        String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive)
        throws SSLException {
      int attempt = buildAttempts.incrementAndGet();
      try {
        observedCertificates.add(Files.readString(certificate));
        if (attempt == 1) {
          Files.writeString(certificate, "certificate-2");
        }
      } catch (IOException e) {
        throw new SSLException("simulated initial certificate change", e);
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
