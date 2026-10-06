package integration.net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
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
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalClient;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadGrpcCodec;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationService;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationTerminalReadGrpcService;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignPublicationTerminalResponse;
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
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/** Actual GD owner DB guard proof; every upstream creator/source/freeze input is ISOLATED. */
@Testcontainers(disabledWithoutDocker = true)
class GameDesignPublicationOperationPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String MANIFEST = "sha256:" + "a".repeat(64);
  private static final AtomicLong TEST_CERTIFICATE_SERIAL = new AtomicLong(1);

  /**
   * Genuine GD SQL result and physical workload identity; upstream creator/export/freeze remain
   * ISOLATED.
   */
  @Test
  void
      physicalMtlsTerminalReadRecoversActualPublishedAndNoPublicationOwnerSealsAndRejectsWrongPeers(
          @TempDir Path directory) throws Exception {
    var pki = publicationReadPki();
    int ownerIndex = 0;
    for (boolean published : java.util.List.of(true, false)) {
      var fixture = fixture();
      if (published) publish(fixture);
      else noPublication(fixture);
      var original = fixture.service().readExact(fixture.operation()).orElseThrow();
      byte[] retained = original.terminalEvidenceBytes();
      var request =
          GameDesignPublicationTerminalReadEvidence.ReadRequest.create(
              "test", fixture.operation().canonicalBytes());
      var headers = new AtomicInteger();
      var loseResponse = new AtomicBoolean(true);
      var server = publicationReadServer(fixture, pki.server(), pki.ca(), headers, loseResponse);
      try {
        try (var client =
            publicationReadClient(
                server,
                pki.account(),
                pki.ca(),
                directory.resolve("owner-" + ownerIndex + "-account"))) {
          client.init();
          // The real handler and owner read finish; the first outbound result is deliberately lost.
          assertThatThrownBy(() -> client.read(request))
              .isInstanceOf(StatusRuntimeException.class)
              .satisfies(
                  e ->
                      assertThat(Status.fromThrowable(e).getCode())
                          .isEqualTo(Status.Code.UNAVAILABLE));
          var recovered = client.read(request).terminalEvidence().orElseThrow();
          assertThat(recovered.canonicalBytes()).isEqualTo(retained);
          assertThat(recovered.operationBytes()).isEqualTo(fixture.operation().canonicalBytes());
          assertThat(recovered.outcome().name())
              .isEqualTo(published ? "PUBLISHED" : "NO_PUBLICATION");
          if (published) {
            assertThat(recovered.publicationVersionStateEpoch())
                .isEqualTo(fixture.operation().world().request().versionStateEpoch() + 1);
            assertThat(recovered.publishedReleaseBundleDigest())
                .isEqualTo(recovered.releaseContent().contentDigest());
          } else
            assertThatThrownBy(recovered::publicationVersionStateEpoch)
                .isInstanceOf(IllegalStateException.class);
          assertThat(client.read(request).terminalEvidence().orElseThrow().canonicalBytes())
              .isEqualTo(retained);
        }
        for (var identity :
            java.util.List.of(pki.wrongCallerService(), pki.wrongCallerNamespace())) {
          try (var client =
              publicationReadClient(
                  server,
                  identity,
                  pki.ca(),
                  directory.resolve("owner-" + ownerIndex + "-" + identity.label()))) {
            client.init();
            assertThatThrownBy(() -> client.read(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(
                    e -> {
                      assertThat(Status.fromThrowable(e).getCode())
                          .isEqualTo(Status.Code.PERMISSION_DENIED);
                      assertThat(TlsTestSupport.isTlsHandshakeRejection(e)).isFalse();
                    });
          }
        }
        assertThat(headers).hasValue(5);
      } finally {
        server.shutdownNow();
        assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      }
      for (var wrongServer :
          java.util.List.of(pki.wrongServerService(), pki.wrongServerNamespace())) {
        var deniedHeaders = new AtomicInteger();
        var deniedServer =
            publicationReadServer(
                fixture, wrongServer, pki.ca(), deniedHeaders, new AtomicBoolean(false));
        try (var client =
            publicationReadClient(
                deniedServer,
                pki.account(),
                pki.ca(),
                directory.resolve("owner-" + ownerIndex + "-" + wrongServer.label()))) {
          client.init();
          assertThatThrownBy(() -> client.read(request))
              .isInstanceOf(StatusRuntimeException.class)
              .satisfies(
                  e -> {
                    assertThat(Status.fromThrowable(e).getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED);
                    assertThat(TlsTestSupport.isTlsHandshakeRejection(e)).isFalse();
                  });
          assertThat(deniedHeaders).hasValue(0);
        } finally {
          deniedServer.shutdownNow();
          assertThat(deniedServer.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
      }
      assertThat(
              fixture
                  .service()
                  .readExact(fixture.operation())
                  .orElseThrow()
                  .terminalEvidenceBytes())
          .isEqualTo(retained);
      assertThat(fixture.service().readExact(fixture.operation()).orElseThrow().receiptBytes())
          .isEqualTo(original.receiptBytes());
      ownerIndex++;
    }
  }

  private Server publicationReadServer(
      Fixture fixture,
      TestCertificate identity,
      X509Certificate ca,
      AtomicInteger headers,
      AtomicBoolean loseResponse)
      throws Exception {
    ServerInterceptor responseLoss =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            headers.incrementAndGet();
            return next.startCall(
                new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                  private boolean dropped;

                  @Override
                  public void sendMessage(RespT message) {
                    if (loseResponse.compareAndSet(true, false)) dropped = true;
                    else super.sendMessage(message);
                  }

                  @Override
                  public void close(Status status, Metadata trailers) {
                    super.close(
                        dropped
                            ? Status.UNAVAILABLE.withDescription(
                                "ISOLATED lost terminal read response")
                            : status,
                        trailers);
                  }
                },
                metadata);
          }
        };
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                .trustManager(ca)
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(
            ServerInterceptors.intercept(
                new GameDesignPublicationTerminalReadGrpcService(fixture.service(), "test"),
                responseLoss,
                new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private GameDesignPublicationTerminalClient publicationReadClient(
      Server server, TestCertificate identity, X509Certificate ca, Path directory)
      throws Exception {
    Files.createDirectories(directory);
    var tls = new CommonGrpcClientProperties();
    tls.setCertChain(
        publicationReadPem(
                directory.resolve("client.crt"), "CERTIFICATE", identity.certificate().getEncoded())
            .toString());
    tls.setPrivateKey(
        publicationReadPem(
                directory.resolve("client.key"), "PRIVATE KEY", identity.privateKey().getEncoded())
            .toString());
    tls.setCaCert(
        publicationReadPem(directory.resolve("ca.crt"), "CERTIFICATE", ca.getEncoded()).toString());
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("localhost:" + server.getPort());
    return new GameDesignPublicationTerminalClient(
        endpoints, tls, new GrpcChannelFactory(), "test");
  }

  private static Path publicationReadPem(Path path, String type, byte[] bytes) throws Exception {
    Files.writeString(
        path,
        "-----BEGIN "
            + type
            + "-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
            + "\n-----END "
            + type
            + "-----\n");
    return path;
  }

  private static TestPki publicationReadPki() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null)
      Security.addProvider(new BouncyCastleProvider());
    var keys = publicationReadKeyPair();
    var name = new X500Name("CN=Game Design PostgreSQL Terminal Test CA, O=FireMUD Test");
    var ca =
        publicationReadCertificate(name, keys.getPublic(), name, keys.getPrivate(), true, null);
    return new TestPki(
        ca,
        publicationReadLeaf(
            name, keys.getPrivate(), "server", "spiffe://firemud/ns/test/sa/game-design-service"),
        publicationReadLeaf(
            name, keys.getPrivate(), "account", "spiffe://firemud/ns/test/sa/account-service"),
        publicationReadLeaf(
            name,
            keys.getPrivate(),
            "wrong-caller-service",
            "spiffe://firemud/ns/test/sa/game-session-service"),
        publicationReadLeaf(
            name,
            keys.getPrivate(),
            "wrong-caller-namespace",
            "spiffe://firemud/ns/other/sa/account-service"),
        publicationReadLeaf(
            name,
            keys.getPrivate(),
            "wrong-server-service",
            "spiffe://firemud/ns/test/sa/world-management-service"),
        publicationReadLeaf(
            name,
            keys.getPrivate(),
            "wrong-server-namespace",
            "spiffe://firemud/ns/other/sa/game-design-service"));
  }

  private static TestCertificate publicationReadLeaf(
      X500Name ca, PrivateKey issuerKey, String label, String uri) throws Exception {
    var keys = publicationReadKeyPair();
    var names =
        new GeneralNames(
            new GeneralName[] {
              new GeneralName(GeneralName.uniformResourceIdentifier, uri),
              new GeneralName(GeneralName.dNSName, "localhost"),
              new GeneralName(GeneralName.iPAddress, "127.0.0.1")
            });
    return new TestCertificate(
        label,
        keys.getPrivate(),
        publicationReadCertificate(
            new X500Name("CN=" + label + ", O=FireMUD Test"),
            keys.getPublic(),
            ca,
            issuerKey,
            false,
            names));
  }

  private static X509Certificate publicationReadCertificate(
      X500Name subject,
      java.security.PublicKey key,
      X500Name issuer,
      PrivateKey issuerKey,
      boolean ca,
      GeneralNames names)
      throws Exception {
    var start = Instant.now().minusSeconds(60);
    var builder =
        new JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(TEST_CERTIFICATE_SERIAL.getAndIncrement()),
            Date.from(start),
            Date.from(start.plusSeconds(60L * 60L * 24L * 14L)),
            subject,
            key);
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
    builder.addExtension(
        Extension.keyUsage,
        true,
        new KeyUsage(
            ca
                ? KeyUsage.keyCertSign | KeyUsage.cRLSign
                : KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
    if (!ca) {
      builder.addExtension(
          Extension.extendedKeyUsage,
          false,
          new ExtendedKeyUsage(
              new KeyPurposeId[] {KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));
      builder.addExtension(Extension.subjectAlternativeName, false, names);
    }
    return new JcaX509CertificateConverter()
        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
        .getCertificate(
            builder.build(
                new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(issuerKey)));
  }

  private static KeyPair publicationReadKeyPair() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private record TestCertificate(
      String label, PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate ca,
      TestCertificate server,
      TestCertificate account,
      TestCertificate wrongCallerService,
      TestCertificate wrongCallerNamespace,
      TestCertificate wrongServerService,
      TestCertificate wrongServerNamespace) {}

  @Test
  void authenticatedTerminalReadDispatchesActualStoredPublishedAndNoPublicationEvidence()
      throws Exception {
    for (boolean published : java.util.List.of(true, false)) {
      var fixture = fixture();
      if (published) publish(fixture);
      else noPublication(fixture);
      var expected =
          fixture.service().readExact(fixture.operation()).orElseThrow().terminalEvidenceBytes();
      var request =
          GameDesignPublicationTerminalReadEvidence.ReadRequest.create(
              "test", fixture.operation().canonicalBytes());
      for (String peer : java.util.List.of("account-service", "world-management-service")) {
        var result = readThroughHandler(fixture, request, peer);
        assertThat(result.error).isNull();
        var evidence =
            GameDesignPublicationTerminalReadGrpcCodec.fromResponse(request, result.response);
        assertThat(evidence.terminalEvidence().orElseThrow().canonicalBytes()).isEqualTo(expected);
        assertThat(evidence.status().name()).isEqualTo(published ? "PUBLISHED" : "NO_PUBLICATION");
      }
    }
  }

  @Test
  void authenticatedTerminalReadReturnsUnknownOnlyForAbsentOrPendingOperation() throws Exception {
    var fixture = fixture();
    var pending =
        GameDesignPublicationTerminalReadEvidence.ReadRequest.create(
            "test", fixture.operation().canonicalBytes());
    var result = readThroughHandler(fixture, pending, "world-management-service");
    var evidence =
        GameDesignPublicationTerminalReadGrpcCodec.fromResponse(pending, result.response);
    assertThat(evidence.status())
        .isEqualTo(GameDesignPublicationTerminalReadEvidence.Status.UNKNOWN);
    assertThat(evidence.terminalEvidence()).isEmpty();
    var absentOperation =
        IsolatedPublicationOperationFixtures.fresh(
            fixture.operation().account().input().selection().target());
    var absent =
        GameDesignPublicationTerminalReadEvidence.ReadRequest.create(
            "test", absentOperation.canonicalBytes());
    var absentResult = readThroughHandler(fixture, absent, "account-service");
    assertThat(
            GameDesignPublicationTerminalReadGrpcCodec.fromResponse(absent, absentResult.response)
                .status())
        .isEqualTo(GameDesignPublicationTerminalReadEvidence.Status.UNKNOWN);
    assertThat(fixture.service().readExact(fixture.operation()).orElseThrow().outcome())
        .isEqualTo("PENDING");
  }

  @Test
  void retainedV50TerminalReadFailsPreconditionWithoutBackfillingEvidenceOrEpoch()
      throws Exception {
    var fixture = fixture("50");
    var op = fixture.operation();
    var old = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(old, "game-design-publication-owner-readback/v1");
    DraftAuthorizationFenceBinding.frame(old, op.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(old, "NO_PUBLICATION");
    DraftAuthorizationFenceBinding.frame(old, "");
    fixture
        .write()
        .executeWithoutResult(
            status -> {
              var attempt =
                  fixture
                      .attempts()
                      .findByPublishWorkflowIdForUpdate(op.workflowId())
                      .orElseThrow();
              attempt.setStatus(PublishAttemptStatus.FAILED);
              fixture.attempts().save(attempt);
              fixture
                  .dsl()
                  .execute(
                      "UPDATE game_design_publication_operation SET outcome = 'NO_PUBLICATION', revision = revision + 1, result_bytes = ? WHERE publish_workflow_id = ?",
                      old.toByteArray(),
                      op.workflowId());
            });
    fixture.completeMigration().run();
    var request =
        GameDesignPublicationTerminalReadEvidence.ReadRequest.create("test", op.canonicalBytes());
    var result = readThroughHandler(fixture, request, "account-service");
    assertThat(result.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(result.response).isNull();
    var retained = fixture.service().readExact(op).orElseThrow();
    assertThat(retained.receiptBytes()).isEqualTo(old.toByteArray());
    assertThat(retained.terminalEvidenceBytes()).isNull();
    assertThat(
            java.util.Objects.requireNonNull(
                    fixture
                        .dsl()
                        .fetchOne(
                            "SELECT publication_version_state_epoch FROM game_design_publication_operation WHERE publish_workflow_id = ?",
                            op.workflowId()))
                .get(0))
        .isNull();
  }

  private ReadCollector readThroughHandler(
      Fixture fixture,
      GameDesignPublicationTerminalReadEvidence.ReadRequest request,
      String service) {
    var handler = new GameDesignPublicationTerminalReadGrpcService(fixture.service(), "test");
    var collector = new ReadCollector();
    var context =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                new GrpcPeerIdentity("spiffe://firemud/ns/test/sa/" + service, "test", service));
    var previous = context.attach();
    try {
      handler.readGameDesignPublicationTerminal(
          GameDesignPublicationTerminalReadGrpcCodec.toRequest(request), collector);
    } finally {
      context.detach(previous);
    }
    return collector;
  }

  private static final class ReadCollector
      implements StreamObserver<ReadGameDesignPublicationTerminalResponse> {
    private ReadGameDesignPublicationTerminalResponse response;
    private Status.Code error;

    @Override
    public void onNext(ReadGameDesignPublicationTerminalResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {}
  }

  @Test
  void noPublicationReceiptReplaysAfterLostResponseAndExcludesDelayedFinalizerAndRawInsert()
      throws Exception {
    var fixture = fixture();
    noPublication(fixture);
    var original = fixture.service().readExact(fixture.operation()).orElseThrow();
    assertThat(original.outcome()).isEqualTo("NO_PUBLICATION");
    var noPublication =
        GameDesignPublicationTerminalEvidence.fromStored(original.terminalEvidenceBytes());
    assertThat(noPublication.outcome())
        .isEqualTo(GameDesignPublicationTerminalEvidence.Outcome.NO_PUBLICATION);
    assertThatThrownBy(noPublication::publicationVersionStateEpoch)
        .isInstanceOf(IllegalStateException.class);
    assertThat(fixture.service().retainVerifiedOperation(fixture.operation()).receiptBytes())
        .isEqualTo(original.receiptBytes());
    assertThatThrownBy(
            () ->
                fixture
                    .write()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .operations()
                                .requirePending(
                                    fixture.operation().tenantKey(),
                                    fixture.operation().workflowId(),
                                    fixture.operation().versionId(),
                                    fixture.operation().selectionDigest())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SEALED_OR_CHANGED");
    assertThatThrownBy(
            () -> fixture.write().executeWithoutResult(status -> rawReleaseInsert(fixture)))
        .hasMessageContaining("selected publication is absent, sealed, or changed");
    assertThat(
            fixture
                .releases()
                .findByTenantIdAndVersionId(
                    fixture.operation().tenantKey(), fixture.operation().versionId()))
        .isEmpty();
    assertThat(fixture.service().readExact(fixture.operation()).orElseThrow().receiptBytes())
        .isEqualTo(original.receiptBytes());
  }

  @Test
  void terminalEvidenceKeepsOriginalCommittedEpochAfterLaterVersionTransition() throws Exception {
    var fixture = fixture();
    publish(fixture);
    var original = fixture.service().readExact(fixture.operation()).orElseThrow();
    var terminal =
        GameDesignPublicationTerminalEvidence.fromStored(original.terminalEvidenceBytes());
    long captured = terminal.publicationVersionStateEpoch();
    assertThat(captured).isEqualTo(fixture.operation().world().request().versionStateEpoch() + 1);
    assertThat(terminal.operationBytes()).isEqualTo(fixture.operation().canonicalBytes());
    fixture
        .write()
        .executeWithoutResult(
            status -> {
              var version =
                  fixture
                      .versions()
                      .findByTenantIdAndIdForUpdate(
                          fixture.operation().tenantKey(), fixture.operation().versionId())
                      .orElseThrow();
              version.setVersionState(VersionLifecycleState.RETIRED);
              version.setVersionStateEpoch(captured + 1);
              fixture.versions().save(version);
            });
    var replay = fixture.service().readExact(fixture.operation()).orElseThrow();
    assertThat(replay.terminalEvidenceBytes()).isEqualTo(original.terminalEvidenceBytes());
    assertThat(
            GameDesignPublicationTerminalEvidence.fromStored(replay.terminalEvidenceBytes())
                .publicationVersionStateEpoch())
        .isEqualTo(captured);
  }

  @Test
  void terminalExtensionRefusesRawEpochDigestBytesAndReleaseContentSubstitution() throws Exception {
    var fixture = fixture();
    publish(fixture);
    var original = fixture.service().readExact(fixture.operation()).orElseThrow();
    for (String assignment :
        java.util.List.of(
            "publication_version_state_epoch = publication_version_state_epoch + 1",
            "published_release_bundle_digest = 'sha256:' || repeat('f', 64)",
            "terminal_evidence_bytes = terminal_evidence_bytes || decode('00', 'hex')",
            "release_content_bytes = release_content_bytes || decode('00', 'hex')")) {
      assertThatThrownBy(
              () ->
                  fixture
                      .write()
                      .executeWithoutResult(
                          status ->
                              fixture
                                  .dsl()
                                  .execute(
                                      "UPDATE game_design_publication_operation SET "
                                          + assignment
                                          + " WHERE publish_workflow_id = ?",
                                      fixture.operation().workflowId())))
          .isInstanceOf(RuntimeException.class);
    }
    assertThatThrownBy(
            () ->
                fixture
                    .write()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .dsl()
                                .execute(
                                    "UPDATE published_release_bundle SET generation_config_revision = 'changed' WHERE tenant_id = ? AND version_id = ?",
                                    fixture.operation().tenantKey(),
                                    fixture.operation().versionId())))
        .isInstanceOf(RuntimeException.class);
    assertThat(
            fixture.service().readExact(fixture.operation()).orElseThrow().terminalEvidenceBytes())
        .isEqualTo(original.terminalEvidenceBytes());
  }

  @Test
  void originalSealRejectsForgedCommittedEpochAndPreservesPendingOwnerRows() throws Exception {
    for (String forgedField : java.util.List.of("epoch", "digest", "content", "terminal")) {
      var fixture = fixture();
      assertThatThrownBy(
              () ->
                  fixture
                      .write()
                      .executeWithoutResult(
                          status -> {
                            publishInTransaction(fixture, false);
                            rawTerminalSeal(fixture, forgedField);
                          }))
          .isInstanceOf(RuntimeException.class);
      var pending = fixture.service().readExact(fixture.operation()).orElseThrow();
      assertThat(pending.outcome()).isEqualTo("PENDING");
      assertThat(pending.terminalEvidenceBytes()).isNull();
      publish(fixture);
      assertThat(
              fixture
                  .service()
                  .readExact(fixture.operation())
                  .orElseThrow()
                  .terminalEvidenceBytes())
          .isNotNull();
    }
  }

  @Test
  void publicationSealEpochMustRemainTheActualVersionEpochThroughCommit() throws Exception {
    var fixture = fixture();
    assertThatThrownBy(
            () ->
                fixture
                    .write()
                    .executeWithoutResult(
                        status -> {
                          publishInTransaction(fixture);
                          fixture
                              .dsl()
                              .execute(
                                  "UPDATE version SET version_state_epoch = version_state_epoch + 1 WHERE tenant_id = ? AND id = ?",
                                  fixture.operation().tenantKey(),
                                  fixture.operation().versionId());
                        }))
        .isInstanceOf(RuntimeException.class);
    assertThat(fixture.service().readExact(fixture.operation()).orElseThrow().outcome())
        .isEqualTo("PENDING");
  }

  @Test
  void publishedReceiptCannotBeDemotedByStaleFailureAndRetainsOriginalFreezeEpoch()
      throws Exception {
    var fixture = fixture();
    var staleFailure =
        fixture.attempts().findByPublishWorkflowId(fixture.operation().workflowId()).orElseThrow();
    publish(fixture);
    var original = fixture.service().readExact(fixture.operation()).orElseThrow();
    assertThat(original.outcome()).isEqualTo("PUBLISHED");
    assertThat(fixture.service().retainVerifiedOperation(fixture.operation()).receiptBytes())
        .isEqualTo(original.receiptBytes());
    assertThat(
            fixture
                .versions()
                .findByTenantIdAndId(
                    fixture.operation().tenantKey(), fixture.operation().versionId())
                .orElseThrow()
                .getVersionStateEpoch())
        .isEqualTo(fixture.operation().world().request().versionStateEpoch() + 1);
    staleFailure.setStatus(PublishAttemptStatus.FAILED);
    assertThatThrownBy(
            () ->
                fixture
                    .write()
                    .executeWithoutResult(status -> fixture.attempts().save(staleFailure)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CAS_CONFLICT");
    assertThatThrownBy(() -> noPublication(fixture)).isInstanceOf(IllegalStateException.class);
    assertThat(fixture.service().readExact(fixture.operation()).orElseThrow().receiptBytes())
        .isEqualTo(original.receiptBytes());
  }

  @Test
  void failedAtomicCommitLeavesAttemptOperationVersionAndReleasePendingForExactRetry()
      throws Exception {
    var fixture = fixture();
    assertThatThrownBy(
            () ->
                fixture
                    .write()
                    .executeWithoutResult(
                        status -> {
                          publishInTransaction(fixture);
                          throw new IllegalStateException(
                              "ISOLATED rollback after all owner writes");
                        }))
        .hasMessageContaining("rollback after all owner writes");
    assertThat(fixture.service().readExact(fixture.operation()).orElseThrow().outcome())
        .isEqualTo("PENDING");
    assertThat(
            fixture
                .releases()
                .findByTenantIdAndVersionId(
                    fixture.operation().tenantKey(), fixture.operation().versionId()))
        .isEmpty();
    assertThat(
            fixture
                .attempts()
                .findByPublishWorkflowId(fixture.operation().workflowId())
                .orElseThrow()
                .getStatus())
        .isEqualTo(PublishAttemptStatus.PENDING);
    assertThat(
            fixture
                .versions()
                .findByTenantIdAndId(
                    fixture.operation().tenantKey(), fixture.operation().versionId())
                .orElseThrow()
                .getVersionState())
        .isEqualTo(VersionLifecycleState.DRAFT);
    publish(fixture);
    assertThat(fixture.service().readExact(fixture.operation()).orElseThrow().outcome())
        .isEqualTo("PUBLISHED");
  }

  @Test
  void noPublicationSerializesAgainstAnAlreadyScheduledLatePublisher() throws Exception {
    var fixture = fixture();
    var sealed = new CountDownLatch(1);
    var releaseSeal = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var winner =
          executor.submit(
              () ->
                  fixture
                      .write()
                      .executeWithoutResult(
                          status -> {
                            noPublicationInTransaction(fixture);
                            sealed.countDown();
                            await(releaseSeal);
                          }));
      assertThat(sealed.await(10, TimeUnit.SECONDS)).isTrue();
      var loser =
          executor.submit(
              () -> {
                assertThatThrownBy(() -> publish(fixture))
                    .isInstanceOf(IllegalStateException.class);
              });
      releaseSeal.countDown();
      winner.get(10, TimeUnit.SECONDS);
      loser.get(10, TimeUnit.SECONDS);
    } finally {
      releaseSeal.countDown();
    }
    assertThat(fixture.service().readExact(fixture.operation()).orElseThrow().outcome())
        .isEqualTo("NO_PUBLICATION");
  }

  private void noPublication(Fixture fixture) {
    fixture.write().executeWithoutResult(status -> noPublicationInTransaction(fixture));
  }

  private void noPublicationInTransaction(Fixture fixture) {
    var op = fixture.operation();
    fixture
        .operations()
        .requirePending(op.tenantKey(), op.workflowId(), op.versionId(), op.selectionDigest());
    var attempt =
        fixture.attempts().findByPublishWorkflowIdForUpdate(op.workflowId()).orElseThrow();
    attempt.setStatus(PublishAttemptStatus.FAILED);
    attempt.setFailureCode("ISOLATED_BUSINESS_DENIAL");
    fixture.attempts().save(attempt);
    fixture
        .operations()
        .seal(op.tenantKey(), op.workflowId(), op.versionId(), op.selectionDigest(), false);
  }

  private void publish(Fixture fixture) {
    fixture.write().executeWithoutResult(status -> publishInTransaction(fixture));
  }

  private void publishInTransaction(Fixture fixture) {
    publishInTransaction(fixture, true);
  }

  private void publishInTransaction(Fixture fixture, boolean seal) {
    var op = fixture.operation();
    fixture
        .operations()
        .requirePending(op.tenantKey(), op.workflowId(), op.versionId(), op.selectionDigest());
    fixture.releases().save(bundle(fixture));
    var version =
        fixture
            .versions()
            .findByTenantIdAndIdForUpdate(op.tenantKey(), op.versionId())
            .orElseThrow();
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(version.getVersionStateEpoch() + 1);
    fixture.versions().save(version);
    var artifact = new VersionAssetArtifact();
    artifact.setTenantId(op.tenantKey());
    artifact.setVersionId(op.versionId());
    artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
    artifact.setStateEpoch(1);
    artifact.setExportedVersionNumber(version.getVersionNumber());
    artifact.setManifestHash(MANIFEST);
    artifact.setLastWorkflowId(op.workflowId());
    artifact.setManifestSchemaVersion(1);
    artifact.setArtifactDigestsJson("[]");
    artifact.setPublishedObjectProofsJson("[]");
    fixture.artifacts().save(artifact);
    var attempt =
        fixture.attempts().findByPublishWorkflowIdForUpdate(op.workflowId()).orElseThrow();
    attempt.setStatus(PublishAttemptStatus.SUCCEEDED);
    fixture.attempts().save(attempt);
    if (seal)
      fixture
          .operations()
          .seal(op.tenantKey(), op.workflowId(), op.versionId(), op.selectionDigest(), true);
  }

  /**
   * Attempts a raw first seal, so failures prove content/epoch binding rather than only
   * immutability.
   */
  private void rawTerminalSeal(Fixture fixture, String forgedField) {
    var op = fixture.operation();
    var row =
        java.util.Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT to_jsonb(r)::TEXT AS evidence, publication_release_content(r) AS content, 'sha256:' || encode(sha256(publication_release_content(r)), 'hex') AS digest FROM published_release_bundle r WHERE tenant_id = ? AND version_id = ?",
                    op.tenantKey(),
                    op.versionId()));
    String evidence = row.get("evidence", String.class);
    byte[] content = row.get("content", byte[].class);
    String digest = row.get("digest", String.class);
    long epoch = op.world().request().versionStateEpoch() + 1;
    var old = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(old, "game-design-publication-owner-readback/v1");
    DraftAuthorizationFenceBinding.frame(old, op.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(old, "PUBLISHED");
    DraftAuthorizationFenceBinding.frame(old, evidence);
    var terminal = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(terminal, "game-design-publication-terminal/v1");
    DraftAuthorizationFenceBinding.frame(terminal, op.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(terminal, "PUBLISHED");
    DraftAuthorizationFenceBinding.frame(terminal, content);
    DraftAuthorizationFenceBinding.frame(terminal, Long.toString(epoch));
    fixture
        .dsl()
        .execute(
            "UPDATE game_design_publication_operation SET outcome = 'PUBLISHED', revision = revision + 1, result_bytes = ?, release_row_json = ?, terminal_evidence_bytes = ?, publication_version_state_epoch = ?, release_content_bytes = ?, published_release_bundle_digest = ? WHERE publish_workflow_id = ?",
            old.toByteArray(),
            evidence,
            forgedField.equals("terminal") ? new byte[] {1} : terminal.toByteArray(),
            forgedField.equals("epoch") ? epoch + 1 : epoch,
            forgedField.equals("content") ? new byte[] {1} : content,
            forgedField.equals("digest") ? "sha256:" + "f".repeat(64) : digest,
            op.workflowId());
  }

  private PublishedReleaseBundle bundle(Fixture fixture) {
    var op = fixture.operation();
    var bundle = new PublishedReleaseBundle();
    bundle.setTenantId(op.tenantKey());
    bundle.setVersionId(op.versionId());
    bundle.setVersionNumber(1);
    bundle.setCanonicalTenantId(op.account().tenantId());
    bundle.setCanonicalVersionId(op.world().request().canonicalVersionId());
    bundle.setAttestationSchemaVersion("v2");
    bundle.setPublishWorkflowId(op.workflowId());
    bundle.setManifestHash(MANIFEST);
    bundle.setManifestSchemaVersion(1);
    bundle.setArtifactDigestsJson("[]");
    bundle.setRequiredManifestAssetKeysJson("[]");
    bundle.setCommandDefinitionsJson("[]");
    bundle.setGenerationConfigRevision("generation-1");
    bundle.setParticipantDigestsJson(
        new ObjectMapper()
            .writeValueAsString(
                PublishedWorldSelectorFixtures.participants(op.versionId(), op.world())));
    bundle.setWorldPublishedStartLocationEvidenceJson(
        new String(op.world().canonicalBytes(), StandardCharsets.UTF_8));
    return bundle;
  }

  private void rawReleaseInsert(Fixture fixture) {
    var bundle = bundle(fixture);
    fixture
        .dsl()
        .execute(
            "INSERT INTO published_release_bundle (tenant_id, version_id, version_number, canonical_tenant_id, canonical_version_id, "
                + "published_release_bundle_ref, attestation_schema_version, publish_workflow_id, manifest_hash, generation_config_revision, manifest_schema_version, "
                + "artifact_digests_json, required_manifest_asset_keys_json, participant_digests_json, command_definitions_json, script_only, world_published_start_location_evidence_json) "
                + "VALUES (?, ?, 1, ?, ?, ?, 'v2', ?, ?, ?, 1, '[]', '[]', ?, '[]', FALSE, ?)",
            bundle.getTenantId(),
            bundle.getVersionId(),
            bundle.getCanonicalTenantId(),
            bundle.getCanonicalVersionId(),
            UUID.randomUUID().toString(),
            bundle.getPublishWorkflowId(),
            MANIFEST,
            bundle.getGenerationConfigRevision(),
            bundle.getParticipantDigestsJson(),
            bundle.getWorldPublishedStartLocationEvidenceJson());
  }

  private Fixture fixture() throws Exception {
    return fixture("51");
  }

  private Fixture fixture(String migrationTarget) throws Exception {
    String schema = "gd_pub_op_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(java.util.Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .table("flyway_schema_history_game_design_service")
        .target(migrationTarget)
        .load()
        .migrate();
    var transactions = new DataSourceTransactionManager(source);
    var write = new TransactionTemplate(transactions);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var configuration = new org.jooq.impl.DefaultConfiguration();
    configuration.set(SQLDialect.POSTGRES);
    configuration.set(
        new org.jooq.impl.DataSourceConnectionProvider(
            new TransactionAwareDataSourceProxy(source)));
    configuration.set(
        new org.springframework.boot.jooq.autoconfigure.SpringTransactionProvider(transactions));
    var dsl = DSL.using(configuration);
    var games = new GameRepository(dsl);
    var properties = new PostgresProperties();
    properties.setSchema(schema);
    var versions = new VersionRepository(dsl, properties);
    Game game =
        write.execute(
            status -> {
              var requested = new Game();
              requested.setTenantId("ISOLATED-" + UUID.randomUUID().toString().substring(0, 8));
              requested.setName("ISOLATED publication owner");
              return games.save(requested);
            });
    Version version =
        write.execute(
            status -> {
              var requested = new Version();
              requested.setTenantId(game.getTenantId());
              requested.setVersionNumber(1);
              return versions.save(requested);
            });
    var target =
        new TargetProof(
            version.getCanonicalTenantId(),
            version.getCanonicalVersionId(),
            version.getId(),
            version.getTenantId(),
            version.getIdentitySourceGameRowId(),
            version.getIdentitySourceGameTenantKey(),
            version.getIdentitySourceProvenanceKind());
    var operation =
        write.execute(
            status -> {
              try {
                return IsolatedPublicationOwnerSetup.retain(
                    dsl, target, version.getVersionStateEpoch());
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
    var operations = new GameDesignPublicationOperationRepository(dsl);
    return new Fixture(
        dsl,
        write,
        versions,
        new PublishAttemptRepository(dsl),
        new PublishedReleaseBundleRepository(dsl),
        new VersionAssetArtifactRepository(dsl),
        operations,
        new GameDesignPublicationOperationService(operations, transactions),
        operation,
        () ->
            Flyway.configure()
                .dataSource(source)
                .schemas(schema)
                .defaultSchema(schema)
                .placeholders(java.util.Map.of("serviceSchema", schema))
                .locations("classpath:db/migration")
                .table("flyway_schema_history_game_design_service")
                .load()
                .migrate());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS))
        throw new IllegalStateException("ISOLATED barrier timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate write,
      VersionRepository versions,
      PublishAttemptRepository attempts,
      PublishedReleaseBundleRepository releases,
      VersionAssetArtifactRepository artifacts,
      GameDesignPublicationOperationRepository operations,
      GameDesignPublicationOperationService service,
      GameDesignPublicationOperation operation,
      Runnable completeMigration) {}
}
