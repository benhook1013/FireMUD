package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AccountJwtReadinessPodReceiverServiceGrpc;
import net.firedevops.firemud.account.v1.AccountPodTargetBinding;
import net.firedevops.firemud.account.v1.AccountSourceIdentity;
import net.firedevops.firemud.account.v1.ReadinessProbeCoordinates;
import net.firedevops.firemud.account.v1.ReadinessReceiverLocalIdentity;
import net.firedevops.firemud.account.v1.ReceiveAccountJwtReadinessProbeRequest;
import net.firedevops.firemud.account.v1.ReceiveAccountJwtReadinessProbeResponse;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.Invocation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.PodTarget;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.ProbeExpectation;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.ProbeKind;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.gamesession.v1.GameSessionJwtReadinessReceiverServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeRequest;
import net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslManagerBundle;

class AccountJwtReadinessGrpcReceiverInvocationPortTest {
  private static final String PEER_URI = "spiffe://firemud/ns/firemud-prod/sa/game-session-service";
  private static final String OTHER_PEER_URI =
      "spiffe://firemud/ns/firemud-prod/sa/account-service";
  private static final char[] KEY_PASSWORD = "test-only-password".toCharArray();

  @TempDir private static Path temporaryDirectory;

  private static TestPki pki;

  private final AtomicInteger handlerCalls = new AtomicInteger();
  private Server server;

  @BeforeAll
  static void createEphemeralTlsFixtures() throws Exception {
    Path trustedDirectory = Files.createDirectories(temporaryDirectory.resolve("trusted"));
    TestAuthority trusted = createAuthority(trustedDirectory, "account-readiness-test-ca");
    TestCertificate accountClient =
        issueLeaf(
            trustedDirectory,
            trusted,
            "account-client",
            "spiffe://firemud/ns/firemud-prod/sa/account-service",
            false);
    TestCertificate pinnedServer =
        issueLeaf(trustedDirectory, trusted, "game-session-pinned", PEER_URI, true);
    TestCertificate sameSanDifferentKey =
        issueLeaf(trustedDirectory, trusted, "game-session-other-key", PEER_URI, true);
    TestCertificate wrongSan =
        issueLeaf(trustedDirectory, trusted, "wrong-game-session-san", OTHER_PEER_URI, true);
    TestCertificate accountServer =
        issueLeaf(
            trustedDirectory,
            trusted,
            "account-pod-receiver",
            "spiffe://firemud/ns/firemud-prod/sa/account-service",
            true);

    Path untrustedDirectory = Files.createDirectories(temporaryDirectory.resolve("untrusted"));
    TestAuthority untrusted = createAuthority(untrustedDirectory, "untrusted-readiness-ca");
    TestCertificate untrustedServer =
        issueLeaf(untrustedDirectory, untrusted, "untrusted-game-session", PEER_URI, true);
    pki =
        new TestPki(
            trusted.certificate(),
            accountClient,
            pinnedServer,
            sameSanDifferentKey,
            wrongSan,
            accountServer,
            untrustedServer);
  }

