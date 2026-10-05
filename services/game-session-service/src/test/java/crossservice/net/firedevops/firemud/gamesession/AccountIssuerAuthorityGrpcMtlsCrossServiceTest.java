package crossservice.net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
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
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.account.v1.AcknowledgeIssuerProjectionForRuntimeRequest;
import net.firedevops.firemud.account.v1.AcknowledgeIssuerProjectionForRuntimeResponse;
import net.firedevops.firemud.account.v1.IssuerAuthorityServiceGrpc;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionAcknowledgmentRepository.Acknowledgment;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository.Receipt;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthorityEventReadback;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityGrpcService;
import net.firedevops.firemud.accountservice.service.AccountIssuerProjectionAcknowledgmentService;
import net.firedevops.firemud.accountservice.service.AccountIssuerProjectionReconciliationService;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import net.firedevops.firemud.common.account.authority.IssuerProjectionInstallationAcknowledgmentDigestV1;
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

/** Physical mutual-TLS proof for the deliberately unwired issuer-source handoffs. */
class AccountIssuerAuthorityGrpcMtlsCrossServiceTest {
  @TempDir private Path tempDir;

  private final Map<String, Path> copiedCertificates = new HashMap<>();

  private static final String NAMESPACE = "test";
  private static final String ISSUER_ID = "https://account.example.test/issuer";
  private static final String GAME_SESSION_PEER_URI =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service";
  private static final String SOURCE_SCOPE = "issuer/" + ISSUER_ID;
  private static final String STREAM_KEY =
      IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + SOURCE_SCOPE;
  private static final String CURRENT_EVENT_REQUEST_ID = "11111111-1111-4111-8111-111111111111";
  private static final String HISTORICAL_EVENT_REQUEST_ID = "22222222-2222-4222-8222-222222222222";
  private static final String CURRENT_READ_REQUEST_ID = "33333333-3333-4333-8333-333333333333";
  private static final String HISTORICAL_READ_REQUEST_ID = "44444444-4444-4444-8444-444444444444";
  private static final String ZERO_CAPTURE_REQUEST_ID = "55555555-5555-4555-8555-555555555555";
  private static final String POSITIVE_CAPTURE_REQUEST_ID = "66666666-6666-4666-8666-666666666666";
  private static final String CAPTURE_EVENT_REQUEST_ID = "77777777-7777-4777-8777-777777777777";
  private static final String ACK_OPERATION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String ACK_REQUEST_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  private static final String ACK_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
  private static final String ACK_CAPTURE_DIGEST = "a".repeat(64);

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

