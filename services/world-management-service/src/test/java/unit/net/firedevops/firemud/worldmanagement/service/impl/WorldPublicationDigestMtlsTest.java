package net.firedevops.firemud.worldmanagement.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.MetadataUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.PublicationReadGuard;
import net.firedevops.firemud.worldmanagement.service.InitialAdmissionBindHoldService;
import net.firedevops.firemud.worldmanagement.service.PingService;
import net.firedevops.firemud.worldmanagement.service.RoomService;
import net.firedevops.firemud.worldmanagement.service.WorldDesignMutationService;
import net.firedevops.firemud.worldmanagement.service.WorldInstanceActivationService;
import net.firedevops.firemud.worldmanagement.service.WorldUpgradeValidationService;
import net.firedevops.firemud.worldmanagement.v1.ApplyWorldDesignMutationRequest;
import net.firedevops.firemud.worldmanagement.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.worldmanagement.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceRequest;
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

/**
 * Physical socket proof for the registered World digest RPC. The handler and transport
 * authorization path are real, but owner collaborators are mocked; this proves denial behavior, not
 * database state, authenticated APPLIED evidence, release completion, or live readiness.
 */
class WorldPublicationDigestMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String JWT_SECRET = "test-secret-key-test-secret-key-32-bytes";
  private static final String UNAVAILABLE_CHECKPOINT_MESSAGE =
      "An authenticated complete World commit/publication checkpoint is unavailable.";
  private static final String PEER_DENIAL_MESSAGE =
      "Publication read requires only the authenticated Game Design workload peer identity";
  private static final String DIGEST_METHOD =
      WorldManagementServiceGrpc.getGetDraftDesignDigestMethod().getFullMethodName();
  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final AtomicLong CERTIFICATE_SERIAL = new AtomicLong(1);
  private static TestPki pki;

  private final PingService pingService = mock(PingService.class);
  private final RoomService roomService = mock(RoomService.class);
  private final WorldInstanceActivationService worldInstanceActivationService =
      mock(WorldInstanceActivationService.class);
  private final WorldDesignMutationService worldDesignMutationService =
      mock(WorldDesignMutationService.class);
  private final WorldUpgradeValidationService worldUpgradeValidationService =
      mock(WorldUpgradeValidationService.class);
  private final GameplaySessionAttestationService gameplaySessionAttestationService =
      mock(GameplaySessionAttestationService.class);
  private final InitialAdmissionBindHoldService initialAdmissionBindHoldService =
      mock(InitialAdmissionBindHoldService.class);

  private Server server;

  @BeforeAll
  static void generateFreshTestPki() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPair caKeyPair = newRsaKeyPair();
    X500Name caName = new X500Name("CN=World Publication Digest Test CA, O=FireMUD Test");
    X509Certificate caCertificate =
        issueCertificate(caName, caKeyPair.getPublic(), caName, caKeyPair.getPrivate(), true, null);
    pki =
        new TestPki(
            caCertificate,
            issueLeaf(caName, caKeyPair.getPrivate(), NAMESPACE, "world-management-service"),
            issueLeaf(caName, caKeyPair.getPrivate(), NAMESPACE, "game-design-service"),
            issueLeaf(caName, caKeyPair.getPrivate(), NAMESPACE, "account-service"),
            issueLeaf(caName, caKeyPair.getPrivate(), "other-test", "game-design-service"));
  }

  @BeforeEach
  void startPhysicalReceiver() throws Exception {
    WorldManagementGrpcService service =
        new WorldManagementGrpcService(
            pingService,
            roomService,
            worldInstanceActivationService,
            worldDesignMutationService,
            worldUpgradeValidationService,
            gameplaySessionAttestationService,
            new SimpleMeterRegistry(),
            new ObjectMapper(),
            new PublicationReadGuard(NAMESPACE));
    service.configureInitialAdmissionBindHoldBoundary(initialAdmissionBindHoldService, NAMESPACE);

    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            pki.worldServer().privateKey(), pki.worldServer().certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service,
                    new AuthTokenInterceptor(
                        new JwtUtil(JWT_SECRET, 60_000L), publicMethods("application.yml")),
                    new GrpcPeerIdentityInterceptor()))
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
  void exactGameDesignPeerGetsInBandCheckpointDenialWithoutSuccessFields() throws Exception {
    ManagedChannel channel = channel(pki.gameDesignClient());
    try {
      GetDraftDesignDigestResponse response = stub(channel).getDraftDesignDigest(validRequest());

      assertCheckpointUnavailable(response);
    } finally {
      stopChannel(channel);
    }
    verifyNoOwnerCalls();
  }

  @Test
  void forgedServiceNameJwtCannotBypassTheWrongWorkloadCertificate() throws Exception {
    JwtUtil jwtUtil = new JwtUtil(JWT_SECRET, 60_000L);
    String forgedServiceNameToken =
        jwtUtil.generateToken(
            "forged-workload",
            Map.of(
                "internalService", true,
                "serviceName", "game-design-service",
                "serviceInstanceId", "forged-instance"));
    Metadata headers = new Metadata();
    headers.put(AUTHORIZATION, "Bearer " + forgedServiceNameToken);

    ManagedChannel channel = channel(pki.accountClient());
    try {
      GetDraftDesignDigestResponse response =
          stub(channel, headers).getDraftDesignDigest(validRequest());

      assertPeerDenied(response);
    } finally {
      stopChannel(channel);
    }
    verifyNoOwnerCalls();
  }

  @Test
  void foreignNamespaceGameDesignCertificateIsDenied() throws Exception {
    ManagedChannel channel = channel(pki.foreignNamespaceGameDesignClient());
    try {
      GetDraftDesignDigestResponse response = stub(channel).getDraftDesignDigest(validRequest());

      assertPeerDenied(response);
    } finally {
      stopChannel(channel);
    }
    verifyNoOwnerCalls();
  }

  @Test
  void authenticatedGameDesignPeerCannotChangeDigestOrTypedPublicationBinding() throws Exception {
    GetDraftDesignDigestRequest validRequest = validRequest();
    String changedDigest =
        (validRequest.getRequestDigest().charAt(0) == '0' ? "1" : "0")
            + validRequest.getRequestDigest().substring(1);
    GetDraftDesignDigestRequest changedDigestRequest =
        validRequest.toBuilder().setRequestDigest(changedDigest).build();
    GetDraftDesignDigestRequest changedTypedBindingRequest =
        validRequest.toBuilder().setPublishRequestId("different-publication-attempt").build();

    ManagedChannel channel = channel(pki.gameDesignClient());
    try {
      GetDraftDesignDigestResponse changedDigestResponse =
          stub(channel).getDraftDesignDigest(changedDigestRequest);
      assertInvalidBinding(changedDigestResponse, "requestDigest does not match canonical digest");

      GetDraftDesignDigestResponse changedTypedBindingResponse =
          stub(channel).getDraftDesignDigest(changedTypedBindingRequest);
      assertInvalidBinding(
          changedTypedBindingResponse, "derivedWorkflowIdentity does not match expected identity");
    } finally {
      stopChannel(channel);
    }
    verifyNoOwnerCalls();
  }

  @Test
  void worldGrpcYamlAllowsOnlyPingAndExactPeerGuardedOwnerMethodsWithoutBearer() throws Exception {
    assertThat(publicMethods("application.yml"))
        .isEqualTo(
            Set.of(
                WorldManagementServiceGrpc.getPingMethod().getFullMethodName(),
                DIGEST_METHOD,
                "world_management.v1.WorldAuthoredSourceIntakeService/IntakeAuthoredWorldSource",
                "world_management.v1.WorldAuthoredSourceIntakeService/ReadAuthoredWorldSourceIntake",
                "world_management.v1.WorldCanonicalInitialAdmissionHoldService/AcquireCanonicalInitialAdmissionHold",
                "world_management.v1.WorldCanonicalInitialAdmissionHoldService/ReadCanonicalInitialAdmissionHoldIdentity",
                "world_management.v1.WorldCanonicalInstanceLifecycleReadService/ReadWorldCanonicalInstanceLifecycle",
                "world_management.v1.WorldCanonicalInitialAdmissionHoldTerminalService/FinalizeCanonicalInitialAdmissionHold"));
  }

  @Test
  void unrelatedMutationAndLifecycleRpcsStillRequireBearerAuthentication() throws Exception {
    ManagedChannel channel = channel(pki.gameDesignClient());
    try {
      assertMissingBearer(
          () ->
              stub(channel).prepareWorldInstance(PrepareWorldInstanceRequest.getDefaultInstance()));
      assertMissingBearer(
          () ->
              stub(channel)
                  .applyWorldDesignMutation(ApplyWorldDesignMutationRequest.getDefaultInstance()));
    } finally {
      stopChannel(channel);
    }
    verifyNoOwnerCalls();
  }

  private WorldManagementServiceGrpc.WorldManagementServiceBlockingStub stub(
      ManagedChannel channel) {
    return WorldManagementServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(3, TimeUnit.SECONDS);
  }

  private WorldManagementServiceGrpc.WorldManagementServiceBlockingStub stub(
      ManagedChannel channel, Metadata headers) {
    return WorldManagementServiceGrpc.newBlockingStub(channel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
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

  private static GetDraftDesignDigestRequest validRequest() {
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.full("tenant-17", "17", "publication-attempt-17");
    return GetDraftDesignDigestRequest.newBuilder()
        .setTenantId(binding.tenantId())
        .setVersionId(binding.versionId())
        .setPublishRequestId(binding.publishRequestId())
        .setDerivedWorkflowIdentity(binding.derivedWorkflowIdentity())
        .setRequestDigest(binding.requestDigest())
        .build();
  }

  private static void assertCheckpointUnavailable(GetDraftDesignDigestResponse response) {
    assertThat(response.hasError()).isTrue();
    assertThat(response.getError().getCode()).isEqualTo("FAILED_PRECONDITION");
    assertThat(response.getError().getMessage()).isEqualTo(UNAVAILABLE_CHECKPOINT_MESSAGE);
    assertThat(response.getTenantId()).isEmpty();
    assertThat(response.getScopeCase())
        .isEqualTo(GetDraftDesignDigestResponse.ScopeCase.SCOPE_NOT_SET);
    assertThat(response.getBaseVersionId()).isEmpty();
    assertThat(response.getAppliedCommitId()).isEmpty();
    assertThat(response.getContentDigest()).isEmpty();
    assertThat(response.getDigestSchemaVersion()).isZero();
  }

  private static void assertPeerDenied(GetDraftDesignDigestResponse response) {
    assertThat(response.hasError()).isTrue();
    assertThat(response.getError().getCode()).isEqualTo("PERMISSION_DENIED");
    assertThat(response.getError().getMessage()).isEqualTo(PEER_DENIAL_MESSAGE);
    assertThat(response.getTenantId()).isEmpty();
    assertThat(response.getAppliedCommitId()).isEmpty();
    assertThat(response.getContentDigest()).isEmpty();
    assertThat(response.getDigestSchemaVersion()).isZero();
  }

  private static void assertInvalidBinding(
      GetDraftDesignDigestResponse response, String expectedMessage) {
    assertThat(response.hasError()).isTrue();
    assertThat(response.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(response.getError().getMessage()).isEqualTo(expectedMessage);
    assertThat(response.getTenantId()).isEmpty();
    assertThat(response.getAppliedCommitId()).isEmpty();
    assertThat(response.getContentDigest()).isEmpty();
    assertThat(response.getDigestSchemaVersion()).isZero();
  }

  private static void assertMissingBearer(Runnable rpc) {
    Throwable failure = null;
    try {
      rpc.run();
    } catch (Throwable throwable) {
      failure = throwable;
    }
    assertThat(failure).isInstanceOf(StatusRuntimeException.class);
    Status status = Status.fromThrowable(failure);
    assertThat(status.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(status.getDescription()).isEqualTo("Missing token");
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
        pingService,
        roomService,
        worldInstanceActivationService,
        worldDesignMutationService,
        worldUpgradeValidationService,
        gameplaySessionAttestationService,
        initialAdmissionBindHoldService);
  }

  private static void stopChannel(ManagedChannel channel) throws InterruptedException {
    channel.shutdownNow();
    assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
  }

  private static TestCertificate issueLeaf(
      X500Name caName, PrivateKey caPrivateKey, String namespace, String serviceName)
      throws Exception {
    KeyPair keyPair = newRsaKeyPair();
    X500Name subject = new X500Name("CN=" + serviceName + ", O=FireMUD Test");
    GeneralNames subjectAltNames =
        new GeneralNames(
            new GeneralName[] {
              new GeneralName(
                  GeneralName.uniformResourceIdentifier,
                  "spiffe://firemud/ns/" + namespace + "/sa/" + serviceName),
              new GeneralName(GeneralName.dNSName, "localhost"),
              new GeneralName(GeneralName.iPAddress, "127.0.0.1")
            });
    X509Certificate certificate =
        issueCertificate(
            subject, keyPair.getPublic(), caName, caPrivateKey, false, subjectAltNames);
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
      TestCertificate gameDesignClient,
      TestCertificate accountClient,
      TestCertificate foreignNamespaceGameDesignClient) {}
}
