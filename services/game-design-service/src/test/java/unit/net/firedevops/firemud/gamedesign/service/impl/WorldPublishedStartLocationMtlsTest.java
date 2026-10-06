package unit.net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationClient;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationGrpcCodec;
import net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures;
import net.firedevops.firemud.test.TlsTestSupport;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedStartLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedStartLocationResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldPublishedStartLocationReadServiceGrpc;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical mTLS proof of the selector client's pre-send and response peer identity checks. */
class WorldPublishedStartLocationMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String GAME_DESIGN_PEER = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String WORLD_PEER = "spiffe://firemud/ns/test/sa/world-management-service";
  private static final AtomicLong CERTIFICATE_SERIAL = new AtomicLong(1L);
  private static TestPki pki;

  @BeforeAll
  static void generateEphemeralPki() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPair caKeyPair = newRsaKeyPair();
    X500Name caName = new X500Name("CN=World Published Selector Test CA, O=FireMUD Test");
    X509Certificate caCertificate =
        issueCertificate(caName, caKeyPair.getPublic(), caName, caKeyPair.getPrivate(), true);
    pki =
        new TestPki(
            caCertificate,
            issueLeaf(caName, caKeyPair.getPrivate(), "game-design-service", GAME_DESIGN_PEER),
            issueLeaf(caName, caKeyPair.getPrivate(), "world-management-service", WORLD_PEER),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "wrong-service-world-server",
                "spiffe://firemud/ns/test/sa/game-design-service"),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "wrong-namespace-world-server",
                "spiffe://firemud/ns/other-test/sa/world-management-service"));
  }

  @Test
  void wrongWorldServiceOrNamespaceSendsNoRpcMetadataOrRequestBodyButExactPeerSucceeds(
      @TempDir Path directory) throws Exception {
    WorldPublishedStartLocationEvidence evidence = evidence();
    int index = 0;
    for (TestCertificate wrongServer :
        List.of(pki.wrongServiceServer(), pki.wrongNamespaceServer())) {
      PhysicalServer receiver = startReceiver(wrongServer, evidence);
      try (WorldPublishedStartLocationClient client =
          newClient(receiver.server().getPort(), directory.resolve("wrong-" + index++))) {
        client.init();
        Throwable failure = catchThrowable(() -> client.read(evidence.request()));

        assertThat(failure).isInstanceOf(StatusRuntimeException.class);
        assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(TlsTestSupport.isTlsHandshakeRejection(failure)).isFalse();
        assertThat(receiver.requestMetadataCalls()).hasValue(0);
        assertThat(receiver.requestBodies()).hasValue(0);
        assertThat(receiver.request()).hasValue(null);
      } finally {
        stopServer(receiver.server());
      }
    }

    PhysicalServer receiver = startReceiver(pki.worldServer(), evidence);
    try (WorldPublishedStartLocationClient client =
        newClient(receiver.server().getPort(), directory.resolve("exact-world-peer"))) {
      client.init();
      WorldPublishedStartLocationEvidence received = client.read(evidence.request());

      assertThat(received).isEqualTo(evidence);
      assertThat(receiver.requestMetadataCalls()).hasValue(1);
      assertThat(receiver.requestBodies()).hasValue(1);
      assertThat(receiver.authenticatedCallerPeer()).hasValue(GAME_DESIGN_PEER);
      assertThat(receiver.request())
          .hasValue(WorldPublishedStartLocationGrpcCodec.toRequest(evidence.request()));
    } finally {
      stopServer(receiver.server());
    }
  }

  private static PhysicalServer startReceiver(
      TestCertificate serverCertificate, WorldPublishedStartLocationEvidence evidence)
      throws Exception {
    AtomicInteger requestMetadataCalls = new AtomicInteger();
    AtomicInteger requestBodies = new AtomicInteger();
    AtomicReference<ReadWorldPublishedStartLocationRequest> request = new AtomicReference<>();
    AtomicReference<String> authenticatedCallerPeer = new AtomicReference<>();
    ReadWorldPublishedStartLocationResponse response =
        WorldPublishedStartLocationGrpcCodec.toResponse(evidence.request(), evidence);
    var service =
        new WorldPublishedStartLocationReadServiceGrpc
            .WorldPublishedStartLocationReadServiceImplBase() {
          @Override
          public void readWorldPublishedStartLocation(
              ReadWorldPublishedStartLocationRequest readRequest,
              StreamObserver<ReadWorldPublishedStartLocationResponse> responseObserver) {
            requestBodies.incrementAndGet();
            request.set(readRequest);
            GrpcPeerIdentity peer = GrpcPeerIdentity.current();
            authenticatedCallerPeer.set(peer == null ? null : peer.uri());
            responseObserver.onNext(response);
            responseObserver.onCompleted();
          }
        };
    ServerInterceptor metadataCounter =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              io.grpc.Metadata headers,
              ServerCallHandler<ReqT, RespT> next) {
            requestMetadataCalls.incrementAndGet();
            return next.startCall(call, headers);
          }
        };
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            serverCertificate.privateKey(), serverCertificate.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service, metadataCounter, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    return new PhysicalServer(
        server, requestMetadataCalls, requestBodies, request, authenticatedCallerPeer);
  }

  private static WorldPublishedStartLocationClient newClient(int port, Path directory)
      throws Exception {
    Files.createDirectories(directory);
    Path clientCertificate =
        writePem(
            directory.resolve("game-design-client.crt"),
            "CERTIFICATE",
            pki.gameDesignClient().certificate().getEncoded());
    Path clientPrivateKey =
        writePem(
            directory.resolve("game-design-client.key"),
            "PRIVATE KEY",
            pki.gameDesignClient().privateKey().getEncoded());
    Path caCertificate =
        writePem(directory.resolve("test-ca.crt"), "CERTIFICATE", pki.caCertificate().getEncoded());
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(clientCertificate.toString());
    tls.setPrivateKey(clientPrivateKey.toString());
    tls.setCaCert(caCertificate.toString());
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("127.0.0.1:" + port);
    return new WorldPublishedStartLocationClient(
        endpoints, tls, new GrpcChannelFactory(), NAMESPACE);
  }

  private static WorldPublishedStartLocationEvidence evidence() throws Exception {
    return PublishedWorldSelectorFixtures.evidence(
        new TargetProof(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            19L,
            "tenant-key",
            42L,
            "tenant-key",
            "NEW_GAME_ROW"));
  }

  private static void stopServer(Server server) throws InterruptedException {
    server.shutdownNow();
    assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
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
      boolean ca)
      throws Exception {
    return issueCertificate(subject, publicKey, issuer, issuerPrivateKey, ca, null);
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
        path, "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n");
    return path;
  }

  private record TestCertificate(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestCertificate gameDesignClient,
      TestCertificate worldServer,
      TestCertificate wrongServiceServer,
      TestCertificate wrongNamespaceServer) {}

  private record PhysicalServer(
      Server server,
      AtomicInteger requestMetadataCalls,
      AtomicInteger requestBodies,
      AtomicReference<ReadWorldPublishedStartLocationRequest> request,
      AtomicReference<String> authenticatedCallerPeer) {}
}
