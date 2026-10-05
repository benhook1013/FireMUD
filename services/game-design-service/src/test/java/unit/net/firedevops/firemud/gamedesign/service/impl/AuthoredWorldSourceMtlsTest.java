package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
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
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameSessionTenantAssociationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameSessionTenantAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameTenantIdentityRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityRequest;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import net.firedevops.firemud.test.TlsTestSupport;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * Physical socket proof for Game Design's authored-world source read boundary.
 *
 * <p>The production gRPC service, peer-identity interceptor, JWT interceptor, and shared source
 * client run over ephemeral mTLS sockets. The owner repository is mocked and returns a complete,
 * digest-valid schema-v1 receipt; this suite does not prove source registration persistence, normal
 * source creation, World intake, local association, publication, or runtime activation.
 */
class AuthoredWorldSourceMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String SOURCE_METHOD =
      "gamedesign.v1.TenantIdentityService/ResolveAuthoredWorldSource";
  private static final String AUTHORIZATION = "authorization";
  private static final String JWT_SECRET = "test-secret-key-test-secret-key-32-bytes";
  private static final UUID REGISTRATION_REQUEST_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID TENANT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final AtomicLong CERTIFICATE_SERIAL = new AtomicLong(1L);
  private static final Set<String> TENANT_IDENTITY_JWT_BYPASS_METHODS =
      Set.of(
          "gamedesign.v1.TenantIdentityService/ResolveLegacyAccountTenantAssociation",
          "gamedesign.v1.TenantIdentityService/ResolveFreshTenantCreation",
          "gamedesign.v1.TenantIdentityService/ResolveRuntimeTenantIdentity",
          SOURCE_METHOD,
          "gamedesign.v1.TenantIdentityService/ResolveLegacyGameSessionTenantAssociation");
  private static final AuthoredWorldSourceEvidence SOURCE_EVIDENCE = sourceEvidence();
  private static final AuthoredWorldSourceGrpcCodec.ReadRequest READ_REQUEST =
      new AuthoredWorldSourceGrpcCodec.ReadRequest(
          NAMESPACE, READ_REQUEST_ID, SOURCE_OPERATION_ID, TENANT_ID, "harbor-world");
  private static TestPki pki;

  private GameRepository gameRepository;
  private TenantAssociationMigrationService associationService;
  private GameTenantCreationRepository creationRepository;
  private GameAuthoredWorldSourceRepository authoredWorldRepository;
  private GameSessionTenantAssociationRepository gameSessionAssociationRepository;
  private Server server;
  private AtomicReference<ResponseMutation> responseMutation;

  @BeforeAll
  static void generateEphemeralPki() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPair caKeyPair = newRsaKeyPair();
    X500Name caName = new X500Name("CN=Authored Source Test CA, O=FireMUD Test");
    X509Certificate caCertificate =
        issueCertificate(
            caName, caKeyPair.getPublic(), caName, caKeyPair.getPrivate(), true, null, false);
    pki =
        new TestPki(
            caCertificate,
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "game-design-service",
                "spiffe://firemud/ns/test/sa/game-design-service",
                true),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "wrong-game-design-server-workload",
                "spiffe://firemud/ns/test/sa/world-management-service",
                true),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "wrong-game-design-server-namespace",
                "spiffe://firemud/ns/other-test/sa/game-design-service",
                true),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "world-management-service",
                "spiffe://firemud/ns/test/sa/world-management-service",
                false),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "game-session-service",
                "spiffe://firemud/ns/test/sa/game-session-service",
                false),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "account-service",
                "spiffe://firemud/ns/test/sa/account-service",
                false),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "cross-namespace-game-session-service",
                "spiffe://firemud/ns/other-test/sa/game-session-service",
                false),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "trusted-certificate-without-workload-uri",
                null,
                false));
  }

  @BeforeEach
  void startPhysicalReceiver() throws Exception {
    gameRepository = mock(GameRepository.class);
    associationService = mock(TenantAssociationMigrationService.class);
    creationRepository = mock(GameTenantCreationRepository.class);
    authoredWorldRepository = mock(GameAuthoredWorldSourceRepository.class);
    gameSessionAssociationRepository = mock(GameSessionTenantAssociationRepository.class);
    when(authoredWorldRepository.read(SOURCE_OPERATION_ID, TENANT_ID, "harbor-world", NAMESPACE))
        .thenReturn(Optional.of(SOURCE_EVIDENCE));
    responseMutation = new AtomicReference<>(ResponseMutation.NONE);
    server = startReceiver(pki.gameDesignServerCertificate());
  }

  @AfterEach
  void stopPhysicalReceiver() throws Exception {
    if (server != null) {
      stopServer(server);
      server = null;
    }
  }

  @Test
  void exactWorldAndGameSessionReadersResolveCompleteSourceReceiptThroughSharedClient(
      @TempDir Path directory) throws Exception {
    for (TestCertificate clientCertificate :
        List.of(pki.worldManagementCertificate(), pki.gameSessionCertificate())) {
      Path clientDirectory = directory.resolve(UUID.randomUUID().toString());
      AuthoredWorldSourceClient client =
          newSharedClient(clientDirectory, server, clientCertificate);
      try {
        client.init();

        assertThat(client.read(READ_REQUEST)).isEqualTo(SOURCE_EVIDENCE);
      } finally {
        client.close();
      }
    }

    verify(authoredWorldRepository, times(2))
        .read(SOURCE_OPERATION_ID, TENANT_ID, "harbor-world", NAMESPACE);
  }

  @Test
  void wrongServiceNamespaceAndSharedCertificatesAreDeniedBeforeOwnerRead() throws Exception {
    for (TestCertificate clientCertificate :
        List.of(
            pki.wrongServiceCertificate(),
            pki.wrongNamespaceCertificate(),
            pki.noWorkloadUriCertificate())) {
      ManagedChannel channel = channel(server, clientCertificate, jwtToken());
      try {
        assertStatus(
            () -> sourceStub(channel).resolveAuthoredWorldSource(wireReadRequest()),
            Status.Code.PERMISSION_DENIED);
      } finally {
        stopChannel(channel);
      }
    }

    verifyNoInteractions(authoredWorldRepository);
  }

  @Test
  void missingClientCertificateFailsWithTlsHandshakeEvidenceBeforeOwnerRead() throws Exception {
    ManagedChannel channel = channel(server, null, null);
    try {
      assertTlsDenied(
          () -> sourceStub(channel).resolveAuthoredWorldSource(wireReadRequest()), SOURCE_METHOD);
    } finally {
      stopChannel(channel);
    }

    verifyNoInteractions(authoredWorldRepository);
  }

  @Test
  void jwtOnlyCallerWithoutClientCertificateFailsWithTlsHandshakeEvidence() throws Exception {
    ManagedChannel channel = channel(server, null, jwtToken());
    try {
      assertTlsDenied(
          () -> sourceStub(channel).resolveAuthoredWorldSource(wireReadRequest()), SOURCE_METHOD);
    } finally {
      stopChannel(channel);
    }

    verifyNoInteractions(authoredWorldRepository);
  }

  @Test
  void validJwtWithTrustedCertificateButNoWorkloadUriIsDeniedByPeerGuard() throws Exception {
    ManagedChannel channel = channel(server, pki.noWorkloadUriCertificate(), jwtToken());
    try {
      assertStatus(
          () -> sourceStub(channel).resolveAuthoredWorldSource(wireReadRequest()),
          Status.Code.PERMISSION_DENIED);
    } finally {
      stopChannel(channel);
    }

    verifyNoInteractions(authoredWorldRepository);
  }

  @Test
  void sharedClientRefusesWrongGameDesignServerWorkloadAndNamespace(@TempDir Path directory)
      throws Exception {
    for (TestCertificate serverCertificate :
        List.of(pki.wrongServerWorkloadCertificate(), pki.wrongServerNamespaceCertificate())) {
      InboundRpcCapture inboundRpcCapture = new InboundRpcCapture();
      Server wrongIdentityServer = startReceiver(serverCertificate, inboundRpcCapture);
      AuthoredWorldSourceClient client =
          newSharedClient(
              directory.resolve(UUID.randomUUID().toString()),
              wrongIdentityServer,
              pki.worldManagementCertificate());
      try {
        client.init();
        assertServerPeerRefused(() -> client.read(READ_REQUEST));
        inboundRpcCapture.assertNoInboundRequests();
      } finally {
        client.close();
        stopServer(wrongIdentityServer);
      }
    }

    verifyNoInteractions(authoredWorldRepository);
  }

  @Test
  void sharedClientRejectsChangedRequestEchoSourceDigestAndUnknownFieldsOverSocket(
      @TempDir Path directory) throws Exception {
    AuthoredWorldSourceClient client =
        newSharedClient(directory, server, pki.worldManagementCertificate());
    try {
      client.init();

      responseMutation.set(ResponseMutation.CHANGE_READ_REQUEST_ID);
      assertClientCodecRejected(() -> client.read(READ_REQUEST), "read request echo changed");

      responseMutation.set(ResponseMutation.CHANGE_SOURCE_DISPLAY_NAME);
      assertClientCodecRejected(
          () -> client.read(READ_REQUEST), "Request digest does not match the registration tuple");

      responseMutation.set(ResponseMutation.CHANGE_EVIDENCE_DIGEST);
      assertClientCodecRejected(
          () -> client.read(READ_REQUEST), "Evidence digest does not match the immutable tuple");

      responseMutation.set(ResponseMutation.ADD_UNKNOWN_FIELD);
      assertClientCodecRejected(
          () -> client.read(READ_REQUEST), "Authored-world source response contains unsupported");
    } finally {
      client.close();
    }

    verify(authoredWorldRepository, times(4))
        .read(SOURCE_OPERATION_ID, TENANT_ID, "harbor-world", NAMESPACE);
  }

  @Test
  void unknownReadRequestFieldsAreRejectedByProductionServiceBeforeOwnerRead() throws Exception {
    ManagedChannel channel = channel(server, pki.worldManagementCertificate(), jwtToken());
    ResolveAuthoredWorldSourceRequest unknownFieldRequest =
        wireReadRequest().toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build();
    try {
      assertStatus(
          () -> sourceStub(channel).resolveAuthoredWorldSource(unknownFieldRequest),
          Status.Code.INVALID_ARGUMENT);
    } finally {
      stopChannel(channel);
    }

    verifyNoInteractions(authoredWorldRepository);
  }

  @Test
  void productionYamlAndSocketGuardsKeepWorldAccessLimitedToExactSourceMethod() throws Exception {
    for (String profile : List.of("application.yml", "application-prod.yml")) {
      Set<String> tenantIdentityBypassMethods =
          publicMethods(profile).stream()
              .filter(method -> method.startsWith("gamedesign.v1.TenantIdentityService/"))
              .collect(java.util.stream.Collectors.toSet());
      assertThat(tenantIdentityBypassMethods)
          .containsExactlyInAnyOrderElementsOf(TENANT_IDENTITY_JWT_BYPASS_METHODS);
    }

    ManagedChannel channel = channel(server, pki.worldManagementCertificate(), jwtToken());
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = sourceStub(channel);
    try {
      // This legacy method requires a valid JWT; the fixture's empty accountId is rejected before
      // the exact-workload peer guard can run.
      assertStatus(
          () ->
              stub.resolveLegacyGameTenantIdentity(
                  ResolveLegacyGameTenantIdentityRequest.newBuilder()
                      .setLegacyGameTenantId("legacy-game-17")
                      .build()),
          Status.Code.UNAUTHENTICATED);
      assertStatus(
          () ->
              stub.resolveLegacyAccountTenantAssociation(
                  ResolveLegacyAccountTenantAssociationRequest.newBuilder()
                      .setLegacyAccountTenantId(17L)
                      .build()),
          Status.Code.PERMISSION_DENIED);
      assertStatus(
          () ->
              stub.resolveFreshTenantCreation(
                  ResolveFreshTenantCreationRequest.getDefaultInstance()),
          Status.Code.PERMISSION_DENIED);
      assertStatus(
          () ->
              stub.resolveRuntimeTenantIdentity(
                  ResolveRuntimeTenantIdentityRequest.getDefaultInstance()),
          Status.Code.PERMISSION_DENIED);
      assertStatus(
          () ->
              stub.resolveLegacyGameSessionTenantAssociation(
                  ResolveLegacyGameSessionTenantAssociationRequest.getDefaultInstance()),
          Status.Code.PERMISSION_DENIED);
    } finally {
      stopChannel(channel);
    }

    verifyNoInteractions(
        gameRepository,
        associationService,
        creationRepository,
        authoredWorldRepository,
        gameSessionAssociationRepository);
  }

  private Server startReceiver(TestCertificate serverCertificate) throws Exception {
    return startReceiver(serverCertificate, null);
  }

  private Server startReceiver(
      TestCertificate serverCertificate, InboundRpcCapture inboundRpcCapture) throws Exception {
    TenantIdentityGrpcService service =
        new TenantIdentityGrpcService(
            gameRepository,
            associationService,
            creationRepository,
            authoredWorldRepository,
            gameSessionAssociationRepository,
            NAMESPACE);
    Set<String> unauthenticatedMethods = publicMethods("application.yml");
    ServerInterceptor[] interceptors =
        inboundRpcCapture == null
            ? new ServerInterceptor[] {
              responseMutationInterceptor(responseMutation),
              new AuthTokenInterceptor(new JwtUtil(JWT_SECRET, 60_000L), unauthenticatedMethods),
              new GrpcPeerIdentityInterceptor()
            }
            : new ServerInterceptor[] {
              inboundRpcCapture,
              responseMutationInterceptor(responseMutation),
              new AuthTokenInterceptor(new JwtUtil(JWT_SECRET, 60_000L), unauthenticatedMethods),
              new GrpcPeerIdentityInterceptor()
            };
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(
                        serverCertificate.privateKey(), serverCertificate.certificate()))
                .trustManager(pki.caCertificate())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(ServerInterceptors.intercept(service, interceptors))
        .build()
        .start();
  }

  private AuthoredWorldSourceClient newSharedClient(
      Path directory, Server receiver, TestCertificate clientCertificate) throws Exception {
    Files.createDirectories(directory);
    Path caCertificate =
        writePem(directory.resolve("test-ca.crt"), "CERTIFICATE", pki.caCertificate().getEncoded());
    Path certificate =
        writePem(
            directory.resolve("client.crt"),
            "CERTIFICATE",
            clientCertificate.certificate().getEncoded());
    Path privateKey =
        writePem(
            directory.resolve("client.key"),
            "PRIVATE KEY",
            clientCertificate.privateKey().getEncoded());

    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate.toString());
    tls.setPrivateKey(privateKey.toString());
    tls.setCaCert(caCertificate.toString());
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("localhost:" + receiver.getPort());
    return new AuthoredWorldSourceClient(endpoints, tls, new GrpcChannelFactory(), NAMESPACE);
  }

  private ManagedChannel channel(Server receiver, TestCertificate clientCertificate, String jwt)
      throws Exception {
    NettyChannelBuilder builder =
        NettyChannelBuilder.forAddress(new InetSocketAddress("127.0.0.1", receiver.getPort()))
            .sslContext(clientSslContext(clientCertificate));
    if (jwt != null) {
      Metadata headers = new Metadata();
      headers.put(
          Metadata.Key.of(AUTHORIZATION, Metadata.ASCII_STRING_MARSHALLER), "Bearer " + jwt);
      builder.intercept(MetadataUtils.newAttachHeadersInterceptor(headers));
    }
    return builder.build();
  }

  private static io.grpc.netty.shaded.io.netty.handler.ssl.SslContext clientSslContext(
      TestCertificate clientCertificate) throws Exception {
    var builder = GrpcSslContexts.forClient().trustManager(pki.caCertificate());
    if (clientCertificate != null) {
      builder.keyManager(clientCertificate.privateKey(), clientCertificate.certificate());
    }
    return builder.build();
  }

  private static TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub sourceStub(
      ManagedChannel channel) {
    return TenantIdentityServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(3, TimeUnit.SECONDS);
  }

  private static ResolveAuthoredWorldSourceRequest wireReadRequest() {
    return AuthoredWorldSourceGrpcCodec.toReadRequest(READ_REQUEST);
  }

  private String jwtToken() {
    JwtUtil jwtUtil = new JwtUtil(JWT_SECRET, 60_000L);
    return jwtUtil.generateToken(
        "service:world-management-service",
        Map.of(
            "accountId",
            "",
            "globalRoles",
            List.of(),
            "scopedRoles",
            Map.of(),
            "internalService",
            true,
            "serviceName",
            "world-management-service"));
  }

  private static ServerInterceptor responseMutationInterceptor(
      AtomicReference<ResponseMutation> pendingMutation) {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        ServerCall<ReqT, RespT> forwardingCall =
            new SimpleForwardingServerCall<>(call) {
              @Override
              public void sendMessage(RespT message) {
                super.sendMessage(
                    mutateResponse(message, pendingMutation.getAndSet(ResponseMutation.NONE)));
              }
            };
        return next.startCall(forwardingCall, headers);
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static <Response> Response mutateResponse(Response response, ResponseMutation mutation) {
    if (mutation == ResponseMutation.NONE) {
      return response;
    }
    if (!(response instanceof ResolveAuthoredWorldSourceResponse source)) {
      throw new IllegalStateException("Response mutation does not match the source read RPC");
    }
    return (Response)
        switch (mutation) {
          case CHANGE_READ_REQUEST_ID ->
              source.toBuilder().setRequestId("55555555-5555-4555-8555-555555555555").build();
          case CHANGE_SOURCE_DISPLAY_NAME ->
              source.toBuilder().setWorldDisplayName("Changed display name").build();
          case CHANGE_EVIDENCE_DIGEST ->
              source.toBuilder().setEvidenceDigest("sha256:" + "f".repeat(64)).build();
          case ADD_UNKNOWN_FIELD ->
              source.toBuilder()
                  .setUnknownFields(
                      UnknownFieldSet.newBuilder()
                          .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                          .build())
                  .build();
          case NONE -> source;
        };
  }

  private static void assertStatus(Runnable call, Status.Code expectedCode) {
    Throwable failure = catchFailure(call);
    assertThat(failure)
        .as("RPC should fail with %s: %s", expectedCode, diagnostics(failure))
        .isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode())
        .as("RPC denial evidence: %s", diagnostics(failure))
        .isEqualTo(expectedCode);
  }

  private static void assertServerPeerRefused(Runnable call) {
    Throwable failure = catchFailure(call);
    assertThat(failure).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(Status.fromThrowable(failure).getDescription())
        .contains("exact authenticated server workload identity");
  }

  private static final class InboundRpcCapture implements ServerInterceptor {
    private final AtomicInteger rpcCount = new AtomicInteger();
    private final AtomicInteger requestMessageCount = new AtomicInteger();
    private final AtomicReference<String> methodName = new AtomicReference<>();
    private final AtomicReference<Metadata> requestMetadata = new AtomicReference<>();

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      rpcCount.incrementAndGet();
      methodName.set(call.getMethodDescriptor().getFullMethodName());
      requestMetadata.set(headers);
      ServerCall.Listener<ReqT> listener = next.startCall(call, headers);
      return new SimpleForwardingServerCallListener<>(listener) {
        @Override
        public void onMessage(ReqT message) {
          requestMessageCount.incrementAndGet();
          super.onMessage(message);
        }
      };
    }

    private void assertNoInboundRequests() {
      assertThat(rpcCount.get()).isZero();
      assertThat(requestMessageCount.get()).isZero();
      assertThat(methodName.get()).isNull();
      assertThat(requestMetadata.get()).isNull();
    }
  }

  private static void assertClientCodecRejected(Runnable call, String expectedEvidence) {
    Throwable failure = catchFailure(call);
    assertThat(failure).isInstanceOf(IllegalStateException.class);
    assertThat(diagnostics(failure)).contains(expectedEvidence);
  }

  private static void assertTlsDenied(Runnable call, String method) {
    Throwable failure = catchFailure(call);
    assertThat(failure)
        .as(
            "%s must fail with client-certificate handshake evidence: %s",
            method, diagnostics(failure))
        .isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode())
        .as(
            "%s must fail at TLS rather than by application timeout: %s",
            method, diagnostics(failure))
        .isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(TlsTestSupport.isTlsHandshakeRejection(failure))
        .as(
            "%s cause chain must identify client-certificate rejection: %s",
            method, diagnostics(failure))
        .isTrue();
  }

  private static Set<String> publicMethods(String resourceName) throws IOException {
    ConfigurableEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    for (var propertySource :
        new YamlPropertySourceLoader()
            .load(resourceName, new FileSystemResource("src/main/resources/" + resourceName))) {
      environment.getPropertySources().addFirst(propertySource);
    }
    return Set.copyOf(
        Binder.get(environment)
            .bind("firemud.auth.grpc.public-methods", Bindable.listOf(String.class))
            .orElse(List.of()));
  }

  private static AuthoredWorldSourceEvidence sourceEvidence() {
    String tenantSlug = "firemud";
    String worldSlug = "harbor-world";
    String worldDisplayName = "Harbor World";
    String sourceGameTenantKey = "legacy-game-17";
    String provenanceKind = "NEW_GAME_ROW";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, REGISTRATION_REQUEST_ID, TENANT_ID, tenantSlug, worldSlug, worldDisplayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            REGISTRATION_REQUEST_ID,
            SOURCE_OPERATION_ID,
            requestDigest,
            TENANT_ID,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            17L,
            sourceGameTenantKey,
            provenanceKind);
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        REGISTRATION_REQUEST_ID,
        SOURCE_OPERATION_ID,
        requestDigest,
        TENANT_ID,
        tenantSlug,
        worldSlug,
        worldDisplayName,
        17L,
        sourceGameTenantKey,
        provenanceKind,
        evidenceDigest);
  }

  private static Path writePem(Path path, String label, byte[] encoded) throws IOException {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
    return Files.writeString(
        path,
        "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n",
        StandardCharsets.US_ASCII);
  }

  private static void stopServer(Server server) throws InterruptedException {
    server.shutdownNow();
    assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
  }

  private static void stopChannel(ManagedChannel channel) throws InterruptedException {
    channel.shutdownNow();
    assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
  }

  private static Throwable catchFailure(Runnable action) {
    try {
      action.run();
      return null;
    } catch (Throwable throwable) {
      return throwable;
    }
  }

  private static String diagnostics(Throwable throwable) {
    if (throwable == null) {
      return "RPC unexpectedly completed";
    }
    StringBuilder result = new StringBuilder();
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      if (!result.isEmpty()) {
        result.append(" <- ");
      }
      result.append(current.getClass().getName()).append(": ").append(current.getMessage());
    }
    return result.toString();
  }

  private static TestCertificate issueLeaf(
      X500Name caName,
      PrivateKey caPrivateKey,
      String commonName,
      String workloadUri,
      boolean serverCertificate)
      throws Exception {
    KeyPair keyPair = newRsaKeyPair();
    X500Name subject = new X500Name("CN=" + commonName + ", O=FireMUD Test");
    GeneralName[] subjectAltNames =
        workloadUri == null
            ? new GeneralName[] {
              new GeneralName(GeneralName.dNSName, "localhost"),
              new GeneralName(GeneralName.iPAddress, "127.0.0.1")
            }
            : new GeneralName[] {
              new GeneralName(GeneralName.uniformResourceIdentifier, workloadUri),
              new GeneralName(GeneralName.dNSName, "localhost"),
              new GeneralName(GeneralName.iPAddress, "127.0.0.1")
            };
    X509Certificate certificate =
        issueCertificate(
            subject,
            keyPair.getPublic(),
            caName,
            caPrivateKey,
            false,
            new GeneralNames(subjectAltNames),
            serverCertificate);
    return new TestCertificate(keyPair.getPrivate(), certificate);
  }

  private static X509Certificate issueCertificate(
      X500Name subject,
      java.security.PublicKey publicKey,
      X500Name issuer,
      PrivateKey issuerPrivateKey,
      boolean ca,
      GeneralNames subjectAltNames,
      boolean serverCertificate)
      throws Exception {
    Instant now = Instant.now().minusSeconds(60L);
    JcaX509v3CertificateBuilder builder =
        new JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(CERTIFICATE_SERIAL.getAndIncrement()),
            Date.from(now),
            Date.from(now.plusSeconds(60L * 60L * 24L * 14L)),
            subject,
            publicKey);
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
    if (ca) {
      builder.addExtension(
          Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
    } else {
      builder.addExtension(
          Extension.keyUsage,
          true,
          new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
      builder.addExtension(
          Extension.extendedKeyUsage,
          false,
          new ExtendedKeyUsage(
              serverCertificate ? KeyPurposeId.id_kp_serverAuth : KeyPurposeId.id_kp_clientAuth));
      builder.addExtension(Extension.subjectAlternativeName, false, subjectAltNames);
    }
    return new JcaX509CertificateConverter()
        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
        .getCertificate(
            builder.build(
                new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(issuerPrivateKey)));
  }

  private static KeyPair newRsaKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private record TestCertificate(PrivateKey privateKey, X509Certificate certificate) {}

  private enum ResponseMutation {
    NONE,
    CHANGE_READ_REQUEST_ID,
    CHANGE_SOURCE_DISPLAY_NAME,
    CHANGE_EVIDENCE_DIGEST,
    ADD_UNKNOWN_FIELD
  }

  private record TestPki(
      X509Certificate caCertificate,
      TestCertificate gameDesignServerCertificate,
      TestCertificate wrongServerWorkloadCertificate,
      TestCertificate wrongServerNamespaceCertificate,
      TestCertificate worldManagementCertificate,
      TestCertificate gameSessionCertificate,
      TestCertificate wrongServiceCertificate,
      TestCertificate wrongNamespaceCertificate,
      TestCertificate noWorkloadUriCertificate) {}
}
