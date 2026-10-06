package unit.net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
import io.grpc.stub.StreamObserver;
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
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.client.GameDesignFreshTenantIdentityClient;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.tenant.FreshTenantCreationReservationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.ReadFreshTenantCreationReservationRequest;
import net.firedevops.firemud.gamedesign.v1.ReadFreshTenantCreationReservationResponse;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical caller-side proof that Account authenticates Game Design before sending a read. */
class GameDesignFreshTenantReservationMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String GAME_DESIGN_PEER = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String WRONG_WORKLOAD_PEER =
      "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String WRONG_NAMESPACE_PEER =
      "spiffe://firemud/ns/other/sa/game-design-service";
  private static final String ACCOUNT_PEER = "spiffe://firemud/ns/test/sa/account-service";
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID CREATION_REQUEST_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID OPERATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID TENANT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID SUBSTITUTED_TENANT_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final String SOURCE_GAME_TENANT_KEY = "game-owner-source-17";
  private static final String NAME = "Reserved Realm";
  private static final String DESCRIPTION = "Prepared before authorization";
  private static final String REQUEST_DIGEST =
      GameTenantCreationDigest.requestDigest(
          NAMESPACE, CREATION_REQUEST_ID, SOURCE_GAME_TENANT_KEY, NAME, DESCRIPTION);
  private static final AtomicLong CERTIFICATE_SERIAL = new AtomicLong(1L);

  @Test
  void authenticServerReturnsExactReservationAndConfirmsAccountClientCertificate(
      @TempDir Path directory) throws Exception {
    TestPki pki = newTestPki();
    PhysicalServer server = startReceiver(pki.gameDesignServer(), pki.caCertificate(), false);
    GameDesignFreshTenantIdentityClient client =
        newClient(server.server().getPort(), pki, directory.resolve("valid"));
    try {
      client.init();
      FreshTenantCreationReservationEvidence evidence = read(client);

      assertThat(evidence.targetNamespace()).isEqualTo(NAMESPACE);
      assertThat(evidence.creationRequestId()).isEqualTo(CREATION_REQUEST_ID);
      assertThat(evidence.requestDigest()).isEqualTo(REQUEST_DIGEST);
      assertThat(evidence.operationId()).isEqualTo(OPERATION_ID);
      assertThat(evidence.canonicalTenantId()).isEqualTo(TENANT_ID);
      assertThat(evidence.sourceGameTenantKey()).isEqualTo(SOURCE_GAME_TENANT_KEY);
      assertThat(evidence.name()).isEqualTo(NAME);
      assertThat(evidence.description()).isEqualTo(DESCRIPTION);

      assertThat(server.applicationMetadataCalls()).hasValue(1);
      assertThat(server.requestBodies()).hasValue(1);
      assertThat(server.ownerReads()).hasValue(1);
      assertThat(server.request().get()).isEqualTo(request());
      assertThat(server.authenticatedCallerPeer().get()).isEqualTo(ACCOUNT_PEER);
    } finally {
      client.close();
      stopServer(server.server());
    }
  }

  @Test
  void wrongTrustedServerWorkloadAndNamespaceReceiveNoRequestMetadataOrBody(@TempDir Path directory)
      throws Exception {
    TestPki pki = newTestPki();
    for (TestCertificate wrongServer :
        List.of(pki.wrongWorkloadServer(), pki.wrongNamespaceServer())) {
      PhysicalServer server = startReceiver(wrongServer, pki.caCertificate(), false);
      GameDesignFreshTenantIdentityClient client =
          newClient(
              server.server().getPort(),
              pki,
              directory.resolve(Integer.toString(server.server().getPort())));
      try {
        client.init();
        Throwable failure = catchThrowable(() -> read(client));

        assertThat(failure).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) failure).getStatus().getCode())
            .isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(TlsTestSupport.isTlsHandshakeRejection(failure)).isFalse();
        assertThat(server.applicationMetadataCalls()).hasValue(0);
        assertThat(server.requestBodies()).hasValue(0);
        assertThat(server.ownerReads()).hasValue(0);
        assertThat(server.request().get()).isNull();
      } finally {
        client.close();
        stopServer(server.server());
      }
    }
  }

  @Test
  void authenticatedServerCannotSubstituteACompleteDifferentReservedTarget(@TempDir Path directory)
      throws Exception {
    TestPki pki = newTestPki();
    PhysicalServer server = startReceiver(pki.gameDesignServer(), pki.caCertificate(), true);
    GameDesignFreshTenantIdentityClient client =
        newClient(server.server().getPort(), pki, directory.resolve("substituted"));
    try {
      client.init();
      Throwable failure = catchThrowable(() -> read(client));

      assertThat(failure).isInstanceOf(IllegalStateException.class);
      assertThat(server.applicationMetadataCalls()).hasValue(1);
      assertThat(server.requestBodies()).hasValue(1);
      assertThat(server.ownerReads()).hasValue(1);
      assertThat(server.authenticatedCallerPeer().get()).isEqualTo(ACCOUNT_PEER);
      assertThat(server.request().get()).isEqualTo(request());
    } finally {
      client.close();
      stopServer(server.server());
    }
  }

  private static FreshTenantCreationReservationEvidence read(
      GameDesignFreshTenantIdentityClient client) {
    return client.readCreationReservation(
        READ_REQUEST_ID, CREATION_REQUEST_ID, REQUEST_DIGEST, OPERATION_ID, TENANT_ID);
  }

  private static ReadFreshTenantCreationReservationRequest request() {
    return ReadFreshTenantCreationReservationRequest.newBuilder()
        .setSchemaVersion(1)
        .setTargetNamespace(NAMESPACE)
        .setReadRequestId(READ_REQUEST_ID.toString())
        .setCreationRequestId(CREATION_REQUEST_ID.toString())
        .setExpectedRequestDigest(REQUEST_DIGEST)
        .setExpectedCreationOperationId(OPERATION_ID.toString())
        .setExpectedCanonicalTenantId(TENANT_ID.toString())
        .build();
  }

  private GameDesignFreshTenantIdentityClient newClient(int port, TestPki pki, Path directory)
      throws IOException, CertificateEncodingException {
    Files.createDirectories(directory);
    Path ca =
        writePem(directory.resolve("test-ca.crt"), "CERTIFICATE", pki.caCertificate().getEncoded());
    Path certificate =
        writePem(
            directory.resolve("account-client.crt"),
            "CERTIFICATE",
            pki.accountClient().certificate().getEncoded());
    Path privateKey =
        writePem(
            directory.resolve("account-client.key"),
            "PRIVATE KEY",
            pki.accountClient().privateKey().getEncoded());

    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("localhost:" + port);
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate.toString());
    tls.setPrivateKey(privateKey.toString());
    tls.setCaCert(ca.toString());
    return new GameDesignFreshTenantIdentityClient(
        endpoints, tls, new GrpcChannelFactory(), NAMESPACE);
  }

  private PhysicalServer startReceiver(
      TestCertificate serverCertificate, X509Certificate caCertificate, boolean substituteTenant)
      throws Exception {
    AtomicInteger applicationMetadataCalls = new AtomicInteger();
    AtomicInteger requestBodies = new AtomicInteger();
    AtomicInteger ownerReads = new AtomicInteger();
    AtomicReference<ReadFreshTenantCreationReservationRequest> capturedRequest =
        new AtomicReference<>();
    AtomicReference<String> authenticatedCallerPeer = new AtomicReference<>();

    // This generated-service test double proves transport/client decoding only; its ownerReads
    // counter is not persistence, Account authorization, creator qualification, or activation.
    TenantIdentityServiceGrpc.TenantIdentityServiceImplBase service =
        new TenantIdentityServiceGrpc.TenantIdentityServiceImplBase() {
          @Override
          public void readFreshTenantCreationReservation(
              ReadFreshTenantCreationReservationRequest request,
              StreamObserver<ReadFreshTenantCreationReservationResponse> responseObserver) {
            requestBodies.incrementAndGet();
            capturedRequest.set(request);
            GrpcPeerIdentity peer = GrpcPeerIdentity.current();
            authenticatedCallerPeer.set(peer == null ? null : peer.uri());
            ownerReads.incrementAndGet();

            UUID tenantId = substituteTenant ? SUBSTITUTED_TENANT_ID : TENANT_ID;
            FreshTenantCreationReservationEvidence evidence =
                FreshTenantCreationReservationEvidence.fromReservation(
                    1,
                    NAMESPACE,
                    CREATION_REQUEST_ID,
                    REQUEST_DIGEST,
                    OPERATION_ID,
                    tenantId,
                    SOURCE_GAME_TENANT_KEY,
                    NAME,
                    DESCRIPTION);
            responseObserver.onNext(
                ReadFreshTenantCreationReservationResponse.newBuilder()
                    .setSchemaVersion(evidence.schemaVersion())
                    .setTargetNamespace(evidence.targetNamespace())
                    .setReadRequestId(request.getReadRequestId())
                    .setCreationRequestId(evidence.creationRequestId().toString())
                    .setRequestDigest(evidence.requestDigest())
                    .setCreationOperationId(evidence.operationId().toString())
                    .setCanonicalTenantId(evidence.canonicalTenantId().toString())
                    .setSourceGameTenantKey(evidence.sourceGameTenantKey())
                    .setName(evidence.name())
                    .setDescription(evidence.description())
                    .setEvidenceDigest(evidence.evidenceDigest())
                    .build());
            responseObserver.onCompleted();
          }
        };
    ServerInterceptor metadataCounter =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            applicationMetadataCalls.incrementAndGet();
            return next.startCall(call, headers);
          }
        };
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            serverCertificate.privateKey(), serverCertificate.certificate()))
                    .trustManager(caCertificate)
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service, metadataCounter, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    return new PhysicalServer(
        server,
        applicationMetadataCalls,
        requestBodies,
        ownerReads,
        capturedRequest,
        authenticatedCallerPeer);
  }

  private static void stopServer(Server server) throws InterruptedException {
    server.shutdownNow();
    assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
  }

  private static TestPki newTestPki() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPair caKeyPair = newRsaKeyPair();
    X500Name caName = new X500Name("CN=Reservation mTLS Test CA, O=FireMUD Test");
    X509Certificate caCertificate =
        issueCertificate(caName, caKeyPair.getPublic(), caName, caKeyPair.getPrivate(), true, null);
    return new TestPki(
        caCertificate,
        issueLeaf(caName, caKeyPair.getPrivate(), "account-service", ACCOUNT_PEER),
        issueLeaf(caName, caKeyPair.getPrivate(), "game-design-service", GAME_DESIGN_PEER),
        issueLeaf(caName, caKeyPair.getPrivate(), "world-management-service", WRONG_WORKLOAD_PEER),
        issueLeaf(
            caName, caKeyPair.getPrivate(), "other-namespace-game-design", WRONG_NAMESPACE_PEER));
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
    Instant notBefore = Instant.now().minusSeconds(60);
    JcaX509v3CertificateBuilder builder =
        new JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(CERTIFICATE_SERIAL.getAndIncrement()),
            Date.from(notBefore),
            Date.from(notBefore.plusSeconds(60L * 60L * 24L * 14L)),
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

  private static Path writePem(Path path, String type, byte[] encoded) throws IOException {
    String base64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
    Files.writeString(
        path,
        "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n",
        StandardCharsets.US_ASCII);
    return path;
  }

  private record TestCertificate(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestCertificate accountClient,
      TestCertificate gameDesignServer,
      TestCertificate wrongWorkloadServer,
      TestCertificate wrongNamespaceServer) {}

  private record PhysicalServer(
      Server server,
      AtomicInteger applicationMetadataCalls,
      AtomicInteger requestBodies,
      AtomicInteger ownerReads,
      AtomicReference<ReadFreshTenantCreationReservationRequest> request,
      AtomicReference<String> authenticatedCallerPeer) {}
}
