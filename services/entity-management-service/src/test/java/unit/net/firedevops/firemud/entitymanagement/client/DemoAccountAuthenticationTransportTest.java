package unit.net.firedevops.firemud.entitymanagement.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.ResolvedGrpcTlsMaterial;
import net.firedevops.firemud.entitymanagement.client.DemoAccountAuthenticationClient;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Physical deadline/retry proof with a controlled Account stub, not real credential-owner proof.
 */
class DemoAccountAuthenticationTransportTest {
  private static final String NAMESPACE = "test";
  private static final String ENTITY_URI = "spiffe://firemud/ns/test/sa/entity-management-service";
  private static final UUID OWNER = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final AuthenticateRequest REQUEST =
      AuthenticateRequest.newBuilder()
          .setEmail("demo@example.com")
          .setPassword("controlled-fixture-password")
          .build();

  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void createLoopbackCertificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "FireMUD demo authentication transport test CA",
            new IdentitySpec("account", "spiffe://firemud/ns/test/sa/account-service", true),
            new IdentitySpec("entity", ENTITY_URI, false));
  }

  @Test
  @Timeout(20)
  void realDeadlineCancelsFirstCallAndRetryReturnsOnlyTheExactAccountOwner() throws Exception {
    var requests = new CopyOnWriteArrayList<AuthenticateRequest>();
    var peers = new CopyOnWriteArrayList<String>();
    var firstCancelled = new CountDownLatch(1);
    var service =
        new AccountServiceGrpc.AccountServiceImplBase() {
          @Override
          public void authenticate(
              AuthenticateRequest request, StreamObserver<AuthenticateResponse> observer) {
            GrpcPeerIdentity peer = GrpcPeerIdentity.current();
            peers.add(peer == null ? "missing" : peer.uri());
            requests.add(request);
            if (!ENTITY_URI.equals(peers.getLast()) || !REQUEST.equals(request)) {
              observer.onError(io.grpc.Status.PERMISSION_DENIED.asRuntimeException());
              return;
            }
            if (requests.size() == 1) {
              // Leave the RPC open without blocking a server thread. The real client deadline
              // must cancel it before the client can retry with a fresh five-second deadline.
              ((ServerCallStreamObserver<AuthenticateResponse>) observer)
                  .setOnCancelHandler(firstCancelled::countDown);
              return;
            }
            observer.onNext(
                AuthenticateResponse.newBuilder()
                    .setAccountId(OWNER.toString())
                    .setAuthToken("controlled-fixture-token")
                    .build());
            observer.onCompleted();
          }
        };
    var identity = pki.identity("account");
    try (var server =
            new TransportServer(
                NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                    .sslContext(
                        GrpcSslContexts.configure(
                                SslContextBuilder.forServer(
                                    identity.privateKey(), identity.certificate()))
                            .trustManager(pki.caCertificate())
                            .clientAuth(ClientAuth.REQUIRE)
                            .build())
                    .addService(
                        ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
                    .build()
                    .start());
        var channels = new CapturingChannelFactory()) {
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setAccountService("127.0.0.1:" + server.server().getPort());
      try (var client =
          new DemoAccountAuthenticationClient(
              endpoints,
              pki.clientProperties(directory, pki.identity("entity")),
              channels,
              NAMESPACE,
              REQUEST.getEmail(),
              REQUEST.getPassword())) {
        client.init();

        assertThat(client.authenticateDemoOwner()).isEqualTo(OWNER);

        assertThat(firstCancelled.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(requests).hasSize(2);
        assertThat(peers).containsExactly(ENTITY_URI, ENTITY_URI);
        assertThat(requests)
            .allSatisfy(
                request -> assertThat(request.toByteArray()).isEqualTo(REQUEST.toByteArray()));
      }
    }
  }

  private record TransportServer(Server server) implements AutoCloseable {
    @Override
    public void close() throws InterruptedException {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  /** Records channels produced by the real factory so teardown also checks physical termination. */
  private static final class CapturingChannelFactory extends GrpcChannelFactory
      implements AutoCloseable {
    private final List<ManagedChannel> channels = new CopyOnWriteArrayList<>();

    @Override
    public ManagedChannel buildChannel(
        String target,
        int defaultPort,
        CommonGrpcClientProperties properties,
        boolean keepAlive,
        ResolvedGrpcTlsMaterial tlsMaterial)
        throws SSLException {
      ManagedChannel channel =
          super.buildChannel(target, defaultPort, properties, keepAlive, tlsMaterial);
      channels.add(channel);
      return channel;
    }

    @Override
    public void close() throws InterruptedException {
      channels.forEach(ManagedChannel::shutdownNow);
      for (ManagedChannel channel : channels) {
        assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      }
    }
  }
}