  @Test
  void exactWorkloadIdentitiesCarryZeroAndPositiveCaptureReceiptsOverSocketMtls() throws Exception {
    AccountIssuerAuthorityEventProducer producer = mock(AccountIssuerAuthorityEventProducer.class);
    AccountIssuerProjectionReconciliationService captureService =
        mock(AccountIssuerProjectionReconciliationService.class);
    UUID zeroRequestId = UUID.fromString(ZERO_CAPTURE_REQUEST_ID);
    UUID positiveRequestId = UUID.fromString(POSITIVE_CAPTURE_REQUEST_ID);
    IssuerAuthoritySnapshot zeroSource =
        new IssuerAuthoritySnapshot(ISSUER_ID, 1L, 1L, STREAM_KEY, 0L, Optional.empty());
    IssuerGenerationAuthorityEvent positiveEvent = event(CAPTURE_EVENT_REQUEST_ID, 4L, 7L, 9L);
    IssuerAuthoritySnapshot positiveSource =
        new IssuerAuthoritySnapshot(ISSUER_ID, 7L, 9L, STREAM_KEY, 4L, Optional.of(positiveEvent));
    Receipt zeroReceipt =
        captureReceipt(
            UUID.fromString("88888888-8888-4888-8888-888888888888"), zeroRequestId, zeroSource);
    Receipt positiveReceipt =
        captureReceipt(
            UUID.fromString("99999999-9999-4999-8999-999999999999"),
            positiveRequestId,
            positiveSource);
    when(captureService.capture(ISSUER_ID, GAME_SESSION_PEER_URI, zeroRequestId))
        .thenReturn(zeroReceipt);
    when(captureService.capture(ISSUER_ID, GAME_SESSION_PEER_URI, positiveRequestId))
        .thenReturn(positiveReceipt);

    Server server = startServer("account", producer, captureService);
    try (AccountIssuerAuthorityClient client = newClient(server.getPort(), "game-session")) {
      client.init();

      AccountIssuerAuthorityClient.ProjectionCaptureReceipt zero =
          client.captureProjection(ZERO_CAPTURE_REQUEST_ID);
      AccountIssuerAuthorityClient.ProjectionCaptureReceipt positive =
          client.captureProjection(POSITIVE_CAPTURE_REQUEST_ID);

      assertThat(zero.operationUUID()).isEqualTo(zeroReceipt.operationId());
      assertThat(zero.requestUUID()).isEqualTo(zeroRequestId);
      assertThat(zero.issuerId()).isEqualTo(ISSUER_ID);
      assertThat(zero.callerWorkloadIdentity()).isEqualTo(GAME_SESSION_PEER_URI);
      assertThat(zero.projectionKey())
          .isEqualTo("session:game:auth:issuer-generation:v1:" + ISSUER_ID);
      assertThat(zero.requestDigestVersion()).isEqualTo(1);
      assertThat(zero.requestDigest()).isEqualTo(zeroReceipt.requestDigest());
      assertThat(zero.capturedSource().outboxSequence()).isEqualTo("0");
      assertThat(zero.capturedSource().latestEvent()).isEmpty();

      assertThat(positive.operationUUID()).isEqualTo(positiveReceipt.operationId());
      assertThat(positive.requestUUID()).isEqualTo(positiveRequestId);
      assertThat(positive.issuerId()).isEqualTo(ISSUER_ID);
      assertThat(positive.callerWorkloadIdentity()).isEqualTo(GAME_SESSION_PEER_URI);
      assertThat(positive.projectionKey())
          .isEqualTo("session:game:auth:issuer-generation:v1:" + ISSUER_ID);
      assertThat(positive.requestDigestVersion()).isEqualTo(1);
      assertThat(positive.requestDigest()).isEqualTo(positiveReceipt.requestDigest());
      assertThat(positive.capturedSource().issuerAuthGeneration()).isEqualTo("7");
      assertThat(positive.capturedSource().sourceVersion()).isEqualTo("9");
      assertThat(positive.capturedSource().outboxSequence()).isEqualTo("4");
      assertThat(positive.capturedSource().latestEvent()).isPresent();
      assertThat(positive.capturedSource().latestEvent().orElseThrow().canonicalJson())
          .isEqualTo(positiveEvent.canonicalJson());

      verify(captureService).capture(ISSUER_ID, GAME_SESSION_PEER_URI, zeroRequestId);
      verify(captureService).capture(ISSUER_ID, GAME_SESSION_PEER_URI, positiveRequestId);
      verifyNoInteractions(producer);
    } finally {
      stop(server);
    }
  }

