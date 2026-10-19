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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.v1.AccountStartSessionWorldParticipationSettlementServiceGrpc;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionWorldParticipationRequest;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationSettlementGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationSettlementService;
import net.firedevops.firemud.accountservice.testsupport.AccountStartSessionReservationProofPki;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationSettlementInput;
import net.firedevops.firemud.common.account.startsession.GrpcAccountStartSessionWorldParticipationSettlementClient;
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

/** Controlled-PKI loopback mTLS proof with a mocked Account settlement owner. */
class AccountStartSessionWorldParticipationSettlementGrpcMtlsTest {
  private static final String NAMESPACE = "world-runtime";
  private static final UUID PARTICIPATION_ID =
      UUID.fromString("5bb41395-a183-4ce5-9343-cd0995ba3b48");
  private static final long PARTICIPATION_FENCE = 43L;
  private static final UUID SETTLEMENT_REQUEST_ID =
      UUID.fromString("10d5b75a-7c46-4890-834e-4c6547458d94");
  private static final String STORE_PASSWORD = "test-only-world-participation-pki-password";

  @TempDir static Path temporaryDirectory;

  private static TestPki pki;

  @BeforeAll
  static void generateTrustedWorkloadCertificates() throws Exception {
    pki = TestPki.create(temporaryDirectory);
  }

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactWorldClientReachesReceiverAndPassesOnlyTheRetainedLookupToOwner() throws Exception {
    var owner = mock(AccountStartSessionWorldParticipationSettlementService.class);
    when(owner.settle(PARTICIPATION_ID, PARTICIPATION_FENCE))
        .thenThrow(Status.UNAVAILABLE.asRuntimeException());
    try (TransportServer server = startServer(owner, false);
        var client = newClient(server.port(), pki.worldClient())) {
      client.init();

      assertThatThrownBy(() -> client.settle(input()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAVAILABLE));

      assertThat(server.handlerCalls()).hasValue(1);
      verify(owner).settle(PARTICIPATION_ID, PARTICIPATION_FENCE);
    }
  }

