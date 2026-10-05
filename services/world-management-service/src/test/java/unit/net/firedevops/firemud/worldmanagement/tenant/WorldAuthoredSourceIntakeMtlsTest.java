package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.ForwardingServerCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.MetadataUtils;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeClient;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.ReadRequest;
import net.firedevops.firemud.worldmanagement.v1.IntakeAuthoredWorldSourceRequest;
import net.firedevops.firemud.worldmanagement.v1.IntakeAuthoredWorldSourceResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldAuthoredSourceIntakeServiceGrpc;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical socket proof with a mocked owner service, not database-to-socket proof. */
class WorldAuthoredSourceIntakeMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String WORLD_PEER_URI =
      "spiffe://firemud/ns/test/sa/world-management-service";
  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final UUID INTAKE_ID = UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID TENANT_ID = UUID.fromString("22345678-1234-4234-8234-123456789abc");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("32345678-1234-4234-8234-123456789abc");
  private static final UUID READ_ID = UUID.fromString("42345678-1234-4234-8234-123456789abc");
  private static final UUID WORLD_OPERATION_ID =
      UUID.fromString("52345678-1234-4234-8234-123456789abc");
  private static final long PRIVATE_WORLD_TENANT_KEY = 987654321L;
  private static final AtomicLong CERTIFICATE_SERIAL = new AtomicLong(1);
  private static final UnknownFieldSet UNKNOWN_FIELD =
      UnknownFieldSet.newBuilder()
          .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
          .build();
  private static TestPki pki;

  @TempDir Path tempDirectory;

  private Server server;
  private WorldAuthoredSourceIntakeService intakeService;

  private static final AuthoredWorldSourceEvidence SOURCE_EVIDENCE = sourceEvidence();
  private static final String SOURCE_EVIDENCE_DIGEST = SOURCE_EVIDENCE.evidenceDigest();
  private static final IntakeRequest INTAKE_REQUEST =
      new IntakeRequest(
          1,
          NAMESPACE,
          INTAKE_ID,
          TENANT_ID,
          "world-one",
          SOURCE_OPERATION_ID,
          SOURCE_EVIDENCE_DIGEST);
  private static final ReadRequest READ_REQUEST = new ReadRequest(INTAKE_REQUEST, READ_ID);
  private static final WorldAuthoredSourceIntakeReceipt WORLD_RECEIPT = worldReceipt();

  @BeforeAll
  static void generateFreshTestPki() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPair caKeyPair = newRsaKeyPair();
    X500Name caName = new X500Name("CN=Fresh World Authored Intake Test CA, O=FireMUD Test");
    X509Certificate caCertificate =
        issueCertificate(caName, caKeyPair.getPublic(), caName, caKeyPair.getPrivate(), true, null);
    pki =
        new TestPki(
            caCertificate,
            issueLeaf(caName, caKeyPair.getPrivate(), "world-management-server", WORLD_PEER_URI),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "wrong-world-server-identity",
                "spiffe://firemud/ns/test/sa/game-design-service"),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "game-design-service",
                "spiffe://firemud/ns/test/sa/game-design-service"),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "account-service",
                "spiffe://firemud/ns/test/sa/account-service"),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "game-session-service",
                "spiffe://firemud/ns/test/sa/game-session-service"),
            issueLeaf(caName, caKeyPair.getPrivate(), "world-management-service", WORLD_PEER_URI),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "wrong-namespace-game-design-service",
                "spiffe://firemud/ns/other-test/sa/game-design-service"),
            issueLeaf(caName, caKeyPair.getPrivate(), "shared-workload-certificate", null));
  }

  @AfterEach
  void stopPhysicalReceiver() throws Exception {
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void sameNamespaceGameDesignIntakesThenReadsExactReceiptOverSocketMtls() throws Exception {
    startServer(pki.worldServer(), null);
    try (WorldAuthoredSourceIntakeClient client = client(pki.gameDesignClient(), "game-design")) {
      CommittedReceipt accepted = client.intake(INTAKE_REQUEST);
      CommittedReceipt readback = client.read(READ_REQUEST);

      assertPublicReceipt(accepted);
      assertPublicReceipt(readback);
      verify(intakeService)
          .intake(
              1,
              NAMESPACE,
              INTAKE_ID,
              TENANT_ID,
              "world-one",
              SOURCE_OPERATION_ID,
              SOURCE_EVIDENCE_DIGEST);
      verify(intakeService)
          .readCommittedReceipt(
              1,
              NAMESPACE,
              READ_ID,
              INTAKE_ID,
              TENANT_ID,
              "world-one",
              SOURCE_OPERATION_ID,
              SOURCE_EVIDENCE_DIGEST);
    }
  }

  @Test
  void wrongWorkloadsAndWrongNamespaceAreDeniedBeforeDecodeOrOwnerAccess() throws Exception {
    startServer(pki.worldServer(), null);
    for (TestCertificate wrongCaller :
        List.of(
            pki.accountClient(),
            pki.gameSessionClient(),
            pki.worldClient(),
            pki.otherNamespaceClient())) {
      try (WorldAuthoredSourceIntakeClient client = client(wrongCaller, "caller")) {
        assertPermissionDenied(() -> client.intake(INTAKE_REQUEST));
        assertPermissionDenied(() -> client.read(READ_REQUEST));
      }
    }

    ManagedChannel malformedCallerChannel = rawMtlsChannel(pki.accountClient());
    try {
      WorldAuthoredSourceIntakeServiceGrpc.WorldAuthoredSourceIntakeServiceBlockingStub stub =
          WorldAuthoredSourceIntakeServiceGrpc.newBlockingStub(malformedCallerChannel)
              .withDeadlineAfter(3, TimeUnit.SECONDS);
      Throwable failure =
          catchFailure(
              () ->
                  stub.intakeAuthoredWorldSource(
                      IntakeAuthoredWorldSourceRequest.getDefaultInstance()));
      assertThat(failure).isInstanceOf(StatusRuntimeException.class);
      assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      stopChannel(malformedCallerChannel);
    }
    verifyNoInteractions(intakeService);
  }

  @Test
  void sharedCertificateWithoutWorkloadUriCannotReachOwner() throws Exception {
    startServer(pki.worldServer(), null);
    try (WorldAuthoredSourceIntakeClient client =
        client(pki.noWorkloadUriClient(), "shared-certificate")) {
      assertPermissionDenied(() -> client.intake(INTAKE_REQUEST));
      assertPermissionDenied(() -> client.read(READ_REQUEST));
    }
    verifyNoInteractions(intakeService);
  }

  @Test
  void jwtOnlyAndPlaintextCallersFailAtThePhysicalTransportBoundary() throws Exception {
    startServer(pki.worldServer(), null);
    Metadata jwtOnlyHeaders = new Metadata();
    jwtOnlyHeaders.put(AUTHORIZATION, "Bearer header.payload.signature");
    ManagedChannel noCertificateChannel = rawMtlsChannel(null);
    try {
      assertTransportFailure(
          () ->
              WorldAuthoredSourceIntakeServiceGrpc.newBlockingStub(noCertificateChannel)
                  .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(jwtOnlyHeaders))
                  .withDeadlineAfter(3, TimeUnit.SECONDS)
                  .intakeAuthoredWorldSource(
                      WorldAuthoredSourceIntakeGrpcCodec.toIntakeRequest(INTAKE_REQUEST)));
    } finally {
      stopChannel(noCertificateChannel);
    }

    ManagedChannel plaintextChannel =
        NettyChannelBuilder.forAddress(new InetSocketAddress("127.0.0.1", server.getPort()))
            .usePlaintext()
            .build();
    try {
      assertTransportFailure(
          () ->
              WorldAuthoredSourceIntakeServiceGrpc.newBlockingStub(plaintextChannel)
                  .withDeadlineAfter(3, TimeUnit.SECONDS)
                  .intakeAuthoredWorldSource(
                      WorldAuthoredSourceIntakeGrpcCodec.toIntakeRequest(INTAKE_REQUEST)));
    } finally {
      stopChannel(plaintextChannel);
    }

    CommonGrpcClientProperties plaintext = tlsProperties(pki.gameDesignClient(), "constructor");
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new WorldAuthoredSourceIntakeClient(
                    endpoints(),
                    plaintext,
                    new net.firedevops.firemud.common.grpc.GrpcChannelFactory(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");
    verifyNoInteractions(intakeService);
  }

  @Test
  void clientRejectsAResponseFromTheWrongAuthenticatedWorldServerIdentity() throws Exception {
    startServer(pki.wrongWorldServer(), null);
    try (WorldAuthoredSourceIntakeClient client = client(pki.gameDesignClient(), "wrong-server")) {
      assertUnauthenticated(() -> client.intake(INTAKE_REQUEST));
      assertUnauthenticated(() -> client.read(READ_REQUEST));
    }
    verifyNoInteractions(intakeService);
  }

  @Test
  void changedEchoDigestAndUnknownFieldsAreRejectedAfterSocketResponse() throws Exception {
    startServer(pki.worldServer(), responseMutation(ResponseMutation.CHANGED_ECHO));
    try (WorldAuthoredSourceIntakeClient client = client(pki.gameDesignClient(), "changed-echo")) {
      assertThatThrownBy(() -> client.intake(INTAKE_REQUEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid authored-source intake receipt");
    }
    stopServer();

    startServer(pki.worldServer(), responseMutation(ResponseMutation.CHANGED_DIGEST));
    try (WorldAuthoredSourceIntakeClient client =
        client(pki.gameDesignClient(), "changed-digest")) {
      assertThatThrownBy(() -> client.intake(INTAKE_REQUEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid authored-source intake receipt");
    }
    stopServer();

    startServer(pki.worldServer(), responseMutation(ResponseMutation.UNKNOWN_FIELDS));
    try (WorldAuthoredSourceIntakeClient client =
        client(pki.gameDesignClient(), "unknown-fields")) {
      assertThatThrownBy(() -> client.intake(INTAKE_REQUEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid authored-source intake receipt");
    }
    stopServer();

    startServer(pki.worldServer(), responseMutation(ResponseMutation.CHANGED_READ_ID));
    try (WorldAuthoredSourceIntakeClient client =
        client(pki.gameDesignClient(), "changed-read-id")) {
      assertThatThrownBy(() -> client.read(READ_REQUEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid authored-source intake readback");
    }
  }

  @Test
  void publicWireMessagesNeverContainWorldPrivateNumericTenantKey() {
    List<com.google.protobuf.Descriptors.Descriptor> messages =
        List.of(
            IntakeAuthoredWorldSourceRequest.getDescriptor(),
            IntakeAuthoredWorldSourceResponse.getDescriptor(),
            ReadAuthoredWorldSourceIntakeRequest.getDescriptor(),
            ReadAuthoredWorldSourceIntakeResponse.getDescriptor());

    for (var descriptor : messages) {
      assertThat(descriptor.getFields())
          .extracting(field -> field.getName())
          .doesNotContain("local_tenant_key", "world_tenant_key", "tenant_id");
    }
    assertThat(WORLD_RECEIPT.localTenantKey()).isEqualTo(PRIVATE_WORLD_TENANT_KEY);
  }

  private void startServer(TestCertificate serverCertificate, ServerInterceptor responseInterceptor)
      throws Exception {
    intakeService = mock(WorldAuthoredSourceIntakeService.class);
    when(intakeService.intake(
            eq(1),
            eq(NAMESPACE),
            eq(INTAKE_ID),
            eq(TENANT_ID),
            eq("world-one"),
            eq(SOURCE_OPERATION_ID),
            eq(SOURCE_EVIDENCE_DIGEST)))
        .thenReturn(WORLD_RECEIPT);
    when(intakeService.readCommittedReceipt(
            eq(1),
            eq(NAMESPACE),
            eq(READ_ID),
            eq(INTAKE_ID),
            eq(TENANT_ID),
            eq("world-one"),
            eq(SOURCE_OPERATION_ID),
            eq(SOURCE_EVIDENCE_DIGEST)))
        .thenReturn(Optional.of(WORLD_RECEIPT));

    WorldAuthoredSourceIntakeGrpcService handler =
        new WorldAuthoredSourceIntakeGrpcService(intakeService, NAMESPACE);
    ServerServiceDefinition intercepted =
        ServerInterceptors.intercept(handler, new GrpcPeerIdentityInterceptor());
    if (responseInterceptor != null) {
      intercepted = ServerInterceptors.intercept(intercepted, responseInterceptor);
    }
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            serverCertificate.privateKey(), serverCertificate.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(intercepted)
            .build()
            .start();
  }

  private void stopServer() throws Exception {
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
  }

  private WorldAuthoredSourceIntakeClient client(TestCertificate certificate, String label)
      throws Exception {
    WorldAuthoredSourceIntakeClient client =
        new WorldAuthoredSourceIntakeClient(
            endpoints(),
            tlsProperties(certificate, label),
            new net.firedevops.firemud.common.grpc.GrpcChannelFactory(),
            NAMESPACE);
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
    Files.writeString(
        clientCertificate, pem("CERTIFICATE", certificate.certificate().getEncoded()));
    Files.writeString(clientPrivateKey, pem("PRIVATE KEY", certificate.privateKey().getEncoded()));
    Path caCertificate = tempDirectory.resolve("test-ca.crt");
    Files.writeString(caCertificate, pem("CERTIFICATE", pki.caCertificate().getEncoded()));

    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(clientCertificate.toString());
    tls.setPrivateKey(clientPrivateKey.toString());
    tls.setCaCert(caCertificate.toString());
    return tls;
  }

  private ManagedChannel rawMtlsChannel(TestCertificate certificate) throws Exception {
    var sslBuilder = GrpcSslContexts.forClient().trustManager(pki.caCertificate());
    if (certificate != null) {
      sslBuilder.keyManager(certificate.privateKey(), certificate.certificate());
    }
    return NettyChannelBuilder.forAddress(new InetSocketAddress("127.0.0.1", server.getPort()))
        .sslContext(sslBuilder.build())
        .build();
  }

  private static ServerInterceptor responseMutation(ResponseMutation mutation) {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        ServerCall<ReqT, RespT> forwardingCall =
            new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
              @Override
              public void sendMessage(RespT message) {
                Object changed = mutateResponse(message, mutation);
                @SuppressWarnings("unchecked")
                RespT typedChanged = (RespT) changed;
                super.sendMessage(typedChanged);
              }
            };
        return next.startCall(forwardingCall, headers);
      }
    };
  }

  private static Object mutateResponse(Object response, ResponseMutation mutation) {
    if (response instanceof IntakeAuthoredWorldSourceResponse intake) {
      return switch (mutation) {
        case CHANGED_ECHO -> intake.toBuilder().setWorldSlug("other-world").build();
        case CHANGED_DIGEST ->
            intake.toBuilder().setRequestDigest("sha256:" + "c".repeat(64)).build();
        case UNKNOWN_FIELDS -> intake.toBuilder().setUnknownFields(UNKNOWN_FIELD).build();
        case CHANGED_READ_ID -> intake;
      };
    }
    if (response instanceof ReadAuthoredWorldSourceIntakeResponse read) {
      return switch (mutation) {
        case CHANGED_READ_ID -> read.toBuilder().setRequestId(INTAKE_ID.toString()).build();
        case UNKNOWN_FIELDS -> read.toBuilder().setUnknownFields(UNKNOWN_FIELD).build();
        case CHANGED_ECHO, CHANGED_DIGEST -> read;
      };
    }
    return response;
  }

  private static void assertPublicReceipt(CommittedReceipt actual) {
    assertThat(actual.schemaVersion()).isEqualTo(1);
    assertThat(actual.targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(actual.intakeRequestId()).isEqualTo(INTAKE_ID);
    assertThat(actual.operationId()).isEqualTo(WORLD_OPERATION_ID);
    assertThat(actual.canonicalTenantId()).isEqualTo(TENANT_ID);
    assertThat(actual.worldSlug()).isEqualTo("world-one");
    assertThat(actual.sourceOperationId()).isEqualTo(SOURCE_OPERATION_ID);
    assertThat(actual.sourceEvidenceDigest()).isEqualTo(SOURCE_EVIDENCE_DIGEST);
    assertThat(actual.requestDigest()).isEqualTo(WORLD_RECEIPT.requestDigest());
    assertThat(actual.receiptDigest()).isEqualTo(WORLD_RECEIPT.receiptDigest());
  }

  private static void assertPermissionDenied(Runnable call) {
    Throwable failure = catchFailure(call);
    assertThat(failure).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
  }

  private static void assertUnauthenticated(Runnable call) {
    Throwable failure = catchFailure(call);
    assertThat(failure).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
  }

  private static void assertTransportFailure(Runnable call) {
    Throwable failure = catchFailure(call);
    assertThat(failure).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode()).isNotEqualTo(Status.Code.DEADLINE_EXCEEDED);
  }

  private static Throwable catchFailure(Runnable call) {
    try {
      call.run();
      return null;
    } catch (Throwable throwable) {
      return throwable;
    }
  }

  private static void stopChannel(ManagedChannel channel) throws InterruptedException {
    channel.shutdownNow();
    assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
  }

  private static AuthoredWorldSourceEvidence sourceEvidence() {
    UUID registrationId = UUID.fromString("62345678-1234-4234-8234-123456789abc");
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationId, TENANT_ID, "tenant-one", "world-one", "World One");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationId,
            SOURCE_OPERATION_ID,
            requestDigest,
            TENANT_ID,
            "tenant-one",
            "world-one",
            "World One",
            17L,
            "source-game-key",
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationId,
        SOURCE_OPERATION_ID,
        requestDigest,
        TENANT_ID,
        "tenant-one",
        "world-one",
        "World One",
        17L,
        "source-game-key",
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static WorldAuthoredSourceIntakeReceipt worldReceipt() {
    String requestDigest =
        WorldAuthoredSourceIntakeDigest.requestDigest(NAMESPACE, INTAKE_ID, SOURCE_EVIDENCE);
    String receiptDigest =
        WorldAuthoredSourceIntakeDigest.receiptDigest(
            NAMESPACE,
            WORLD_OPERATION_ID,
            requestDigest,
            SOURCE_EVIDENCE,
            PRIVATE_WORLD_TENANT_KEY);
    return new WorldAuthoredSourceIntakeReceipt(
        1,
        NAMESPACE,
        INTAKE_ID,
        WORLD_OPERATION_ID,
        TENANT_ID,
        "world-one",
        SOURCE_OPERATION_ID,
        SOURCE_EVIDENCE_DIGEST,
        requestDigest,
        receiptDigest,
        PRIVATE_WORLD_TENANT_KEY,
        SOURCE_EVIDENCE);
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
            new GeneralNames(subjectAltNames));
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

  private enum ResponseMutation {
    CHANGED_ECHO,
    CHANGED_DIGEST,
    UNKNOWN_FIELDS,
    CHANGED_READ_ID
  }

  private record TestCertificate(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestCertificate worldServer,
      TestCertificate wrongWorldServer,
      TestCertificate gameDesignClient,
      TestCertificate accountClient,
      TestCertificate gameSessionClient,
      TestCertificate worldClient,
      TestCertificate otherNamespaceClient,
      TestCertificate noWorkloadUriClient) {}
}