  @AfterEach
  void stopServer() throws InterruptedException {
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void wrongServerUriSanIsRejectedDuringTlsBeforeProbeRpcDispatch() throws Exception {
    startServer(pki.wrongSan(), pki.trustedCa());
    assertProbeRejected(
        invocation(pki.wrongSan(), server.getPort()), pki.accountClient(), pki.trustedCa());
    assertThat(handlerCalls.get()).isZero();
  }

  @Test
  void sameUriWithWrongPodSpecificSpkiIsRejectedDuringTlsBeforeProbeRpcDispatch() throws Exception {
    startServer(pki.pinnedServer(), pki.trustedCa());
    assertProbeRejected(
        invocation(pki.sameSanDifferentKey(), server.getPort()),
        pki.accountClient(),
        pki.trustedCa());
    assertThat(handlerCalls.get()).isZero();
  }

  @Test
  void untrustedServerCaIsRejectedBeforeProbeRpcDispatch() throws Exception {
    startServer(pki.untrustedServer(), pki.trustedCa());
    assertProbeRejected(
        invocation(pki.untrustedServer(), server.getPort()), pki.accountClient(), pki.trustedCa());
    assertThat(handlerCalls.get()).isZero();
  }

  @Test
  void missingAccountClientCertificateIsRejectedByRequiredMtlsBeforeProbeRpcDispatch()
      throws Exception {
    startServer(pki.pinnedServer(), pki.trustedCa());
    assertProbeRejected(invocation(pki.pinnedServer(), server.getPort()), null, pki.trustedCa());
    assertThat(handlerCalls.get()).isZero();
  }

  @Test
  void exactTlsPeerCanDispatchButMismatchedReceiverCoordinatesNeverBecomeAcceptance()
      throws Exception {
    startServer(pki.pinnedServer(), pki.trustedCa());
    assertThatThrownBy(
            () ->
                port(pki.accountClient(), pki.trustedCa())
                    .invoke(invocation(pki.pinnedServer(), server.getPort())))
        .isInstanceOf(AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException.class);
    assertThat(handlerCalls.get()).isEqualTo(1);
  }

  @Test
  void accountPodTargetUsesSeparateReceiverRpcAndRejectsMismatchedCoordinates() throws Exception {
    startAccountServer(pki.accountServer(), pki.trustedCa());
    assertThatThrownBy(
            () ->
                port(pki.accountClient(), pki.trustedCa())
                    .invoke(
                        invocation(
                            pki.accountServer(),
                            server.getPort(),
                            "account-service",
                            "spiffe://firemud/ns/firemud-prod/sa/account-service")))
        .isInstanceOf(AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException.class);
    assertThat(handlerCalls.get()).isEqualTo(1);
  }

  @Test
  void validButDifferentAccountSourceClusterNamespaceOrNamespaceUidCannotMatchPodTarget() {
    PodTarget target = protectedPodTarget();
    SourceIdentity matching =
        sourceIdentity(
            target,
            target.clusterId(),
            target.namespace(),
            target.namespaceUid(),
            "88888888-8888-4888-8888-888888888888",
            "different-trust-binding-revision");

    assertThat(
            AccountJwtReadinessGrpcReceiverInvocationPort.sourceIdentityMatchesTarget(
                matching, target))
        .isTrue();
    assertThat(
            AccountJwtReadinessGrpcReceiverInvocationPort.sourceIdentityMatchesTarget(
                sourceIdentity(
                    target,
                    "other-cluster",
                    target.namespace(),
                    target.namespaceUid(),
                    "88888888-8888-4888-8888-888888888888",
                    "different-trust-binding-revision"),
                target))
        .isFalse();
    assertThat(
            AccountJwtReadinessGrpcReceiverInvocationPort.sourceIdentityMatchesTarget(
                sourceIdentity(
                    target,
                    target.clusterId(),
                    "other-namespace",
                    target.namespaceUid(),
                    "88888888-8888-4888-8888-888888888888",
                    "different-trust-binding-revision"),
                target))
        .isFalse();
    assertThat(
            AccountJwtReadinessGrpcReceiverInvocationPort.sourceIdentityMatchesTarget(
                sourceIdentity(
                    target,
                    target.clusterId(),
                    target.namespace(),
                    "77777777-7777-4777-8777-777777777777",
                    "88888888-8888-4888-8888-888888888888",
                    "different-trust-binding-revision"),
                target))
        .isFalse();
  }

  @Test
  void loopbackMtlsInvocationRejectsStructurallyValidOtherAccountSourceCluster() throws Exception {
    AtomicReference<Invocation> currentInvocation = new AtomicReference<>();
    startSourceIdentityServer(pki.pinnedServer(), pki.trustedCa(), currentInvocation, true);
    Invocation invocation = invocation(pki.pinnedServer(), server.getPort());
    currentInvocation.set(invocation);

    assertThatThrownBy(() -> port(pki.accountClient(), pki.trustedCa()).invoke(invocation))
        .isInstanceOf(AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException.class);
    assertThat(handlerCalls.get()).isEqualTo(1);
  }

  @Test
  void matchingLoopbackMtlsResponseYieldsOnlyNonAuthorizingAcceptanceEvidence() throws Exception {
    AtomicReference<Invocation> currentInvocation = new AtomicReference<>();
    startSourceIdentityServer(pki.pinnedServer(), pki.trustedCa(), currentInvocation, false);
    Invocation invocation = invocation(pki.pinnedServer(), server.getPort());
    currentInvocation.set(invocation);

    var acceptance = port(pki.accountClient(), pki.trustedCa()).invoke(invocation);

    assertThat(acceptance.jti()).isEqualTo(invocation.jti());
    assertThat(acceptance.compactTokenSha256()).isEqualTo(invocation.compactTokenSha256());
    assertThat(acceptance.verifiedKid()).isEqualTo(invocation.targetKid());
    assertThat(acceptance.podUid()).isEqualTo(invocation.target().podUid());
    assertThat(acceptance.actualServiceUri()).isEqualTo(PEER_URI);
    assertThat(acceptance.actualPeerSpkiSha256())
        .isEqualTo(invocation.target().podLeafSpkiSha256().orElseThrow());
    assertThat(acceptance.result()).isEqualTo(ProbeExpectation.ACCEPT);
    assertThat(handlerCalls.get()).isEqualTo(1);
  }

  private void startServer(TestCertificate serverCertificate, Path trustedCa) throws Exception {
    SslContext tlsContext =
        GrpcSslContexts.configure(
                SslContextBuilder.forServer(
                    serverCertificate.certificate().toFile(),
                    serverCertificate.privateKey().toFile()))
            .trustManager(trustedCa.toFile())
            .clientAuth(ClientAuth.REQUIRE)
            .build();
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(tlsContext)
            .addService(
                new GameSessionJwtReadinessReceiverServiceGrpc
                    .GameSessionJwtReadinessReceiverServiceImplBase() {
                  @Override
                  public void receiveReadinessProbe(
                      ReceiveReadinessProbeRequest request,
                      StreamObserver<ReceiveReadinessProbeResponse> responseObserver) {
                    handlerCalls.incrementAndGet();
                    responseObserver.onNext(
                        ReceiveReadinessProbeResponse.newBuilder()
                            .setSchemaVersion(1)
                            .setCoordinates(
                                request.getCoordinates().toBuilder()
                                    .setOperationDigest("f".repeat(64)))
                            .setOutcome(ReceiveReadinessProbeResponse.ObservationOutcome.VERIFIED)
                            .build());
                    responseObserver.onCompleted();
                  }
                })
            .build()
            .start();
  }

  private void startAccountServer(TestCertificate serverCertificate, Path trustedCa)
      throws Exception {
    SslContext tlsContext =
        GrpcSslContexts.configure(
                SslContextBuilder.forServer(
                    serverCertificate.certificate().toFile(),
                    serverCertificate.privateKey().toFile()))
            .trustManager(trustedCa.toFile())
            .clientAuth(ClientAuth.REQUIRE)
            .build();
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(tlsContext)
            .addService(
                new AccountJwtReadinessPodReceiverServiceGrpc
                    .AccountJwtReadinessPodReceiverServiceImplBase() {
                  @Override
                  public void receiveReadinessProbe(
                      ReceiveAccountJwtReadinessProbeRequest request,
                      StreamObserver<ReceiveAccountJwtReadinessProbeResponse> responseObserver) {
                    handlerCalls.incrementAndGet();
                    responseObserver.onNext(
                        ReceiveAccountJwtReadinessProbeResponse.newBuilder()
                            .setSchemaVersion(1)
                            .setCoordinates(
                                request.getCoordinates().toBuilder()
                                    .setOperationDigest("f".repeat(64)))
                            .setOutcome(
                                ReceiveAccountJwtReadinessProbeResponse.ObservationOutcome.VERIFIED)
                            .build());
                    responseObserver.onCompleted();
                  }
                })
            .build()
            .start();
  }

  private void startSourceIdentityServer(
      TestCertificate serverCertificate,
      Path trustedCa,
      AtomicReference<Invocation> currentInvocation,
      boolean otherCluster)
      throws Exception {
    SslContext tlsContext = tlsContext(serverCertificate, trustedCa);
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(tlsContext)
            .addService(
                new GameSessionJwtReadinessReceiverServiceGrpc
                    .GameSessionJwtReadinessReceiverServiceImplBase() {
                  @Override
                  public void receiveReadinessProbe(
                      ReceiveReadinessProbeRequest request,
                      StreamObserver<ReceiveReadinessProbeResponse> responseObserver) {
                    handlerCalls.incrementAndGet();
                    Invocation invocation = currentInvocation.get();
                    PodTarget target = invocation.target();
                    responseObserver.onNext(
                        ReceiveReadinessProbeResponse.newBuilder()
                            .setSchemaVersion(1)
                            .setCoordinates(request.getCoordinates())
                            .setCompactTokenSha256(invocation.compactTokenSha256())
                            .setVerifiedKeyId(invocation.targetKid())
                            .setReceiverIdentity(
                                ReadinessReceiverLocalIdentity.newBuilder()
                                    .setValidatorId(target.validatorId())
                                    .setDeploymentUid(target.deploymentUid())
                                    .setPodUid(target.podUid())
                                    .setPodIp(target.podIp())
                                    .setDirectPodEndpoint(
                                        target.exactPodEndpoint().orElseThrow().toString())
                                    .setCanonicalServiceUri(
                                        target.canonicalServiceUri().orElseThrow())
                                    .setImage(target.image())
                                    .setVerifierConfigSha256(target.verifierConfigSha256())
                                    .setApplicabilityMatrixDigest(
                                        target.applicabilityMatrixDigest())
                                    .setSourceInventoryRevision("source-r1")
                                    .setSourceInventoryDigest("8".repeat(64))
                                    .setServerLeafSpkiSha256(
                                        target.podLeafSpkiSha256().orElseThrow())
                                    .setAccountJwksSourceIdentity(
                                        AccountSourceIdentity.newBuilder()
                                            .setEnvironmentId(target.environmentId())
                                            .setClusterId(
                                                otherCluster ? "other-cluster" : target.clusterId())
                                            .setClusterIncarnationUid(
                                                target.clusterIncarnationUid())
                                            .setNamespace(target.namespace())
                                            .setNamespaceUid(target.namespaceUid())
                                            .setConfigMapUid("88888888-8888-4888-8888-888888888888")
                                            .setBindingRevision("unrelated-trust-revision")
                                            .setApiServerOrigin("https://kubernetes.example:6443")
                                            .setServingCaSha256("9".repeat(64)))
                                    .setAccountJwksTrustConfigRevision(1L))
                            .setAccountPodTarget(accountTargetBinding(target))
                            .setObservedAtEpochSeconds(System.currentTimeMillis() / 1000)
                            .setOutcome(ReceiveReadinessProbeResponse.ObservationOutcome.VERIFIED)
                            .build());
                    responseObserver.onCompleted();
                  }
                })
            .build()
            .start();
  }

  private static SslContext tlsContext(TestCertificate serverCertificate, Path trustedCa)
      throws Exception {
    return GrpcSslContexts.configure(
            SslContextBuilder.forServer(
                serverCertificate.certificate().toFile(), serverCertificate.privateKey().toFile()))
        .trustManager(trustedCa.toFile())
        .clientAuth(ClientAuth.REQUIRE)
        .build();
  }

  private static AccountPodTargetBinding accountTargetBinding(PodTarget target) {
    return AccountPodTargetBinding.newBuilder()
        .setInventorySnapshotDigest(target.inventorySnapshotDigest())
        .setEnvironmentId(target.environmentId())
        .setClusterId(target.clusterId())
        .setClusterIncarnationUid(target.clusterIncarnationUid())
        .setNamespace(target.namespace())
        .setNamespaceUid(target.namespaceUid())
        .setApiBindingRevision(target.apiBindingRevision())
        .setApiBindingDigest(target.apiBindingDigest())
        .setInventoryBindingRevision(target.inventoryBindingRevision())
        .setInventoryBindingDigest(target.inventoryBindingDigest())
        .setValidatorId(target.validatorId())
        .setDeploymentUid(target.deploymentUid())
        .setPodUid(target.podUid())
        .setPodIp(target.podIp())
        .setImage(target.image())
        .setVerifierConfigSha256(target.verifierConfigSha256())
        .setApplicabilityMatrixDigest(target.applicabilityMatrixDigest())
        .setExpectedOutcome(ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT)
        .setDirectPodEndpoint(target.exactPodEndpoint().orElseThrow().toString())
        .setCanonicalServiceUri(target.canonicalServiceUri().orElseThrow())
        .setServerLeafSpkiSha256(target.podLeafSpkiSha256().orElseThrow())
        .build();
  }

  private static void assertProbeRejected(
      Invocation invocation, TestCertificate accountClient, Path trustedCa) {
    assertThatThrownBy(() -> port(accountClient, trustedCa).invoke(invocation))
        .isInstanceOf(RuntimeException.class);
  }

  private static AccountJwtReadinessGrpcReceiverInvocationPort port(
      TestCertificate accountClient, Path trustedCa) {
    try {
      KeyStore keyStore;
      if (accountClient == null) {
        keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, KEY_PASSWORD);
      } else {
        keyStore = keyStore(accountClient);
      }
      var keyManagerFactory =
          javax.net.ssl.KeyManagerFactory.getInstance(
              javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
      keyManagerFactory.init(keyStore, KEY_PASSWORD);
      var trustManagerFactory =
          javax.net.ssl.TrustManagerFactory.getInstance(
              javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
      KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
      trustStore.load(null, null);
      trustStore.setCertificateEntry("readiness-test-ca", readCertificate(trustedCa));
      trustManagerFactory.init(trustStore);

      SslBundles bundles = mock(SslBundles.class);
      SslBundle bundle = mock(SslBundle.class);
      SslManagerBundle managers = mock(SslManagerBundle.class);
      when(bundles.getBundle("firemud-grpc")).thenReturn(bundle);
      when(bundle.getManagers()).thenReturn(managers);
      when(managers.getKeyManagerFactory()).thenReturn(keyManagerFactory);
      when(managers.getTrustManagerFactory()).thenReturn(trustManagerFactory);
      return new AccountJwtReadinessGrpcReceiverInvocationPort(bundles, "firemud-prod");
    } catch (Exception failure) {
      throw new IllegalStateException("Could not assemble test-only Account TLS bundle", failure);
    }
  }

  private static Invocation invocation(TestCertificate expectedServer, int port) throws Exception {
    return invocation(expectedServer, port, "game-session-service", PEER_URI);
  }

  private static PodTarget protectedPodTarget() {
    return new PodTarget(
        "a".repeat(64),
        "prod",
        "prod-cluster-1",
        "11111111-1111-4111-8111-111111111111",
        "firemud-prod",
        "22222222-2222-4222-8222-222222222222",
        "api-r1",
        "b".repeat(64),
        "inventory-r1",
        "c".repeat(64),
        "game-session-service",
        "33333333-3333-4333-8333-333333333333",
        "44444444-4444-4444-8444-444444444444",
        "127.0.0.1",
        "registry.example/firemud/game-session-service@sha256:" + "d".repeat(64),
        "e".repeat(64),
        "f".repeat(64),
        ProbeExpectation.ACCEPT,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private static SourceIdentity sourceIdentity(
      PodTarget target,
      String clusterId,
      String namespace,
      String namespaceUid,
      String configMapUid,
      String bindingRevision) {
    return new SourceIdentity(
        target.environmentId(),
        clusterId,
        target.clusterIncarnationUid(),
        namespace,
        namespaceUid,
        configMapUid,
        bindingRevision,
        "https://kubernetes.example:6443",
        "9".repeat(64));
  }

  private static Invocation invocation(
      TestCertificate expectedServer, int port, String validatorId, String expectedServiceUri)
      throws Exception {
    long now = System.currentTimeMillis() / 1000;
    String podIp = "127.0.0.1";
    PodTarget target =
        new PodTarget(
            "a".repeat(64),
            "prod",
            "prod-cluster-1",
            "11111111-1111-4111-8111-111111111111",
            "firemud-prod",
            "22222222-2222-4222-8222-222222222222",
            "api-r1",
            "b".repeat(64),
            "inventory-r1",
            "c".repeat(64),
            validatorId,
            "33333333-3333-4333-8333-333333333333",
            "44444444-4444-4444-8444-444444444444",
            podIp,
            "registry.example/firemud/" + validatorId + "@sha256:" + "d".repeat(64),
            "e".repeat(64),
            "f".repeat(64),
            ProbeExpectation.ACCEPT,
            Optional.of(java.net.URI.create("grpcs://" + podIp + ":" + port)),
            Optional.of(expectedServiceUri),
            Optional.of(spki(expectedServer.certificate())));
    String compact = "e30.eyJ4IjoxfQ.c2lnbmF0dXJl";
    return new Invocation(
        UUID.fromString("55555555-5555-4555-8555-555555555555"),
        "1".repeat(64),
        "2".repeat(64),
        AccountJwtReadinessProbeRepository.INVENTORY_PLAN_VERSION,
        now + 120,
        validatorId,
        "canary",
        "account-service",
        ProbeKind.CANARY,
        ProbeExpectation.ACCEPT,
        1,
        1L,
        UUID.fromString("66666666-6666-4666-8666-666666666666"),
        compact,
        sha256(compact),
        "2",
        "target-kid",
        Optional.empty(),
        Optional.empty(),
        now - 1,
        now + 60,
        target);
  }

  private static KeyStore keyStore(TestCertificate certificate) throws Exception {
    Path privateKeyDirectory =
        Objects.requireNonNull(
            certificate.privateKey().getParent(),
            "test certificate private-key directory is required");
    Path pkcs12 = privateKeyDirectory.resolve("account-client-" + UUID.randomUUID() + ".p12");
    Path pkcs12Directory =
        Objects.requireNonNull(pkcs12.getParent(), "test client keystore directory is required");
    runOpenSsl(
        pkcs12Directory,
        "pkcs12",
        "-export",
        "-in",
        certificate.certificate().toString(),
        "-inkey",
        certificate.privateKey().toString(),
        "-certfile",
        pki.trustedCa().toString(),
        "-out",
        pkcs12.toString(),
        "-name",
        "account-client",
        "-passout",
        "pass:" + new String(KEY_PASSWORD));
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (InputStream input = Files.newInputStream(pkcs12)) {
      keyStore.load(input, KEY_PASSWORD);
    }
    return keyStore;
  }

  private static X509Certificate readCertificate(Path path) throws Exception {
    try (InputStream input = Files.newInputStream(path)) {
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
    }
  }

  private static String spki(Path certificatePath) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(readCertificate(certificatePath).getPublicKey().getEncoded()));
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
  }

  private static TestAuthority createAuthority(Path directory, String commonName) throws Exception {
    Path privateKey = directory.resolve(commonName + ".key");
    Path certificate = directory.resolve(commonName + ".crt");
    runOpenSsl(
        directory,
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        privateKey.toString(),
        "-out",
        certificate.toString(),
        "-subj",
        "/CN=" + commonName,
        "-days",
        "2",
        "-sha256",
        "-addext",
        "basicConstraints=critical,CA:TRUE",
        "-addext",
        "keyUsage=critical,keyCertSign,cRLSign");
    return new TestAuthority(privateKey, certificate);
  }

  private static TestCertificate issueLeaf(
      Path directory,
      TestAuthority authority,
      String commonName,
      String workloadUri,
      boolean serverCertificate)
      throws Exception {
    Path privateKey = directory.resolve(commonName + ".key");
    Path request = directory.resolve(commonName + ".csr");
    Path certificate = directory.resolve(commonName + ".crt");
    Path extensions = directory.resolve(commonName + ".ext");
    Files.writeString(
        extensions,
        "basicConstraints=critical,CA:FALSE\n"
            + "keyUsage=critical,digitalSignature,keyEncipherment\n"
            + "extendedKeyUsage="
            + (serverCertificate ? "serverAuth" : "clientAuth")
            + "\nsubjectAltName=URI:"
            + workloadUri
            + ",DNS:localhost,IP:127.0.0.1\n");
    runOpenSsl(
        directory,
        "req",
        "-new",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        privateKey.toString(),
        "-out",
        request.toString(),
        "-subj",
        "/CN=" + commonName);
    runOpenSsl(
        directory,
        "x509",
        "-req",
        "-in",
        request.toString(),
        "-CA",
        authority.certificate().toString(),
        "-CAkey",
        authority.privateKey().toString(),
        "-CAcreateserial",
        "-out",
        certificate.toString(),
        "-days",
        "2",
        "-sha256",
        "-extfile",
        extensions.toString());
    return new TestCertificate(privateKey, certificate);
  }

  private static void runOpenSsl(Path directory, String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    command.add("openssl");
    command.addAll(List.of(arguments));
    Process process;
    try {
      process =
          new ProcessBuilder(command)
              .directory(directory.toFile())
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
    } catch (IOException exception) {
      throw new IllegalStateException("OpenSSL is required for ephemeral TLS test fixtures");
    }
    if (!process.waitFor(20, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(2, TimeUnit.SECONDS);
      throw new IllegalStateException("Ephemeral TLS test fixture generation timed out");
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException("Ephemeral TLS test fixture generation failed");
    }
  }

  private record TestAuthority(Path privateKey, Path certificate) {}

  private record TestCertificate(Path privateKey, Path certificate) {}

  private record TestPki(
      Path trustedCa,
      TestCertificate accountClient,
      TestCertificate pinnedServer,
      TestCertificate sameSanDifferentKey,
      TestCertificate wrongSan,
      TestCertificate accountServer,
      TestCertificate untrustedServer) {}
}