  @Test
  void exactGameSessionPeerCarriesTheClosedInstallationAcknowledgmentOverSocketMtls()
      throws Exception {
    String projectionJson = zeroProjectionJson();
    UUID operationId = UUID.fromString(ACK_OPERATION_ID);
    UUID requestId = UUID.fromString(ACK_REQUEST_ID);
    String projectionKey = "session:game:auth:issuer-generation:v1:" + ISSUER_ID;
    String requestDigest =
        Acknowledgment.requestDigestFor(
            ISSUER_ID,
            GAME_SESSION_PEER_URI,
            projectionKey,
            operationId,
            requestId,
            1,
            ACK_CAPTURE_DIGEST,
            projectionJson);
    byte[] projectionBytes = projectionJson.getBytes(StandardCharsets.UTF_8);
    Acknowledgment acknowledgment =
        new Acknowledgment(
            UUID.fromString(ACK_ID),
            operationId,
            requestId,
            ISSUER_ID,
            GAME_SESSION_PEER_URI,
            projectionKey,
            1,
            ACK_CAPTURE_DIGEST,
            IssuerProjectionInstallationAcknowledgmentDigestV1.VERSION,
            requestDigest,
            projectionBytes,
            sha256(projectionBytes));
    AcknowledgeIssuerProjectionForRuntimeRequest request =
        AcknowledgeIssuerProjectionForRuntimeRequest.newBuilder()
            .setIssuerId(ISSUER_ID)
            .setCaptureOperationId(ACK_OPERATION_ID)
            .setCaptureRequestId(ACK_REQUEST_ID)
            .setCaptureRequestDigestVersion(1)
            .setCaptureRequestDigest(ACK_CAPTURE_DIGEST)
            .setProjectionKey(projectionKey)
            .setInstalledProjectionJson(projectionJson)
            .build();
    AccountIssuerAuthorityEventProducer producer = mock(AccountIssuerAuthorityEventProducer.class);
    AccountIssuerProjectionReconciliationService captureService =
        mock(AccountIssuerProjectionReconciliationService.class);
    AccountIssuerProjectionAcknowledgmentService acknowledgmentService =
        mock(AccountIssuerProjectionAcknowledgmentService.class);
    when(acknowledgmentService.acknowledge(
            ISSUER_ID,
            GAME_SESSION_PEER_URI,
            operationId,
            requestId,
            1,
            ACK_CAPTURE_DIGEST,
            projectionJson))
        .thenReturn(acknowledgment);
    Server server = startServer("account", producer, captureService, acknowledgmentService);

    ManagedChannel channel = newRawClientChannel(server.getPort(), "game-session");
    try {
      AcknowledgeIssuerProjectionForRuntimeResponse response =
          IssuerAuthorityServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(5, TimeUnit.SECONDS)
              .acknowledgeIssuerProjectionForRuntime(request);

      assertThat(response.getAllFields()).hasSize(14);
      assertThat(response.getSchemaVersion())
          .isEqualTo("account-auth-issuer-projection-installation-ack/v1");
      assertThat(response.getTargetNamespace()).isEqualTo(NAMESPACE);
      assertThat(response.getAcknowledgmentId()).isEqualTo(ACK_ID);
      assertThat(response.getIssuerId()).isEqualTo(ISSUER_ID);
      assertThat(response.getCallerWorkloadIdentity()).isEqualTo(GAME_SESSION_PEER_URI);
      assertThat(response.getProjectionKey()).isEqualTo(projectionKey);
      assertThat(response.getCaptureOperationId()).isEqualTo(ACK_OPERATION_ID);
      assertThat(response.getCaptureRequestId()).isEqualTo(ACK_REQUEST_ID);
      assertThat(response.getCaptureRequestDigestVersion()).isEqualTo(1);
      assertThat(response.getCaptureRequestDigest()).isEqualTo(ACK_CAPTURE_DIGEST);
      assertThat(response.getRequestDigestVersion())
          .isEqualTo(IssuerProjectionInstallationAcknowledgmentDigestV1.VERSION);
      assertThat(response.getRequestDigest()).isEqualTo(requestDigest);
      assertThat(response.getInstalledProjectionJson()).isEqualTo(projectionJson);
      assertThat(response.getInstalledProjectionSha256())
          .isEqualTo(HexFormat.of().formatHex(sha256(projectionBytes)));
      verify(acknowledgmentService)
          .acknowledge(
              ISSUER_ID,
              GAME_SESSION_PEER_URI,
              operationId,
              requestId,
              1,
              ACK_CAPTURE_DIGEST,
              projectionJson);
      verifyNoInteractions(producer, captureService);
    } finally {
      shutdown(channel);
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

  @ParameterizedTest(name = "capture caller {0}")
  @ValueSource(strings = {"wrong-service", "wrong-namespace"})
  void trustedButWrongGameSessionPeerIsDeniedBeforeCaptureOrSourceAccess(String clientIdentity)
      throws Exception {
    AccountIssuerAuthorityEventProducer producer = mock(AccountIssuerAuthorityEventProducer.class);
    AccountIssuerProjectionReconciliationService captureService =
        mock(AccountIssuerProjectionReconciliationService.class);
    Server server = startServer("account", producer, captureService);
    try (AccountIssuerAuthorityClient client = newClient(server.getPort(), clientIdentity)) {
      client.init();

      assertThatThrownBy(() -> client.captureProjection(ZERO_CAPTURE_REQUEST_ID))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
      verifyNoInteractions(captureService, producer);
    } finally {
      stop(server);
    }
  }

  @ParameterizedTest(name = "acknowledgment caller {0}")
  @ValueSource(strings = {"wrong-service", "wrong-namespace"})
  void trustedButWrongGameSessionPeerIsDeniedBeforeAcknowledgmentOwnerAccess(String clientIdentity)
      throws Exception {
    AccountIssuerAuthorityEventProducer producer = mock(AccountIssuerAuthorityEventProducer.class);
    AccountIssuerProjectionReconciliationService captureService =
        mock(AccountIssuerProjectionReconciliationService.class);
    AccountIssuerProjectionAcknowledgmentService acknowledgmentService =
        mock(AccountIssuerProjectionAcknowledgmentService.class);
    Server server = startServer("account", producer, captureService, acknowledgmentService);
    ManagedChannel channel = newRawClientChannel(server.getPort(), clientIdentity);
    try {
      assertThatThrownBy(
              () ->
                  IssuerAuthorityServiceGrpc.newBlockingStub(channel)
                      .withDeadlineAfter(5, TimeUnit.SECONDS)
                      .acknowledgeIssuerProjectionForRuntime(validAcknowledgmentRequest()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
      verifyNoInteractions(acknowledgmentService, captureService, producer);
    } finally {
      shutdown(channel);
      stop(server);
    }
  }

  @Test
  void missingClientCertificateFailsTheRequiredTlsHandshakeBeforeProducerAccess() throws Exception {
    AccountIssuerAuthorityEventProducer producer = mock(AccountIssuerAuthorityEventProducer.class);
    AccountIssuerProjectionReconciliationService captureService =
        mock(AccountIssuerProjectionReconciliationService.class);
    AccountIssuerProjectionAcknowledgmentService acknowledgmentService =
        mock(AccountIssuerProjectionAcknowledgmentService.class);
    Server server = startServer("account", producer, captureService, acknowledgmentService);
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
      verifyNoInteractions(captureService);
      verifyNoInteractions(acknowledgmentService);
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

  @ParameterizedTest(name = "capture server {0}")
  @ValueSource(strings = {"game-session", "wrong-account-namespace"})
  void wrongAccountServerIdentityCannotReturnATypedCaptureReceipt(String serverIdentity)
      throws Exception {
    AccountIssuerAuthorityEventProducer producer = mock(AccountIssuerAuthorityEventProducer.class);
    AccountIssuerProjectionReconciliationService captureService =
        mock(AccountIssuerProjectionReconciliationService.class);
    UUID requestId = UUID.fromString(ZERO_CAPTURE_REQUEST_ID);
    IssuerAuthoritySnapshot capturedSource =
        new IssuerAuthoritySnapshot(ISSUER_ID, 1L, 1L, STREAM_KEY, 0L, Optional.empty());
    when(captureService.capture(ISSUER_ID, GAME_SESSION_PEER_URI, requestId))
        .thenReturn(
            captureReceipt(
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                requestId,
                capturedSource));
    Server server = startServer(serverIdentity, producer, captureService);
    try (AccountIssuerAuthorityClient client = newClient(server.getPort(), "game-session")) {
      client.init();

      assertThatThrownBy(() -> client.captureProjection(ZERO_CAPTURE_REQUEST_ID))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
      verify(captureService).capture(ISSUER_ID, GAME_SESSION_PEER_URI, requestId);
      verifyNoInteractions(producer);
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

  private static Receipt captureReceipt(
      UUID operationId, UUID requestId, IssuerAuthoritySnapshot capturedSource) {
    String projectionKey = "session:game:auth:issuer-generation:v1:" + ISSUER_ID;
    return new Receipt(
        operationId,
        requestId,
        ISSUER_ID,
        GAME_SESSION_PEER_URI,
        projectionKey,
        1,
        Receipt.requestDigestFor(ISSUER_ID, GAME_SESSION_PEER_URI, projectionKey, requestId),
        capturedSource);
  }

  private static AcknowledgeIssuerProjectionForRuntimeRequest validAcknowledgmentRequest() {
    return AcknowledgeIssuerProjectionForRuntimeRequest.newBuilder()
        .setIssuerId(ISSUER_ID)
        .setCaptureOperationId(ACK_OPERATION_ID)
        .setCaptureRequestId(ACK_REQUEST_ID)
        .setCaptureRequestDigestVersion(1)
        .setCaptureRequestDigest(ACK_CAPTURE_DIGEST)
        .setProjectionKey("session:game:auth:issuer-generation:v1:" + ISSUER_ID)
        .setInstalledProjectionJson(zeroProjectionJson())
        .build();
  }

  private static String zeroProjectionJson() {
    return "{\"schemaVersion\":\"game-session-auth-issuer-projection/v1\","
        + "\"issuerId\":\""
        + ISSUER_ID
        + "\",\"lastAppliedIssuerGeneration\":\"1\","
        + "\"lastAppliedSourceOutboxSequence\":\"0\",\"outboxStreamKey\":\""
        + STREAM_KEY
        + "\",\"appliedAt\":\"2026-10-03T09:30:00Z\","
        + "\"appliedSourceEvidence\":{}}";
  }

  private static byte[] sha256(byte[] value) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256").digest(value);
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private ManagedChannel newRawClientChannel(int port, String clientIdentity) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate(clientIdentity + ".crt").toString());
    tls.setPrivateKey(certificate(clientIdentity + ".key").toString());
    tls.setCaCert(certificate("ca.crt").toString());
    return new GrpcChannelFactory().buildChannel("localhost:" + port, 6565, tls, false);
  }

  private static void shutdown(ManagedChannel channel) throws InterruptedException {
    channel.shutdownNow();
    channel.awaitTermination(2, TimeUnit.SECONDS);
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
    return startServer(
        serverIdentity,
        producer,
        mock(AccountIssuerProjectionReconciliationService.class),
        mock(AccountIssuerProjectionAcknowledgmentService.class));
  }

  private Server startServer(
      String serverIdentity,
      AccountIssuerAuthorityEventProducer producer,
      AccountIssuerProjectionReconciliationService captureService)
      throws Exception {
    return startServer(
        serverIdentity,
        producer,
        captureService,
        mock(AccountIssuerProjectionAcknowledgmentService.class));
  }

  private Server startServer(
      String serverIdentity,
      AccountIssuerAuthorityEventProducer producer,
      AccountIssuerProjectionReconciliationService captureService,
      AccountIssuerProjectionAcknowledgmentService acknowledgmentService)
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
                new AccountIssuerAuthorityGrpcService(
                    producer, captureService, acknowledgmentService, NAMESPACE),
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
