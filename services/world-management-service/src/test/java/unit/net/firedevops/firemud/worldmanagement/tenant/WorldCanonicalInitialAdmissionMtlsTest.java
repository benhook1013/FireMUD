package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.PublicationReadGuard;
import net.firedevops.firemud.worldmanagement.service.InitialAdmissionBindHoldService;
import net.firedevops.firemud.worldmanagement.service.PingService;
import net.firedevops.firemud.worldmanagement.service.RoomService;
import net.firedevops.firemud.worldmanagement.service.WorldDesignMutationService;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService;
import net.firedevops.firemud.worldmanagement.service.WorldInstanceActivationService;
import net.firedevops.firemud.worldmanagement.service.WorldUpgradeValidationService;
import net.firedevops.firemud.worldmanagement.service.impl.WorldManagementGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldFinalizationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldTerminalGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceLifecycleReadGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceLifecycleReadRepository;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalInitialAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.FinalizeCanonicalInitialAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldIdentityRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldCanonicalInstanceLifecycleRequest;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInitialAdmissionHoldServiceGrpc;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInitialAdmissionHoldTerminalServiceGrpc;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInstanceLifecycleReadServiceGrpc;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
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
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.ObjectMapper;

/** Physical socket proof for the exact-method-protected canonical first-admission handlers. */
class WorldCanonicalInitialAdmissionMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String JWT_SECRET = "test-secret-key-test-secret-key-32-bytes";
  private static final AtomicLong CERTIFICATE_SERIAL = new AtomicLong(1);
  private static final String ACQUIRE_METHOD =
      WorldCanonicalInitialAdmissionHoldServiceGrpc.getAcquireCanonicalInitialAdmissionHoldMethod()
          .getFullMethodName();
  private static final String IDENTITY_READ_METHOD =
      WorldCanonicalInitialAdmissionHoldServiceGrpc
          .getReadCanonicalInitialAdmissionHoldIdentityMethod()
          .getFullMethodName();
  private static final String LIFECYCLE_READ_METHOD =
      WorldCanonicalInstanceLifecycleReadServiceGrpc.getReadWorldCanonicalInstanceLifecycleMethod()
          .getFullMethodName();
  private static final String TERMINAL_METHOD =
      WorldCanonicalInitialAdmissionHoldTerminalServiceGrpc
          .getFinalizeCanonicalInitialAdmissionHoldMethod()
          .getFullMethodName();
  private static TestPki pki;

  private final WorldCanonicalInitialAdmissionHoldRepository holdRepository =
      mock(WorldCanonicalInitialAdmissionHoldRepository.class);
  private final WorldCanonicalInstanceLifecycleReadRepository lifecycleRepository =
      mock(WorldCanonicalInstanceLifecycleReadRepository.class);
  private final WorldCanonicalInitialAdmissionHoldFinalizationService finalizationService =
      mock(WorldCanonicalInitialAdmissionHoldFinalizationService.class);
  private final PingService pingService = mock(PingService.class);
  private final RoomService roomService = mock(RoomService.class);
  private final WorldInstanceActivationService activationService =
      mock(WorldInstanceActivationService.class);
  private final WorldDraftDesignDigestService draftDesignDigestService =
      mock(WorldDraftDesignDigestService.class);
  private final WorldDesignMutationService mutationService = mock(WorldDesignMutationService.class);
  private final WorldUpgradeValidationService validationService =
      mock(WorldUpgradeValidationService.class);
  private final GameplaySessionAttestationService attestationService =
      mock(GameplaySessionAttestationService.class);
  private final InitialAdmissionBindHoldService bindHoldService =
      mock(InitialAdmissionBindHoldService.class);

  private Server server;

  @BeforeAll
  static void generateFreshTestPki() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPair caKeyPair = newRsaKeyPair();
    X500Name caName = new X500Name("CN=World Canonical Admission Test CA, O=FireMUD Test");
    X509Certificate caCertificate =
        issueCertificate(caName, caKeyPair.getPublic(), caName, caKeyPair.getPrivate(), true, null);
    pki =
        new TestPki(
            caCertificate,
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                List.of(workloadUri(NAMESPACE, "world-management-service"))),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                List.of(workloadUri(NAMESPACE, "game-session-service"))),
            issueLeaf(
                caName, caKeyPair.getPrivate(), List.of(workloadUri(NAMESPACE, "account-service"))),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                List.of(workloadUri("other-test", "game-session-service"))),
            issueLeaf(caName, caKeyPair.getPrivate(), List.of()),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                List.of(
                    workloadUri(NAMESPACE, "game-session-service"),
                    workloadUri(NAMESPACE, "account-service"))));
  }

  @BeforeEach
  void startPhysicalReceiver() throws Exception {
    var holdService = new WorldCanonicalInitialAdmissionHoldGrpcService(holdRepository, NAMESPACE);
    var lifecycleService =
        new WorldCanonicalInstanceLifecycleReadGrpcService(lifecycleRepository, NAMESPACE);
    var terminalService =
        new WorldCanonicalInitialAdmissionHoldTerminalGrpcService(finalizationService, NAMESPACE);
    var legacyMutationService =
        new WorldManagementGrpcService(
            pingService,
            roomService,
            activationService,
            draftDesignDigestService,
            mutationService,
            validationService,
            attestationService,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            new ObjectMapper(),
            new PublicationReadGuard(NAMESPACE));
    legacyMutationService.configureInitialAdmissionBindHoldBoundary(bindHoldService, NAMESPACE);

    var authInterceptor =
        new AuthTokenInterceptor(
            new JwtUtil(JWT_SECRET, 60_000L), publicMethods("application.yml"));
    var peerInterceptor = new GrpcPeerIdentityInterceptor();
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            pki.worldServer().privateKey(), pki.worldServer().certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(holdService, authInterceptor, peerInterceptor))
            .addService(
                ServerInterceptors.intercept(lifecycleService, authInterceptor, peerInterceptor))
            .addService(
                ServerInterceptors.intercept(terminalService, authInterceptor, peerInterceptor))
            .addService(
                ServerInterceptors.intercept(
                    legacyMutationService, authInterceptor, peerInterceptor))
            .build()
            .start();
  }

  @AfterEach
  void stopPhysicalReceiver() throws Exception {
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
  }

  @Test
  void exactSameNamespaceGameSessionCertificateReachesAllFourHandlersWithoutBearer()
      throws Exception {
    ManagedChannel channel = channel(pki.gameSessionClient());
    try {
      assertInvalidArgument(
          () ->
              holdStub(channel)
                  .acquireCanonicalInitialAdmissionHold(
                      AcquireCanonicalInitialAdmissionHoldRequest.getDefaultInstance()));
      assertInvalidArgument(
          () ->
              holdStub(channel)
                  .readCanonicalInitialAdmissionHoldIdentity(
                      ReadCanonicalInitialAdmissionHoldIdentityRequest.getDefaultInstance()));
      assertInvalidArgument(
          () ->
              lifecycleStub(channel)
                  .readWorldCanonicalInstanceLifecycle(
                      ReadWorldCanonicalInstanceLifecycleRequest.getDefaultInstance()));
      assertInvalidArgument(
          () ->
              terminalStub(channel)
                  .finalizeCanonicalInitialAdmissionHold(
                      FinalizeCanonicalInitialAdmissionHoldRequest.getDefaultInstance()));
    } finally {
      stopChannel(channel);
    }
    verifyNoOwnerCalls();
  }

  @Test
  void wrongServiceNamespaceMissingAndAmbiguousWorkloadIdentitiesAreDeniedBeforeOwnerAccess()
      throws Exception {
    assertAllProtectedMethodsDenied(pki.accountClient());
    assertAllProtectedMethodsDenied(pki.foreignNamespaceGameSessionClient());
    assertAllProtectedMethodsDenied(pki.sharedLegacyClient());
    assertAllProtectedMethodsDenied(pki.ambiguousWorkloadClient());
    verifyNoOwnerCalls();
  }

  @Test
  void clientWithoutCertificateCannotReachProtectedHandlerOrOwner() throws Exception {
    ManagedChannel channel = channelWithoutCertificate();
    Throwable failure = null;
    try {
      holdStub(channel)
          .acquireCanonicalInitialAdmissionHold(
              AcquireCanonicalInitialAdmissionHoldRequest.getDefaultInstance());
    } catch (Throwable transportFailure) {
      failure = transportFailure;
    } finally {
      stopChannel(channel);
    }
    assertThat(failure).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    verifyNoOwnerCalls();
  }

  @Test
  void unrelatedWorldMutationMethodStillRequiresBearerAuthentication() throws Exception {
    assertThat(publicMethods("application.yml"))
        .containsExactlyInAnyOrder(
            "world_management.v1.WorldManagementService/Ping",
            "world_management.v1.WorldManagementService/GetDraftDesignDigest",
            "world_management.v1.WorldAuthoredSourceIntakeService/IntakeAuthoredWorldSource",
            "world_management.v1.WorldAuthoredSourceIntakeService/ReadAuthoredWorldSourceIntake",
            ACQUIRE_METHOD,
            IDENTITY_READ_METHOD,
            LIFECYCLE_READ_METHOD,
            TERMINAL_METHOD);
    assertThat(publicMethods("application.yml"))
        .doesNotContain(
            WorldManagementServiceGrpc.getApplyWorldDesignMutationMethod().getFullMethodName());

    ManagedChannel channel = channel(pki.gameSessionClient());
    Throwable failure = null;
    try {
      WorldManagementServiceGrpc.newBlockingStub(channel)
          .withDeadlineAfter(3, TimeUnit.SECONDS)
          .applyWorldDesignMutation(
              net.firedevops.firemud.worldmanagement.v1.ApplyWorldDesignMutationRequest
                  .getDefaultInstance());
    } catch (Throwable transportFailure) {
      failure = transportFailure;
    } finally {
      stopChannel(channel);
    }
    assertThat(failure).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(Status.fromThrowable(failure).getDescription()).isEqualTo("Missing token");
    verifyNoOwnerCalls();
  }

  private void assertAllProtectedMethodsDenied(TestCertificate certificate) throws Exception {
    ManagedChannel channel = channel(certificate);
    try {
      assertPermissionDenied(
          () ->
              holdStub(channel)
                  .acquireCanonicalInitialAdmissionHold(
                      AcquireCanonicalInitialAdmissionHoldRequest.getDefaultInstance()));
      assertPermissionDenied(
          () ->
              holdStub(channel)
                  .readCanonicalInitialAdmissionHoldIdentity(
                      ReadCanonicalInitialAdmissionHoldIdentityRequest.getDefaultInstance()));
      assertPermissionDenied(
          () ->
              lifecycleStub(channel)
                  .readWorldCanonicalInstanceLifecycle(
                      ReadWorldCanonicalInstanceLifecycleRequest.getDefaultInstance()));
      assertPermissionDenied(
          () ->
              terminalStub(channel)
                  .finalizeCanonicalInitialAdmissionHold(
                      FinalizeCanonicalInitialAdmissionHoldRequest.getDefaultInstance()));
    } finally {
      stopChannel(channel);
    }
  }

  private WorldCanonicalInitialAdmissionHoldServiceGrpc
          .WorldCanonicalInitialAdmissionHoldServiceBlockingStub
      holdStub(ManagedChannel channel) {
    return WorldCanonicalInitialAdmissionHoldServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(3, TimeUnit.SECONDS);
  }

  private WorldCanonicalInstanceLifecycleReadServiceGrpc
          .WorldCanonicalInstanceLifecycleReadServiceBlockingStub
      lifecycleStub(ManagedChannel channel) {
    return WorldCanonicalInstanceLifecycleReadServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(3, TimeUnit.SECONDS);
  }

  private WorldCanonicalInitialAdmissionHoldTerminalServiceGrpc
          .WorldCanonicalInitialAdmissionHoldTerminalServiceBlockingStub
      terminalStub(ManagedChannel channel) {
    return WorldCanonicalInitialAdmissionHoldTerminalServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(3, TimeUnit.SECONDS);
  }

  private ManagedChannel channel(TestCertificate clientCertificate) throws Exception {
    return NettyChannelBuilder.forAddress(new InetSocketAddress("127.0.0.1", server.getPort()))
        .sslContext(
            GrpcSslContexts.forClient()
                .trustManager(pki.caCertificate())
                .keyManager(clientCertificate.privateKey(), clientCertificate.certificate())
                .build())
        .build();
  }

  private ManagedChannel channelWithoutCertificate() throws Exception {
    return NettyChannelBuilder.forAddress(new InetSocketAddress("127.0.0.1", server.getPort()))
        .sslContext(GrpcSslContexts.forClient().trustManager(pki.caCertificate()).build())
        .build();
  }

  private static void assertInvalidArgument(Runnable rpc) {
    assertStatus(rpc, Status.Code.INVALID_ARGUMENT);
  }

  private static void assertPermissionDenied(Runnable rpc) {
    assertStatus(rpc, Status.Code.PERMISSION_DENIED);
  }

  private static void assertStatus(Runnable rpc, Status.Code expected) {
    Throwable failure = null;
    try {
      rpc.run();
    } catch (Throwable rpcFailure) {
      failure = rpcFailure;
    }
    assertThat(failure).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected);
  }

  private static Set<String> publicMethods(String resourceName) throws Exception {
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

  private void verifyNoOwnerCalls() {
    verifyNoInteractions(
        holdRepository,
        lifecycleRepository,
        finalizationService,
        pingService,
        roomService,
        activationService,
        draftDesignDigestService,
        mutationService,
        validationService,
        attestationService,
        bindHoldService);
  }

  private static void stopChannel(ManagedChannel channel) throws InterruptedException {
    channel.shutdownNow();
    assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
  }

  private static String workloadUri(String namespace, String serviceName) {
    return "spiffe://firemud/ns/" + namespace + "/sa/" + serviceName;
  }

  private static TestCertificate issueLeaf(
      X500Name caName, PrivateKey caPrivateKey, List<String> workloadUris) throws Exception {
    KeyPair keyPair = newRsaKeyPair();
    X500Name subject = new X500Name("CN=FireMUD Test Workload, O=FireMUD Test");
    List<GeneralName> subjectAltNames = new ArrayList<>();
    workloadUris.forEach(
        uri -> subjectAltNames.add(new GeneralName(GeneralName.uniformResourceIdentifier, uri)));
    subjectAltNames.add(new GeneralName(GeneralName.dNSName, "localhost"));
    subjectAltNames.add(new GeneralName(GeneralName.iPAddress, "127.0.0.1"));
    X509Certificate certificate =
        issueCertificate(
            subject,
            keyPair.getPublic(),
            caName,
            caPrivateKey,
            false,
            new GeneralNames(subjectAltNames.toArray(GeneralName[]::new)));
    return new TestCertificate(keyPair.getPrivate(), certificate);
  }

  private static X509Certificate issueCertificate(
      X500Name subject,
      java.security.PublicKey publicKey,
      X500Name issuer,
      PrivateKey issuerPrivateKey,
      boolean ca,
      GeneralNames subjectAltNames)
      throws Exception {
    Instant now = Instant.now().minusSeconds(60);
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
              new KeyPurposeId[] {KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));
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

  private record TestPki(
      X509Certificate caCertificate,
      TestCertificate worldServer,
      TestCertificate gameSessionClient,
      TestCertificate accountClient,
      TestCertificate foreignNamespaceGameSessionClient,
      TestCertificate sharedLegacyClient,
      TestCertificate ambiguousWorkloadClient) {}
}