  @Test
  void trustedWrongServiceAndNamespaceCertificatesAreDeniedBeforeOwnerInteraction()
      throws Exception {
    for (var identity : List.of(pki.wrongServiceClient(), pki.otherNamespaceClient())) {
      var owner = mock(AccountStartSessionWorldParticipationSettlementService.class);
      try (TransportServer server = startServer(owner, false);
          var client = newClient(server.port(), identity)) {
        client.init();

        assertThatThrownBy(() -> client.settle(input()))
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
  void missingClientCertificateFailsDuringTlsBeforeHandlerOrOwner() throws Exception {
    var owner = mock(AccountStartSessionWorldParticipationSettlementService.class);
    try (TransportServer server = startServer(owner, false);
        ClientTransport client = newClientWithoutIdentity(server.port())) {
      var request =
          net.firedevops.firemud.common.account.startsession
              .AccountStartSessionWorldParticipationSettlementGrpcCodec.toRequest(input());

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
  void authenticatedEndUserContextIsDeniedAtReceiverBeforeOwnerInteraction() throws Exception {
    var owner = mock(AccountStartSessionWorldParticipationSettlementService.class);
    try (TransportServer server = startServer(owner, true);
        var client = newClient(server.port(), pki.worldClient())) {
      client.init();

      assertThatThrownBy(() -> client.settle(input()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));

      assertThat(server.handlerCalls()).hasValue(1);
      verifyNoInteractions(owner);
    }
  }

  private static AccountStartSessionWorldParticipationSettlementInput input() {
    return new AccountStartSessionWorldParticipationSettlementInput(
        NAMESPACE, SETTLEMENT_REQUEST_ID, PARTICIPATION_ID, PARTICIPATION_FENCE);
  }

  private static TransportServer startServer(
      AccountStartSessionWorldParticipationSettlementService owner, boolean injectEndUserContext)
      throws Exception {
    AtomicInteger handlerCalls = new AtomicInteger();
    var service = new AccountStartSessionWorldParticipationSettlementGrpcService(owner, NAMESPACE);
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
                SessionContext.setContext("authenticated-user", List.of(), Map.of());
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

  private static GrpcAccountStartSessionWorldParticipationSettlementClient newClient(
      int port, TestIdentity identity) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("127.0.0.1:" + port);
    return new GrpcAccountStartSessionWorldParticipationSettlementClient(
        endpoints,
        pki.clientProperties(temporaryDirectory, identity),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static ClientTransport newClientWithoutIdentity(int port) throws Exception {
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCaCert(pki.caCertificatePem(temporaryDirectory).toString());
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
    private void settle(SettleOriginalStartSessionWorldParticipationRequest request) {
      AccountStartSessionWorldParticipationSettlementServiceGrpc.newBlockingStub(channel)
          .withDeadlineAfter(3, TimeUnit.SECONDS)
          .settleOriginalStartSessionWorldParticipation(request);
    }

    @Override
    public void close() throws InterruptedException {
      if (!channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback gRPC client did not terminate");
      }
    }
  }

  private record TestIdentity(String alias, PrivateKey privateKey, X509Certificate certificate) {}

  /** Controlled local certificates exercise the receiver's SPIFFE mTLS boundary only. */
  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity accountServer,
      TestIdentity worldClient,
      TestIdentity wrongServiceClient,
      TestIdentity otherNamespaceClient) {
    private static final String WORLD_URI =
        "spiffe://firemud/ns/world-runtime/sa/world-management-service";

    private static TestPki create(Path directory) throws Exception {
      var authority =
          AccountStartSessionReservationProofPki.createCertificateAuthority(
              directory,
              "world-participation-settlement-test-ca.p12",
              "world-participation-settlement-test-ca.crt",
              "world-participation-settlement-ca",
              "CN=FireMUD controlled World participation settlement test CA",
              STORE_PASSWORD);
      return new TestPki(
          authority.certificate(),
          issueIdentity(
              directory,
              authority,
              "account-server",
              "spiffe://firemud/ns/world-runtime/sa/account-service",
              true),
          issueIdentity(directory, authority, "world-client", WORLD_URI, false),
          issueIdentity(
              directory,
              authority,
              "wrong-service-client",
              "spiffe://firemud/ns/world-runtime/sa/game-session-service",
              false),
          issueIdentity(
              directory,
              authority,
              "other-namespace-client",
              "spiffe://firemud/ns/other-runtime/sa/world-management-service",
              false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory, TestIdentity identity)
        throws Exception {
      var properties = new CommonGrpcClientProperties();
      properties.setPlaintext(false);
      properties.setCertChain(
          writePem(
                  directory.resolve(identity.alias() + ".client.crt"),
                  "CERTIFICATE",
                  identity.certificate().getEncoded())
              .toString());
      properties.setPrivateKey(
          writePem(
                  directory.resolve(identity.alias() + ".client.key"),
                  "PRIVATE KEY",
                  identity.privateKey().getEncoded())
              .toString());
      properties.setCaCert(caCertificatePem(directory).toString());
      return properties;
    }

    private Path caCertificatePem(Path directory) throws Exception {
      return writePem(
          directory.resolve("world-participation-settlement-client-ca.crt"),
          "CERTIFICATE",
          caCertificate.getEncoded());
    }

    private static TestIdentity issueIdentity(
        Path directory,
        AccountStartSessionReservationProofPki.TestCertificateAuthority authority,
        String alias,
        String workloadUri,
        boolean server)
        throws Exception {
      var identity =
          AccountStartSessionReservationProofPki.issueIdentity(
                  directory,
                  authority,
                  alias,
                  alias + ".p12",
                  alias + ".csr",
                  alias + "-signed.crt",
                  workloadUri,
                  server,
                  false,
                  true)
              .identity();
      return new TestIdentity(identity.alias(), identity.privateKey(), identity.certificate());
    }

    private static Path writePem(Path path, String label, byte[] bytes) throws IOException {
      String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
      return Files.writeString(
          path,
          "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n",
          StandardCharsets.US_ASCII);
    }
  }
}
