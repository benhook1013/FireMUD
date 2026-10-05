package integration.net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.impl.AuthoredWorldVersionStateGrpcService;
import net.firedevops.firemud.gamedesign.service.impl.AuthoredWorldVersionStateService;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityService;
import org.assertj.core.api.Assertions;
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
import org.jooq.impl.DataSourceConnectionProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL-to-physical-mTLS-socket proof for the unregistered current version-state read.
 *
 * <p>Fixtures are synthetic persisted Game Design source/version rows. The producer, repositories,
 * transaction manager, standalone production handler, peer interceptor, physical World client, and
 * World identity owner repositories are real. The World source intake is explicitly
 * fixture-injected from the exact persisted Game Design source under the authenticated
 * same-namespace caller context; this does not prove automatic delivery. The proof creates no World
 * content or lifecycle rows and does not prove publication or activation. Ephemeral test-only
 * certificates cover the one authorized World client and Game Design server needed here; the
 * socket-only {@code AuthoredWorldVersionStateMtlsTest} retains wrong-peer and malformed-evidence
 * coverage.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = GameDesignServiceApplication.class,
    properties = {
      "spring.profiles.active=test",
      "firemud.auth.jwt-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "firemud.grpc.plaintext=true",
      "firemud.grpc.workload-namespace=authored-version-state-mtls-test",
      "spring.grpc.server.port=0",
      "asset.store.endpoint=http://localhost:9000",
      "asset.store.bucket=test-bucket",
      "asset.store.region=us-east-1",
      "asset.store.access-key=test-access-key",
      "asset.store.secret-key=test-secret-key"
    })
@Import(NoGrpcServerTestConfiguration.class)
class AuthoredWorldVersionStateMtlsIntegrationTest {
  private static final String NAMESPACE = "authored-version-state-mtls-test";
  private static final AtomicLong CERTIFICATE_SERIAL = new AtomicLong(1L);
  private static final java.util.List<String> WORLD_CONTENT_AND_LIFECYCLE_TABLES =
      java.util.List.of(
          "generation_rule",
          "instance",
          "region",
          "region_instance",
          "room",
          "room_exit",
          "room_instance",
          "room_instance_exit",
          "world_design_aggregate_epoch",
          "world_design_revision_ledger",
          "world_design_scope_epoch",
          "world_entity_spawn_binding",
          "world_event",
          "world_instance",
          "zone",
          "zone_instance");

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static PostgreSQLContainer<?> worldPostgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private static TestPki pki;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @Autowired private AuthoredWorldVersionStateService versionStateService;
  @Autowired private GameRepository gameRepository;
  @Autowired private GameAuthoredWorldSourceRepository sourceRepository;
  @Autowired private VersionRepository versionRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private org.jooq.DSLContext dsl;

  private Server server;
  private final AtomicLong producerReadCalls = new AtomicLong();

