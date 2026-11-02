package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.DiscoveryRequest;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.VisiblePublicProductionTargets;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionDecisionReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ListVisiblePublicProductionAdmissionTargetsRequest;
import net.firedevops.firemud.gamesession.v1.ListVisiblePublicProductionAdmissionTargetsResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Physical loopback mTLS proof for the Account public-target source client only. */
class CanonicalGameplayAdmissionDecisionReadClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";

  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void createLoopbackWorkloadCertificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Canonical public-target client test CA",
            new IdentitySpec("account", ACCOUNT_URI, false),
            new IdentitySpec(
                "game-session", "spiffe://firemud/ns/test/sa/game-session-service", true),
            new IdentitySpec(
                "wrong-service-server",
                "spiffe://firemud/ns/test/sa/world-management-service",
                true),
            new IdentitySpec(
                "wrong-namespace-server",
                "spiffe://firemud/ns/other/sa/game-session-service",
                true));
  }

  @AfterEach
  void clearAmbientSecurityAndTransactionContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactSameNamespaceGameSessionPeerAcceptsAccountIdentityAndCompleteEmptyCollection()
      throws Exception {
    var service = new DiscoveryProbeService();
    try (var server = startServer("game-session", service);
        var client = newClient(server.port(), "account")) {
      client.init();
      UUID readRequestId = UUID.randomUUID();

      VisiblePublicProductionTargets result =
          client.listVisiblePublicProductionAdmissionTargets(readRequestId);

      assertThat(result.request()).isEqualTo(new DiscoveryRequest(readRequestId, NAMESPACE));
      assertThat(result.targets()).isEmpty();
      assertThat(service.calls()).hasValue(1);
      assertThat(service.observedPeer())
          .hasValue(new GrpcPeerIdentity(ACCOUNT_URI, NAMESPACE, "account-service"));
      assertThat(service.observedRequest())
          .hasValue(new DiscoveryRequest(readRequestId, NAMESPACE));
      assertThat(service.observedDeadline()).isNotNull();
      long deadlineNanos = service.observedDeadline().timeRemaining(TimeUnit.NANOSECONDS);
      assertThat(deadlineNanos).isPositive().isLessThanOrEqualTo(TimeUnit.SECONDS.toNanos(5));
    }
  }

  @Test
  void wrongServerServiceOrNamespaceIsRejectedBeforeTheApplicationHandler() throws Exception {
    for (String serverIdentity : List.of("wrong-service-server", "wrong-namespace-server")) {
      var service = new DiscoveryProbeService();
      try (var server = startServer(serverIdentity, service);
          var client = newClient(server.port(), "account")) {
        client.init();

        assertThatThrownBy(
                () -> client.listVisiblePublicProductionAdmissionTargets(UUID.randomUUID()))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(service.calls()).hasValue(0);
      }
    }
  }

  @Test
  void staleCorrelationAndMalformedCompleteResponseRemainRefusals() throws Exception {
    var service = new DiscoveryProbeService();
    try (var server = startServer("game-session", service);
        var client = newClient(server.port(), "account")) {
      client.init();
      service.respondWith(
          request ->
              CanonicalGameplayAdmissionDecisionReadGrpcCodec.toDiscoveryResponse(
                  new DiscoveryRequest(UUID.randomUUID(), request.targetNamespace()), List.of()));

      assertThatThrownBy(
              () -> client.listVisiblePublicProductionAdmissionTargets(UUID.randomUUID()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid canonical discovery evidence");

      service.respondWith(
          ignored -> ListVisiblePublicProductionAdmissionTargetsResponse.getDefaultInstance());
      assertThatThrownBy(
              () -> client.listVisiblePublicProductionAdmissionTargets(UUID.randomUUID()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid canonical discovery evidence");
      assertThat(service.calls()).hasValue(2);
    }
  }

  private static LoopbackServer startServer(String identityAlias, DiscoveryProbeService service)
      throws Exception {
    var identity = pki.identity(identityAlias);
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    return new LoopbackServer(server);
  }

  private static CanonicalGameplayAdmissionDecisionReadClient newClient(
      int port, String identityAlias) throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("127.0.0.1:" + port);
    CommonGrpcClientProperties tls = pki.clientProperties(directory, pki.identity(identityAlias));
    return new CanonicalGameplayAdmissionDecisionReadClient(
        endpoints, tls, new GrpcChannelFactory(), NAMESPACE);
  }

  private record LoopbackServer(Server server) implements AutoCloseable {
    private int port() {
      return server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
      server.shutdownNow();
      if (!server.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback public-target server did not terminate");
      }
    }
  }

  private static final class DiscoveryProbeService
      extends CanonicalGameplayAdmissionDecisionReadServiceGrpc
          .CanonicalGameplayAdmissionDecisionReadServiceImplBase {
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<GrpcPeerIdentity> observedPeer = new AtomicReference<>();
    private final AtomicReference<DiscoveryRequest> observedRequest = new AtomicReference<>();
    private final AtomicReference<Deadline> observedDeadline = new AtomicReference<>();
    private final AtomicReference<
            Function<DiscoveryRequest, ListVisiblePublicProductionAdmissionTargetsResponse>>
        responseFactory =
            new AtomicReference<>(
                request ->
                    CanonicalGameplayAdmissionDecisionReadGrpcCodec.toDiscoveryResponse(
                        request, List.of()));

    @Override
    public void listVisiblePublicProductionAdmissionTargets(
        ListVisiblePublicProductionAdmissionTargetsRequest request,
        StreamObserver<ListVisiblePublicProductionAdmissionTargetsResponse> observer) {
      calls.incrementAndGet();
      observedPeer.set(GrpcPeerIdentity.current());
      observedDeadline.set(Context.current().getDeadline());
      try {
        DiscoveryRequest parsed =
            CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseDiscoveryRequest(request);
        observedRequest.set(parsed);
        observer.onNext(responseFactory.get().apply(parsed));
        observer.onCompleted();
      } catch (RuntimeException failure) {
        observer.onError(Status.INTERNAL.withCause(failure).asRuntimeException());
      }
    }

    private AtomicInteger calls() {
      return calls;
    }

    private AtomicReference<GrpcPeerIdentity> observedPeer() {
      return observedPeer;
    }

    private AtomicReference<DiscoveryRequest> observedRequest() {
      return observedRequest;
    }

    private Deadline observedDeadline() {
      return observedDeadline.get();
    }

    private void respondWith(
        Function<DiscoveryRequest, ListVisiblePublicProductionAdmissionTargetsResponse> factory) {
      responseFactory.set(factory);
    }
  }
}
