package crossservice.net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthorityEventReadback;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityGrpcService;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Physical mutual-TLS proof for the deliberately unwired issuer-source readback handoff. */
class AccountIssuerAuthorityGrpcMtlsCrossServiceTest {
  @TempDir private Path tempDir;

  private final Map<String, Path> copiedCertificates = new HashMap<>();

  private static final String NAMESPACE = "test";
  private static final String ISSUER_ID = "https://account.example.test/issuer";
  private static final String SOURCE_SCOPE = "issuer/" + ISSUER_ID;
  private static final String STREAM_KEY =
      IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + SOURCE_SCOPE;
  private static final String CURRENT_EVENT_REQUEST_ID = "11111111-1111-4111-8111-111111111111";
  private static final String HISTORICAL_EVENT_REQUEST_ID = "22222222-2222-4222-8222-222222222222";
  private static final String CURRENT_READ_REQUEST_ID = "33333333-3333-4333-8333-333333333333";
  private static final String HISTORICAL_READ_REQUEST_ID = "44444444-4444-4444-8444-444444444444";

  @Test
  void exactWorkloadIdentitiesCarryCurrentAndHistoricalReadbacksOverSocketMtls() throws Exception {
    AccountIssuerAuthorityEventProducer producer = producerWithPositiveReadbacks();
    Server server = startServer("account", producer);
    try (AccountIssuerAuthorityClient client = newClient(server.getPort(), "game-session")) {
      client.init();

      AccountIssuerAuthorityClient.SourceReadback current =
          client.readCurrent(CURRENT_READ_REQUEST_ID);
      AccountIssuerAuthorityClient.SourceReadback historical =
          client.readCommittedEvent(HISTORICAL_READ_REQUEST_ID, "1");

      assertThat(current.requestId()).isEqualTo(CURRENT_READ_REQUEST_ID);
      assertThat(current.targetNamespace()).isEqualTo(NAMESPACE);
      assertThat(current.sourceSnapshot().outboxSequence()).isEqualTo("2");
      assertThat(current.sourceSnapshot().latestEvent().orElseThrow().canonicalJson())
          .isEqualTo(event(CURRENT_EVENT_REQUEST_ID, 2L, 3L, 3L).canonicalJson());
      assertThat(current.requestedEvent()).isEmpty();
      assertThat(historical.sourceSnapshot().outboxSequence()).isEqualTo("2");
      assertThat(historical.sourceSnapshot().latestEvent().orElseThrow().canonicalJson())
          .isEqualTo(event(CURRENT_EVENT_REQUEST_ID, 2L, 3L, 3L).canonicalJson());
      assertThat(historical.requestedEvent().orElseThrow().canonicalJson())
          .isEqualTo(event(HISTORICAL_EVENT_REQUEST_ID, 1L, 2L, 2L).canonicalJson());

      verify(producer).readCurrent(ISSUER_ID);
      verify(producer).readCommittedEvent(ISSUER_ID, 1L);
    } finally {
      stop(server);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrong-service", "wrong-namespace"})
  void trustedButWrongGameSessionPeerIsDeniedBeforeProducerAccess(String clientIdentity)
      throws Exception {
    AccountIssuerAuthorityEventProducer producer = mock(AccountIssuerAuthorityEventProducer.class);
    Server server = startServer("account", producer);
    try (AccountIssuerAuthorityClient client = newClient(server.getPort(), clientIdentity)) {
      client.init();

      assertThatThrownBy(() -> client.readCurrent(CURRENT_READ_REQUEST_ID))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
      verifyNoInteractions(producer);
    } finally {
      stop(server);
    }
  }

  @Test
  void missingClientCertificateFailsTheRequiredTlsHandshakeBeforeProducerAccess() throws Exception {
    AccountIssuerAuthorityEventProducer producer = mock(AccountIssuerAuthorityEventProducer.class);
    Server server = startServer("account", producer);
    SSLContext trustOnlyClientContext = trustOnlyClientContext();
    try {
      Throwable handshakeFailure =
          catchThrowable(
              () -> {
                try (Socket transport = new Socket()) {
                  transport.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 5_000);
                  try (SSLSocket socket =
                      (SSLSocket)
                          trustOnlyClientContext
                              .getSocketFactory()
                              .createSocket(transport, "127.0.0.1", server.getPort(), true)) {
                    socket.setSoTimeout(5_000);
                    SSLParameters parameters = socket.getSSLParameters();
                    parameters.setApplicationProtocols(new String[] {"h2"});
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    socket.setSSLParameters(parameters);
                    socket.startHandshake();
                    // TLS 1.3 can deliver the server's certificate rejection after the client's
                    // handshake returns. Observe that alert rather than accepting a silent close.
                    socket
                        .getOutputStream()
                        .write(
                            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    socket.getInputStream().read();
                  }
                }
              });

      assertThat(handshakeFailure)
          .as("the REQUIRED client-certificate TLS handshake must fail")
          .isNotNull();
      assertThat(TlsTestSupport.isTlsHandshakeRejection(handshakeFailure))
          .as("failure must identify client-certificate rejection: %s", describe(handshakeFailure))
          .isTrue();
      verifyNoInteractions(producer);
    } finally {
      stop(server);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"game-session", "wrong-account-namespace"})
  void trustedAccountServerWithWrongServiceOrNamespaceIsRejectedBeforeResponseAcceptance(
      String serverIdentity) throws Exception {
    AccountIssuerAuthorityEventProducer producer = producerWithPositiveReadbacks();
    Server server = startServer(serverIdentity, producer);
    try (AccountIssuerAuthorityClient client = newClient(server.getPort(), "game-session")) {
      client.init();

      assertThatThrownBy(() -> client.readCurrent(CURRENT_READ_REQUEST_ID))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
      verify(producer).readCurrent(ISSUER_ID);
    } finally {
      stop(server);
    }
  }

  private static AccountIssuerAuthorityEventProducer producerWithPositiveReadbacks() {
    AccountIssuerAuthorityEventProducer producer = mock(AccountIssuerAuthorityEventProducer.class);
    IssuerGenerationAuthorityEvent latest = event(CURRENT_EVENT_REQUEST_ID, 2L, 3L, 3L);
    IssuerGenerationAuthorityEvent selected = event(HISTORICAL_EVENT_REQUEST_ID, 1L, 2L, 2L);
    IssuerAuthoritySnapshot current =
        new IssuerAuthoritySnapshot(ISSUER_ID, 3L, 3L, STREAM_KEY, 2L, Optional.of(latest));
    when(producer.readCurrent(ISSUER_ID)).thenReturn(current);
    when(producer.readCommittedEvent(ISSUER_ID, 1L))
        .thenReturn(new IssuerAuthorityEventReadback(current, selected));
    return producer;
  }

  private AccountIssuerAuthorityClient newClient(int port, String clientIdentity) throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + port);
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate(clientIdentity + ".crt").toString());
    tls.setPrivateKey(certificate(clientIdentity + ".key").toString());
    tls.setCaCert(certificate("ca.crt").toString());
    return new AccountIssuerAuthorityClient(
        endpoints,
        tls,
        new GrpcChannelFactory(),
        BlockingGrpcStubCustomizer.noop(),
        NAMESPACE,
        ISSUER_ID);
  }

  private Server startServer(String serverIdentity, AccountIssuerAuthorityEventProducer producer)
      throws Exception {
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.forServer(
                    certificate(serverIdentity + ".crt").toFile(),
                    certificate(serverIdentity + ".key").toFile())
                .trustManager(certificate("ca.crt").toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(
            ServerInterceptors.intercept(
                new AccountIssuerAuthorityGrpcService(producer, NAMESPACE),
                new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private Path certificate(String name) throws IOException {
    Path existing = copiedCertificates.get(name);
    if (existing != null) {
      return existing;
    }

    String resourcePath = "/certs/issuer-authority/" + name;
    try (InputStream resource =
        AccountIssuerAuthorityGrpcMtlsCrossServiceTest.class.getResourceAsStream(resourcePath)) {
      if (resource == null) {
        throw new IllegalStateException("Missing issuer-authority TLS fixture: " + resourcePath);
      }
      Path copied = tempDir.resolve(name);
      Files.copy(resource, copied);
      copiedCertificates.put(name, copied);
      return copied;
    }
  }

  private SSLContext trustOnlyClientContext() throws Exception {
    X509Certificate caCertificate;
    try (InputStream input = Files.newInputStream(certificate("ca.crt"))) {
      caCertificate =
          (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
    }

    KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustStore.load(null);
    trustStore.setCertificateEntry("test-ca", caCertificate);
    TrustManagerFactory trustManagers =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trustStore);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(new KeyManager[0], trustManagers.getTrustManagers(), null);
    return context;
  }

  private static String describe(Throwable failure) {
    StringBuilder description = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (description.length() > 0) {
        description.append(" <- ");
      }
      description.append(cause.getClass().getSimpleName());
      if (cause.getMessage() != null) {
        description.append(": ").append(cause.getMessage());
      }
    }
    return description.toString();
  }

  private static IssuerGenerationAuthorityEvent event(
      String requestId, long sequence, long generation, long sourceVersion) {
    return IssuerGenerationAuthorityEventV1Codec.seal(
        Map.of(
            "schemaVersion",
            IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
            "eventType",
            IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE,
            "eventId",
            "account-issuer-authority-event-v1:" + requestId,
            "requestId",
            requestId,
            "issuerId",
            ISSUER_ID,
            "sourceScope",
            SOURCE_SCOPE,
            "outboxStreamKey",
            STREAM_KEY,
            "outboxSequence",
            Long.toString(sequence),
            "issuerAuthGeneration",
            Long.toString(generation),
            "sourceVersion",
            Long.toString(sourceVersion)));
  }

  private static void stop(Server server) throws InterruptedException {
    server.shutdownNow();
    server.awaitTermination(2, TimeUnit.SECONDS);
  }
}
