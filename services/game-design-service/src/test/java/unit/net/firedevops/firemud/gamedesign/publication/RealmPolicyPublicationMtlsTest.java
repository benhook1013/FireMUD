package unit.net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyReadGrpcCodec;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyPublicationGrpcService;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyPublicationService;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesRequest;
import net.firedevops.firemud.gamedesign.v1.PublishedRealmEntryPolicyServiceGrpc;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Loopback transport proof for the production handler/interceptor; owner data stays isolated. */
class RealmPolicyPublicationMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String SERVER_URI = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final AtomicLong CERTIFICATE_SERIAL = new AtomicLong(1);
  private static TestPki pki;

  @BeforeAll
  static void generateEphemeralPki() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    var pair = newRsaKeyPair();
    var name = new X500Name("CN=Realm Policy Test CA, O=FireMUD Test");
    var ca = issueCertificate(name, pair.getPublic(), name, pair.getPrivate(), true);
    pki =
        new TestPki(
            ca,
            issueLeaf(name, pair.getPrivate(), "game-design-server", SERVER_URI),
            issueLeaf(
                name,
                pair.getPrivate(),
                "game-session-client",
                "spiffe://firemud/ns/test/sa/game-session-service"),
            issueLeaf(
                name,
                pair.getPrivate(),
                "wrong-service-client",
                "spiffe://firemud/ns/test/sa/account-service"),
            issueLeaf(
                name,
                pair.getPrivate(),
                "wrong-namespace-client",
                "spiffe://firemud/ns/other/sa/game-session-service"));
  }

  @Test
  void trustedWrongServiceAndNamespaceDenyBeforeMalformedDecodeOrOwnerRead() throws Exception {
    var owner = mock(RealmPolicyPublicationService.class);
    var server = server(owner);
    try {
      for (var identity : List.of(pki.wrongService(), pki.wrongNamespace())) {
        var channel = channel(server, identity);
        try {
          assertThat(
                  listFailure(channel, ListPublishedRealmEntryPoliciesRequest.getDefaultInstance()))
              .isEqualTo(Status.Code.PERMISSION_DENIED);
        } finally {
          close(channel);
        }
      }
      verifyNoInteractions(owner);
    } finally {
      stopServer(server);
    }
  }

  @Test
  void exactGameSessionPeerReadsTheFullSetAndResolvesOnlyItsExactSelector() throws Exception {
    var fixture = RealmPolicyPublicationGrpcServiceTest.publishedSet();
    var owner = mock(RealmPolicyPublicationService.class);
    when(owner.readPublishedSet(
            fixture.target().canonicalTenantId(), fixture.target().canonicalVersionId()))
        .thenReturn(Optional.of(fixture.set()));
    var server = server(owner);
    try {
      var channel = channel(server, pki.gameSession());
      try {
        var listRequest = RealmPolicyPublicationGrpcServiceTest.listRequest(fixture);
        var listed =
            stub(channel)
                .listPublishedRealmEntryPolicies(
                    PublishedRealmEntryPolicyReadGrpcCodec.toRequest(listRequest));
        var complete = PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(listRequest, listed);
        assertThat(complete.policyCount()).isEqualTo(fixture.set().policyCount());
        assertThat(complete.policies()).hasSize(fixture.set().policyCount());
        assertThat(complete.policySetDigest()).isEqualTo(fixture.set().policySetDigest());
        assertThat(complete.operationBytes()).isEqualTo(fixture.set().operationBytes());
        assertThat(complete.captureBytes()).isEqualTo(fixture.set().captureBytes());
        assertThat(complete.terminalEvidenceBytes())
            .isEqualTo(fixture.set().terminalEvidenceBytes());

        var resolveRequest = RealmPolicyPublicationGrpcServiceTest.resolveRequest(fixture, "main");
        var resolved =
            stub(channel)
                .resolvePublishedRealmEntryPolicy(
                    PublishedRealmEntryPolicyReadGrpcCodec.toRequest(resolveRequest));
        var selected =
            PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(resolveRequest, resolved);
        assertThat(selected).isEqualTo(complete);
        assertThat(selected.policies())
            .anySatisfy(
                policy -> {
                  assertThat(policy.policy().worldSlug()).isEqualTo("earth");
                  assertThat(policy.policy().realmSlug()).isEqualTo("main");
                });
        verify(owner, times(2))
            .readPublishedSet(
                fixture.target().canonicalTenantId(), fixture.target().canonicalVersionId());
      } finally {
        close(channel);
      }
    } finally {
      stopServer(server);
    }
  }

  @Test
  void exactPeerRejectsMalformedAndMismatchedNamespaceBeforeOwnerRead() throws Exception {
    var owner = mock(RealmPolicyPublicationService.class);
    var server = server(owner);
    try {
      var channel = channel(server, pki.gameSession());
      try {
        assertThat(
                listFailure(channel, ListPublishedRealmEntryPoliciesRequest.getDefaultInstance()))
            .isEqualTo(Status.Code.INVALID_ARGUMENT);

        var fixture = RealmPolicyPublicationGrpcServiceTest.publishedSet();
        var mismatchedNamespace =
            PublishedRealmEntryPolicyReadGrpcCodec.toRequest(
                    RealmPolicyPublicationGrpcServiceTest.listRequest(fixture))
                .toBuilder()
                .setTargetNamespace("other")
                .build();
        assertThat(listFailure(channel, mismatchedNamespace))
            .isEqualTo(Status.Code.PERMISSION_DENIED);
      } finally {
        close(channel);
      }
      verifyNoInteractions(owner);
    } finally {
      stopServer(server);
    }
  }

  private static Server server(RealmPolicyPublicationService owner) throws Exception {
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(
                        pki.server().privateKey(), pki.server().certificate()))
                .trustManager(pki.caCertificate())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(
            ServerInterceptors.intercept(
                new RealmPolicyPublicationGrpcService(owner, NAMESPACE),
                new GrpcPeerIdentityInterceptor(),
                new AuthTokenInterceptor(
                    new JwtUtil("testsecretkeytestsecretkeytest1234", 60_000L),
                    Set.of(
                        PublishedRealmEntryPolicyServiceGrpc
                            .getResolvePublishedRealmEntryPolicyMethod()
                            .getFullMethodName(),
                        PublishedRealmEntryPolicyServiceGrpc
                            .getListPublishedRealmEntryPoliciesMethod()
                            .getFullMethodName()))))
        .build()
        .start();
  }

  private static ManagedChannel channel(Server server, TestCertificate identity) throws Exception {
    return NettyChannelBuilder.forAddress("localhost", server.getPort())
        .sslContext(
            GrpcSslContexts.forClient()
                .trustManager(pki.caCertificate())
                .keyManager(identity.privateKey(), identity.certificate())
                .build())
        .build();
  }

  private static PublishedRealmEntryPolicyServiceGrpc.PublishedRealmEntryPolicyServiceBlockingStub
      stub(ManagedChannel channel) {
    return PublishedRealmEntryPolicyServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(SERVER_URI))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(SERVER_URI))
        .withDeadlineAfter(5, TimeUnit.SECONDS);
  }

  private static Status.Code listFailure(
      ManagedChannel channel, ListPublishedRealmEntryPoliciesRequest request) {
    try {
      stub(channel).listPublishedRealmEntryPolicies(request);
      return null;
    } catch (StatusRuntimeException failure) {
      assertThat(TlsTestSupport.isTlsHandshakeRejection(failure)).isFalse();
      return Status.fromThrowable(failure).getCode();
    }
  }

  private static void close(ManagedChannel channel) throws InterruptedException {
    channel.shutdownNow();
    assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
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

  private record TestCertificate(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestCertificate server,
      TestCertificate gameSession,
      TestCertificate wrongService,
      TestCertificate wrongNamespace) {}
}
