package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.v1.AccountStartSessionActivationProtectionSettlementServiceGrpc;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionActivationProtectionRequest;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionActivationProtectionResponse;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionActivationProtectionRepository.StoredSettlement;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionActivationProtectionSettlementGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionActivationProtectionSettlementService;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionSettlementGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionSettlementInput;
import net.firedevops.firemud.common.account.startsession.GrpcAccountStartSessionActivationProtectionSettlementClient;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Physical loopback mTLS transport-only proof for the unregistered Account settlement receiver. The
 * mocked Account owner proves no Account authority, SQL, or World activation.
 */
class AccountStartSessionActivationProtectionSettlementGrpcMtlsTest {
  private static final String NAMESPACE = "world-runtime";
  private static final UUID PROTECTION_ID = UUID.fromString("6b763f1d-c5bc-4080-b499-d29debc0a7b8");
  private static final long PROTECTION_FENCE = 23L;
  private static final UUID SETTLEMENT_REQUEST_ID =
      UUID.fromString("70fa5422-85c8-4704-b516-229e130fedcc");

  @TempDir static Path temporaryDirectory;

  private static AccountStartSessionActivationProtectionAcquisitionGrpcMtlsTest.TestPki pki;

  @BeforeAll
  static void generateTrustedWorkloadCertificates() throws Exception {
    pki =
        AccountStartSessionActivationProtectionAcquisitionGrpcMtlsTest.TestPki.create(
            temporaryDirectory);
  }

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactSameNamespaceWorldPeerSettlesAndReturnsExactCorrelationAndTerminalBytes()
      throws Exception {
    var fixture = AccountStartSessionActivationProtectionAcquisitionGrpcMtlsTest.Fixtures.create();
    Instant settledAt = Instant.parse("2026-10-11T01:02:03.000004Z");
    var stored = new StoredSettlement(fixture.terminal(), settledAt);
    var owner = mock(AccountStartSessionActivationProtectionSettlementService.class);
    when(owner.settle(PROTECTION_ID, PROTECTION_FENCE)).thenReturn(stored);
    try (TransportServer server = startServer(owner, false);
        var client = newClient(server.port(), pki.worldClient())) {
      client.init();
      var input =
          new AccountStartSessionActivationProtectionSettlementInput(
              NAMESPACE, SETTLEMENT_REQUEST_ID, PROTECTION_ID, PROTECTION_FENCE);

      var actual = client.settle(input);

      assertThat(actual.settlementRequestId()).isEqualTo(SETTLEMENT_REQUEST_ID);
      assertThat(actual.accountProtectionId()).isEqualTo(PROTECTION_ID);
      assertThat(actual.accountProtectionFence()).isEqualTo(PROTECTION_FENCE);
      assertThat(actual.settledAt()).isEqualTo(settledAt);
      assertThat(actual.terminal().canonicalBytes())
          .containsExactly(fixture.terminal().canonicalBytes());
      assertThat(server.handlerCalls()).hasValue(1);
      verify(owner).settle(PROTECTION_ID, PROTECTION_FENCE);
    }
  }

