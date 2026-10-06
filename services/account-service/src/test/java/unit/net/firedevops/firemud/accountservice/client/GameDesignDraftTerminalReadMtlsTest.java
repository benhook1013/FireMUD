package unit.net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.protobuf.ByteString;
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
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadEvidence;
import net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadGrpcCodec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.gamedesign.v1.GameDesignDraftTerminalReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.GameDesignDraftTerminalReadStatus;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignDraftTerminalOutcomeRequest;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignDraftTerminalOutcomeResponse;
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

/**
 * Physical Account-client mTLS proof; the receiver is a transport-only generated-service double.
 */
class GameDesignDraftTerminalReadMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_PEER = "spiffe://firemud/ns/test/sa/account-service";
  private static final String GAME_DESIGN_PEER = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String WRONG_WORKLOAD_PEER =
      "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String WRONG_NAMESPACE_PEER =
      "spiffe://firemud/ns/other/sa/game-design-service";
  private static final UUID OPERATION_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID FENCE_ID = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID ACTOR_ID = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID TENANT_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID READ_REQUEST_ID = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID REVISION_ID = uuid("66666666-6666-4666-8666-666666666666");
  private static final AtomicLong CERTIFICATE_SERIAL = new AtomicLong(1L);

  @Test
  void physicallyReadsCanonicalOutcomeAndWithholdsRequestFromWrongServerPeer(@TempDir Path dir)
      throws Exception {
    TestPki pki = newTestPki();
    PhysicalServer server =
        startReceiver(pki.gameDesignServer(), pki.caCertificate(), Reply.COMMITTED);
    GameDesignDraftTerminalReadClient client = newClient(server.server().getPort(), pki, dir);
    try {
      client.init();
      var request = request();
      var evidence = client.read(request);

      assertThat(evidence.ownerReadback()).isPresent();
      assertThat(evidence.ownerReadback().orElseThrow().owner()).isEqualTo(Owner.GAME_DESIGN);
      assertThat(evidence.ownerReadback().orElseThrow().outcome()).isEqualTo(Outcome.COMMITTED);
      assertThat(evidence.ownerReadback().orElseThrow().fullBinding())
          .containsExactly(request.originalAccountBinding());
      assertThat(server.applicationMetadataCalls()).hasValue(1);
      assertThat(server.requestBodies()).hasValue(1);
      assertThat(server.request().get())
          .isEqualTo(GameDesignDraftTerminalReadGrpcCodec.toRequest(request));
      assertThat(server.authenticatedCallerPeer().get()).isEqualTo(ACCOUNT_PEER);
    } finally {
      client.close();
      stopServer(server.server());
    }

    int index = 0;
    for (TestCertificate wrongIdentity :
        List.of(pki.wrongWorkloadServer(), pki.wrongNamespaceServer())) {
      PhysicalServer wrongServer = startReceiver(wrongIdentity, pki.caCertificate(), Reply.UNKNOWN);
      GameDesignDraftTerminalReadClient wrongClient =
          newClient(wrongServer.server().getPort(), pki, dir.resolve("wrong-" + index++));
      try {
        wrongClient.init();
        Throwable failure = catchThrowable(() -> wrongClient.read(request()));

        assertThat(failure).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) failure).getStatus().getCode())
            .isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(TlsTestSupport.isTlsHandshakeRejection(failure)).isFalse();
        assertThat(wrongServer.applicationMetadataCalls()).hasValue(0);
        assertThat(wrongServer.requestBodies()).hasValue(0);
        assertThat(wrongServer.request().get()).isNull();
      } finally {
        wrongClient.close();
        stopServer(wrongServer.server());
      }
    }
  }

  @Test
  void physicallyReturnsUnknownWithoutInventingAnOutcome(@TempDir Path dir) throws Exception {
    TestPki pki = newTestPki();
    PhysicalServer server =
        startReceiver(pki.gameDesignServer(), pki.caCertificate(), Reply.UNKNOWN);
    GameDesignDraftTerminalReadClient client = newClient(server.server().getPort(), pki, dir);
    try {
      client.init();
      var request = request();
      var evidence = client.read(request);

      assertThat(evidence.ownerReadback()).isEmpty();
      assertThat(server.applicationMetadataCalls()).hasValue(1);
      assertThat(server.requestBodies()).hasValue(1);
      assertThat(server.authenticatedCallerPeer().get()).isEqualTo(ACCOUNT_PEER);
    } finally {
      client.close();
      stopServer(server.server());
    }
  }

  private static GameDesignDraftTerminalReadEvidence.Request request() {
    return new GameDesignDraftTerminalReadEvidence.Request(
        1, NAMESPACE, READ_REQUEST_ID, originalAccountBinding());
  }

  private static byte[] originalAccountBinding() {
    DraftCommitBinding gameDesign =
        DraftCommitBinding.create(
            new TargetProof(
                TENANT_ID, VERSION_ID, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
            REQUEST_ID,
            COMMIT_ID,
            "base-1",
            List.of(
                new RevisionPayload(
                    "0", REVISION_ID, DraftCommitBinding.Owner.WORLD_MANAGEMENT, "{}")),
            List.of(
                new AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "WORLD_TEMPLATE",
                    "world-1",
                    "ROOM_SCOPE",
                    "room-1",
                    "0")));
    byte[] draftBytes = gameDesign.canonicalBytes();
    return new DraftAuthorizationFenceBinding(
            OPERATION_ID,
            REQUEST_ID,
            COMMIT_ID,
            FENCE_ID,
            ACTOR_ID,
            TENANT_ID,
            VERSION_ID,
            "base-1",
            "0",
            draftBytes,
            draftBytes,
            gameDesign.digest(),
            List.of(
                new SourceEvidence(
                    SourceKind.GLOBAL_ROLES,
                    uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd").toString(),
                    null,
                    "1",
                    null,
                    null,
                    new byte[] {1, 2, 3})))
        .canonicalBytes();
  }

  private static PhysicalServer startReceiver(
      TestCertificate serverCertificate, X509Certificate caCertificate, Reply reply)
      throws Exception {
    AtomicInteger applicationMetadataCalls = new AtomicInteger();
    AtomicInteger requestBodies = new AtomicInteger();
    AtomicReference<ReadGameDesignDraftTerminalOutcomeRequest> capturedRequest =
        new AtomicReference<>();
    AtomicReference<String> authenticatedCallerPeer = new AtomicReference<>();
    var originalBinding = DraftAuthorizationFenceBinding.fromStored(originalAccountBinding());
    var ownerReadback =
        new DraftAuthorizationFenceBinding.OwnerReadback(
            Owner.GAME_DESIGN,
            Outcome.COMMITTED,
            originalBinding.operationId(),
            originalBinding.commitId(),
            originalBinding.fenceId(),
            originalBinding.inputDigest(),
            originalBinding.canonicalBytes(),
            new byte[] {1, 2, 3});

    // This receiver double proves transport and client decoding only, not owner persistence,
    // Account source authentication, authorization, or participant settlement.
    GameDesignDraftTerminalReadServiceGrpc.GameDesignDraftTerminalReadServiceImplBase service =
        new GameDesignDraftTerminalReadServiceGrpc.GameDesignDraftTerminalReadServiceImplBase() {
          @Override
          public void readGameDesignDraftTerminalOutcome(
              ReadGameDesignDraftTerminalOutcomeRequest request,
              StreamObserver<ReadGameDesignDraftTerminalOutcomeResponse> responseObserver) {
            requestBodies.incrementAndGet();
            capturedRequest.set(request);
            GrpcPeerIdentity peer = GrpcPeerIdentity.current();
            authenticatedCallerPeer.set(peer == null ? null : peer.uri());
            var builder =
                ReadGameDesignDraftTerminalOutcomeResponse.newBuilder()
                    .setSchemaVersion(request.getSchemaVersion())
                    .setTargetNamespace(request.getTargetNamespace())
                    .setReadRequestId(request.getReadRequestId())
                    .setOriginalAccountBinding(request.getOriginalAccountBinding());
            if (reply == Reply.UNKNOWN) {
              builder.setStatus(
                  GameDesignDraftTerminalReadStatus.GAME_DESIGN_DRAFT_TERMINAL_READ_STATUS_UNKNOWN);
            } else {
              builder
                  .setStatus(
                      GameDesignDraftTerminalReadStatus
                          .GAME_DESIGN_DRAFT_TERMINAL_READ_STATUS_COMMITTED)
                  .setOwnerReadbackBytes(ByteString.copyFrom(ownerReadback.canonicalBytes()));
            }
            responseObserver.onNext(builder.build());
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
        server, applicationMetadataCalls, requestBodies, capturedRequest, authenticatedCallerPeer);
  }

  private static GameDesignDraftTerminalReadClient newClient(int port, TestPki pki, Path directory)
      throws Exception {
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
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate.toString());
    tls.setPrivateKey(privateKey.toString());
    tls.setCaCert(ca.toString());
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("localhost:" + port);
    return new GameDesignDraftTerminalReadClient(
        endpoints, tls, new GrpcChannelFactory(), NAMESPACE);
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
    X500Name caName = new X500Name("CN=Game Design terminal mTLS Test CA, O=FireMUD Test");
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
    return new TestCertificate(
        keyPair.getPrivate(),
        issueCertificate(
            subject, keyPair.getPublic(), caName, caPrivateKey, false, subjectAltNames));
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
            Date.from(notBefore.plusSeconds(14L * 24L * 60L * 60L)),
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

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private enum Reply {
    UNKNOWN,
    COMMITTED
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
      AtomicReference<ReadGameDesignDraftTerminalOutcomeRequest> request,
      AtomicReference<String> authenticatedCallerPeer) {}
}
