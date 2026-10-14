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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
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
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationService;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationTerminalReadGrpcService;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.FrozenAssetExportCandidate;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures;
import net.firedevops.firemud.gamedesign.service.impl.VersionAssetArtifactServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.VersionAssetExportCandidateServiceImpl;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignPublicationTerminalResponse;
import net.firedevops.firemud.test.TestContainerImages;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/** Actual GD owner DB guard proof; every upstream creator/source/freeze input is ISOLATED. */
@Testcontainers(disabledWithoutDocker = true)
class GameDesignPublicationOperationPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

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
        assertThat(result.completed).isTrue();
        var evidence =
            GameDesignPublicationTerminalReadGrpcCodec.fromResponse(request, result.response);
        assertThat(evidence.terminalEvidence().orElseThrow().canonicalBytes()).isEqualTo(expected);
        assertThat(evidence.status().name()).isEqualTo(published ? "PUBLISHED" : "NO_PUBLICATION");
      }
    }
  }

  @Test
  void authenticatedTerminalReadMapsTransactionFailuresToUnavailable() throws Exception {
    var fixture = fixture();
    var request =
        GameDesignPublicationTerminalReadEvidence.ReadRequest.create(
            "test", fixture.operation().canonicalBytes());
    PlatformTransactionManager failingTransactions =
        new PlatformTransactionManager() {
          @Override
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            throw new TransactionException("ISOLATED transaction manager failure") {};
          }

          @Override
          public void commit(TransactionStatus status) {}

          @Override
          public void rollback(TransactionStatus status) {}
        };
    var owner =
        new GameDesignPublicationOperationService(
            new GameDesignPublicationOperationRepository(fixture.dsl()), failingTransactions);

    var result = readThroughHandler(fixture, request, "account-service", owner);

    assertThat(result.error).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(result.response).isNull();
    assertThat(result.completed).isFalse();
  }

  @Test
  void authenticatedTerminalReadMapsResponseEncodingFailuresWithoutPartialResponses()
      throws Exception {
    var fixture = fixture();
    publish(fixture);
    var request =
        GameDesignPublicationTerminalReadEvidence.ReadRequest.create(
            "test", fixture.operation().canonicalBytes());

    for (RuntimeException encodingFailure :
        List.of(
            new IllegalArgumentException("ISOLATED invalid response"),
            new IllegalStateException("ISOLATED inconsistent response"),
            new RuntimeException("ISOLATED response encoder failure"))) {
      try (var codec =
          org.mockito.Mockito.mockStatic(
              GameDesignPublicationTerminalReadGrpcCodec.class,
              org.mockito.Mockito.CALLS_REAL_METHODS)) {
        codec
            .when(
                () ->
                    GameDesignPublicationTerminalReadGrpcCodec.toResponse(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(encodingFailure);

        var result = readThroughHandler(fixture, request, "account-service");

        Status.Code expected =
            encodingFailure instanceof IllegalArgumentException
                    || encodingFailure instanceof IllegalStateException
                ? Status.Code.FAILED_PRECONDITION
                : Status.Code.INTERNAL;
        assertThat(result.error).isEqualTo(expected);
        assertThat(result.response).isNull();
        assertThat(result.completed).isFalse();
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
  void retainedV50_6TerminalReadFailsPreconditionWithoutBackfillingEvidenceOrEpoch()
      throws Exception {
    var fixture = historicalFixture("50.6");
    assertThat(fixture.candidate()).isNull();
    var op = fixture.operation();
    var old = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(old, "game-design-publication-owner-readback/v1");
    DraftAuthorizationFenceBinding.frame(old, op.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(old, "NO_PUBLICATION");
    // V50.6 framed nullable release_row_json as a present, zero-byte fourth frame for
    // NO_PUBLICATION. Preserve that historical encoding exactly without routing an empty value
    // through the nonempty canonical-text helper.
    DraftAuthorizationFenceBinding.frame(old, new byte[0]);
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

  @Test
  void retainedPreV56V1OperationStaysByteExactReadableAndReadOnlyAfterV56AndV70() throws Exception {
    var fixture = historicalOperationOnlyFixture("50.7");
    var operation = fixture.operation();
    byte[] originalRequest = operation.canonicalBytes();
    new DraftAuthorizationFenceBinding.FrameReader(originalRequest)
        .expect("game-design-publication-operation/v1");
    var before = operationSnapshot(fixture);
    assertThat(before.requestBytes()).isEqualTo(originalRequest);
    assertThat(before.requestChecksum()).isEqualTo(sha256(originalRequest));
    assertThatThrownBy(operation::inventory)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("HISTORICAL_PUBLICATION_INVENTORY_UNAVAILABLE");

    fixture.completeMigration().run();

    var afterMigration = operationSnapshot(fixture);
    assertThat(afterMigration.rowJson()).isEqualTo(before.rowJson());
    assertThat(afterMigration.requestBytes()).isEqualTo(before.requestBytes());
    assertThat(afterMigration.requestChecksum()).isEqualTo(before.requestChecksum());
    assertThat(afterMigration.rowChecksum()).isEqualTo(before.rowChecksum());
    var retained = fixture.service().readExact(operation).orElseThrow();
    assertThat(retained.operation().canonicalBytes()).isEqualTo(originalRequest);
    assertThat(retained.outcome()).isEqualTo("PENDING");

    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "INSERT INTO game_design_publication_operation "
                            + "(publish_workflow_id, tenant_id, version_id, selection_digest, request_bytes) "
                            + "VALUES (?, ?, ?, ?, ?)",
                        operation.workflowId(),
                        operation.tenantKey(),
                        operation.versionId(),
                        operation.selectionDigest(),
                        originalRequest))
        .hasMessageContaining("Truncated publication inventory frame");

    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE game_design_publication_operation SET outcome = 'NO_PUBLICATION', "
                            + "revision = revision + 1, result_bytes = ? WHERE publish_workflow_id = ?",
                        new byte[] {1},
                        operation.workflowId()))
        .hasMessageContaining("Truncated publication inventory frame");

    var selection = operation.account().input().selection();
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "INSERT INTO game_design_realm_policy_capture "
                            + "(canonical_tenant_id, canonical_version_id, publish_workflow_id, "
                            + "commit_id, operation_bytes, capture_bytes) VALUES (?, ?, ?, ?, ?, ?)",
                        operation.account().tenantId(),
                        operation.world().request().canonicalVersionId(),
                        operation.workflowId(),
                        selection.selectedCommit().commitId(),
                        originalRequest,
                        new byte[] {1}))
        .hasMessageContaining("Truncated publication inventory frame");

    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM game_design_realm_policy_capture WHERE publish_workflow_id = ?",
                    operation.workflowId())
                .get(0, Long.class))
        .isZero();
    var afterDeniedWrites = operationSnapshot(fixture);
    assertThat(afterDeniedWrites.rowJson()).isEqualTo(before.rowJson());
    assertThat(afterDeniedWrites.requestBytes()).isEqualTo(before.requestBytes());
    assertThat(afterDeniedWrites.requestChecksum()).isEqualTo(before.requestChecksum());
    assertThat(afterDeniedWrites.rowChecksum()).isEqualTo(before.rowChecksum());
    assertThat(fixture.service().readExact(operation).orElseThrow().outcome()).isEqualTo("PENDING");
  }

  @Test
  void retainedPublicationReceiptSurvivesAdditiveReleaseColumnAndV1SealIsDeniedAfterV56()
      throws Exception {
    var retained = historicalFixture("50.7");
    publish(retained);
    var original = retained.service().readExact(retained.operation()).orElseThrow();
    String originalEvidence =
        java.util.Objects.requireNonNull(
                retained
                    .dsl()
                    .fetchOne(
                        "SELECT release_row_json FROM game_design_publication_operation WHERE publish_workflow_id = ?",
                        retained.operation().workflowId()),
                "Expected retained publication operation row")
            .get("release_row_json", String.class);
    String originalWholeRow =
        java.util.Objects.requireNonNull(
                retained
                    .dsl()
                    .fetchOne(
                        "SELECT to_jsonb(r)::TEXT AS evidence FROM published_release_bundle r WHERE tenant_id = ? AND version_id = ?",
                        retained.operation().tenantKey(),
                        retained.operation().versionId()),
                "Expected original published release row")
            .get("evidence", String.class);
    assertThat(originalEvidence).isEqualTo(originalWholeRow);

    retained.completeMigration().run();
    retained
        .write()
        .executeWithoutResult(
            status ->
                retained
                    .dsl()
                    .execute(
                        "ALTER TABLE published_release_bundle ADD COLUMN future_optional_evidence TEXT"));
    String expandedWholeRow =
        java.util.Objects.requireNonNull(
                retained
                    .dsl()
                    .fetchOne(
                        "SELECT to_jsonb(r)::TEXT AS evidence FROM published_release_bundle r WHERE tenant_id = ? AND version_id = ?",
                        retained.operation().tenantKey(),
                        retained.operation().versionId()),
                "Expected published release row after additive column")
            .get("evidence", String.class);
    assertThat(expandedWholeRow).isNotEqualTo(originalEvidence);
    var replay = retained.service().readExact(retained.operation()).orElseThrow();
    assertThat(replay.receiptBytes()).isEqualTo(original.receiptBytes());
    assertThat(replay.terminalEvidenceBytes()).isEqualTo(original.terminalEvidenceBytes());
    assertThatThrownBy(
            () ->
                retained
                    .write()
                    .executeWithoutResult(
                        status ->
                            retained
                                .dsl()
                                .execute(
                                    "UPDATE published_release_bundle SET generation_config_revision = 'changed' WHERE tenant_id = ? AND version_id = ?",
                                    retained.operation().tenantKey(),
                                    retained.operation().versionId())))
        .isInstanceOf(RuntimeException.class);
    assertThat(retained.service().readExact(retained.operation()).orElseThrow().receiptBytes())
        .isEqualTo(original.receiptBytes());

    var retainedPending = historicalFixture("50.7");
    var originalOperationBytes = retainedPending.operation().canonicalBytes();
    retainedPending.completeMigration().run();
    assertThatThrownBy(
            () ->
                retainedPending
                    .dsl()
                    .execute(
                        "UPDATE game_design_publication_operation SET outcome = 'NO_PUBLICATION', "
                            + "revision = revision + 1, result_bytes = ? WHERE publish_workflow_id = ?",
                        new byte[] {1},
                        retainedPending.operation().workflowId()))
        .hasMessageContaining("Truncated publication inventory frame");
    var stillPending =
        retainedPending.service().readExact(retainedPending.operation()).orElseThrow();
    assertThat(stillPending.outcome()).isEqualTo("PENDING");
    assertThat(stillPending.operation().canonicalBytes()).isEqualTo(originalOperationBytes);

    var fresh = fixture();
    fresh.completeMigration().run();
    fresh
        .write()
        .executeWithoutResult(
            status ->
                fresh
                    .dsl()
                    .execute(
                        "ALTER TABLE published_release_bundle ADD COLUMN future_optional_evidence TEXT"));
    publish(fresh);
    var freshRead = fresh.service().readExact(fresh.operation()).orElseThrow();
    assertThat(freshRead.outcome()).isEqualTo("PUBLISHED");
    assertThat(freshRead.receiptBytes()).isNotNull();
    assertThat(fresh.service().readExact(fresh.operation()).orElseThrow().receiptBytes())
        .isEqualTo(freshRead.receiptBytes());
  }

  private ReadCollector readThroughHandler(
      Fixture fixture,
      GameDesignPublicationTerminalReadEvidence.ReadRequest request,
      String service) {
    return readThroughHandler(fixture, request, service, fixture.service());
  }

  private OperationRowSnapshot operationSnapshot(Fixture fixture) {
    var row =
        java.util.Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT to_jsonb(o)::TEXT AS row_json, o.request_bytes, "
                        + "'sha256:' || encode(sha256(o.request_bytes), 'hex') AS request_checksum "
                        + "FROM game_design_publication_operation o WHERE publish_workflow_id = ?",
                    fixture.operation().workflowId()));
    String rowJson = row.get("row_json", String.class);
    return new OperationRowSnapshot(
        rowJson,
        row.get("request_bytes", byte[].class),
        row.get("request_checksum", String.class),
        sha256(rowJson.getBytes(StandardCharsets.UTF_8)));
  }

  private ReadCollector readThroughHandler(
      Fixture fixture,
      GameDesignPublicationTerminalReadEvidence.ReadRequest request,
      String service,
      GameDesignPublicationOperationService owner) {
    var handler = new GameDesignPublicationTerminalReadGrpcService(owner, "test");
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
    private boolean completed;

    @Override
    public void onNext(ReadGameDesignPublicationTerminalResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

  private static final String ASSET_FILE_NAME = "publication-operation-logo.png";
  private static final String ASSET_CONTENT_TYPE = "image/png";
  private static final byte[] ASSET_BYTES =
      "isolated publication asset bytes".getBytes(StandardCharsets.UTF_8);
  private static final String PUBLIC_BASE_URL = "https://assets.example.invalid/assets";

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
          .hasMessageContaining("publication operation identity or terminal outcome is immutable");
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
      String expectedGuardMessage =
          forgedField.equals("terminal")
              ? "publication terminal bytes differ from original owner result"
              : "publication terminal content or committed epoch differs from owner seal";
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
          .hasMessageContaining(expectedGuardMessage);
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
    var attempting = new CountDownLatch(1);
    var winnerPid = new AtomicInteger();
    var loserPid = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var winner =
          executor.submit(
              () ->
                  fixture
                      .write()
                      .executeWithoutResult(
                          status -> {
                            noPublicationInTransaction(fixture);
                            winnerPid.set(
                                java.util.Objects.requireNonNull(
                                        fixture.dsl().fetchOne("SELECT pg_backend_pid()"),
                                        "no-publication transaction must expose its PostgreSQL backend")
                                    .get(0, Integer.class));
                            sealed.countDown();
                            await(releaseSeal);
                          }));
      assertThat(sealed.await(10, TimeUnit.SECONDS)).isTrue();
      var loser =
          executor.submit(
              () -> {
                fixture
                    .write()
                    .executeWithoutResult(
                        status -> {
                          loserPid.set(
                              java.util.Objects.requireNonNull(
                                      fixture.dsl().fetchOne("SELECT pg_backend_pid()"),
                                      "late publisher transaction must expose its PostgreSQL backend")
                                  .get(0, Integer.class));
                          attempting.countDown();
                          assertThatThrownBy(() -> publishInTransaction(fixture))
                              .isInstanceOf(IllegalStateException.class);
                        });
              });
      assertThat(attempting.await(10, TimeUnit.SECONDS)).isTrue();
      boolean observedBlock = false;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < deadline) {
        var activity =
            fixture
                .dsl()
                .fetchOne(
                    "SELECT ? = ANY(pg_blocking_pids(pid)) AND wait_event_type = 'Lock' "
                        + "AND query LIKE 'SELECT id FROM game WHERE tenant_id%' AS blocked "
                        + "FROM pg_stat_activity WHERE pid = ?",
                    winnerPid.get(), loserPid.get());
        if (activity != null && Boolean.TRUE.equals(activity.get("blocked", Boolean.class))) {
          observedBlock = true;
          break;
        }
        Thread.sleep(25);
      }
      assertThat(observedBlock)
          .as("late publisher backend blocked by no-publication winner on the game owner lock")
          .isTrue();
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
    // Emulate the historical V50.6/V50.7 seal storage directly. The current attempt wrapper also
    // writes the V50.9 realm-policy association, which is absent from these schemas.
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
    if (fixture.historicalV1()) {
      int updatedArtifact =
          fixture
              .dsl()
              .execute(
                  "UPDATE version_asset_artifact SET artifact_state = 'PUBLISHED', "
                      + "state_epoch = state_epoch + 1, updated_at = CURRENT_TIMESTAMP "
                      + "WHERE tenant_id = ? AND version_id = ? AND artifact_state = 'STAGED' "
                      + "AND state_epoch = 2 AND last_workflow_id = ?",
                  op.tenantKey(),
                  op.versionId(),
                  op.workflowId());
      if (updatedArtifact != 1) {
        throw new IllegalStateException("HISTORICAL_ASSET_ARTIFACT_TRANSITION_CONFLICT");
      }
    } else {
      var artifact =
          fixture
              .artifacts()
              .findByTenantIdAndVersionIdForUpdate(op.tenantKey(), op.versionId())
              .orElseThrow();
      artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
      artifact.setStateEpoch(Math.addExact(artifact.getStateEpoch(), 1L));
      fixture.artifacts().save(artifact);
    }
    var attempt =
        fixture.attempts().findByPublishWorkflowIdForUpdate(op.workflowId()).orElseThrow();
    attempt.setStatus(PublishAttemptStatus.SUCCEEDED);
    fixture.attempts().save(attempt);
    if (seal) {
      // Emulate the historical V50.6/V50.7 seal storage without querying the V50.9 policy
      // association.
      fixture
          .operations()
          .seal(op.tenantKey(), op.workflowId(), op.versionId(), op.selectionDigest(), true);
    }
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
    bundle.setManifestHash(fixture.candidate().manifest().manifestHash());
    bundle.setManifestSchemaVersion(1);
    bundle.setArtifactDigestsJson(
        new ObjectMapper().writeValueAsString(fixture.candidate().manifest().artifactDigests()));
    bundle.setRequiredManifestAssetKeysJson(
        new ObjectMapper()
            .writeValueAsString(fixture.candidate().manifest().requiredManifestAssetKeys()));
    bundle.setCommandDefinitionsJson("[]");
    bundle.setGenerationConfigRevision("generation-1");
    bundle.setParticipantDigestsJson(
        new ObjectMapper()
            .writeValueAsString(
                PublishedWorldSelectorFixtures.participants(
                    op.versionId(),
                    op.world(),
                    AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION)));
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
            bundle.getManifestHash(),
            bundle.getGenerationConfigRevision(),
            bundle.getParticipantDigestsJson(),
            bundle.getWorldPublishedStartLocationEvidenceJson());
  }

  private Version insertRetainedVersion(
      DSLContext dsl, VersionRepository versions, Game game, int versionNumber) {
    var inserted =
        dsl.fetchOne(
            "INSERT INTO version (tenant_id, canonical_version_id, canonical_tenant_id, "
                + "identity_source_game_row_id, identity_source_game_tenant_key, "
                + "identity_source_provenance_kind, version_number, version_state, version_state_epoch, "
                + "script_patch_version, base_version_id, is_script_only, notes, created_at, updated_at) "
                + "SELECT g.tenant_id, ?, g.canonical_tenant_id, g.id, g.tenant_id, "
                + "g.tenant_identity_provenance_kind, ?, 'DRAFT', 1, NULL, NULL, FALSE, "
                + "'ISOLATED retained publication operation fixture', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
                + "FROM game g WHERE g.id = ? RETURNING id",
            UUID.randomUUID(),
            versionNumber,
            game.getId());
    if (inserted == null) {
      throw new IllegalStateException("ISOLATED retained Game Design owner row is absent");
    }
    return versions.findById(inserted.get("id", Long.class)).orElseThrow();
  }

  private Fixture fixture() throws Exception {
    return fixture(null, false, true);
  }

  private Fixture historicalFixture(String migrationTarget) throws Exception {
    return fixture(migrationTarget, true, true);
  }

  /** This profile proves retained operation history without seeding unrelated old asset rows. */
  private Fixture historicalOperationOnlyFixture(String migrationTarget) throws Exception {
    return fixture(migrationTarget, true, false);
  }

  private Fixture fixture(
      String migrationTarget, boolean historicalV1, boolean includeAssetCandidate)
      throws Exception {
    if (historicalV1 && !List.of("50.6", "50.7").contains(migrationTarget)) {
      throw new IllegalArgumentException("Historical operation fixture must target pre-V56 schema");
    }
    String schema = "gd_pub_op_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    var flyway =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(java.util.Map.of("serviceSchema", schema))
            .locations("classpath:db/migration")
            .table("flyway_schema_history_game_design_service");
    if (migrationTarget != null) {
      flyway.target(migrationTarget);
    }
    flyway.load().migrate();
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
    var versions = new VersionRepository(dsl);
    Game game =
        write.execute(
            status -> {
              var requested = new Game();
              requested.setTenantId("ISOLATED-" + UUID.randomUUID().toString().substring(0, 8));
              requested.setName("ISOLATED publication owner");
              return games.save(requested);
            });
    Version version;
    if (historicalV1) {
      // Retained pre-V56 rows use the original Version shape and operation framing.
      version = write.execute(status -> insertRetainedVersion(dsl, versions, game, 1));
    } else {
      var requested = new Version();
      requested.setTenantId(game.getTenantId());
      requested.setVersionNumber(1);
      version = write.execute(status -> versions.save(requested));
    }
    var target =
        new TargetProof(
            version.getCanonicalTenantId(),
            version.getCanonicalVersionId(),
            version.getId(),
            version.getTenantId(),
            version.getIdentitySourceGameRowId(),
            version.getIdentitySourceGameTenantKey(),
            version.getIdentitySourceProvenanceKind());
    GameAsset asset = null;
    if (includeAssetCandidate && !"50.6".equals(migrationTarget)) {
      var gameAssets = new GameAssetRepository(dsl);
      asset =
          write.execute(
              status -> {
                var requested = new GameAsset();
                requested.setTenantId(game.getTenantId());
                requested.setFileName(ASSET_FILE_NAME);
                requested.setContentType(ASSET_CONTENT_TYPE);
                requested.setData(ASSET_BYTES);
                return gameAssets.save(requested);
              });
    }
    GameAsset fixtureAsset = asset;
    var operation =
        write.execute(
            status -> {
              try {
                return historicalV1
                    ? IsolatedPublicationOwnerSetup.retainHistoricalV1PreV56(
                        dsl, target, version.getVersionStateEpoch())
                    : IsolatedPublicationOwnerSetup.retainFrozenSourceBacked(
                        dsl,
                        target,
                        version.getVersionStateEpoch(),
                        "ISOLATED selected source and publication fixture",
                        List.of(
                            new IsolatedPublicationOwnerSetup.OrdinaryAsset(
                                Objects.requireNonNull(fixtureAsset).getId(), ASSET_FILE_NAME)));
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
    var operations = new GameDesignPublicationOperationRepository(dsl);
    var artifacts = new VersionAssetArtifactRepository(dsl);
    FrozenAssetExportCandidate readCandidate = null;
    if (includeAssetCandidate && !"50.6".equals(migrationTarget)) {
      if (historicalV1) {
        var snapshot =
            seedHistoricalAssetSnapshot(
                dsl, write, game, version, Objects.requireNonNull(fixtureAsset));
        readCandidate =
            seedHistoricalAssetCandidate(dsl, write, game, version, operation, snapshot);
      } else {
        var publicationRepository = new VersionAssetPublicationRepository(dsl);
        var snapshot =
            write.execute(
                status ->
                    publicationRepository.freezeOrReadSnapshot(
                        game.getTenantId(), version.getVersionNumber()));
        var artifactService =
            new VersionAssetArtifactServiceImpl(
                artifacts, null, versions, null, null, null, null, null, new ObjectMapper());
        write.execute(
            status ->
                artifactService.stageExport(
                    game.getTenantId(),
                    version.getId(),
                    version.getVersionNumber(),
                    operation.workflowId()));
        var candidateService =
            new VersionAssetExportCandidateServiceImpl(
                versions,
                games,
                new PublishAttemptRepository(dsl),
                artifacts,
                publicationRepository,
                transactions,
                new ObjectMapper());
        var requestedCandidate = candidate(snapshot);
        var committedCandidate =
            candidateService.recordExportCandidate(
                game.getTenantId(), version.getVersionNumber(), requestedCandidate);
        readCandidate =
            candidateService.readExportCandidate(game.getTenantId(), version.getVersionNumber());
        assertThat(committedCandidate).isEqualTo(requestedCandidate);
        assertThat(readCandidate).isEqualTo(committedCandidate);
      }
    }
    return new Fixture(
        dsl,
        write,
        versions,
        new PublishAttemptRepository(dsl),
        new PublishedReleaseBundleRepository(dsl),
        artifacts,
        operations,
        new GameDesignPublicationOperationService(operations, transactions),
        operation,
        readCandidate,
        historicalV1,
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

  private FrozenAssetExportCandidate candidate(
      VersionAssetPublicationRepository.ExportSnapshot snapshot) {
    var source = snapshot.items().get(0);
    String contentDigest = source.contentDigest();
    String objectKey = "artifacts/sha256/" + contentDigest.substring("sha256:".length());
    var digest =
        new PublishedArtifactDigest(
            source.usageKey(), "BINARY", objectKey, contentDigest, source.contentType(), 1);
    String url = PUBLIC_BASE_URL + "/" + objectKey;
    String manifestJson =
        "{\"schemaVersion\":1,\"assets\":{\""
            + source.usageKey()
            + "\":{\"usageKey\":\""
            + source.usageKey()
            + "\",\"artifactKind\":\"BINARY\",\"immutableObjectKey\":\""
            + objectKey
            + "\",\"contentDigest\":\""
            + contentDigest
            + "\",\"contentType\":\""
            + source.contentType()
            + "\",\"artifactSchemaVersion\":1,\"producerService\":\"game-design-service\",\"versionId\":\""
            + snapshot.canonicalVersionId()
            + "\",\"url\":\""
            + url
            + "\"}}}";
    return new FrozenAssetExportCandidate(
        new ExportedAssetManifest(
            sha256(manifestJson.getBytes(StandardCharsets.UTF_8)),
            1,
            List.of(source.usageKey()),
            List.of(digest)),
        PUBLIC_BASE_URL);
  }

  /**
   * Seeds the actual V37 mapping/export rows that existed before V55 introduced source projection.
   * The old schema permits these owner rows directly; no modern ASSET_REFERENCE evidence is
   * fabricated for a Version created before that contract existed.
   */
  private VersionAssetPublicationRepository.ExportSnapshot seedHistoricalAssetSnapshot(
      DSLContext dsl, TransactionTemplate write, Game game, Version version, GameAsset asset) {
    String contentDigest = sha256(ASSET_BYTES);
    write.executeWithoutResult(
        status -> {
          dsl.execute(
              "INSERT INTO version_asset (tenant_id, version_id, asset_id, usage_key, usage_type) "
                  + "VALUES (?, ?, ?, ?, ?)",
              game.getTenantId(),
              version.getId(),
              asset.getId(),
              ASSET_FILE_NAME,
              "logo");
          dsl.execute(
              "INSERT INTO version_asset_export_item "
                  + "(tenant_id, version_id, usage_key, asset_id, content_type, content_hash, byte_size) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?)",
              game.getTenantId(),
              version.getId(),
              ASSET_FILE_NAME,
              asset.getId(),
              ASSET_CONTENT_TYPE,
              contentDigest,
              (long) ASSET_BYTES.length);
          dsl.execute(
              "INSERT INTO version_asset_export_snapshot "
                  + "(tenant_id, version_id, canonical_tenant_id, canonical_version_id, "
                  + "version_number, captured_version_state_epoch, item_count) "
                  + "VALUES (?, ?, ?, ?, ?, ?, 1)",
              game.getTenantId(),
              version.getId(),
              version.getCanonicalTenantId(),
              version.getCanonicalVersionId(),
              version.getVersionNumber(),
              version.getVersionStateEpoch());
        });
    return new VersionAssetPublicationRepository(dsl)
        .readFrozenSnapshot(game.getTenantId(), version.getVersionNumber());
  }

  /** Persists and rereads the original V38/V50 candidate shape without V65 inventory authority. */
  private FrozenAssetExportCandidate seedHistoricalAssetCandidate(
      DSLContext dsl,
      TransactionTemplate write,
      Game game,
      Version version,
      GameDesignPublicationOperation operation,
      VersionAssetPublicationRepository.ExportSnapshot snapshot)
      throws Exception {
    var requested = candidate(snapshot);
    var mapper = new ObjectMapper();
    String artifactDigestsJson = mapper.writeValueAsString(requested.manifest().artifactDigests());
    String objectProofsJson = historicalObjectProofsJson(requested, mapper);
    String assetKeysJson =
        mapper.writeValueAsString(requested.manifest().requiredManifestAssetKeys());
    write.executeWithoutResult(
        status ->
            dsl.execute(
                "INSERT INTO version_asset_artifact "
                    + "(tenant_id, version_id, exported_version_number, artifact_state, state_epoch, "
                    + "last_workflow_id, exported_manifest_asset_keys_json) "
                    + "VALUES (?, ?, ?, 'STAGED', 1, ?, '[]')",
                game.getTenantId(),
                version.getId(),
                version.getVersionNumber(),
                operation.workflowId()));
    var updated =
        write.execute(
            status ->
                dsl.execute(
                    "UPDATE version_asset_artifact SET manifest_hash = ?, manifest_schema_version = ?, "
                        + "artifact_digests_json = ?, published_object_proofs_json = ?, "
                        + "candidate_snapshot_version_id = ?, candidate_public_base_url = ?, "
                        + "exported_manifest_asset_keys_json = ?, state_epoch = state_epoch + 1, "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "WHERE tenant_id = ? AND version_id = ? AND artifact_state = 'STAGED' "
                        + "AND state_epoch = 1 AND manifest_schema_version IS NULL "
                        + "AND last_workflow_id = ?",
                    requested.manifest().manifestHash(),
                    requested.manifest().manifestSchemaVersion(),
                    artifactDigestsJson,
                    objectProofsJson,
                    version.getId(),
                    requested.publicBaseUrl(),
                    assetKeysJson,
                    game.getTenantId(),
                    version.getId(),
                    operation.workflowId()));
    if (updated == null || updated != 1) {
      throw new IllegalStateException("HISTORICAL_ASSET_CANDIDATE_WRITE_CONFLICT");
    }
    var committed =
        write.execute(
            status -> readHistoricalAssetCandidate(dsl, game.getTenantId(), version, mapper));
    assertThat(committed).isEqualTo(requested);
    var readback =
        write.execute(
            status -> readHistoricalAssetCandidate(dsl, game.getTenantId(), version, mapper));
    assertThat(readback).isEqualTo(committed);
    return readback;
  }

  private FrozenAssetExportCandidate readHistoricalAssetCandidate(
      DSLContext dsl, String tenantId, Version version, ObjectMapper mapper) {
    var row =
        dsl.fetchOne(
            "SELECT manifest_hash, manifest_schema_version, artifact_digests_json, "
                + "published_object_proofs_json, candidate_public_base_url, "
                + "exported_manifest_asset_keys_json FROM version_asset_artifact "
                + "WHERE tenant_id = ? AND version_id = ?",
            tenantId,
            version.getId());
    if (row == null) {
      throw new IllegalStateException("HISTORICAL_ASSET_CANDIDATE_UNAVAILABLE");
    }
    var keys = new ArrayList<String>();
    for (var node : mapper.readTree(row.get("exported_manifest_asset_keys_json", String.class))) {
      keys.add(node.textValue());
    }
    var digests = new ArrayList<PublishedArtifactDigest>();
    for (var node : mapper.readTree(row.get("artifact_digests_json", String.class))) {
      digests.add(
          new PublishedArtifactDigest(
              node.get("usageKey").textValue(),
              node.get("artifactKind").textValue(),
              node.get("immutableObjectKey").textValue(),
              node.get("contentDigest").textValue(),
              node.get("contentType").textValue(),
              node.get("artifactSchemaVersion").intValue()));
    }
    var manifest =
        new ExportedAssetManifest(
            row.get("manifest_hash", String.class),
            row.get("manifest_schema_version", Integer.class),
            keys,
            digests);
    var candidate =
        new FrozenAssetExportCandidate(
            manifest, row.get("candidate_public_base_url", String.class));
    if (!historicalObjectProofsJson(candidate, mapper)
        .equals(row.get("published_object_proofs_json", String.class))) {
      throw new IllegalStateException("HISTORICAL_ASSET_CANDIDATE_READBACK_CONFLICT");
    }
    return candidate;
  }

  private String historicalObjectProofsJson(
      FrozenAssetExportCandidate candidate, ObjectMapper mapper) {
    var proofs = new ArrayList<Map<String, String>>();
    for (var digest : candidate.manifest().artifactDigests()) {
      proofs.add(historicalObjectProof(digest.immutableObjectKey(), digest.contentDigest()));
    }
    String manifestHash = candidate.manifest().manifestHash();
    proofs.add(
        historicalObjectProof(
            "manifests/sha256/" + manifestHash.substring("sha256:".length()), manifestHash));
    proofs.sort(Comparator.comparing(proof -> proof.get("immutableObjectKey")));
    return mapper.writeValueAsString(proofs);
  }

  private Map<String, String> historicalObjectProof(String objectKey, String contentDigest) {
    var proof = new LinkedHashMap<String, String>();
    proof.put("immutableObjectKey", objectKey);
    proof.put("contentDigest", contentDigest);
    return proof;
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
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

  private record OperationRowSnapshot(
      String rowJson, byte[] requestBytes, String requestChecksum, String rowChecksum) {}

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
      FrozenAssetExportCandidate candidate,
      boolean historicalV1,
      Runnable completeMigration) {}
}
