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
import io.grpc.stub.StreamObserver;
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
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto.MembershipBaseline;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxSourceEvidence;
import net.firedevops.firemud.accountservice.service.impl.AccountMembershipAuthorityGrpcService;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamesession.client.AccountMembershipAuthorityClient;
import net.firedevops.firemud.gamesession.support.RuntimeMembershipTestFixtures;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** Physical mutual-TLS proof for the deliberately unwired runtime membership readback. */
class AccountMembershipAuthorityGrpcMtlsCrossServiceTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_UUID = "04ef66b4-c0ad-3d5b-b3b2-0e8510e72001";
  private static final String TENANT_UUID = "04ef66b4-c0ad-3d5b-b3b2-0e8510e72002";
  private static final String OTHER_ACCOUNT_UUID = "04ef66b4-c0ad-3d5b-b3b2-0e8510e72003";
  private static final String OTHER_TENANT_UUID = "04ef66b4-c0ad-3d5b-b3b2-0e8510e72004";
  private static final String REALM_UUID = "04ef66b4-c0ad-3d5b-b3b2-0e8510e72005";
  private static final String PLAYABLE_NAMESPACE_UUID = "04ef66b4-c0ad-3d5b-b3b2-0e8510e72006";
  private static final String GAME_INSTANCE_UUID = "04ef66b4-c0ad-3d5b-b3b2-0e8510e72007";
  private static final String REQUEST_ID = "runtime-membership/read:operation-42";
  private static final String ACCOUNT_PEER_URI =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service";
  private static final String MEMBERSHIP_STREAM =
      MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
          + "membership/"
          + ACCOUNT_UUID
          + "/"
          + TENANT_UUID;

  @TempDir private Path tempDir;

  private final Map<String, Path> copiedCertificates = new HashMap<>();

  @Test
  void exactGameSessionPeerReceivesNonadmittingPositiveMissingBaselineOverSocketMtls()
      throws Exception {
    AccountMembershipAuthorityEventProducer producer =
        mock(AccountMembershipAuthorityEventProducer.class);
    when(producer.readRuntimeMembershipSnapshot(
            UUID.fromString(ACCOUNT_UUID), UUID.fromString(TENANT_UUID)))
        .thenReturn(missingSnapshot(ACCOUNT_UUID, TENANT_UUID));
    Server server = startCandidateServer("account", producer);

    try (AccountMembershipAuthorityClient client = newClient(server.getPort(), "game-session")) {
      client.init();
      GetTenantMembershipForRuntimeResponse response =
          client.getTenantMembershipForRuntime(validPlayerContext());

      assertThat(response.getAuthorityAvailability()).isEqualTo("AVAILABLE");
      assertThat(response.getAccountId()).isEqualTo(ACCOUNT_UUID);
      assertThat(response.getTenantId()).isEqualTo(TENANT_UUID);
      assertThat(response.getRequestAccountId()).isEqualTo(ACCOUNT_UUID);
      assertThat(response.getRequestTenantId()).isEqualTo(TENANT_UUID);
      assertThat(response.getRequestId()).isEqualTo(REQUEST_ID);
      assertThat(response.getMembershipExists()).isFalse();
      assertThat(response.getMembershipLifecycleState()).isEqualTo("MISSING");
      assertThat(response.getGameplayAdmissionAllowed()).isFalse();
      assertThat(response.getRolesList()).isEmpty();
      assertThat(response.getMembershipVersionMap())
          .containsOnlyKeys(TENANT_UUID)
          .containsEntry(TENANT_UUID, "1");
      assertThat(response.getMembershipAuthorityGeneration()).isEqualTo("1");
      assertThat(response.getMembershipBaseline().getMembershipVersionMap())
          .containsOnlyKeys(TENANT_UUID)
          .containsEntry(TENANT_UUID, "1");
      assertThat(response.getOutboxCheckpointsList())
          .anySatisfy(
              checkpoint -> {
                assertThat(checkpoint.getOutboxStreamKey()).isEqualTo(MEMBERSHIP_STREAM);
                assertThat(checkpoint.getOutboxSequence()).isEqualTo("0");
              });
      assertThat(response.getOutboxSourceEvidenceList()).isEmpty();

      verify(producer)
          .readRuntimeMembershipSnapshot(
              UUID.fromString(ACCOUNT_UUID), UUID.fromString(TENANT_UUID));
    } finally {
      stop(server);
    }
  }

  @Test
  void exactGameSessionPeerReceivesBoundActiveMembershipEventOverSocketMtls() throws Exception {
    AccountMembershipAuthorityEventProducer producer =
        mock(AccountMembershipAuthorityEventProducer.class);
    when(producer.readRuntimeMembershipSnapshot(
            UUID.fromString(ACCOUNT_UUID), UUID.fromString(TENANT_UUID)))
        .thenReturn(activeSnapshot());
    Server server = startCandidateServer("account", producer);

    try (AccountMembershipAuthorityClient client = newClient(server.getPort(), "game-session")) {
      client.init();
      GetTenantMembershipForRuntimeResponse response =
          client.getTenantMembershipForRuntime(validPlayerContext());

      assertThat(response.getAccountId()).isEqualTo(ACCOUNT_UUID);
      assertThat(response.getTenantId()).isEqualTo(TENANT_UUID);
      assertThat(response.getRequestAccountId()).isEqualTo(ACCOUNT_UUID);
      assertThat(response.getRequestTenantId()).isEqualTo(TENANT_UUID);
      assertThat(response.getRequestId()).isEqualTo(REQUEST_ID);
      assertThat(response.getMembershipExists()).isTrue();
      assertThat(response.getMembershipLifecycleState()).isEqualTo("ACTIVE");
      assertThat(response.getGameplayAdmissionAllowed()).isTrue();
      assertThat(response.getMembershipVersionMap())
          .containsOnlyKeys(TENANT_UUID)
          .containsEntry(TENANT_UUID, "2");
      assertThat(response.getRolesList()).containsExactly("player");
      assertThat(response.getOutboxCheckpointsList())
          .anySatisfy(
              checkpoint -> {
                assertThat(checkpoint.getOutboxStreamKey()).isEqualTo(MEMBERSHIP_STREAM);
                assertThat(checkpoint.getOutboxSequence()).isEqualTo("1");
              });
      assertThat(response.getOutboxSourceEvidenceList()).hasSize(1);
      var source = response.getOutboxSourceEvidence(0);
      MembershipEvent verifiedEvent =
          MembershipAuthorityEventV1Codec.verify(source.getCanonicalEventJson());
      assertThat(source.getOutboxStreamKey()).isEqualTo(MEMBERSHIP_STREAM);
      assertThat(source.getOutboxSequence()).isEqualTo("1");
      assertThat(verifiedEvent.accountId()).isEqualTo(ACCOUNT_UUID);
      assertThat(verifiedEvent.tenantId()).isEqualTo(TENANT_UUID);
      assertThat(verifiedEvent.eventId()).isEqualTo(source.getEventId());
      assertThat(verifiedEvent.eventDigest()).isEqualTo(source.getEventDigest());

      verify(producer)
          .readRuntimeMembershipSnapshot(
              UUID.fromString(ACCOUNT_UUID), UUID.fromString(TENANT_UUID));
    } finally {
      stop(server);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrong-service", "wrong-namespace"})
  void trustedButUnauthorizedGameSessionPeerIsDeniedBeforeProducerAccess(String clientIdentity)
      throws Exception {
    AccountMembershipAuthorityEventProducer producer =
        mock(AccountMembershipAuthorityEventProducer.class);
    Server server = startCandidateServer("account", producer);

    try (AccountMembershipAuthorityClient client = newClient(server.getPort(), clientIdentity)) {
      client.init();

      Throwable failure =
          catchThrowable(() -> client.getTenantMembershipForRuntime(validPlayerContext()));
      assertStatusFailure(failure, Status.Code.PERMISSION_DENIED);
      verifyNoInteractions(producer);
    } finally {
      stop(server);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"game-session", "wrong-account-namespace"})
  void trustedAccountWithWrongServiceOrNamespaceIsRejectedBeforeContentAcceptance(
      String serverIdentity) throws Exception {
    AccountMembershipAuthorityEventProducer producer =
        mock(AccountMembershipAuthorityEventProducer.class);
    when(producer.readRuntimeMembershipSnapshot(
            UUID.fromString(ACCOUNT_UUID), UUID.fromString(TENANT_UUID)))
        .thenReturn(missingSnapshot(ACCOUNT_UUID, TENANT_UUID));
    Server server = startCandidateServer(serverIdentity, producer);

    try (AccountMembershipAuthorityClient client = newClient(server.getPort(), "game-session")) {
      client.init();

      Throwable failure =
          catchThrowable(() -> client.getTenantMembershipForRuntime(validPlayerContext()));
      assertStatusFailure(failure, Status.Code.UNAUTHENTICATED);
      verify(producer)
          .readRuntimeMembershipSnapshot(
              UUID.fromString(ACCOUNT_UUID), UUID.fromString(TENANT_UUID));
    } finally {
      stop(server);
    }
  }

  @Test
  void missingClientCertificateIsRejectedByTheRequiredTlsHandshake() throws Exception {
    AccountMembershipAuthorityEventProducer producer =
        mock(AccountMembershipAuthorityEventProducer.class);
    Server server = startCandidateServer("account", producer);
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
                    // TLS 1.3 may report the server's certificate rejection on the first read.
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
          .as("the REQUIRED client-certificate handshake must fail")
          .isNotNull();
      assertThat(TlsTestSupport.isTlsHandshakeRejection(handshakeFailure))
          .as("failure must identify certificate rejection: %s", describe(handshakeFailure))
          .isTrue();
      verifyNoInteractions(producer);
    } finally {
      stop(server);
    }
  }

  @Test
  void exactGameSessionPeerCannotSendMalformedTypedContextToTheCandidateHandler() throws Exception {
    AccountMembershipAuthorityEventProducer producer =
        mock(AccountMembershipAuthorityEventProducer.class);
    Server server = startCandidateServer("account", producer);
    RawClient rawClient = newRawClient(server.getPort(), "game-session");

    try {
      PlayerExecutionContext malformedContext =
          validPlayerContext().toBuilder().setSessionId("050").build();
      GetTenantMembershipForRuntimeRequest request =
          GetTenantMembershipForRuntimeRequest.newBuilder()
              .setPlayerContext(malformedContext)
              .build();

      assertThatThrownBy(
              () ->
                  rawClient
                      .stub()
                      .withDeadlineAfter(5, TimeUnit.SECONDS)
                      .getTenantMembershipForRuntime(request))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.INVALID_ARGUMENT));
      verifyNoInteractions(producer);
    } finally {
      stop(rawClient.channel());
      stop(server);
    }
  }

  @Test
  void exactPeerCannotReceiveCrossBoundProducerSnapshot() throws Exception {
    AccountMembershipAuthorityEventProducer producer =
        mock(AccountMembershipAuthorityEventProducer.class);
    when(producer.readRuntimeMembershipSnapshot(
            UUID.fromString(ACCOUNT_UUID), UUID.fromString(TENANT_UUID)))
        .thenReturn(missingSnapshot(OTHER_ACCOUNT_UUID, OTHER_TENANT_UUID));
    Server server = startCandidateServer("account", producer);

    try (AccountMembershipAuthorityClient client = newClient(server.getPort(), "game-session")) {
      client.init();

      Throwable failure =
          catchThrowable(() -> client.getTenantMembershipForRuntime(validPlayerContext()));
      assertStatusFailure(failure, Status.Code.FAILED_PRECONDITION);
      verify(producer)
          .readRuntimeMembershipSnapshot(
              UUID.fromString(ACCOUNT_UUID), UUID.fromString(TENANT_UUID));
    } finally {
      stop(server);
    }
  }

  @Test
  void exactAccountPeerCannotDeliverCrossBoundRecipientEchoes() throws Exception {
    GetTenantMembershipForRuntimeResponse crossBoundResponse =
        currentActiveResponse(validPlayerContext()).toBuilder()
            .setRequestAccountId(OTHER_ACCOUNT_UUID)
            .build();
    Server server = startResponseServer("account", ignored -> crossBoundResponse);

    try (AccountMembershipAuthorityClient client = newClient(server.getPort(), "game-session")) {
      client.init();

      assertThatThrownBy(() -> client.getTenantMembershipForRuntime(validPlayerContext()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("availability or exact recipient/request echoes changed");
    } finally {
      stop(server);
    }
  }

  @Test
  void exactAccountPeerCannotDeliverMalformedCurrentMembershipCarrier() throws Exception {
    GetTenantMembershipForRuntimeResponse malformedResponse =
        currentActiveResponse(validPlayerContext()).toBuilder().clearOutboxSourceEvidence().build();
    Server server = startResponseServer("account", ignored -> malformedResponse);

    try (AccountMembershipAuthorityClient client = newClient(server.getPort(), "game-session")) {
      client.init();

      assertThatThrownBy(() -> client.getTenantMembershipForRuntime(validPlayerContext()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("content evidence is incomplete or contradictory")
          .satisfies(
              failure ->
                  assertThat(failure.getCause())
                      .isInstanceOf(IllegalArgumentException.class)
                      .hasMessageContaining("sourceEvidence"));
    } finally {
      stop(server);
    }
  }

  private AccountMembershipAuthorityClient newClient(int port, String clientIdentity)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + port);
    return new AccountMembershipAuthorityClient(
        endpoints,
        tlsProperties(clientIdentity),
        new GrpcChannelFactory(),
        BlockingGrpcStubCustomizer.noop(),
        NAMESPACE);
  }

  private RawClient newRawClient(int port, String clientIdentity) throws Exception {
    CommonGrpcClientProperties tls = tlsProperties(clientIdentity);
    ManagedChannel channel =
        new GrpcChannelFactory().buildChannel("localhost:" + port, 6565, tls, false);
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        AccountServiceGrpc.newBlockingStub(channel)
            .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(ACCOUNT_PEER_URI));
    return new RawClient(channel, stub);
  }

  private CommonGrpcClientProperties tlsProperties(String clientIdentity) throws IOException {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate(clientIdentity + ".crt").toString());
    tls.setPrivateKey(certificate(clientIdentity + ".key").toString());
    tls.setCaCert(certificate("ca.crt").toString());
    return tls;
  }

  private Server startCandidateServer(
      String serverIdentity, AccountMembershipAuthorityEventProducer producer) throws Exception {
    return startServer(
        serverIdentity,
        new AccountMembershipAuthorityGrpcService(
            producer, new TestTransactionManager(), NAMESPACE));
  }

  private Server startResponseServer(String serverIdentity, ResponseFactory responseFactory)
      throws Exception {
    AccountServiceGrpc.AccountServiceImplBase responseService =
        new AccountServiceGrpc.AccountServiceImplBase() {
          @Override
          public void getTenantMembershipForRuntime(
              GetTenantMembershipForRuntimeRequest request,
              StreamObserver<GetTenantMembershipForRuntimeResponse> responseObserver) {
            responseObserver.onNext(responseFactory.response(request.getPlayerContext()));
            responseObserver.onCompleted();
          }
        };
    return startServer(serverIdentity, responseService);
  }

  private Server startServer(
      String serverIdentity, AccountServiceGrpc.AccountServiceImplBase service) throws Exception {
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.forServer(
                    certificate(serverIdentity + ".crt").toFile(),
                    certificate(serverIdentity + ".key").toFile())
                .trustManager(certificate("ca.crt").toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
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
        AccountMembershipAuthorityGrpcMtlsCrossServiceTest.class.getResourceAsStream(
            resourcePath)) {
      if (resource == null) {
        throw new IllegalStateException("Missing workload mTLS test fixture: " + resourcePath);
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

  private static RuntimeMembershipSnapshotDto missingSnapshot(
      String accountUuid, String tenantUuid) {
    return new RuntimeMembershipSnapshotDto(
        accountUuid,
        tenantUuid,
        accountUuid,
        tenantUuid,
        false,
        false,
        new MembershipBaseline("MISSING", Map.of(tenantUuid, "1"), "1"),
        List.of(),
        new AuthorityTuple(
            "1",
            "1",
            Map.of(tenantUuid, "1"),
            Map.of(tenantUuid, "1"),
            List.of(),
            Optional.empty(),
            Optional.empty()),
        "7",
        Instant.now(),
        checkpoints(accountUuid, tenantUuid, "0"),
        List.of(),
        null);
  }

  private static RuntimeMembershipSnapshotDto activeSnapshot() {
    MembershipEvent event = activeMembershipEvent();
    return new RuntimeMembershipSnapshotDto(
        ACCOUNT_UUID,
        TENANT_UUID,
        ACCOUNT_UUID,
        TENANT_UUID,
        true,
        true,
        new MembershipBaseline("ACTIVE", Map.of(TENANT_UUID, "2"), "1"),
        List.of("player"),
        new AuthorityTuple(
            "1",
            "1",
            Map.of(TENANT_UUID, "1"),
            Map.of(TENANT_UUID, "1"),
            List.of(),
            Optional.empty(),
            Optional.empty()),
        "7",
        Instant.now(),
        checkpoints(ACCOUNT_UUID, TENANT_UUID, "1"),
        List.of(
            new OutboxSourceEvidence(
                event.outboxStreamKey(),
                event.outboxSequence(),
                event.eventId(),
                event.eventDigest(),
                event.canonicalJson())),
        event);
  }

  private static List<OutboxCheckpointEntry> checkpoints(
      String accountUuid, String tenantUuid, String membershipSequence) {
    return List.of(
        new OutboxCheckpointEntry(
            MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "account/" + accountUuid, "0"),
        new OutboxCheckpointEntry(
            MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
                + "issuer/"
                + AccountServiceImpl.ACCOUNT_JWT_ISSUER,
            "0"),
        new OutboxCheckpointEntry(
            MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
                + "membership/"
                + accountUuid
                + "/"
                + tenantUuid,
            membershipSequence),
        new OutboxCheckpointEntry(
            MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "tenant/" + tenantUuid, "0"));
  }

  private static MembershipEvent activeMembershipEvent() {
    Map<String, Object> preimage = new LinkedHashMap<>();
    preimage.put("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION);
    preimage.put("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE);
    preimage.put("eventId", "04ef66b4-c0ad-3d5b-b3b2-0e8510e72008");
    preimage.put("requestId", "membership-join/committed-operation-1");
    preimage.put("outboxStreamKey", MEMBERSHIP_STREAM);
    preimage.put("outboxSequence", "1");
    preimage.put("sourceScope", "membership/" + ACCOUNT_UUID + "/" + TENANT_UUID);
    preimage.put("accountId", ACCOUNT_UUID);
    preimage.put("tenantId", TENANT_UUID);
    preimage.put("membershipExists", true);
    preimage.put("membershipLifecycleState", "ACTIVE");
    preimage.put("membershipVersion", Map.of(TENANT_UUID, "2"));
    preimage.put("membershipAuthorityGeneration", "1");
    preimage.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration",
            "1",
            "accountAuthorityGeneration",
            "1",
            "tenantAuthorityGeneration",
            Map.of(TENANT_UUID, "1"),
            "membershipAuthorityGeneration",
            Map.of(TENANT_UUID, "1"),
            "privateRealmGrantVersions",
            List.of()));
    preimage.put("issuanceFence", "7");
    preimage.put("roles", List.of("player"));
    preimage.put("gameplayAdmissionAllowed", true);
    preimage.put("callerBoundAuthorityInvalidated", false);
    return MembershipAuthorityEventV1Codec.seal(preimage);
  }

  private static GetTenantMembershipForRuntimeResponse currentActiveResponse(
      PlayerExecutionContext request) {
    return RuntimeMembershipTestFixtures.active(ACCOUNT_UUID, 1L, TENANT_UUID, "2").toBuilder()
        .setRequestAccountId(request.getAccountId())
        .setRequestTenantId(request.getTenantId())
        .setRequestId(request.getRequestId())
        .build();
  }

  private static PlayerExecutionContext validPlayerContext() {
    return PlayerExecutionContext.newBuilder()
        .setAccountId(ACCOUNT_UUID)
        .setTenantId(TENANT_UUID)
        .setRealmId(REALM_UUID)
        .setPlayableStateNamespaceId(PLAYABLE_NAMESPACE_UUID)
        .setPlayableStateScope("SHARED")
        .setGameInstanceId(GAME_INSTANCE_UUID)
        .setSessionId("50")
        .setCharacterId("9")
        .setRequestId(REQUEST_ID)
        .build();
  }

  private static void assertStatusFailure(Throwable failure, Status.Code code) {
    assertThat(failure).isInstanceOf(IllegalStateException.class);
    assertThat(failure.getCause()).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(code);
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

  private static void stop(Server server) throws InterruptedException {
    server.shutdownNow();
    server.awaitTermination(2, TimeUnit.SECONDS);
  }

  private static void stop(ManagedChannel channel) throws InterruptedException {
    channel.shutdownNow();
    channel.awaitTermination(2, TimeUnit.SECONDS);
  }

  @FunctionalInterface
  private interface ResponseFactory {
    GetTenantMembershipForRuntimeResponse response(PlayerExecutionContext request);
  }

  private record RawClient(
      ManagedChannel channel, AccountServiceGrpc.AccountServiceBlockingStub stub) {}

  private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
      return false;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
