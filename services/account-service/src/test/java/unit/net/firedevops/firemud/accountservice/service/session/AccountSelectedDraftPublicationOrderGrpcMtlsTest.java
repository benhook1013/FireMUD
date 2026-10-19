package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Server;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.v1.AccountSelectedPublicationOrderServiceGrpc;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.service.session.AccountSelectedDraftPublicationOrderGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountSelectedDraftPublicationOrderService;
import net.firedevops.firemud.accountservice.testsupport.AccountStartSessionReservationProofPki;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.publication.AccountSelectedPublicationOrderClient;
import net.firedevops.firemud.common.publication.AccountSelectedPublicationOrderGrpcCodec;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Native loopback mTLS transport proof for the unregistered selected-publication producer. */
class AccountSelectedDraftPublicationOrderGrpcMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String WRONG_ACCOUNT_URI =
      "spiffe://firemud/ns/other-test/sa/account-service";
  private static final String WRONG_SERVER_URI =
      "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String GAME_DESIGN_URI = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String WRONG_CLIENT_URI =
      "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String OTHER_NAMESPACE_CLIENT_URI =
      "spiffe://firemud/ns/other-test/sa/game-design-service";

  @TempDir static Path temporaryDirectory;
  private static TestPki pki;

  @BeforeAll
  static void createTrustedWorkloadCertificates() throws Exception {
    pki = TestPki.create(temporaryDirectory);
  }

  @Test
  void authenticatedGameDesignClientReachesEnvironmentCaptureThenUnresolvedOwner()
      throws Exception {
    var owner = mock(AccountSelectedDraftPublicationOrderService.class);
    var terms = mock(AccountHostedTermsService.class);
    var captured = mock(AccountHostedTermsService.CapturedEnvironmentBoundary.class);
    when(terms.captureCurrentEnvironmentBoundary()).thenReturn(captured);
    when(owner.authorize(eq(CREDENTIAL), any(), same(captured)))
        .thenThrow(Status.FAILED_PRECONDITION.asRuntimeException());

    try (TransportServer server = startServer(pki.accountServer(), owner, terms);
        var client = newClient(server.port(), pki.gameDesignClient())) {
      client.init();

      assertThatThrownBy(() -> client.authorize(request()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));

      assertThat(server.handlerCalls()).hasValue(1);
      var calls = inOrder(terms, owner);
      calls.verify(terms).captureCurrentEnvironmentBoundary();
      calls.verify(owner).authorize(eq(CREDENTIAL), any(), same(captured));
    }
  }

  @Test
  void trustedWrongServiceAndNamespaceGameDesignCallersAreDeniedBeforeCapture() throws Exception {
    for (TestIdentity identity : List.of(pki.wrongServiceClient(), pki.otherNamespaceClient())) {
      var owner = mock(AccountSelectedDraftPublicationOrderService.class);
      var terms = mock(AccountHostedTermsService.class);
      try (TransportServer server = startServer(pki.accountServer(), owner, terms);
          var client = newClient(server.port(), identity)) {
        client.init();

        assertThatThrownBy(() -> client.authorize(request()))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .as(identity.alias())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));

        assertThat(server.handlerCalls()).as(identity.alias()).hasValue(1);
        verifyNoInteractions(terms, owner);
      }
    }
  }

  @Test
  void missingClientCertificateFailsTlsBeforeReceiverOrOwner() throws Exception {
    var owner = mock(AccountSelectedDraftPublicationOrderService.class);
    var terms = mock(AccountHostedTermsService.class);
    try (TransportServer server = startServer(pki.accountServer(), owner, terms);
        ClientTransport client = newClientWithoutIdentity(server.port())) {
      var wire = AccountSelectedPublicationOrderGrpcCodec.toRequest(request());

      assertThatThrownBy(() -> client.authorize(wire))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(TlsTestSupport.isTlsHandshakeRejection(failure))
                      .as(
                          "missing client certificate must fail during TLS negotiation: %s",
                          failure)
                      .isTrue());

      assertThat(server.handlerCalls()).hasValue(0);
      verifyNoInteractions(terms, owner);
    }
  }

  @Test
  void creatorCredentialMetadataIsRequiredBeforeEnvironmentCapture() throws Exception {
    var owner = mock(AccountSelectedDraftPublicationOrderService.class);
    var terms = mock(AccountHostedTermsService.class);
    try (TransportServer server = startServer(pki.accountServer(), owner, terms);
        ClientTransport client = newClientWithIdentity(server.port(), pki.gameDesignClient())) {
      assertThatThrownBy(
              () -> client.authorize(AccountSelectedPublicationOrderGrpcCodec.toRequest(request())))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));

      assertThat(server.handlerCalls()).hasValue(1);
      verifyNoInteractions(terms, owner);
    }
  }

  @Test
  void clientRejectsTrustedWrongServiceAndNamespaceAccountServerIdentities() throws Exception {
    for (TestIdentity identity : List.of(pki.wrongServiceServer(), pki.otherNamespaceServer())) {
      var owner = mock(AccountSelectedDraftPublicationOrderService.class);
      var terms = mock(AccountHostedTermsService.class);
      try (TransportServer server = startServer(identity, owner, terms);
          var client = newClient(server.port(), pki.gameDesignClient())) {
        client.init();

        assertThatThrownBy(() -> client.authorize(request()))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .as(identity.alias())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));

        assertThat(server.handlerCalls()).as(identity.alias()).hasValue(0);
        verifyNoInteractions(terms, owner);
      }
    }
  }

  private static TransportServer startServer(
      TestIdentity serverIdentity,
      AccountSelectedDraftPublicationOrderService owner,
      AccountHostedTermsService terms)
      throws Exception {
    AtomicInteger handlerCalls = new AtomicInteger();
    ServerInterceptor countHandlerCalls =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
              io.grpc.ServerCall<ReqT, RespT> call,
              io.grpc.Metadata headers,
              io.grpc.ServerCallHandler<ReqT, RespT> next) {
            handlerCalls.incrementAndGet();
            return next.startCall(call, headers);
          }
        };
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            serverIdentity.privateKey(), serverIdentity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    new AccountSelectedDraftPublicationOrderGrpcService(owner, terms, NAMESPACE),
                    countHandlerCalls,
                    new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    return new TransportServer(server, handlerCalls);
  }

  private static AccountSelectedPublicationOrderClient newClient(int port, TestIdentity identity)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("127.0.0.1:" + port);
    return new AccountSelectedPublicationOrderClient(
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

  private static ClientTransport newClientWithIdentity(int port, TestIdentity identity)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("127.0.0.1:" + port);
    var channel =
        new GrpcChannelFactory()
            .buildChannel(
                endpoints.getAccountService(),
                6565,
                pki.clientProperties(temporaryDirectory, identity),
                false);
    return new ClientTransport(channel);
  }

  private static AccountSelectedPublicationOrderGrpcCodec.Request request() {
    UUID tenant = UUID.randomUUID(), version = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, version, 19, "tenant-key", 42, "tenant-key", "NEW_GAME_ROW");
    var commit =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "base-1",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0", UUID.randomUUID(), DraftCommitBinding.Owner.WORLD_MANAGEMENT, "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "WORLD_TEMPLATE",
                    "world-1",
                    "ROOM_SCOPE",
                    "room-1",
                    "0")));
    var selection =
        AuthoredDraftPublishSelectionBinding.capture(
            new AuthoredDraftPublishSelectionBinding.PublishIntent(
                tenant,
                version,
                "publication-request",
                "5",
                "fixture",
                commit.requestId(),
                commit.commitId(),
                commit.digest()),
            target,
            commit,
            new AuthoredDraftPublishSelectionBinding.VisibilityFence(
                target,
                commit.requestId(),
                commit.commitId(),
                commit.digest(),
                "[]",
                OffsetDateTime.parse("2026-10-01T00:00:00Z")));
    return AccountSelectedPublicationOrderGrpcCodec.Request.create(
        NAMESPACE, selection, CREDENTIAL);
  }

  private static final String CREDENTIAL = "original.secret.credential";

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
    private void authorize(
        net.firedevops.firemud.account.v1.AuthorizeSelectedPublicationRequest request) {
      AccountSelectedPublicationOrderServiceGrpc.newBlockingStub(channel)
          .withDeadlineAfter(3, TimeUnit.SECONDS)
          .authorizeSelectedPublication(request);
    }

    @Override
    public void close() throws InterruptedException {
      if (!channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback gRPC client did not terminate");
      }
    }
  }

  private record TestIdentity(String alias, PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      Path caCertificatePem,
      TestIdentity accountServer,
      TestIdentity wrongServiceServer,
      TestIdentity otherNamespaceServer,
      TestIdentity gameDesignClient,
      TestIdentity wrongServiceClient,
      TestIdentity otherNamespaceClient) {
    private static final String STORE_PASSWORD = "test-only-store-password";

    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("selected-publication-test-ca.p12");
      AccountStartSessionReservationProofPki.runKeytool(
          directory,
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD selected publication test CA",
          "-validity",
          "30",
          "-ext",
          "BC=ca:true",
          "-ext",
          "KU=keyCertSign,cRLSign",
          "-storetype",
          "PKCS12",
          "-keystore",
          caStore.toString(),
          "-storepass",
          STORE_PASSWORD,
          "-keypass",
          STORE_PASSWORD);
      Path caFile = directory.resolve("selected-publication-test-ca.crt");
      AccountStartSessionReservationProofPki.runKeytool(
          directory,
          "-exportcert",
          "-alias",
          "test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          caFile.toString(),
          "-rfc");
      var ca = readCertificate(caFile);
      return new TestPki(
          ca,
          caFile,
          issueIdentity(directory, caStore, caFile, "account-server", ACCOUNT_URI, true),
          issueIdentity(directory, caStore, caFile, "wrong-service-server", WRONG_SERVER_URI, true),
          issueIdentity(
              directory, caStore, caFile, "other-namespace-server", WRONG_ACCOUNT_URI, true),
          issueIdentity(directory, caStore, caFile, "game-design-client", GAME_DESIGN_URI, false),
          issueIdentity(
              directory, caStore, caFile, "wrong-service-client", WRONG_CLIENT_URI, false),
          issueIdentity(
              directory,
              caStore,
              caFile,
              "other-namespace-client",
              OTHER_NAMESPACE_CLIENT_URI,
              false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory, TestIdentity identity)
        throws Exception {
      var tls = new CommonGrpcClientProperties();
      tls.setCertChain(
          writePem(
                  directory.resolve(identity.alias() + ".client.crt"),
                  "CERTIFICATE",
                  identity.certificate().getEncoded())
              .toString());
      tls.setPrivateKey(
          writePem(
                  directory.resolve(identity.alias() + ".client.key"),
                  "PRIVATE KEY",
                  identity.privateKey().getEncoded())
              .toString());
      tls.setCaCert(caCertificatePem.toString());
      tls.setPlaintext(false);
      return tls;
    }

    private static TestIdentity issueIdentity(
        Path directory, Path caStore, Path caFile, String alias, String workloadUri, boolean server)
        throws Exception {
      Path store = directory.resolve(alias + ".p12");
      Path request = directory.resolve(alias + ".csr");
      Path certificate = directory.resolve(alias + ".crt");
      String san = "URI:" + workloadUri + ",DNS:localhost,IP:127.0.0.1";
      AccountStartSessionReservationProofPki.runKeytool(
          directory,
          "-genkeypair",
          "-alias",
          alias,
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=" + alias,
          "-validity",
          "30",
          "-ext",
          "KU=digitalSignature,keyEncipherment",
          "-ext",
          "EKU=" + (server ? "serverAuth" : "clientAuth"),
          "-ext",
          "SAN=" + san,
          "-storetype",
          "PKCS12",
          "-keystore",
          store.toString(),
          "-storepass",
          STORE_PASSWORD,
          "-keypass",
          STORE_PASSWORD);
      AccountStartSessionReservationProofPki.runKeytool(
          directory,
          "-certreq",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          request.toString(),
          "-ext",
          "SAN=" + san);
      AccountStartSessionReservationProofPki.runKeytool(
          directory,
          "-gencert",
          "-alias",
          "test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-infile",
          request.toString(),
          "-outfile",
          certificate.toString(),
          "-validity",
          "30",
          "-rfc",
          "-ext",
          "BC=ca:false",
          "-ext",
          "KU=digitalSignature,keyEncipherment",
          "-ext",
          "EKU=" + (server ? "serverAuth" : "clientAuth"),
          "-ext",
          "SAN=" + san);
      AccountStartSessionReservationProofPki.runKeytool(
          directory,
          "-importcert",
          "-alias",
          "test-ca",
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          caFile.toString(),
          "-noprompt");
      AccountStartSessionReservationProofPki.runKeytool(
          directory,
          "-importcert",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          certificate.toString(),
          "-noprompt");
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(store)) {
        keyStore.load(input, STORE_PASSWORD.toCharArray());
      }
      return new TestIdentity(
          alias,
          (PrivateKey) keyStore.getKey(alias, STORE_PASSWORD.toCharArray()),
          (X509Certificate) keyStore.getCertificate(alias));
    }

    private static X509Certificate readCertificate(Path path) throws Exception {
      try (var input = Files.newInputStream(path)) {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      }
    }

    private static Path writePem(Path path, String label, byte[] bytes) throws IOException {
      String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
      return Files.writeString(
          path, "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n");
    }
  }
}
