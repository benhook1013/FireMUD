package unit.net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.accountservice.client.AccountOriginalNewCapacityExecutionReadClient;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionTestFixture;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.v1.OriginalNewCapacityExecutionReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadOriginalNewCapacityExecutionRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalNewCapacityExecutionResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountOriginalNewCapacityExecutionReadClientTest {
  @Test
  void readReachesItsTransportDeadlineWhileTlsReloadIsBlocked(@TempDir Path directory)
      throws Exception {
    var pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Capacity execution read test CA",
            new IdentitySpec("gs", CapacityAdmissionTestFixture.PEER, true),
            new IdentitySpec("account", "spiffe://firemud/ns/prod/sa/account-service", false));
    var tls = pki.clientProperties(directory, pki.identity("account"));
    var identity = pki.identity("gs");
    var readStarted = new CountDownLatch(1);
    var service =
        new OriginalNewCapacityExecutionReadServiceGrpc
            .OriginalNewCapacityExecutionReadServiceImplBase() {
          @Override
          public void readOriginalNewCapacityExecution(
              ReadOriginalNewCapacityExecutionRequest request,
              StreamObserver<ReadOriginalNewCapacityExecutionResponse> response) {
            readStarted.countDown();
          }
        };
    var server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(service)
            .build()
            .start();
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("127.0.0.1:" + server.getPort());
    var factory = new BlockingReloadChannelFactory();
    var request = CapacityAdmissionTestFixture.playerRequest();
    var reservation =
        new TenantCapacityAdmission.Result(
            request.identity(),
            request.admissionAttemptId(),
            request.capacityRearmFence(),
            request.mutationDigest(),
            UUID.randomUUID(),
            1,
            request.evidence().expiresAt(),
            TenantCapacityAdmission.State.RESERVED,
            request.evidence().snapshotIdentity(),
            request.evidence().evaluationIdentity());

    try (var client =
            new AccountOriginalNewCapacityExecutionReadClient(
                endpoints, tls, factory, request.targetNamespace(), Clock.systemUTC());
        var executor = Executors.newSingleThreadExecutor()) {
      client.init();
      try {
        Files.writeString(Path.of(tls.getCertChain()), "\n", StandardOpenOption.APPEND);
        assertThat(factory.reloadStarted.await(5, TimeUnit.SECONDS)).isTrue();
        var read =
            executor.submit(
                () ->
                    assertThatThrownBy(
                            () ->
                                client.readExact(
                                    request, reservation, Instant.now().plusSeconds(2)))
                        .isInstanceOfSatisfying(
                            StatusRuntimeException.class,
                            failure ->
                                assertThat(failure.getStatus().getCode())
                                    .isEqualTo(Status.Code.DEADLINE_EXCEEDED)));
        assertThat(readStarted.await(3, TimeUnit.SECONDS)).isTrue();
        read.get(4, TimeUnit.SECONDS);
      } finally {
        factory.releaseReload.countDown();
      }
    } finally {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static final class BlockingReloadChannelFactory extends GrpcChannelFactory {
    private final AtomicBoolean initialBuild = new AtomicBoolean(true);
    private final CountDownLatch reloadStarted = new CountDownLatch(1);
    private final CountDownLatch releaseReload = new CountDownLatch(1);

    @Override
    public ManagedChannel buildChannel(
        String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive)
        throws SSLException {
      if (!initialBuild.getAndSet(false)) {
        reloadStarted.countDown();
        try {
          if (!releaseReload.await(10, TimeUnit.SECONDS)) {
            throw new SSLException("Test reload release timed out");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new SSLException("Test reload wait interrupted", interrupted);
        }
      }
      return super.buildChannel(target, defaultPort, properties, keepAlive);
    }
  }
}
