package net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.MetadataUtils;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.RequestEmailLoginOtpRequest;
import net.firedevops.firemud.account.v1.VerifyEmailLoginOtpRequest;
import net.firedevops.firemud.accountservice.dto.AuthenticationResult;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.PingService;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Physical TLS caller-policy proof with a mocked Account credential owner, not credential proof.
 */
class AccountCredentialPeerGrpcMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_UUID = "4cae05e8-7a6b-4b14-9d44-665e3eec450b";
  private static final String DEMO_EMAIL = "demo@example.com";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String ENTITY_URI = "spiffe://firemud/ns/test/sa/entity-management-service";

  @TempDir static Path temporaryDirectory;

  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void createTrustedWorkloadCertificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            temporaryDirectory,
            "FireMUD Account credential peer test CA",
            new IdentitySpec("account", "spiffe://firemud/ns/test/sa/account-service", true),
            new IdentitySpec("game-session", GAME_SESSION_URI, false),
            new IdentitySpec("entity", ENTITY_URI, false),
            new IdentitySpec(
                "world", "spiffe://firemud/ns/test/sa/world-management-service", false),
            new IdentitySpec(
                "other-game-session", "spiffe://firemud/ns/other/sa/game-session-service", false),
            new IdentitySpec(
                "other-entity", "spiffe://firemud/ns/other/sa/entity-management-service", false));
  }

  @Test
  void gameSessionCertificateAuthorizesAllThreeCredentialMethodsWithoutFixtureSettings()
      throws Exception {
    AccountService owner = credentialOwner(GAME_SESSION_URI);
    AccountGrpcService service =
        new AccountGrpcService(mock(PingService.class), owner, null, NAMESPACE);
    try (TransportServer server = startServer(service);
        ClientTransport client = newClient(server.port(), "game-session")) {
      var stub = client.stub();
      assertAuthenticated(stub.authenticate(authentication(DEMO_EMAIL)));
      var challenge = stub.requestEmailLoginOtp(challenge());
      assertThat(challenge.hasError()).isFalse();
      assertThat(challenge.getAccepted()).isTrue();
      assertAuthenticated(stub.verifyEmailLoginOtp(verification()));

      verify(owner).authenticateForGameplay(DEMO_EMAIL, "password");
      verify(owner).requestEmailLoginOtp(DEMO_EMAIL);
      verify(owner).verifyEmailLoginOtp(DEMO_EMAIL, "123456");
      verifyNoMoreInteractions(owner);
    }
  }

  @Test
  void exactEntityCertificateAuthenticatesOnlyDemoFixtureAndNeverAuthorizesOtp() throws Exception {
    AccountService owner = credentialOwner(ENTITY_URI);
    try (TransportServer server = startServer(fixtureService(owner, true, "local-fixture"));
        ClientTransport client = newClient(server.port(), "entity")) {
      var stub = client.stub();
      assertAuthenticated(stub.authenticate(authentication(DEMO_EMAIL)));
      assertOtpDenied(stub);

      verify(owner).authenticateForGameplay(DEMO_EMAIL, "password");
      verifyNoMoreInteractions(owner);
    }
  }

  @ParameterizedTest
  @MethodSource("unavailableFixtureSettings")
  void entityCertificateCannotBypassReceivingFlagOrProfileGates(boolean enabled, String[] profiles)
      throws Exception {
    AccountService owner = mock(AccountService.class);
    try (TransportServer server = startServer(fixtureService(owner, enabled, profiles));
        ClientTransport client = newClient(server.port(), "entity")) {
      assertAllDenied(client.stub(), authentication(DEMO_EMAIL));
      verifyNoInteractions(owner);
    }
  }

  private static Stream<Arguments> unavailableFixtureSettings() {
    return Stream.of(
        Arguments.of(false, new String[] {"local-fixture"}),
        Arguments.of(true, new String[] {}),
        Arguments.of(true, new String[] {"prod"}),
        Arguments.of(true, new String[] {"local-fixture", "prod"}));
  }

  @Test
  void defaultConstructorDoesNotAuthorizeEntityFixture() throws Exception {
    AccountService owner = mock(AccountService.class);
    AccountGrpcService service =
        new AccountGrpcService(mock(PingService.class), owner, null, NAMESPACE);
    try (TransportServer server = startServer(service);
        ClientTransport client = newClient(server.port(), "entity")) {
      assertAllDenied(client.stub(), authentication(DEMO_EMAIL));
      verifyNoInteractions(owner);
    }
  }

  @Test
  void trustedWrongWorkloadAndNamespaceCertificatesAreDeniedBeforeAccountInvocation()
      throws Exception {
    AccountService owner = mock(AccountService.class);
    try (TransportServer server = startServer(fixtureService(owner, true, "local-fixture"))) {
      for (String alias : List.of("world", "other-game-session", "other-entity")) {
        try (ClientTransport client = newClient(server.port(), alias)) {
          assertAllDenied(client.stub(), AuthenticateRequest.getDefaultInstance());
        }
      }
      verifyNoInteractions(owner);
    }
  }

  @Test
  void entityCertificateCannotAuthenticateAnotherEmail() throws Exception {
    AccountService owner = mock(AccountService.class);
    try (TransportServer server = startServer(fixtureService(owner, true, "local-fixture"));
        ClientTransport client = newClient(server.port(), "entity")) {
      assertAuthenticationDenied(client.stub().authenticate(authentication("other@example.com")));
      verifyNoInteractions(owner);
    }
  }

  @Test
  void callerSuppliedPeerHeadersCannotReplaceWrongWorkloadCertificate() throws Exception {
    AccountService owner = mock(AccountService.class);
    Metadata spoofed = new Metadata();
    for (String header : List.of("x-peer-identity", "x-spiffe-id", "x-workload-identity")) {
      spoofed.put(Metadata.Key.of(header, Metadata.ASCII_STRING_MARSHALLER), GAME_SESSION_URI);
    }
    try (TransportServer server = startServer(fixtureService(owner, true, "local-fixture"));
        ClientTransport client = newClient(server.port(), "world")) {
      var stub = client.stub().withInterceptors(MetadataUtils.newAttachHeadersInterceptor(spoofed));
      assertAllDenied(stub, authentication(DEMO_EMAIL));
      verifyNoInteractions(owner);
    }
  }

  @Test
  void missingClientCertificateFailsHandshakeWithoutAccountInvocation() throws Exception {
    AccountService owner = mock(AccountService.class);
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    // Reuse the fixture's CA projection without supplying its client certificate or key.
    var issued = pki.clientProperties(temporaryDirectory, pki.identity("game-session"));
    tls.setCaCert(issued.getCaCert());
    try (TransportServer server = startServer(fixtureService(owner, true, "local-fixture"));
        ClientTransport client = newClient(server.port(), tls)) {
      assertThatThrownBy(() -> client.stub().authenticate(authentication(DEMO_EMAIL)))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure -> assertThat(TlsTestSupport.isTlsHandshakeRejection(failure)).isTrue());
      verifyNoInteractions(owner);
    }
  }

  private static AccountService credentialOwner(String expectedPeerUri) {
    AccountService owner = mock(AccountService.class);
    doAnswer(
            invocation -> {
              assertThat(GrpcPeerIdentity.current()).isNotNull();
              assertThat(GrpcPeerIdentity.current().uri()).isEqualTo(expectedPeerUri);
              return new AuthenticationResult(ACCOUNT_UUID, "test-owner-token");
            })
        .when(owner)
        .authenticateForGameplay(DEMO_EMAIL, "password");
    doAnswer(
            invocation -> {
              assertThat(GrpcPeerIdentity.current()).isNotNull();
              assertThat(GrpcPeerIdentity.current().uri()).isEqualTo(expectedPeerUri);
              return new AuthenticationResult(ACCOUNT_UUID, "test-owner-token");
            })
        .when(owner)
        .verifyEmailLoginOtp(DEMO_EMAIL, "123456");
    doAnswer(
            invocation -> {
              assertThat(GrpcPeerIdentity.current()).isNotNull();
              assertThat(GrpcPeerIdentity.current().uri()).isEqualTo(expectedPeerUri);
              return null;
            })
        .when(owner)
        .requestEmailLoginOtp(DEMO_EMAIL);
    return owner;
  }

  private static AccountGrpcService fixtureService(
      AccountService owner, boolean enabled, String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return new AccountGrpcService(
        mock(PingService.class), owner, null, NAMESPACE, environment, enabled, DEMO_EMAIL);
  }

  private static AuthenticateRequest authentication(String email) {
    return AuthenticateRequest.newBuilder().setEmail(email).setPassword("password").build();
  }

  private static RequestEmailLoginOtpRequest challenge() {
    return RequestEmailLoginOtpRequest.newBuilder().setEmail(DEMO_EMAIL).build();
  }

  private static VerifyEmailLoginOtpRequest verification() {
    return VerifyEmailLoginOtpRequest.newBuilder().setEmail(DEMO_EMAIL).setCode("123456").build();
  }

  private static void assertAuthenticated(AuthenticateResponse response) {
    assertThat(response.hasError()).isFalse();
    assertThat(response.getAccountId()).isEqualTo(ACCOUNT_UUID);
    assertThat(response.getAuthToken()).isEqualTo("test-owner-token");
  }

  private static void assertAuthenticationDenied(AuthenticateResponse response) {
    assertThat(response.getError().getCode()).isEqualTo("PERMISSION_DENIED");
    assertThat(response.getAccountId()).isEmpty();
    assertThat(response.getAuthToken()).isEmpty();
  }

  private static void assertOtpDenied(AccountServiceGrpc.AccountServiceBlockingStub stub) {
    var challenge = stub.requestEmailLoginOtp(RequestEmailLoginOtpRequest.getDefaultInstance());
    assertThat(challenge.getError().getCode()).isEqualTo("PERMISSION_DENIED");
    assertThat(challenge.getAccepted()).isFalse();
    assertAuthenticationDenied(
        stub.verifyEmailLoginOtp(VerifyEmailLoginOtpRequest.getDefaultInstance()));
  }

  private static void assertAllDenied(
      AccountServiceGrpc.AccountServiceBlockingStub stub, AuthenticateRequest request) {
    assertAuthenticationDenied(stub.authenticate(request));
    assertOtpDenied(stub);
  }

  private static TransportServer startServer(AccountGrpcService service) throws Exception {
    var identity = pki.identity("account");
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
    return new TransportServer(server);
  }

  private static ClientTransport newClient(int port, String alias) throws Exception {
    return newClient(port, pki.clientProperties(temporaryDirectory, pki.identity(alias)));
  }

  private static ClientTransport newClient(int port, CommonGrpcClientProperties tls)
      throws Exception {
    return new ClientTransport(
        new GrpcChannelFactory().buildChannel("127.0.0.1:" + port, 6565, tls, false));
  }

  private record TransportServer(Server server) implements AutoCloseable {
    private int port() {
      return server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
      server.shutdownNow();
      if (!server.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback Account credential server did not terminate");
      }
    }
  }

  private record ClientTransport(ManagedChannel channel) implements AutoCloseable {
    private AccountServiceGrpc.AccountServiceBlockingStub stub() {
      return AccountServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws InterruptedException {
      if (!channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback Account credential client did not terminate");
      }
    }
  }
}