  @Test
  void trustedWrongServiceAndNamespacePeersAreDeniedBeforeOwnerInteraction() throws Exception {
    var input =
        new AccountStartSessionActivationProtectionSettlementInput(
            NAMESPACE, SETTLEMENT_REQUEST_ID, PROTECTION_ID, PROTECTION_FENCE);
    for (var identity : List.of(pki.wrongServiceClient(), pki.otherNamespaceClient())) {
      var owner = mock(AccountStartSessionActivationProtectionSettlementService.class);
      try (TransportServer server = startServer(owner, false);
          var client = newClient(server.port(), identity)) {
        client.init();

        assertThatThrownBy(() -> client.settle(input))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .as(identity.alias())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));

        assertThat(server.handlerCalls()).as(identity.alias()).hasValue(1);
        verifyNoInteractions(owner);
      }
    }
  }

  @Test
  void missingClientCertificateIsRejectedByTlsBeforeHandlerOrOwner() throws Exception {
    var owner = mock(AccountStartSessionActivationProtectionSettlementService.class);
    try (TransportServer server = startServer(owner, false);
        ClientTransport client = newClientWithoutIdentity(server.port())) {
      var input =
          new AccountStartSessionActivationProtectionSettlementInput(
              NAMESPACE, SETTLEMENT_REQUEST_ID, PROTECTION_ID, PROTECTION_FENCE);
      var request = AccountStartSessionActivationProtectionSettlementGrpcCodec.toRequest(input);

      assertThatThrownBy(() -> client.settle(request))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(TlsTestSupport.isTlsHandshakeRejection(failure))
                      .as(
                          "missing client certificate must fail during TLS negotiation: %s",
                          failure)
                      .isTrue());

      assertThat(server.handlerCalls()).hasValue(0);
      verifyNoInteractions(owner);
    }
  }

  @Test
  void authenticatedEndUserContextIsDeniedAtHandlerBeforeOwnerInteraction() throws Exception {
    var owner = mock(AccountStartSessionActivationProtectionSettlementService.class);
    try (TransportServer server = startServer(owner, true);
        var client = newClient(server.port(), pki.worldClient())) {
      client.init();
      var input =
          new AccountStartSessionActivationProtectionSettlementInput(
              NAMESPACE, SETTLEMENT_REQUEST_ID, PROTECTION_ID, PROTECTION_FENCE);

      assertThatThrownBy(() -> client.settle(input))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));

      assertThat(server.handlerCalls()).hasValue(1);
      verifyNoInteractions(owner);
    }
  }

  private static TransportServer startServer(
      AccountStartSessionActivationProtectionSettlementService owner, boolean injectEndUserContext)
      throws Exception {
    AtomicInteger handlerCalls = new AtomicInteger();
    var service =
        new AccountStartSessionActivationProtectionSettlementGrpcService(owner, NAMESPACE);
    ServerInterceptor countHandlerCalls =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            handlerCalls.incrementAndGet();
            return next.startCall(call, metadata);
          }
        };
    ServerInterceptor endUserContext =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            var delegate = next.startCall(call, metadata);
            if (!injectEndUserContext) return delegate;
            return new io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
                delegate) {
              @Override
              public void onHalfClose() {
                SessionContext.setContext("123", List.of(), Map.of());
                try {
                  super.onHalfClose();
                } finally {
                  SessionContext.clear();
                }
              }
            };
          }
        };
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            pki.accountServer().privateKey(), pki.accountServer().certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service,
                    countHandlerCalls,
                    new net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor(),
                    endUserContext))
            .build()
            .start();
    return new TransportServer(server, handlerCalls);
  }

  private static GrpcAccountStartSessionActivationProtectionSettlementClient newClient(
      int port,
      AccountStartSessionActivationProtectionAcquisitionGrpcMtlsTest.TestIdentity identity)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("127.0.0.1:" + port);
    return new GrpcAccountStartSessionActivationProtectionSettlementClient(
        endpoints,
        pki.clientProperties(temporaryDirectory, identity),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static ClientTransport newClientWithoutIdentity(int port) throws Exception {
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCaCert(pki.caCertificatePem().toString());
    ManagedChannel channel =
        new GrpcChannelFactory().buildChannel("127.0.0.1:" + port, 6565, tls, false);
    return new ClientTransport(channel);
  }

  private record TransportServer(Server server, AtomicInteger handlerCalls)
      implements AutoCloseable {
    private int port() {
      return server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
      server.shutdownNow();
      if (!server.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback Account gRPC server did not terminate");
      }
    }
  }

  private record ClientTransport(ManagedChannel channel) implements AutoCloseable {
    private SettleOriginalStartSessionActivationProtectionResponse settle(
        SettleOriginalStartSessionActivationProtectionRequest request) {
      return AccountStartSessionActivationProtectionSettlementServiceGrpc.newBlockingStub(channel)
          .withDeadlineAfter(3, TimeUnit.SECONDS)
          .settleOriginalStartSessionActivationProtection(request);
    }

    @Override
    public void close() throws InterruptedException {
      if (!channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback Account gRPC client did not terminate");
      }
    }
  }
}