  @BeforeAll
  static void createEphemeralTestCertificates() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPair caKeyPair = newRsaKeyPair();
    X500Name caName = new X500Name("CN=Authored Version State Integration Test CA, O=FireMUD Test");
    X509Certificate caCertificate =
        issueCertificate(
            caName, caKeyPair.getPublic(), caName, caKeyPair.getPrivate(), true, null, false);
    pki =
        new TestPki(
            caCertificate,
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "game-design-service",
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-design-service",
                true),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "world-management-service",
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/world-management-service",
                false));
  }

  @BeforeEach
  void startProductionHandlerOnPhysicalMutualTlsSocket() throws Exception {
    AuthoredWorldVersionStateGrpcService handler =
        new AuthoredWorldVersionStateGrpcService(versionStateService, NAMESPACE);
    producerReadCalls.set(0L);
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            pki.gameDesignServerCertificate().privateKey(),
                            pki.gameDesignServerCertificate().certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    handler,
                    new GrpcPeerIdentityInterceptor(),
                    new ServerInterceptor() {
                      @Override
                      public <RequestT, ResponseT> ServerCall.Listener<RequestT> interceptCall(
                          ServerCall<RequestT, ResponseT> call,
                          Metadata headers,
                          ServerCallHandler<RequestT, ResponseT> next) {
                        if (call.getMethodDescriptor()
                            .getBareMethodName()
                            .equals("GetAuthoredWorldVersionState")) {
                          producerReadCalls.incrementAndGet();
                        }
                        return next.startCall(call, headers);
                      }
                    }))
            .build()
            .start();
  }

  @AfterEach
  void stopPhysicalSocket() throws Exception {
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
  }

  @Test
  void returnsCompletePersistedSourceAndVersionStateOverSocketWithoutMutation(
      @TempDir Path directory) throws Exception {
    Fixture fixture = fixture("complete-socket-read");
    Map<String, String> before = rowVersions(fixture);
    AuthoredWorldVersionStateEvidence.Request request = request(fixture, UUID.randomUUID());

    AuthoredWorldVersionStateEvidence actual;
    try (AuthoredWorldVersionStateClient client = newClient(directory)) {
      client.init();
      actual = client.read(request);
    }

    AuthoredWorldVersionStateEvidence expected =
        AuthoredWorldVersionStateEvidence.create(
            request,
            fixture.source(),
            fixture.version().getCanonicalVersionId(),
            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                .VERSION_LIFECYCLE_STATE_DRAFT,
            1L);
    assertThat(actual).isEqualTo(expected);
    assertThat(actual.canonicalVersionId()).isEqualTo(fixture.version().getCanonicalVersionId());
    assertThat(actual.request()).isEqualTo(request);
    assertThat(actual.sourceEvidence()).isEqualTo(fixture.source());
    assertThat(actual.request().canonicalTenantId().toString())
        .isNotEqualTo(fixture.privateTenantKey());
    assertThat(rowVersions(fixture)).isEqualTo(before);
  }

  @Test
  void changedSourceDigestAndForeignPersistedVersionFailClosedWithoutMutation(
      @TempDir Path directory) throws Exception {
    Fixture fixture = fixture("closed-source");
    Fixture otherGame = fixture("closed-foreign-version");
    Map<String, String> before = rowVersions(fixture);
    Map<String, String> otherBefore = rowVersions(otherGame);
    AuthoredWorldVersionStateEvidence.Request validRequest = request(fixture, UUID.randomUUID());
    AuthoredWorldVersionStateEvidence.Request changedDigestRequest =
        new AuthoredWorldVersionStateEvidence.Request(
            1,
            NAMESPACE,
            validRequest.readRequestId(),
            validRequest.canonicalTenantId(),
            validRequest.worldSlug(),
            validRequest.sourceOperationId(),
            "sha256:" + "f".repeat(64),
            validRequest.versionId());
    AuthoredWorldVersionStateEvidence.Request foreignVersionRequest =
        new AuthoredWorldVersionStateEvidence.Request(
            1,
            NAMESPACE,
            UUID.randomUUID(),
            validRequest.canonicalTenantId(),
            validRequest.worldSlug(),
            validRequest.sourceOperationId(),
            validRequest.expectedSourceEvidenceDigest(),
            otherGame.version().getId());

    try (AuthoredWorldVersionStateClient client = newClient(directory)) {
      client.init();
      assertStatus(() -> client.read(changedDigestRequest), Status.Code.FAILED_PRECONDITION);
      assertThat(rowVersions(fixture)).isEqualTo(before);

      assertStatus(() -> client.read(foreignVersionRequest), Status.Code.FAILED_PRECONDITION);
    }

    assertThat(rowVersions(fixture)).isEqualTo(before);
    assertThat(rowVersions(otherGame)).isEqualTo(otherBefore);
  }

  @Test
  void sameReadIdentityObservesFreshCommittedLifecycleAndEpochSnapshot(@TempDir Path directory)
      throws Exception {
    Fixture fixture = fixture("fresh-current-snapshot");
    Map<String, String> before = rowVersions(fixture);
    AuthoredWorldVersionStateEvidence.Request sameRequest = request(fixture, UUID.randomUUID());

    AuthoredWorldVersionStateEvidence first;
    AuthoredWorldVersionStateEvidence current;
    try (AuthoredWorldVersionStateClient client = newClient(directory)) {
      client.init();
      first = client.read(sameRequest);
      assertThat(rowVersions(fixture)).isEqualTo(before);

      Version changed = fixture.version();
      changed.setVersionState(VersionLifecycleState.RETIRED);
      changed.setVersionStateEpoch(2L);
      new TransactionTemplate(transactionManager)
          .execute(status -> versionRepository.save(changed));
      Map<String, String> afterCommittedChange = rowVersions(fixture);
      assertThat(afterCommittedChange).containsEntry("game", before.get("game"));
      assertThat(afterCommittedChange).containsEntry("binding", before.get("binding"));
      assertThat(afterCommittedChange).containsEntry("source", before.get("source"));
      assertThat(afterCommittedChange.get("version")).isNotEqualTo(before.get("version"));

      current = client.read(sameRequest);
      assertThat(rowVersions(fixture)).isEqualTo(afterCommittedChange);
    }

    assertThat(first.request()).isEqualTo(sameRequest);
    assertThat(first.sourceEvidence()).isEqualTo(fixture.source());
    assertThat(first.canonicalVersionId()).isEqualTo(fixture.version().getCanonicalVersionId());
    assertThat(first.versionState())
        .isEqualTo(
            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                .VERSION_LIFECYCLE_STATE_DRAFT);
    assertThat(first.versionStateEpoch()).isEqualTo(1L);
    assertThat(current.request()).isEqualTo(sameRequest);
    assertThat(current.sourceEvidence()).isEqualTo(first.sourceEvidence());
    assertThat(current.canonicalVersionId()).isEqualTo(first.canonicalVersionId());
    assertThat(current.versionState())
        .isEqualTo(
            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                .VERSION_LIFECYCLE_STATE_RETIRED);
    assertThat(current.versionStateEpoch()).isEqualTo(2L);
    assertThat(current.evidenceDigest()).isNotEqualTo(first.evidenceDigest());
  }

  @Test
  void composesPersistedGameDesignVersionOverMtlsWithWorldIdentityReadbackAndExactRetry(
      @TempDir Path directory) throws Exception {
    Fixture fixture = fixture("world-version-identity-over-mtls");
    WorldFixture world = worldFixture();
    WorldAuthoredSourceIntakeReceipt intake = acceptWorldSourceIntake(world, fixture);
    Map<String, Long> contentBefore = worldContentAndLifecycleCounts(world.dsl());
    assertThat(contentBefore.values()).containsOnly(0L);
    assertThat(worldIdentityCount(world.dsl())).isZero();

    UUID canonicalVersionId = fixture.version().getCanonicalVersionId();
    long gameDesignVersionId = fixture.version().getId();
    UUID initialReadRequestId = UUID.randomUUID();
    AuthoredWorldVersionStateEvidence.Request initialRequest =
        request(fixture, initialReadRequestId);
    AuthoredWorldVersionStateEvidence expectedOriginalEvidence =
        AuthoredWorldVersionStateEvidence.create(
            initialRequest,
            fixture.source(),
            canonicalVersionId,
            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                .VERSION_LIFECYCLE_STATE_DRAFT,
            1L);

    try (AuthoredWorldVersionStateClient client = newClient(directory)) {
      client.init();
      WorldAuthoredVersionIdentityRepository identityRepository =
          new WorldAuthoredVersionIdentityRepository(world.dsl());
      WorldAuthoredVersionIdentityService identityService =
          new WorldAuthoredVersionIdentityService(
              client,
              identityRepository,
              world.sourceRepository(),
              world.transactionManager(),
              NAMESPACE);

      WorldAuthoredVersionIdentityReceipt first =
          associate(identityService, fixture, canonicalVersionId, initialReadRequestId);

      assertThat(producerReadCalls.get()).isEqualTo(1L);
      assertThat(first.sourceIntakeReceipt()).isEqualTo(intake);
      assertThat(first.sourceIntakeReceipt().source()).isEqualTo(fixture.source());
      assertThat(first.sourceIntakeReceipt().localTenantKey()).isPositive();
      assertThat(first.localVersionKey()).isPositive();
      assertThat(first.canonicalVersionId()).isEqualTo(canonicalVersionId);
      assertThat(first.gameDesignVersionId()).isEqualTo(gameDesignVersionId);
      assertThat(first.versionStateEvidence()).isEqualTo(expectedOriginalEvidence);
      assertThat(first.versionStateEvidence().request()).isEqualTo(initialRequest);
      assertThat(first.versionStateEvidence().sourceEvidence()).isEqualTo(fixture.source());
      assertThat(first.versionStateEvidence().versionStateEpoch()).isEqualTo(1L);

      Map<String, String> beforeAdvance = rowVersions(fixture);
      Version advanced = fixture.version();
      advanced.setVersionState(VersionLifecycleState.RETIRED);
      advanced.setVersionStateEpoch(2L);
      new TransactionTemplate(transactionManager)
          .execute(status -> versionRepository.save(advanced));
      Map<String, String> afterAdvance = rowVersions(fixture);
      assertThat(afterAdvance).containsEntry("game", beforeAdvance.get("game"));
      assertThat(afterAdvance).containsEntry("binding", beforeAdvance.get("binding"));
      assertThat(afterAdvance).containsEntry("source", beforeAdvance.get("source"));
      assertThat(afterAdvance.get("version")).isNotEqualTo(beforeAdvance.get("version"));
      var advancedVersionRow =
          Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT version_state, version_state_epoch FROM version WHERE id = ?",
                  gameDesignVersionId),
              "Advanced Game Design Version row is missing");
      assertThat(advancedVersionRow.get("version_state", String.class)).isEqualTo("RETIRED");
      assertThat(advancedVersionRow.get("version_state_epoch", Long.class)).isEqualTo(2L);

      WorldAuthoredVersionIdentityReceipt retry =
          associate(identityService, fixture, canonicalVersionId, UUID.randomUUID());
      assertThat(retry).isEqualTo(first);
      assertThat(retry.versionStateEvidence()).isEqualTo(expectedOriginalEvidence);
      assertThat(producerReadCalls.get()).isEqualTo(1L);
      assertThat(rowVersions(fixture)).isEqualTo(afterAdvance);

      assertThat(
              identityRepository.readByCanonicalVersion(
                  NAMESPACE,
                  fixture.canonicalTenantId(),
                  fixture.source().worldSlug(),
                  canonicalVersionId))
          .contains(first);
      assertThat(
              identityRepository.readByGameDesignVersion(
                  NAMESPACE,
                  fixture.canonicalTenantId(),
                  fixture.source().worldSlug(),
                  gameDesignVersionId))
          .contains(first);
      assertThat(world.sourceRepository().read(NAMESPACE, intake.intakeRequestId()))
          .contains(intake);
      assertThat(worldIdentityCount(world.dsl())).isEqualTo(1L);
      assertThat(worldContentAndLifecycleCounts(world.dsl()).values()).containsOnly(0L);
    }
  }

  @Test
  void rejectsSubstitutedCanonicalVersionBeforeWorldIdentityPersistence(@TempDir Path directory)
      throws Exception {
    Fixture fixture = fixture("world-version-identity-substituted-uuid");
    WorldFixture world = worldFixture();
    WorldAuthoredSourceIntakeReceipt intake = acceptWorldSourceIntake(world, fixture);
    Map<String, Long> contentBefore = worldContentAndLifecycleCounts(world.dsl());
    assertThat(contentBefore.values()).containsOnly(0L);

    UUID canonicalVersionId = fixture.version().getCanonicalVersionId();
    UUID substitutedCanonicalVersionId = UUID.randomUUID();
    assertThat(substitutedCanonicalVersionId).isNotEqualTo(canonicalVersionId);
    long gameDesignVersionId = fixture.version().getId();
    WorldAuthoredVersionIdentityRepository identityRepository =
        new WorldAuthoredVersionIdentityRepository(world.dsl());

    try (AuthoredWorldVersionStateClient client = newClient(directory)) {
      client.init();
      WorldAuthoredVersionIdentityService identityService =
          new WorldAuthoredVersionIdentityService(
              client,
              identityRepository,
              world.sourceRepository(),
              world.transactionManager(),
              NAMESPACE);

      assertThatThrownBy(
              () ->
                  associate(
                      identityService, fixture, substitutedCanonicalVersionId, UUID.randomUUID()))
          .isInstanceOf(
              WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException.class)
          .hasMessageContaining("expected UUID");

      assertThat(producerReadCalls.get()).isEqualTo(1L);
      assertThat(worldIdentityCount(world.dsl())).isZero();
      assertThat(world.sourceRepository().read(NAMESPACE, intake.intakeRequestId()))
          .contains(intake);
      assertThat(worldContentAndLifecycleCounts(world.dsl())).isEqualTo(contentBefore);

      WorldAuthoredVersionIdentityReceipt accepted =
          associate(identityService, fixture, canonicalVersionId, UUID.randomUUID());
      assertThat(accepted.canonicalVersionId()).isEqualTo(canonicalVersionId);
      assertThat(accepted.gameDesignVersionId()).isEqualTo(gameDesignVersionId);
      assertThat(accepted.sourceIntakeReceipt()).isEqualTo(intake);
      assertThat(accepted.versionStateEvidence().sourceEvidence()).isEqualTo(fixture.source());
      assertThat(producerReadCalls.get()).isEqualTo(2L);
      assertThat(worldIdentityCount(world.dsl())).isEqualTo(1L);
      assertThat(
              identityRepository.readByCanonicalVersion(
                  NAMESPACE,
                  fixture.canonicalTenantId(),
                  fixture.source().worldSlug(),
                  canonicalVersionId))
          .contains(accepted);
      assertThat(world.sourceRepository().read(NAMESPACE, intake.intakeRequestId()))
          .contains(intake);
      assertThat(worldContentAndLifecycleCounts(world.dsl()).values()).containsOnly(0L);
    }
  }

  private WorldFixture worldFixture() throws Exception {
    String schema = "world_identity_mtls_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(worldPostgres.getJdbcUrl());
    dataSource.setUsername(worldPostgres.getUsername());
    dataSource.setPassword(worldPostgres.getPassword());
    dataSource.setSchema(schema);

    Path repositoryRoot = repositoryRoot();
    Path worldMigrations =
        repositoryRoot.resolve("services/world-management-service/src/main/resources/db/migration");
    Path sagaMigrations =
        repositoryRoot.resolve("services/common-saga/src/main/resources/db/migration/saga");
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("filesystem:" + worldMigrations, "filesystem:" + sagaMigrations)
        .load()
        .migrate();

    DSLContext worldDsl =
        DSL.using(
            new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource)),
            SQLDialect.POSTGRES);
    PlatformTransactionManager worldTransactionManager =
        new DataSourceTransactionManager(dataSource);
    return new WorldFixture(
        worldDsl, new WorldAuthoredSourceIntakeRepository(worldDsl), worldTransactionManager);
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("settings.gradle.kts"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Unable to locate repository migration sources");
  }

  /**
   * Persists the exact Game Design owner receipt as test-fixture World intake under the same
   * authenticated caller context. This fixture injection is not automatic service delivery.
   */
  private WorldAuthoredSourceIntakeReceipt acceptWorldSourceIntake(
      WorldFixture world, Fixture gameDesignFixture) {
    UUID intakeRequestId = UUID.randomUUID();
    TransactionTemplate ownerTransaction = new TransactionTemplate(world.transactionManager());
    ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    WorldAuthoredSourceIntakeReceipt accepted =
        ownerTransaction.execute(
            status ->
                withGameDesign(
                    () ->
                        world
                            .sourceRepository()
                            .acceptFresh(NAMESPACE, intakeRequestId, gameDesignFixture.source())));
    assertThat(accepted).isNotNull();
    WorldAuthoredSourceIntakeReceipt independentReadback =
        withGameDesign(
            () ->
                world
                    .sourceRepository()
                    .read(NAMESPACE, intakeRequestId)
                    .orElseThrow(
                        () -> new IllegalStateException("World source intake is missing")));
    assertThat(independentReadback).isEqualTo(accepted);
    return independentReadback;
  }

  private static WorldAuthoredVersionIdentityReceipt associate(
      WorldAuthoredVersionIdentityService service,
      Fixture fixture,
      UUID expectedCanonicalVersionId,
      UUID readRequestId) {
    return withGameDesign(
        () ->
            service.associate(
                NAMESPACE,
                fixture.canonicalTenantId(),
                fixture.source().worldSlug(),
                fixture.source().operationId(),
                fixture.source().evidenceDigest(),
                expectedCanonicalVersionId,
                fixture.version().getId(),
                readRequestId));
  }

  private static <T> T withGameDesign(Supplier<T> action) {
    GrpcPeerIdentity peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-design-service",
            NAMESPACE,
            "game-design-service");
    Context authenticated = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = authenticated.attach();
    try {
      return action.get();
    } finally {
      authenticated.detach(previous);
    }
  }

  private static long worldIdentityCount(DSLContext worldDsl) {
    return Objects.requireNonNull(
            worldDsl.fetchOne("SELECT COUNT(*) FROM world_authored_version_identity"))
        .get(0, Long.class);
  }

  private static Map<String, Long> worldContentAndLifecycleCounts(DSLContext worldDsl) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String table : WORLD_CONTENT_AND_LIFECYCLE_TABLES) {
      counts.put(
          table,
          Objects.requireNonNull(worldDsl.fetchOne("SELECT COUNT(*) FROM " + table))
              .get(0, Long.class));
    }
    return Map.copyOf(counts);
  }

  private AuthoredWorldVersionStateClient newClient(Path directory) throws Exception {
    Files.createDirectories(directory);
    Path caCertificate =
        writePem(directory.resolve("test-ca.crt"), "CERTIFICATE", pki.caCertificate().getEncoded());
    Path clientCertificate =
        writePem(
            directory.resolve("world-client.crt"),
            "CERTIFICATE",
            pki.worldManagementCertificate().certificate().getEncoded());
    Path clientPrivateKey =
        writePem(
            directory.resolve("world-client.key"),
            "PRIVATE KEY",
            pki.worldManagementCertificate().privateKey().getEncoded());

    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(clientCertificate.toString());
    tls.setPrivateKey(clientPrivateKey.toString());
    tls.setCaCert(caCertificate.toString());
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("localhost:" + server.getPort());
    return new AuthoredWorldVersionStateClient(endpoints, tls, new GrpcChannelFactory(), NAMESPACE);
  }

  private Fixture fixture(String label) {
    String privateTenantKey = "version-state-" + UUID.randomUUID().toString().substring(0, 8);
    Game game = new Game();
    game.setTenantId(privateTenantKey);
    game.setName("Synthetic " + label);
    game.setDescription("Synthetic persisted source/version socket proof fixture");
    Game persistedGame =
        new TransactionTemplate(transactionManager).execute(status -> gameRepository.save(game));
    assertThat(persistedGame).isNotNull();

    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    AuthoredWorldSourceEvidence source =
        new TransactionTemplate(transactionManager)
            .execute(
                status ->
                    sourceRepository.register(
                        NAMESPACE,
                        UUID.randomUUID(),
                        persistedGame.getCanonicalTenantId(),
                        "tenant-" + suffix,
                        "world-" + suffix,
                        "Synthetic World 🐉"));
    assertThat(source).isNotNull();

    Version version = new Version();
    version.setTenantId(privateTenantKey);
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    version.setNotes("synthetic source-qualified version-state socket proof fixture");
    Version persistedVersion =
        new TransactionTemplate(transactionManager)
            .execute(status -> versionRepository.save(version));
    assertThat(persistedVersion).isNotNull();
    return new Fixture(
        persistedGame.getCanonicalTenantId(), privateTenantKey, source, persistedVersion);
  }

  private AuthoredWorldVersionStateEvidence.Request request(Fixture fixture, UUID readRequestId) {
    return new AuthoredWorldVersionStateEvidence.Request(
        1,
        NAMESPACE,
        readRequestId,
        fixture.canonicalTenantId(),
        fixture.source().worldSlug(),
        fixture.source().operationId(),
        fixture.source().evidenceDigest(),
        fixture.version().getId());
  }

  private Map<String, String> rowVersions(Fixture fixture) {
    return Map.of(
        "game",
        xmin(
            "SELECT xmin::text AS xmin FROM game WHERE canonical_tenant_id = ?",
            fixture.canonicalTenantId()),
        "binding",
        xmin(
            "SELECT xmin::text AS xmin FROM game_design_tenant_slug_binding "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
            NAMESPACE,
            fixture.canonicalTenantId()),
        "source",
        xmin(
            "SELECT xmin::text AS xmin FROM game_design_authored_world_source_operations "
                + "WHERE operation_id = ?",
            fixture.source().operationId()),
        "version",
        xmin("SELECT xmin::text AS xmin FROM version WHERE id = ?", fixture.version().getId()));
  }

  private String xmin(String sql, Object... arguments) {
    var rows = dsl.fetch(sql, arguments);
    assertThat(rows).hasSize(1);
    return rows.getFirst().get("xmin", String.class);
  }

  private static void assertStatus(Runnable call, Status.Code expectedCode) {
    Throwable failure = Assertions.catchThrowable(call::run);
    assertThat(failure).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expectedCode);
  }

  private static Path writePem(Path path, String label, byte[] encoded) throws IOException {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
    return Files.writeString(
        path,
        "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n",
        StandardCharsets.US_ASCII);
  }

  private static TestCertificate issueLeaf(
      X500Name caName,
      PrivateKey caPrivateKey,
      String commonName,
      String workloadUri,
      boolean serverCertificate)
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
            subject,
            keyPair.getPublic(),
            caName,
            caPrivateKey,
            false,
            subjectAltNames,
            serverCertificate);
    return new TestCertificate(keyPair.getPrivate(), certificate);
  }

  private static X509Certificate issueCertificate(
      X500Name subject,
      java.security.PublicKey publicKey,
      X500Name issuer,
      PrivateKey issuerPrivateKey,
      boolean ca,
      GeneralNames subjectAltNames,
      boolean serverCertificate)
      throws Exception {
    Instant now = Instant.now().minusSeconds(60L);
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
              serverCertificate ? KeyPurposeId.id_kp_serverAuth : KeyPurposeId.id_kp_clientAuth));
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

  private record Fixture(
      UUID canonicalTenantId,
      String privateTenantKey,
      AuthoredWorldSourceEvidence source,
      Version version) {}

  private record WorldFixture(
      DSLContext dsl,
      WorldAuthoredSourceIntakeRepository sourceRepository,
      PlatformTransactionManager transactionManager) {}

  private record TestCertificate(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestCertificate gameDesignServerCertificate,
      TestCertificate worldManagementCertificate) {}
}
