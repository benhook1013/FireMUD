package net.firedevops.firemud.springcloudgateway.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AccountControlUiAuthenticationServiceGrpc;
import net.firedevops.firemud.account.v1.AuthLoginRequest;
import net.firedevops.firemud.account.v1.AuthLoginResponse;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.Authenticated;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.Outcome;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.Request;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.SourceContext;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/** Socket mTLS proof: a trusted but wrong server workload receives no authentication request. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AccountControlUiAuthenticationClientMtlsTest {
  private static final String NAMESPACE = "checkout-test";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/checkout-test/sa/account-service";
  private static final String GATEWAY_URI =
      "spiffe://firemud/ns/checkout-test/sa/spring-cloud-gateway";
  private static final UUID REQUEST_ID = UUID.fromString("2a1a9840-a249-4e19-90be-774d6afc937a");
  private static final UUID CALLER_CONTEXT_ID =
      UUID.fromString("540923e1-bb03-4cf2-98e7-25344c4a6ae8");
  private static final UUID ACCOUNT_ID = UUID.fromString("d0deddf3-d96d-456d-b85e-f025fa2e6ee3");
  private static final String SECRET = "ephemeral-test-primary-secret";
  private static final String TOKEN = "header.payload.signature";

  @TempDir static Path temporaryDirectory;
  private static LoopbackMtlsTestPki.Pki pki;
  private Server server;
  private AccountControlUiAuthenticationClient client;
  private GrpcChannelFactory channelFactory;

  @BeforeAll
  static void createPki() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            temporaryDirectory,
            "Checkout Gateway test CA",
            new LoopbackMtlsTestPki.IdentitySpec("account-server", ACCOUNT_URI, true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-server", "spiffe://firemud/ns/checkout-test/sa/game-session-service", true),
            new LoopbackMtlsTestPki.IdentitySpec("gateway-client", GATEWAY_URI, false));
  }

  @AfterEach
  void stopTransport() throws Exception {
    if (client != null) {
      client.close();
      client = null;
    }
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
  }

  @Test
  void authenticatesAccountPeerAndReturnsOnlyAnExactBoundResponse() throws Exception {
    var observed = new AtomicReference<Request>();
    var peer = new AtomicReference<GrpcPeerIdentity>();
    AtomicInteger handlers = startServer("account-server", observed, peer);
    client = newClient(server.getPort());
    client.init();

    var result = client.login(request());

    assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(result.authenticated())
        .isEqualTo(new Authenticated(ACCOUNT_ID, TOKEN, 1_900_000_000L));
    assertThat(observed.get()).isEqualTo(request());
    assertThat(observed.get().toString()).doesNotContain(SECRET);
    assertThat(peer.get().uri()).isEqualTo(GATEWAY_URI);
    assertThat(handlers).hasValue(1);
    org.mockito.Mockito.verify(channelFactory)
        .buildNonRetryingChannel(
            eq("localhost:" + server.getPort()),
            eq(6565),
            any(CommonGrpcClientProperties.class),
            eq(true));
  }

  @Test
  void caTrustedWrongServerWorkloadReceivesNoPrimaryRequestOrSecret() throws Exception {
    var observed = new AtomicReference<Request>();
    var peer = new AtomicReference<GrpcPeerIdentity>();
    AtomicInteger handlers = startServer("wrong-server", observed, peer);
    client = newClient(server.getPort());
    client.init();

    assertThatThrownBy(() -> client.login(request()))
        .isInstanceOf(AccountControlUiAuthenticationClient.AuthenticationUnavailableException.class)
        .hasMessage("Account authentication is unavailable");

    assertThat(handlers).hasValue(0);
    assertThat(observed.get()).isNull();
    assertThat(peer.get()).isNull();
  }

  @Test
  void mapsAuthenticatedInvalidArgumentWithoutForwardingGrpcErrorDetails() throws Exception {
    var observed = new AtomicReference<Request>();
    var peer = new AtomicReference<GrpcPeerIdentity>();
    AtomicInteger handlers =
        startServer("account-server", observed, peer, Status.Code.INVALID_ARGUMENT);
    client = newClient(server.getPort());
    client.init();

    assertThatThrownBy(() -> client.login(request()))
        .isInstanceOf(
            AccountControlUiAuthenticationClient.AuthenticationInvalidRequestException.class)
        .hasMessage("Account authentication request is invalid")
        .hasNoCause();

    assertThat(handlers).hasValue(1);
    assertThat(observed.get()).isEqualTo(request());
    assertThat(peer.get().uri()).isEqualTo(GATEWAY_URI);
  }

  @Test
  void mapsRetainedRequestInputConflictWithoutForwardingGrpcErrorDetails() throws Exception {
    var observed = new AtomicReference<Request>();
    var peer = new AtomicReference<GrpcPeerIdentity>();
    AtomicInteger handlers = startServer("account-server", observed, peer, true);
    client = newClient(server.getPort());
    client.init();

    assertThatThrownBy(() -> client.login(request()))
        .isInstanceOf(AccountControlUiAuthenticationClient.AuthenticationConflictException.class)
        .hasMessage("Account authentication request conflicts")
        .hasNoCause();

    assertThat(handlers).hasValue(1);
    assertThat(observed.get()).isEqualTo(request());
  }

  private AtomicInteger startServer(
      String serverIdentityAlias,
      AtomicReference<Request> observed,
      AtomicReference<GrpcPeerIdentity> peer)
      throws Exception {
    return startServer(serverIdentityAlias, observed, peer, (Status.Code) null);
  }

  private AtomicInteger startServer(
      String serverIdentityAlias,
      AtomicReference<Request> observed,
      AtomicReference<GrpcPeerIdentity> peer,
      boolean conflict)
      throws Exception {
    return startServer(
        serverIdentityAlias, observed, peer, conflict ? Status.Code.ALREADY_EXISTS : null);
  }

  private AtomicInteger startServer(
      String serverIdentityAlias,
      AtomicReference<Request> observed,
      AtomicReference<GrpcPeerIdentity> peer,
      Status.Code failureCode)
      throws Exception {
    var serverIdentity = pki.identity(serverIdentityAlias);
    AtomicInteger handlers = new AtomicInteger();
    AccountControlUiAuthenticationServiceGrpc.AccountControlUiAuthenticationServiceImplBase
        service =
            new AccountControlUiAuthenticationServiceGrpc
                .AccountControlUiAuthenticationServiceImplBase() {
              @Override
              public void authLogin(
                  AuthLoginRequest wireRequest,
                  StreamObserver<AuthLoginResponse> responseObserver) {
                handlers.incrementAndGet();
                peer.set(GrpcPeerIdentity.current());
                Request request = AccountControlUiAuthenticationGrpcCodec.parseRequest(wireRequest);
                observed.set(request);
                if (failureCode != null) {
                  responseObserver.onError(
                      Status.fromCode(failureCode)
                          .withDescription("controlled upstream detail")
                          .asRuntimeException());
                  return;
                }
                responseObserver.onNext(
                    AccountControlUiAuthenticationGrpcCodec.toResponse(
                        request, new Authenticated(ACCOUNT_ID, TOKEN, 1_900_000_000L)));
                responseObserver.onCompleted();
              }
            };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            serverIdentity.privateKey(), serverIdentity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    return handlers;
  }

  private AccountControlUiAuthenticationClient newClient(int port) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + port);
    channelFactory = org.mockito.Mockito.spy(new GrpcChannelFactory());
    return new AccountControlUiAuthenticationClient(
        endpoints,
        pki.clientProperties(temporaryDirectory, pki.identity("gateway-client")),
        channelFactory,
        NAMESPACE);
  }

  private static Request request() {
    return new Request(
        REQUEST_ID,
        CALLER_CONTEXT_ID,
        NAMESPACE,
        "operator@example.test",
        SECRET,
        new SourceContext("192.0.2.10"));
  }
}
