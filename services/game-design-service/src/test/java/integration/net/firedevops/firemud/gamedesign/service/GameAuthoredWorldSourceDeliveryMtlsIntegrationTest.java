package integration.net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeClient;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceDeliveryRepository;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceDeliveryRepository.DeliveryClaim;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.service.impl.GameAuthoredWorldSourceDeliveryService;
import net.firedevops.firemud.gamedesign.service.impl.TenantIdentityGrpcService;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeService;
import net.firedevops.firemud.worldmanagement.v1.IntakeAuthoredWorldSourceResponse;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL-to-physical-mTLS proof of durable fresh Game Design source delivery to World.
 *
 * <p>The Game Design source and delivery repositories, Game Design source handler, World source
 * client, World intake service/repository/handler, delivery service, and both transaction managers
 * are real. Ephemeral file-backed workload certificates protect both physical service directions.
 * The Game row is a synthetic persisted source fixture; this does not exercise a public creator,
 * content materialization, release publication, lifecycle wiring, or runtime activation.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = GameDesignServiceApplication.class,
    properties = {
      "spring.profiles.active=test",
      "firemud.auth.jwt-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "firemud.grpc.plaintext=true",
      "firemud.grpc.workload-namespace=authored-source-delivery-mtls-test",
      "spring.grpc.server.port=0",
      "asset.store.endpoint=http://localhost:9000",
      "asset.store.bucket=test-bucket",
      "asset.store.region=us-east-1",
      "asset.store.access-key=test-access-key",
      "asset.store.secret-key=test-secret-key"
    })
@Import(NoGrpcServerTestConfiguration.class)
class GameAuthoredWorldSourceDeliveryMtlsIntegrationTest {
  private static final String NAMESPACE = "authored-source-delivery-mtls-test";
  private static final AtomicLong CERTIFICATE_SERIAL = new AtomicLong(1L);
  private static TestPki pki;

  @Container
  static PostgreSQLContainer<?> gameDesignPostgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static PostgreSQLContainer<?> worldPostgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @TempDir Path temporaryDirectory;

  @Autowired private GameRepository gameRepository;
  @Autowired private GameAuthoredWorldSourceRepository sourceRepository;
  @Autowired private TenantIdentityGrpcService sourceHandler;
  @Autowired private PlatformTransactionManager gameDesignTransactionManager;
  @Autowired private DSLContext gameDesignDsl;

