package net.firedevops.firemud.worldmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.Message;
import io.grpc.ForwardingServerCall;
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
import java.math.BigInteger;
import java.net.InetSocketAddress;
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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeClient;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.ReadRequest;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeService;
import net.firedevops.firemud.worldmanagement.v1.IntakeAuthoredWorldSourceResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeResponse;
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
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL owner commit and exact receipt readback over the unregistered physical mTLS boundary.
 * Game Design source evidence is synthetic; this does not exercise its producer or network.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class WorldAuthoredSourceIntakeMtlsIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final String WORLD_SERVER_URI =
      "spiffe://firemud/ns/firemud/sa/world-management-service";
  private static final AtomicLong CERTIFICATE_SERIAL = new AtomicLong(1);
  private static TestPki pki;

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @TempDir Path tempDirectory;

  @Autowired private WorldAuthoredSourceIntakeRepository repository;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean
  private net.firedevops.firemud.worldmanagement.client.GameDesignClient gameDesignClient;

  @MockitoBean
  private net.firedevops.firemud.worldmanagement.client.GameSessionClient gameSessionClient;

  @MockitoBean
  private net.firedevops.firemud.worldmanagement.client.EntityManagementClient
      entityManagementClient;

  private Server server;
  private ConcurrentLinkedQueue<Message> wireResponses;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "world_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @BeforeAll
  static void createFreshTestPki() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPair caKeyPair = newRsaKeyPair();
    X500Name caName = new X500Name("CN=World Intake Integration Test CA, O=FireMUD Test");
    X509Certificate caCertificate =
        issueCertificate(caName, caKeyPair.getPublic(), caName, caKeyPair.getPrivate(), true, null);
    pki =
        new TestPki(
            caCertificate,
            issueLeaf(caName, caKeyPair.getPrivate(), "world-management-service", WORLD_SERVER_URI),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "game-design-service",
                "spiffe://firemud/ns/firemud/sa/game-design-service"),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "account-service",
                "spiffe://firemud/ns/firemud/sa/account-service"));
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
  void commitsFreshWorldOwnerReceiptThenReturnsExactReceiptOverAuthenticatedSocket()
      throws Exception {
    Scenario scenario = newScenario("violet-wilds");
    AuthoredWorldSourceClient syntheticSource = sourceBoundary(scenario);
    startReceiver(syntheticSource, new AtomicBoolean(false));

    try (WorldAuthoredSourceIntakeClient client =
        intakeClient(pki.gameDesignClient(), "gd-first")) {
      CommittedReceipt intakeReceipt = client.intake(scenario.intakeRequest());
      WorldAuthoredSourceIntakeReceipt persisted =
          repository.read(NAMESPACE, scenario.intakeRequestId()).orElseThrow();
      CommittedReceipt exactRead = client.read(scenario.readRequest());

      assertThat(persisted.source()).isEqualTo(scenario.sourceEvidence());
      assertThat(intakeReceipt).isEqualTo(publicReceipt(persisted));
      assertThat(exactRead).isEqualTo(intakeReceipt);
      assertThat(repository.read(NAMESPACE, scenario.intakeRequestId())).contains(persisted);
      assertThat(dbCounts(scenario)).isEqualTo(new DbCounts(1L, 1L, 1L));
      assertNoPrivateLocalTenantKeyOnWire();
      verify(syntheticSource, times(1)).read(any(AuthoredWorldSourceGrpcCodec.ReadRequest.class));
      verifyNoMoreInteractions(syntheticSource);
    }
  }

  @Test
  void reconcilesLostSuccessAcknowledgementWithExactRetryAndCommittedReadback() throws Exception {
    Scenario scenario = newScenario("amber-coast");
    AuthoredWorldSourceClient syntheticSource = sourceBoundary(scenario);
    AtomicBoolean dropNextSuccess = new AtomicBoolean(true);
    startReceiver(syntheticSource, dropNextSuccess);

    try (WorldAuthoredSourceIntakeClient client =
        intakeClient(pki.gameDesignClient(), "gd-retry")) {
      assertThatThrownBy(() -> client.intake(scenario.intakeRequest()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAVAILABLE));

      WorldAuthoredSourceIntakeReceipt committedAfterLostAck =
          repository.read(NAMESPACE, scenario.intakeRequestId()).orElseThrow();
      DbCounts afterCommit = dbCounts(scenario);
      assertThat(committedAfterLostAck.source()).isEqualTo(scenario.sourceEvidence());
      assertThat(afterCommit).isEqualTo(new DbCounts(1L, 1L, 1L));
      assertThat(dropNextSuccess.get()).isFalse();

      CommittedReceipt retryReceipt = client.intake(scenario.intakeRequest());
      CommittedReceipt readReceipt = client.read(scenario.readRequest());

      assertThat(retryReceipt).isEqualTo(publicReceipt(committedAfterLostAck));
      assertThat(readReceipt).isEqualTo(publicReceipt(committedAfterLostAck));
      assertThat(retryReceipt.operationId()).isEqualTo(committedAfterLostAck.operationId());
      assertThat(retryReceipt.requestDigest()).isEqualTo(committedAfterLostAck.requestDigest());
      assertThat(retryReceipt.receiptDigest()).isEqualTo(committedAfterLostAck.receiptDigest());
      assertThat(repository.read(NAMESPACE, scenario.intakeRequestId()))
          .contains(committedAfterLostAck);
      assertThat(dbCounts(scenario)).isEqualTo(afterCommit);
      assertNoPrivateLocalTenantKeyOnWire();
      verify(syntheticSource, times(1)).read(any(AuthoredWorldSourceGrpcCodec.ReadRequest.class));
      verifyNoMoreInteractions(syntheticSource);
    }
  }

  @Test
  void deniesWrongWorkloadAndChangedBindingWithoutOwnerMutation() throws Exception {
    Scenario scenario = newScenario("silver-marsh");
    AuthoredWorldSourceClient syntheticSource = sourceBoundary(scenario);
    startReceiver(syntheticSource, new AtomicBoolean(false));

    try (WorldAuthoredSourceIntakeClient wrongCaller =
        intakeClient(pki.accountClient(), "account")) {
      assertStatus(
          Status.Code.PERMISSION_DENIED, () -> wrongCaller.intake(scenario.intakeRequest()));
      assertStatus(Status.Code.PERMISSION_DENIED, () -> wrongCaller.read(scenario.readRequest()));
    }
    assertThat(dbCounts(scenario)).isEqualTo(new DbCounts(0L, 0L, 0L));
    verifyNoInteractions(syntheticSource);

    try (WorldAuthoredSourceIntakeClient client =
        intakeClient(pki.gameDesignClient(), "gd-scope")) {
      CommittedReceipt accepted = client.intake(scenario.intakeRequest());
      WorldAuthoredSourceIntakeReceipt original =
          repository.read(NAMESPACE, scenario.intakeRequestId()).orElseThrow();
      assertThat(client.read(scenario.readRequest())).isEqualTo(accepted);

      IntakeRequest changedIntake =
          new IntakeRequest(
              1,
              NAMESPACE,
              scenario.intakeRequestId(),
              scenario.canonicalTenantId(),
              "changed-world-selector",
              scenario.sourceOperationId(),
              scenario.sourceEvidence().evidenceDigest());
      ReadRequest changedRead = new ReadRequest(changedIntake, UUID.randomUUID());

      assertStatus(Status.Code.FAILED_PRECONDITION, () -> client.intake(changedIntake));
      assertStatus(Status.Code.FAILED_PRECONDITION, () -> client.read(changedRead));

      assertThat(repository.read(NAMESPACE, scenario.intakeRequestId())).contains(original);
      assertThat(original.source()).isEqualTo(scenario.sourceEvidence());
      assertThat(dbCounts(scenario)).isEqualTo(new DbCounts(1L, 1L, 1L));
      verify(syntheticSource, times(1)).read(any(AuthoredWorldSourceGrpcCodec.ReadRequest.class));
      verifyNoMoreInteractions(syntheticSource);
    }
  }

  private AuthoredWorldSourceClient sourceBoundary(Scenario scenario) {
    AuthoredWorldSourceClient syntheticSource = mock(AuthoredWorldSourceClient.class);
    when(syntheticSource.read(any(AuthoredWorldSourceGrpcCodec.ReadRequest.class)))
        .thenAnswer(
            invocation -> {
              AuthoredWorldSourceGrpcCodec.ReadRequest request = invocation.getArgument(0);
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(request.targetNamespace()).isEqualTo(NAMESPACE);
              assertThat(request.operationId()).isEqualTo(scenario.sourceOperationId());
              assertThat(request.canonicalTenantId()).isEqualTo(scenario.canonicalTenantId());
              assertThat(request.worldSlug()).isEqualTo(scenario.worldSlug());
              AuthoredWorldSourceEvidence answer = scenario.sourceEvidence();
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return answer;
            });
    return syntheticSource;
  }

  private void startReceiver(
      AuthoredWorldSourceClient syntheticSource, AtomicBoolean dropNextSuccess) throws Exception {
    WorldAuthoredSourceIntakeService owner =
        new WorldAuthoredSourceIntakeService(
            syntheticSource, repository, transactionManager, NAMESPACE);
    WorldAuthoredSourceIntakeGrpcService handler =
        new WorldAuthoredSourceIntakeGrpcService(owner, NAMESPACE);
    wireResponses = new ConcurrentLinkedQueue<>();
    var withPeerIdentity = ServerInterceptors.intercept(handler, new GrpcPeerIdentityInterceptor());
    var withResponseCapture =
        ServerInterceptors.intercept(
            withPeerIdentity, responseCaptureAndDrop(wireResponses, dropNextSuccess));
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            pki.worldServer().privateKey(), pki.worldServer().certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(withResponseCapture)
            .build()
            .start();
  }

  private WorldAuthoredSourceIntakeClient intakeClient(TestCertificate certificate, String label)
      throws Exception {
    WorldAuthoredSourceIntakeClient client =
        new WorldAuthoredSourceIntakeClient(
            endpoints(), tlsProperties(certificate, label), new GrpcChannelFactory(), NAMESPACE);
    client.init();
    return client;
  }

  private ServiceEndpointsProperties endpoints() {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("127.0.0.1:" + server.getPort());
    return endpoints;
  }

  private CommonGrpcClientProperties tlsProperties(TestCertificate certificate, String label)
      throws Exception {
    Path clientCertificate = tempDirectory.resolve(label + "-client.crt");
    Path clientPrivateKey = tempDirectory.resolve(label + "-client.key");
    Path caCertificate = tempDirectory.resolve("test-ca.crt");
    Files.writeString(
        clientCertificate, pem("CERTIFICATE", certificate.certificate().getEncoded()));
    Files.writeString(clientPrivateKey, pem("PRIVATE KEY", certificate.privateKey().getEncoded()));
    Files.writeString(caCertificate, pem("CERTIFICATE", pki.caCertificate().getEncoded()));

    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(clientCertificate.toString());
    tls.setPrivateKey(clientPrivateKey.toString());
    tls.setCaCert(caCertificate.toString());
    return tls;
  }

  private static ServerInterceptor responseCaptureAndDrop(
      ConcurrentLinkedQueue<Message> capturedResponses, AtomicBoolean dropNextSuccess) {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        ServerCall<ReqT, RespT> forwardingCall =
            new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
              private boolean droppedResponse;

              @Override
              public void sendMessage(RespT message) {
                if (message instanceof Message protobufMessage) {
                  capturedResponses.add(protobufMessage);
                }
                if (message instanceof IntakeAuthoredWorldSourceResponse
                    && dropNextSuccess.compareAndSet(true, false)) {
                  droppedResponse = true;
                  return;
                }
                super.sendMessage(message);
              }

              @Override
              public void close(Status status, Metadata trailers) {
                if (droppedResponse && status.isOk()) {
                  super.close(
                      Status.UNAVAILABLE.withDescription(
                          "Test deliberately lost a committed intake acknowledgement"),
                      trailers);
                  return;
                }
                super.close(status, trailers);
              }
            };
        return next.startCall(forwardingCall, headers);
      }
    };
  }

  private void assertNoPrivateLocalTenantKeyOnWire() {
    assertThat(
            IntakeAuthoredWorldSourceResponse.getDescriptor().findFieldByName("local_tenant_key"))
        .isNull();
    assertThat(
            ReadAuthoredWorldSourceIntakeResponse.getDescriptor()
                .findFieldByName("local_tenant_key"))
        .isNull();
    assertThat(wireResponses).isNotEmpty();
    for (Message response : wireResponses) {
      assertThat(response.getDescriptorForType().findFieldByName("local_tenant_key")).isNull();
      assertThat(response.getUnknownFields().asMap()).isEmpty();
      assertThat(response.getAllFields().keySet())
          .noneMatch(field -> field.getName().equals("local_tenant_key"));
    }
  }

  private DbCounts dbCounts(Scenario scenario) {
    return new DbCounts(
        count(
            "SELECT COUNT(*) FROM world_authored_source_intake "
                + "WHERE target_namespace = ? AND intake_request_id = ?",
            NAMESPACE,
            scenario.intakeRequestId()),
        count(
            "SELECT COUNT(*) FROM world_authored_source_tenant_association "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
            NAMESPACE,
            scenario.canonicalTenantId()),
        count(
            "SELECT COUNT(*) FROM world_authored_source_tenant_key_reservation "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ? "
                + "AND claim_kind = 'CANONICAL_AUTHORED_SOURCE'",
            NAMESPACE,
            scenario.canonicalTenantId()));
  }

  private long count(String query, Object... bindings) {
    return Objects.requireNonNull(
            dsl.fetchOne(query, bindings), "World intake count query returned no row")
        .get(0, Long.class);
  }

  private static CommittedReceipt publicReceipt(WorldAuthoredSourceIntakeReceipt receipt) {
    return new CommittedReceipt(
        receipt.schemaVersion(),
        receipt.targetNamespace(),
        receipt.intakeRequestId(),
        receipt.operationId(),
        receipt.canonicalTenantId(),
        receipt.worldSlug(),
        receipt.sourceOperationId(),
        receipt.sourceEvidenceDigest(),
        receipt.requestDigest(),
        receipt.receiptDigest());
  }

  private static Scenario newScenario(String worldSlug) {
    UUID tenantId = UUID.randomUUID();
    UUID intakeRequestId = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    UUID registrationRequestId = UUID.randomUUID();
    String tenantSlug = "tenant-" + tenantId.toString().replace("-", "").substring(0, 12);
    String displayName = "World " + worldSlug;
    long sourceGameRowId =
        Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1L;
    String sourceGameTenantKey = "src-" + UUID.randomUUID().toString().replace("-", "");
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationRequestId, tenantId, tenantSlug, worldSlug, displayName);
    String sourceEvidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequestId,
            sourceOperationId,
            sourceRequestDigest,
            tenantId,
            tenantSlug,
            worldSlug,
            displayName,
            sourceGameRowId,
            sourceGameTenantKey,
            "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence evidence =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registrationRequestId,
            sourceOperationId,
            sourceRequestDigest,
            tenantId,
            tenantSlug,
            worldSlug,
            displayName,
            sourceGameRowId,
            sourceGameTenantKey,
            "NEW_GAME_ROW",
            sourceEvidenceDigest);
    return new Scenario(
        tenantId, intakeRequestId, sourceOperationId, UUID.randomUUID(), worldSlug, evidence);
  }

  private static void assertStatus(Status.Code expected, ThrowingAction action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private static String pem(String type, byte[] bytes) {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
    return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
  }

  private static TestCertificate issueLeaf(
      X500Name caName, PrivateKey caPrivateKey, String commonName, String workloadUri)
      throws Exception {
    KeyPair keyPair = newRsaKeyPair();
    X500Name subject = new X500Name("CN=" + commonName + ", O=FireMUD Test");
    GeneralNames subjectAltNames =
        new GeneralNames(
            new GeneralName[] {
              new GeneralName(GeneralName.uniformResourceIdentifier, workloadUri),
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

  private record Scenario(
      UUID canonicalTenantId,
      UUID intakeRequestId,
      UUID sourceOperationId,
      UUID readRequestId,
      String worldSlug,
      AuthoredWorldSourceEvidence sourceEvidence) {
    IntakeRequest intakeRequest() {
      return new IntakeRequest(
          1,
          NAMESPACE,
          intakeRequestId,
          canonicalTenantId,
          worldSlug,
          sourceOperationId,
          sourceEvidence.evidenceDigest());
    }

    ReadRequest readRequest() {
      return new ReadRequest(intakeRequest(), readRequestId);
    }
  }

  private record DbCounts(long receipts, long associations, long reservations) {}

  private record TestCertificate(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestCertificate worldServer,
      TestCertificate gameDesignClient,
      TestCertificate accountClient) {}

  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws Throwable;
  }
}