  private final List<Server> servers = new ArrayList<>();
  private final List<AuthoredWorldSourceClient> sourceClients = new ArrayList<>();
  private final List<WorldAuthoredSourceIntakeClient> intakeClients = new ArrayList<>();

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, gameDesignPostgres, "game_design_service");
  }

  @BeforeAll
  static void createEphemeralWorkloadCertificates() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPair caKeyPair = newRsaKeyPair();
    X500Name caName =
        new X500Name("CN=Authored Source Delivery Integration Test CA, O=FireMUD Test");
    X509Certificate caCertificate =
        issueCertificate(caName, caKeyPair.getPublic(), caName, caKeyPair.getPrivate(), true, null);
    pki =
        new TestPki(
            caCertificate,
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "game-design-server",
                workloadUri("game-design-service"),
                true),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "world-management-server",
                workloadUri("world-management-service"),
                true),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "game-design-service",
                workloadUri("game-design-service"),
                false),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "world-management-service",
                workloadUri("world-management-service"),
                false),
            issueLeaf(
                caName,
                caKeyPair.getPrivate(),
                "account-service",
                workloadUri("account-service"),
                false));
  }

  @AfterEach
  void closePhysicalClientsAndServers() throws Exception {
    for (WorldAuthoredSourceIntakeClient client : intakeClients) {
      client.close();
    }
    intakeClients.clear();
    for (AuthoredWorldSourceClient client : sourceClients) {
      client.close();
    }
    sourceClients.clear();
    for (Server server : servers) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
    servers.clear();
  }

  @Test
  void deliversPersistedFreshSourceAndCompletedRestartRetryReturnsOriginalReceipt()
      throws Exception {
    Composition composition = compose(pki.gameDesignClient(), new AtomicBoolean(false));
    AuthoredWorldSourceEvidence source = composition.fixture().source();
    DeliveryClaim originalClaim = pendingClaim(composition, source);

    AuthoredWorldSourceEvidence exactRegistrationRetry = registerAgain(composition.fixture());
    DeliveryClaim claimAfterRegistrationRetry =
        composition.deliveryRepository().read(source.operationId()).orElseThrow();
    assertThat(exactRegistrationRetry).isEqualTo(source);
    assertThat(claimAfterRegistrationRetry).isEqualTo(originalClaim);
    assertThat(claimAfterRegistrationRetry.request().intakeRequestId())
        .isNotEqualTo(source.operationId());
    assertThat(worldCounts(composition.world(), source)).isEqualTo(new WorldCounts(0, 0, 0));

    DeliveryClaim delivered = composition.deliveryService().deliver(source.operationId());
    DeliveryClaim durableClaim =
        composition.deliveryRepository().read(source.operationId()).orElseThrow();
    WorldAuthoredSourceIntakeReceipt worldReceipt =
        composition
            .world()
            .sourceRepository()
            .read(NAMESPACE, originalClaim.request().intakeRequestId())
            .orElseThrow();

    assertThat(source.provenanceKind()).isEqualTo("NEW_GAME_ROW");
    assertThat(delivered).isEqualTo(durableClaim);
    assertThat(durableClaim.source()).isEqualTo(source);
    assertThat(durableClaim.request()).isEqualTo(originalClaim.request());
    assertThat(durableClaim.acknowledgedReceipt()).contains(publicReceipt(worldReceipt));
    assertThat(worldReceipt.source()).isEqualTo(source);
    assertThat(worldReceipt.canonicalTenantId()).isEqualTo(source.canonicalTenantId());
    assertThat(worldReceipt.worldSlug()).isEqualTo(source.worldSlug());
    assertThat(worldReceipt.intakeRequestId()).isEqualTo(originalClaim.request().intakeRequestId());
    assertThat(worldCounts(composition.world(), source)).isEqualTo(new WorldCounts(1, 1, 1));
    assertThat(composition.worldIntakeCalls().get()).isEqualTo(1);
    assertThat(composition.worldReadCalls().get()).isEqualTo(1);
    assertThat(composition.gameDesignSourceReadCalls().get()).isEqualTo(1);

    WorldAuthoredSourceIntakeClient restartedClient =
        newIntakeClient(composition.worldServer(), pki.gameDesignClient(), "completed-restart");
    GameAuthoredWorldSourceDeliveryService restartedService =
        new GameAuthoredWorldSourceDeliveryService(
            composition.deliveryRepository(),
            restartedClient,
            gameDesignTransactionManager,
            NAMESPACE);
    assertThat(restartedService.deliver(source.operationId())).isEqualTo(durableClaim);
    assertThat(composition.worldIntakeCalls().get()).isEqualTo(1);
    assertThat(composition.worldReadCalls().get()).isEqualTo(1);
    assertThat(composition.gameDesignSourceReadCalls().get()).isEqualTo(1);
    assertThat(worldCounts(composition.world(), source)).isEqualTo(new WorldCounts(1, 1, 1));
  }

  @Test
  void lostWorldResponseLeavesPendingIntentAndExactRetryRecoversCommittedOwnerReceipt()
      throws Exception {
    Composition composition = compose(pki.gameDesignClient(), new AtomicBoolean(true));
    AuthoredWorldSourceEvidence source = composition.fixture().source();
    DeliveryClaim originalClaim = pendingClaim(composition, source);

    assertThatThrownBy(() -> composition.deliveryService().deliver(source.operationId()))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.UNAVAILABLE));

    DeliveryClaim afterLostResponse =
        composition.deliveryRepository().read(source.operationId()).orElseThrow();
    WorldAuthoredSourceIntakeReceipt committedWorldReceipt =
        composition
            .world()
            .sourceRepository()
            .read(NAMESPACE, originalClaim.request().intakeRequestId())
            .orElseThrow();
    assertThat(afterLostResponse.source()).isEqualTo(source);
    assertThat(afterLostResponse.request()).isEqualTo(originalClaim.request());
    assertThat(afterLostResponse.acknowledgedReceipt()).isEmpty();
    assertThat(committedWorldReceipt.source()).isEqualTo(source);
    assertThat(committedWorldReceipt.intakeRequestId())
        .isEqualTo(originalClaim.request().intakeRequestId());
    assertThat(worldCounts(composition.world(), source)).isEqualTo(new WorldCounts(1, 1, 1));
    assertThat(composition.worldIntakeCalls().get()).isEqualTo(1);
    assertThat(composition.worldReadCalls().get()).isZero();
    assertThat(composition.gameDesignSourceReadCalls().get()).isEqualTo(1);

    assertThat(registerAgain(composition.fixture())).isEqualTo(source);
    DeliveryClaim afterExactRegistrationRetry =
        composition.deliveryRepository().read(source.operationId()).orElseThrow();
    assertThat(afterExactRegistrationRetry.request()).isEqualTo(originalClaim.request());
    assertThat(afterExactRegistrationRetry.acknowledgedReceipt()).isEmpty();

    WorldAuthoredSourceIntakeClient restartedClient =
        newIntakeClient(composition.worldServer(), pki.gameDesignClient(), "lost-response-retry");
    GameAuthoredWorldSourceDeliveryService restartedService =
        new GameAuthoredWorldSourceDeliveryService(
            composition.deliveryRepository(),
            restartedClient,
            gameDesignTransactionManager,
            NAMESPACE);
    DeliveryClaim recovered = restartedService.deliver(source.operationId());
    WorldAuthoredSourceIntakeReceipt durableWorldReceipt =
        composition
            .world()
            .sourceRepository()
            .read(NAMESPACE, originalClaim.request().intakeRequestId())
            .orElseThrow();

    assertThat(recovered.request()).isEqualTo(originalClaim.request());
    assertThat(recovered.acknowledgedReceipt()).contains(publicReceipt(committedWorldReceipt));
    assertThat(durableWorldReceipt).isEqualTo(committedWorldReceipt);
    assertThat(worldCounts(composition.world(), source)).isEqualTo(new WorldCounts(1, 1, 1));
    assertThat(composition.worldIntakeCalls().get()).isEqualTo(2);
    assertThat(composition.worldReadCalls().get()).isEqualTo(1);
    assertThat(composition.gameDesignSourceReadCalls().get()).isEqualTo(1);
  }

  @Test
  void wrongAuthenticatedWorldCallerCannotCreateOwnerRowsOrAcknowledgeDelivery() throws Exception {
    Composition composition = compose(pki.accountClient(), new AtomicBoolean(false));
    AuthoredWorldSourceEvidence source = composition.fixture().source();
    DeliveryClaim originalClaim = pendingClaim(composition, source);

    assertThatThrownBy(() -> composition.deliveryService().deliver(source.operationId()))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.PERMISSION_DENIED));

    DeliveryClaim afterDeniedCaller =
        composition.deliveryRepository().read(source.operationId()).orElseThrow();
    assertThat(afterDeniedCaller.source()).isEqualTo(source);
    assertThat(afterDeniedCaller.request()).isEqualTo(originalClaim.request());
    assertThat(afterDeniedCaller.acknowledgedReceipt()).isEmpty();
    assertThat(worldCounts(composition.world(), source)).isEqualTo(new WorldCounts(0, 0, 0));
    assertThat(composition.worldIntakeCalls().get()).isEqualTo(1);
    assertThat(composition.worldReadCalls().get()).isZero();
    assertThat(composition.gameDesignSourceReadCalls().get()).isZero();
  }

  private Composition compose(TestCertificate deliveryCaller, AtomicBoolean dropFirstIntakeReply)
      throws Exception {
    AuthoredSourceFixture fixture = createPersistedSource();
    WorldFixture world = worldFixture();
    AtomicInteger gameDesignSourceReadCalls = new AtomicInteger();
    Server gameDesignServer = startGameDesignSourceServer(gameDesignSourceReadCalls);

    AuthoredWorldSourceClient worldToGameDesignClient =
        new AuthoredWorldSourceClient(
            gameDesignEndpoints(gameDesignServer),
            tlsProperties(pki.worldManagementClient(), "world-to-game-design"),
            new GrpcChannelFactory(),
            NAMESPACE);
    worldToGameDesignClient.init();
    sourceClients.add(worldToGameDesignClient);

    AtomicInteger worldIntakeCalls = new AtomicInteger();
    AtomicInteger worldReadCalls = new AtomicInteger();
    WorldAuthoredSourceIntakeService worldOwner =
        new WorldAuthoredSourceIntakeService(
            worldToGameDesignClient,
            world.sourceRepository(),
            world.transactionManager(),
            NAMESPACE);
    WorldAuthoredSourceIntakeGrpcService worldHandler =
        new WorldAuthoredSourceIntakeGrpcService(worldOwner, NAMESPACE);
    Server worldServer =
        startWorldServer(worldHandler, worldIntakeCalls, worldReadCalls, dropFirstIntakeReply);
    WorldAuthoredSourceIntakeClient gameDesignToWorldClient =
        newIntakeClient(worldServer, deliveryCaller, "game-design-to-world");
    GameAuthoredWorldSourceDeliveryRepository deliveryRepository =
        new GameAuthoredWorldSourceDeliveryRepository(gameDesignDsl);
    GameAuthoredWorldSourceDeliveryService deliveryService =
        new GameAuthoredWorldSourceDeliveryService(
            deliveryRepository, gameDesignToWorldClient, gameDesignTransactionManager, NAMESPACE);
    return new Composition(
        fixture,
        world,
        worldServer,
        deliveryRepository,
        deliveryService,
        gameDesignSourceReadCalls,
        worldIntakeCalls,
        worldReadCalls);
  }

  private Server startGameDesignSourceServer(AtomicInteger sourceReadCalls) throws Exception {
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(serverTlsContext(pki.gameDesignServer()))
            .addService(
                ServerInterceptors.intercept(
                    sourceHandler,
                    new GrpcPeerIdentityInterceptor(),
                    countingMethod("ResolveAuthoredWorldSource", sourceReadCalls, null, null)))
            .build()
            .start();
    servers.add(server);
    return server;
  }

  private Server startWorldServer(
      WorldAuthoredSourceIntakeGrpcService handler,
      AtomicInteger intakeCalls,
      AtomicInteger readCalls,
      AtomicBoolean dropFirstIntakeReply)
      throws Exception {
    var authenticated = ServerInterceptors.intercept(handler, new GrpcPeerIdentityInterceptor());
    var observed =
        ServerInterceptors.intercept(
            authenticated,
            countingMethod(
                "IntakeAuthoredWorldSource", intakeCalls, readCalls, dropFirstIntakeReply));
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(serverTlsContext(pki.worldManagementServer()))
            .addService(observed)
            .build()
            .start();
    servers.add(server);
    return server;
  }

  private static ServerInterceptor countingMethod(
      String intakeMethod,
      AtomicInteger intakeCalls,
      AtomicInteger readCalls,
      AtomicBoolean dropFirstIntakeReply) {
    return new ServerInterceptor() {
      @Override
      public <RequestT, ResponseT> ServerCall.Listener<RequestT> interceptCall(
          ServerCall<RequestT, ResponseT> call,
          Metadata headers,
          ServerCallHandler<RequestT, ResponseT> next) {
        String method = call.getMethodDescriptor().getBareMethodName();
        if (intakeCalls != null && method.equals(intakeMethod)) {
          intakeCalls.incrementAndGet();
        } else if (readCalls != null && method.equals("ReadAuthoredWorldSourceIntake")) {
          readCalls.incrementAndGet();
        }
        ServerCall<RequestT, ResponseT> observedCall =
            new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
              private boolean droppedCommittedIntakeReply;

              @Override
              public void sendMessage(ResponseT message) {
                if (message instanceof IntakeAuthoredWorldSourceResponse
                    && dropFirstIntakeReply != null
                    && dropFirstIntakeReply.compareAndSet(true, false)) {
                  droppedCommittedIntakeReply = true;
                  return;
                }
                super.sendMessage(message);
              }

              @Override
              public void close(Status status, Metadata trailers) {
                if (droppedCommittedIntakeReply && status.isOk()) {
                  super.close(
                      Status.UNAVAILABLE.withDescription(
                          "Test deliberately lost a committed World intake acknowledgement"),
                      trailers);
                  return;
                }
                super.close(status, trailers);
              }
            };
        return next.startCall(observedCall, headers);
      }
    };
  }

  private WorldAuthoredSourceIntakeClient newIntakeClient(
      Server server, TestCertificate certificate, String label) throws Exception {
    WorldAuthoredSourceIntakeClient client =
        new WorldAuthoredSourceIntakeClient(
            worldEndpoints(server),
            tlsProperties(certificate, label),
            new GrpcChannelFactory(),
            NAMESPACE);
    client.init();
    intakeClients.add(client);
    return client;
  }

  private AuthoredSourceFixture createPersistedSource() {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String privateTenantKey = "delivery-source-" + suffix;
    Game game = new Game();
    game.setTenantId(privateTenantKey);
    game.setName("Synthetic authored source delivery " + suffix);
    game.setDescription("Persisted source fixture for PostgreSQL and workload-mTLS composition");
    Game persistedGame =
        new TransactionTemplate(gameDesignTransactionManager)
            .execute(status -> gameRepository.save(game));
    assertThat(persistedGame).isNotNull();

    UUID registrationRequestId = UUID.randomUUID();
    String tenantSlug = "tenant-" + suffix;
    String worldSlug = "world-" + suffix;
    String worldDisplayName = "Delivery World " + suffix;
    AuthoredWorldSourceEvidence source =
        new TransactionTemplate(gameDesignTransactionManager)
            .execute(
                status ->
                    sourceRepository.register(
                        NAMESPACE,
                        registrationRequestId,
                        persistedGame.getCanonicalTenantId(),
                        tenantSlug,
                        worldSlug,
                        worldDisplayName));
    assertThat(source).isNotNull();
    assertThat(source.provenanceKind()).isEqualTo("NEW_GAME_ROW");
    return new AuthoredSourceFixture(
        persistedGame.getCanonicalTenantId(),
        registrationRequestId,
        tenantSlug,
        worldSlug,
        worldDisplayName,
        source);
  }

  private AuthoredWorldSourceEvidence registerAgain(AuthoredSourceFixture fixture) {
    AuthoredWorldSourceEvidence source = fixture.source();
    AuthoredWorldSourceEvidence retry =
        new TransactionTemplate(gameDesignTransactionManager)
            .execute(
                status ->
                    sourceRepository.register(
                        NAMESPACE,
                        fixture.registrationRequestId(),
                        fixture.canonicalTenantId(),
                        fixture.tenantSlug(),
                        fixture.worldSlug(),
                        fixture.worldDisplayName()));
    assertThat(retry).isEqualTo(source);
    return retry;
  }

  private DeliveryClaim pendingClaim(Composition composition, AuthoredWorldSourceEvidence source) {
    DeliveryClaim claim = composition.deliveryRepository().read(source.operationId()).orElseThrow();
    assertThat(claim.source()).isEqualTo(source);
    assertThat(claim.acknowledgedReceipt()).isEmpty();
    assertThat(claim.request().targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(claim.request().canonicalTenantId()).isEqualTo(source.canonicalTenantId());
    assertThat(claim.request().worldSlug()).isEqualTo(source.worldSlug());
    assertThat(claim.request().sourceOperationId()).isEqualTo(source.operationId());
    assertThat(claim.request().expectedSourceEvidenceDigest()).isEqualTo(source.evidenceDigest());
    return claim;
  }

  private WorldFixture worldFixture() throws Exception {
    String schema = "world_source_delivery_" + UUID.randomUUID().toString().replace("-", "");
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

  private static WorldCounts worldCounts(WorldFixture world, AuthoredWorldSourceEvidence source) {
    return new WorldCounts(
        count(
            world.dsl(),
            "SELECT COUNT(*) FROM world_authored_source_intake "
                + "WHERE target_namespace = ? AND source_operation_id = ?",
            NAMESPACE,
            source.operationId()),
        count(
            world.dsl(),
            "SELECT COUNT(*) FROM world_authored_source_tenant_association "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
            NAMESPACE,
            source.canonicalTenantId()),
        count(
            world.dsl(),
            "SELECT COUNT(*) FROM world_authored_source_tenant_key_reservation "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ? "
                + "AND claim_kind = 'CANONICAL_AUTHORED_SOURCE'",
            NAMESPACE,
            source.canonicalTenantId()));
  }

  private static long count(DSLContext dsl, String query, Object... bindings) {
    return Objects.requireNonNull(dsl.fetchOne(query, bindings), "Count query returned no row")
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

  private ServiceEndpointsProperties gameDesignEndpoints(Server server) {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("127.0.0.1:" + server.getPort());
    return endpoints;
  }

  private ServiceEndpointsProperties worldEndpoints(Server server) {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("127.0.0.1:" + server.getPort());
    return endpoints;
  }

  private CommonGrpcClientProperties tlsProperties(TestCertificate certificate, String label)
      throws Exception {
    Path clientCertificate = temporaryDirectory.resolve(label + "-client.crt");
    Path clientPrivateKey = temporaryDirectory.resolve(label + "-client.key");
    Path caCertificate = temporaryDirectory.resolve(label + "-ca.crt");
    Files.writeString(
        clientCertificate, pem("CERTIFICATE", certificate.certificate().getEncoded()));
    Files.writeString(clientPrivateKey, pem("PRIVATE KEY", certificate.privateKey().getEncoded()));
    Files.writeString(caCertificate, pem("CERTIFICATE", pki.caCertificate().getEncoded()));

    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCertChain(clientCertificate.toString());
    tls.setPrivateKey(clientPrivateKey.toString());
    tls.setCaCert(caCertificate.toString());
    return tls;
  }

  private static io.grpc.netty.shaded.io.netty.handler.ssl.SslContext serverTlsContext(
      TestCertificate certificate) throws Exception {
    return GrpcSslContexts.configure(
            SslContextBuilder.forServer(certificate.privateKey(), certificate.certificate()))
        .trustManager(pki.caCertificate())
        .clientAuth(ClientAuth.REQUIRE)
        .build();
  }

  private static String workloadUri(String service) {
    return "spiffe://firemud/ns/" + NAMESPACE + "/sa/" + service;
  }

  private static String pem(String type, byte[] encoded) {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
    return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
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
    return new TestCertificate(
        keyPair.getPrivate(),
        issueCertificate(
            subject,
            keyPair.getPublic(),
            caName,
            caPrivateKey,
            false,
            subjectAltNames,
            serverCertificate));
  }

  private static X509Certificate issueCertificate(
      X500Name subject,
      java.security.PublicKey publicKey,
      X500Name issuer,
      PrivateKey issuerPrivateKey,
      boolean ca,
      GeneralNames subjectAltNames)
      throws Exception {
    return issueCertificate(
        subject, publicKey, issuer, issuerPrivateKey, ca, subjectAltNames, false);
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

  private record AuthoredSourceFixture(
      UUID canonicalTenantId,
      UUID registrationRequestId,
      String tenantSlug,
      String worldSlug,
      String worldDisplayName,
      AuthoredWorldSourceEvidence source) {}

  private record WorldFixture(
      DSLContext dsl,
      WorldAuthoredSourceIntakeRepository sourceRepository,
      PlatformTransactionManager transactionManager) {}

  private record Composition(
      AuthoredSourceFixture fixture,
      WorldFixture world,
      Server worldServer,
      GameAuthoredWorldSourceDeliveryRepository deliveryRepository,
      GameAuthoredWorldSourceDeliveryService deliveryService,
      AtomicInteger gameDesignSourceReadCalls,
      AtomicInteger worldIntakeCalls,
      AtomicInteger worldReadCalls) {}

  private record WorldCounts(long intakes, long tenantAssociations, long reservations) {}

  private record TestCertificate(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestCertificate gameDesignServer,
      TestCertificate worldManagementServer,
      TestCertificate gameDesignClient,
      TestCertificate worldManagementClient,
      TestCertificate accountClient) {}
}
