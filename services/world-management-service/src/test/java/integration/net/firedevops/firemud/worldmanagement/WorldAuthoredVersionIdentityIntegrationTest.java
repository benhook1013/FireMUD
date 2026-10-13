package net.firedevops.firemud.worldmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository.RegistrationConflictException;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class WorldAuthoredVersionIdentityIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final UUID OTHER_CANONICAL_VERSION =
      UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
  private static final List<String> WORLD_CONTENT_AND_LIFECYCLE_TABLES =
      List.of(
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
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "world_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private DSLContext dsl;
  @Autowired private WorldAuthoredSourceIntakeRepository sourceRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void persistsExactIdentityAndPrivateKeyWithoutContentOrLifecycleWrites() {
    long legacyTenantKey =
        6_900_000_000L + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000L);
    String legacyRegionName = "unmapped-legacy-region-" + UUID.randomUUID();
    long legacyRegionId =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "INSERT INTO region (tenant_id, name) VALUES (?, ?) RETURNING id",
                    legacyTenantKey,
                    legacyRegionName),
                "legacy region insert returned no row")
            .get(0, Long.class);
    long legacyGameInstanceId = positiveLong();
    String legacyControlRequest = "retained-legacy-" + UUID.randomUUID();
    long legacyWorldInstanceId =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "INSERT INTO world_instance (tenant_id, game_instance_id, game_template_id, "
                        + "control_plane_request_id, launch_descriptor_id, version_id, "
                        + "generation_config_revision, release_bundle_id, "
                        + "published_release_bundle_ref, version_state_epoch, status) "
                        + "VALUES (?, ?, 17, ?, 'retained-descriptor', 29, 'legacy-config', "
                        + "31, 'retained-release', 3, 'ACTIVE') RETURNING id",
                    legacyTenantKey,
                    legacyGameInstanceId,
                    legacyControlRequest),
                "legacy world-instance insert returned no row")
            .get(0, Long.class);

    AuthoredWorldSourceEvidence source = source(UUID.randomUUID(), randomWorld());
    WorldAuthoredSourceIntakeReceipt sourceReceipt = acceptSource(source);
    Map<String, Long> rowsBeforeIdentity = worldContentAndLifecycleCounts();
    assertThat(sourceReceipt.localTenantKey()).isNotEqualTo(legacyTenantKey);

    UUID canonicalVersionId = UUID.randomUUID();
    long gameDesignVersionId = 103L;
    AuthoredWorldVersionStateClient client = mock(AuthoredWorldVersionStateClient.class);
    when(client.read(any()))
        .thenAnswer(
            invocation -> {
              AuthoredWorldVersionStateEvidence.Request request = invocation.getArgument(0);
              return versionEvidence(
                  request,
                  source,
                  canonicalVersionId,
                  VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
                  5L);
            });
    WorldAuthoredVersionIdentityRepository repository =
        new WorldAuthoredVersionIdentityRepository(dsl);
    WorldAuthoredVersionIdentityService service = service(client, repository, transactionManager);
    UUID readRequestId = UUID.randomUUID();

    WorldAuthoredVersionIdentityReceipt receipt =
        withGameDesign(
            () ->
                service.associate(
                    NAMESPACE,
                    source.canonicalTenantId(),
                    source.worldSlug(),
                    source.operationId(),
                    source.evidenceDigest(),
                    canonicalVersionId,
                    gameDesignVersionId,
                    readRequestId));
    WorldAuthoredVersionIdentityReceipt independentReadback =
        repository
            .readByCanonicalVersion(
                NAMESPACE, source.canonicalTenantId(), source.worldSlug(), canonicalVersionId)
            .orElseThrow();

    assertThat(receipt).isEqualTo(independentReadback);
    assertThat(receipt.sourceIntakeReceipt()).isEqualTo(sourceReceipt);
    assertThat(receipt.canonicalVersionId()).isEqualTo(canonicalVersionId);
    assertThat(receipt.gameDesignVersionId()).isEqualTo(gameDesignVersionId);
    assertThat(receipt.localVersionKey()).isPositive();
    assertThat(receipt.versionStateEvidence().request().readRequestId()).isEqualTo(readRequestId);
    assertThat(
            repository.readByGameDesignVersion(
                NAMESPACE, source.canonicalTenantId(), source.worldSlug(), gameDesignVersionId))
        .contains(receipt);
    assertThat(identityCount(source)).isEqualTo(1L);
    assertThat(worldContentAndLifecycleCounts()).isEqualTo(rowsBeforeIdentity);

    var storedRow =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT * FROM world_authored_version_identity WHERE operation_id = ?",
                receipt.operationId()),
            "World authored-Version identity row was not persisted");
    assertThat(storedRow.get("operation_id", UUID.class)).isEqualTo(receipt.operationId());
    assertThat(storedRow.get("target_namespace", String.class)).isEqualTo(NAMESPACE);
    assertThat(storedRow.get("canonical_tenant_id", UUID.class))
        .isEqualTo(source.canonicalTenantId());
    assertThat(storedRow.get("world_slug", String.class)).isEqualTo(source.worldSlug());
    assertThat(storedRow.get("canonical_version_id", UUID.class)).isEqualTo(canonicalVersionId);
    assertThat(storedRow.get("game_design_version_id", Long.class)).isEqualTo(gameDesignVersionId);
    assertThat(storedRow.get("local_version_key", Long.class)).isEqualTo(receipt.localVersionKey());
    assertThat(storedRow.get("intake_operation_id", UUID.class))
        .isEqualTo(sourceReceipt.operationId());
    assertThat(storedRow.get("intake_request_id", UUID.class))
        .isEqualTo(sourceReceipt.intakeRequestId());
    assertThat(storedRow.get("local_tenant_key", Long.class))
        .isEqualTo(sourceReceipt.localTenantKey());
    assertThat(storedRow.get("intake_request_digest", String.class))
        .isEqualTo(sourceReceipt.requestDigest());
    assertThat(storedRow.get("source_operation_id", UUID.class)).isEqualTo(source.operationId());
    assertThat(storedRow.get("source_evidence_digest", String.class))
        .isEqualTo(source.evidenceDigest());
    assertThat(storedRow.get("intake_receipt_digest", String.class))
        .isEqualTo(sourceReceipt.receiptDigest());
    assertThat(storedRow.get("version_state_read_request_id", UUID.class)).isEqualTo(readRequestId);
    assertThat(storedRow.get("version_state_evidence_digest", String.class))
        .isEqualTo(receipt.versionStateEvidence().evidenceDigest());
    assertThat(storedRow.get("version_state_evidence_json", String.class))
        .contains(source.operationId().toString(), canonicalVersionId.toString());
    var localKeyColumn =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT is_identity, identity_generation FROM information_schema.columns "
                    + "WHERE table_schema = current_schema() "
                    + "AND table_name = 'world_authored_version_identity' "
                    + "AND column_name = 'local_version_key'"),
            "World local version key identity metadata was not found");
    assertThat(localKeyColumn.get("is_identity", String.class)).isEqualTo("YES");
    assertThat(localKeyColumn.get("identity_generation", String.class)).isEqualTo("ALWAYS");

    var retainedRegion =
        Objects.requireNonNull(
            dsl.fetchOne("SELECT tenant_id, name FROM region WHERE id = ?", legacyRegionId),
            "unmapped legacy region disappeared");
    assertThat(retainedRegion.get("tenant_id", Long.class)).isEqualTo(legacyTenantKey);
    assertThat(retainedRegion.get("name", String.class)).isEqualTo(legacyRegionName);
    var retainedWorld =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT tenant_id, game_instance_id, control_plane_request_id, version_id, status "
                    + "FROM world_instance WHERE id = ?",
                legacyWorldInstanceId),
            "unmapped legacy world-instance row disappeared");
    assertThat(retainedWorld.get("tenant_id", Long.class)).isEqualTo(legacyTenantKey);
    assertThat(retainedWorld.get("game_instance_id", Long.class)).isEqualTo(legacyGameInstanceId);
    assertThat(retainedWorld.get("control_plane_request_id", String.class))
        .isEqualTo(legacyControlRequest);
    assertThat(retainedWorld.get("version_id", Long.class)).isEqualTo(29L);
    assertThat(retainedWorld.get("status", String.class)).isEqualTo("ACTIVE");

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE world_authored_version_identity SET world_slug = ? "
                        + "WHERE operation_id = ?",
                    "rewritten-world",
                    receipt.operationId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM world_authored_version_identity WHERE operation_id = ?",
                    receipt.operationId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> dsl.execute("TRUNCATE world_authored_version_identity"))
        .isInstanceOf(DataAccessException.class);
    assertThat(identityCount(source)).isEqualTo(1L);
    verify(client, times(1)).read(any());
  }

  @Test
  void deniesWrongGameDesignIdentitySourceNamespaceAndMissingIntakeBeforeOwnerWrites() {
    AuthoredWorldSourceEvidence source = source(UUID.randomUUID(), randomWorld());
    acceptSource(source);
    UUID canonicalVersionId = UUID.randomUUID();
    long gameDesignVersionId = 211L;

    AuthoredWorldVersionStateClient wrongUuidClient = mock(AuthoredWorldVersionStateClient.class);
    when(wrongUuidClient.read(any()))
        .thenAnswer(
            invocation ->
                versionEvidence(
                    invocation.getArgument(0),
                    source,
                    OTHER_CANONICAL_VERSION,
                    VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
                    8L));
    WorldAuthoredVersionIdentityRepository repository =
        new WorldAuthoredVersionIdentityRepository(dsl);
    WorldAuthoredVersionIdentityService wrongUuidService =
        service(wrongUuidClient, repository, transactionManager);
    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        associate(
                            wrongUuidService,
                            source,
                            canonicalVersionId,
                            gameDesignVersionId,
                            UUID.randomUUID())))
        .isInstanceOf(InvalidIdentityEvidenceException.class);
    assertThat(identityCount(source)).isZero();

    AuthoredWorldVersionStateClient wrongPeerClient = mock(AuthoredWorldVersionStateClient.class);
    WorldAuthoredVersionIdentityService wrongPeerService =
        service(wrongPeerClient, repository, transactionManager);
    assertThatThrownBy(
            () ->
                withPeer(
                    NAMESPACE,
                    "game-session-service",
                    () ->
                        associate(
                            wrongPeerService,
                            source,
                            canonicalVersionId,
                            gameDesignVersionId,
                            UUID.randomUUID())))
        .isInstanceOf(SecurityException.class);
    verify(wrongPeerClient, never()).read(any());
    assertThat(identityCount(source)).isZero();

    AuthoredWorldSourceEvidence substitutedSource = source(UUID.randomUUID(), randomWorld());
    AuthoredWorldVersionStateClient wrongSourceClient = mock(AuthoredWorldVersionStateClient.class);
    UUID substitutedReadId = UUID.randomUUID();
    when(wrongSourceClient.read(any()))
        .thenAnswer(
            invocation ->
                versionEvidence(
                    request(substitutedSource, substitutedReadId, gameDesignVersionId),
                    substitutedSource,
                    canonicalVersionId,
                    VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
                    9L));
    WorldAuthoredVersionIdentityService wrongSourceService =
        service(wrongSourceClient, repository, transactionManager);
    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        associate(
                            wrongSourceService,
                            source,
                            canonicalVersionId,
                            gameDesignVersionId,
                            substitutedReadId)))
        .isInstanceOf(InvalidIdentityEvidenceException.class);
    assertThat(identityCount(source)).isZero();
    assertThat(identityCount(substitutedSource)).isZero();

    AuthoredWorldVersionStateClient wrongNamespaceClient =
        mock(AuthoredWorldVersionStateClient.class);
    WorldAuthoredVersionIdentityService wrongNamespaceService =
        service(wrongNamespaceClient, repository, transactionManager);
    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        wrongNamespaceService.associate(
                            "other-namespace",
                            source.canonicalTenantId(),
                            source.worldSlug(),
                            source.operationId(),
                            source.evidenceDigest(),
                            canonicalVersionId,
                            gameDesignVersionId,
                            UUID.randomUUID())))
        .isInstanceOf(SecurityException.class);
    verify(wrongNamespaceClient, never()).read(any());
    assertThat(identityCount(source)).isZero();

    AuthoredWorldSourceEvidence missingSource = source(UUID.randomUUID(), randomWorld());
    AuthoredWorldVersionStateClient missingSourceClient =
        mock(AuthoredWorldVersionStateClient.class);
    when(missingSourceClient.read(any()))
        .thenAnswer(
            invocation ->
                versionEvidence(
                    invocation.getArgument(0),
                    missingSource,
                    canonicalVersionId,
                    VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
                    10L));
    WorldAuthoredVersionIdentityService missingSourceService =
        service(missingSourceClient, repository, transactionManager);
    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        associate(
                            missingSourceService,
                            missingSource,
                            canonicalVersionId,
                            gameDesignVersionId,
                            UUID.randomUUID())))
        .isInstanceOf(InvalidIdentityEvidenceException.class);
    assertThat(identityCount(missingSource)).isZero();
    assertThat(
            sourceRepository.readBySource(
                NAMESPACE,
                missingSource.canonicalTenantId(),
                missingSource.worldSlug(),
                missingSource.operationId(),
                missingSource.evidenceDigest()))
        .isEmpty();
  }

  @Test
  void concurrentExactStableIdentityReturnsOneOriginalHistoricalReceipt() throws Exception {
    AuthoredWorldSourceEvidence source = source(UUID.randomUUID(), randomWorld());
    WorldAuthoredSourceIntakeReceipt sourceReceipt = acceptSource(source);
    UUID canonicalVersionId = UUID.randomUUID();
    long gameDesignVersionId = 307L;
    UUID firstReadId = UUID.randomUUID();
    UUID secondReadId = UUID.randomUUID();
    CountDownLatch bothRead = new CountDownLatch(2);
    AtomicReference<AuthoredWorldVersionStateEvidence> firstEvidence = new AtomicReference<>();
    AtomicReference<AuthoredWorldVersionStateEvidence> secondEvidence = new AtomicReference<>();
    AuthoredWorldVersionStateClient firstClient = mock(AuthoredWorldVersionStateClient.class);
    AuthoredWorldVersionStateClient secondClient = mock(AuthoredWorldVersionStateClient.class);
    when(firstClient.read(any()))
        .thenAnswer(
            invocation -> {
              AuthoredWorldVersionStateEvidence.Request request = invocation.getArgument(0);
              awaitReadBarrier(bothRead);
              AuthoredWorldVersionStateEvidence evidence =
                  versionEvidence(
                      request,
                      source,
                      canonicalVersionId,
                      VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
                      12L);
              firstEvidence.set(evidence);
              return evidence;
            });
    when(secondClient.read(any()))
        .thenAnswer(
            invocation -> {
              AuthoredWorldVersionStateEvidence.Request request = invocation.getArgument(0);
              awaitReadBarrier(bothRead);
              AuthoredWorldVersionStateEvidence evidence =
                  versionEvidence(
                      request,
                      source,
                      canonicalVersionId,
                      VersionLifecycleState.VERSION_LIFECYCLE_STATE_ACTIVE,
                      13L);
              secondEvidence.set(evidence);
              return evidence;
            });
    WorldAuthoredVersionIdentityRepository repository =
        new WorldAuthoredVersionIdentityRepository(dsl);
    WorldAuthoredVersionIdentityService firstService =
        service(firstClient, repository, transactionManager);
    WorldAuthoredVersionIdentityService secondService =
        service(secondClient, repository, transactionManager);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<WorldAuthoredVersionIdentityReceipt> first =
          executor.submit(
              () ->
                  withGameDesign(
                      () ->
                          associate(
                              firstService,
                              source,
                              canonicalVersionId,
                              gameDesignVersionId,
                              firstReadId)));
      Future<WorldAuthoredVersionIdentityReceipt> second =
          executor.submit(
              () ->
                  withGameDesign(
                      () ->
                          associate(
                              secondService,
                              source,
                              canonicalVersionId,
                              gameDesignVersionId,
                              secondReadId)));
      WorldAuthoredVersionIdentityReceipt firstReceipt = first.get(25, TimeUnit.SECONDS);
      WorldAuthoredVersionIdentityReceipt secondReceipt = second.get(25, TimeUnit.SECONDS);

      assertThat(firstReceipt).isEqualTo(secondReceipt);
      assertThat(firstReceipt.operationId()).isEqualTo(secondReceipt.operationId());
      assertThat(firstReceipt.localVersionKey()).isEqualTo(secondReceipt.localVersionKey());
      assertThat(firstReceipt.sourceIntakeReceipt()).isEqualTo(sourceReceipt);
      assertThat(firstReceipt.versionStateEvidence())
          .isIn(firstEvidence.get(), secondEvidence.get());
      assertThat(firstEvidence.get().evidenceDigest())
          .isNotEqualTo(secondEvidence.get().evidenceDigest());
      assertThat(firstEvidence.get().request().readRequestId())
          .isNotEqualTo(secondEvidence.get().request().readRequestId());
      assertThat(
              repository.readByCanonicalVersion(
                  NAMESPACE, source.canonicalTenantId(), source.worldSlug(), canonicalVersionId))
          .contains(firstReceipt);
      assertThat(identityCount(source)).isEqualTo(1L);
    }
    verify(firstClient, times(1)).read(any());
    verify(secondClient, times(1)).read(any());
  }

  @Test
  void conflictingUuidOrGameDesignSelectorDoesNotRewriteTheOriginalReceipt() {
    AuthoredWorldSourceEvidence source = source(UUID.randomUUID(), randomWorld());
    acceptSource(source);
    UUID canonicalVersionId = UUID.randomUUID();
    long gameDesignVersionId = 401L;
    AuthoredWorldVersionStateClient client = mock(AuthoredWorldVersionStateClient.class);
    when(client.read(any()))
        .thenAnswer(
            invocation -> {
              AuthoredWorldVersionStateEvidence.Request request = invocation.getArgument(0);
              UUID returnedCanonicalId =
                  request.versionId() == gameDesignVersionId
                      ? canonicalVersionId
                      : OTHER_CANONICAL_VERSION;
              return versionEvidence(
                  request,
                  source,
                  returnedCanonicalId,
                  VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
                  16L);
            });
    WorldAuthoredVersionIdentityRepository repository =
        new WorldAuthoredVersionIdentityRepository(dsl);
    WorldAuthoredVersionIdentityService service = service(client, repository, transactionManager);
    WorldAuthoredVersionIdentityReceipt original =
        withGameDesign(
            () ->
                associate(
                    service, source, canonicalVersionId, gameDesignVersionId, UUID.randomUUID()));

    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        associate(
                            service,
                            source,
                            canonicalVersionId,
                            gameDesignVersionId + 1,
                            UUID.randomUUID())))
        .isInstanceOf(RegistrationConflictException.class);
    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        associate(
                            service,
                            source,
                            OTHER_CANONICAL_VERSION,
                            gameDesignVersionId,
                            UUID.randomUUID())))
        .isInstanceOf(RegistrationConflictException.class);

    assertThat(
            repository.readByCanonicalVersion(
                NAMESPACE, source.canonicalTenantId(), source.worldSlug(), canonicalVersionId))
        .contains(original);
    assertThat(identityCount(source)).isEqualTo(1L);
    verify(client, times(1)).read(any());
  }

  @Test
  void recoversLostCommitAcknowledgmentAndRetriesWithoutRereadingOrReminting() {
    AuthoredWorldSourceEvidence source = source(UUID.randomUUID(), randomWorld());
    acceptSource(source);
    UUID canonicalVersionId = UUID.randomUUID();
    long gameDesignVersionId = 503L;
    AuthoredWorldVersionStateClient client = mock(AuthoredWorldVersionStateClient.class);
    when(client.read(any()))
        .thenAnswer(
            invocation ->
                versionEvidence(
                    invocation.getArgument(0),
                    source,
                    canonicalVersionId,
                    VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
                    21L));
    WorldAuthoredVersionIdentityRepository repository =
        new WorldAuthoredVersionIdentityRepository(dsl);
    WorldAuthoredVersionIdentityService service =
        service(
            client, repository, new LostCommitAcknowledgmentTransactionManager(transactionManager));
    UUID originalReadId = UUID.randomUUID();

    WorldAuthoredVersionIdentityReceipt recovered =
        withGameDesign(
            () ->
                associate(
                    service, source, canonicalVersionId, gameDesignVersionId, originalReadId));
    WorldAuthoredVersionIdentityReceipt retry =
        withGameDesign(
            () ->
                associate(
                    service, source, canonicalVersionId, gameDesignVersionId, UUID.randomUUID()));

    assertThat(recovered).isEqualTo(retry);
    assertThat(retry.versionStateEvidence().request().readRequestId()).isEqualTo(originalReadId);
    assertThat(identityCount(source)).isEqualTo(1L);
    assertThat(
            repository.readByCanonicalVersion(
                NAMESPACE, source.canonicalTenantId(), source.worldSlug(), canonicalVersionId))
        .contains(recovered);
    verify(client, times(1)).read(any());
  }

  @Test
  void forwardV27MigrationPreservesRetainedRowsWithoutInferringIdentityAssociations()
      throws SQLException {
    String schema = "world_version_identity_" + UUID.randomUUID().toString().replace("-", "");
    long legacyTenantKey =
        8_300_000_000L + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000L);
    String firstRegionName = "retained-v26-region-a-" + UUID.randomUUID();
    String secondRegionName = "retained-v26-region-b-" + UUID.randomUUID();
    long legacyGameInstanceId = positiveLong();
    try {
      migrateFixture(schema, MigrationVersion.fromVersion("26"));
      try (Connection connection = fixtureConnection(schema)) {
        try (PreparedStatement region =
            connection.prepareStatement("INSERT INTO region (tenant_id, name) VALUES (?, ?)")) {
          region.setLong(1, legacyTenantKey);
          region.setString(2, firstRegionName);
          region.executeUpdate();
          region.setLong(1, legacyTenantKey + 1);
          region.setString(2, secondRegionName);
          region.executeUpdate();
        }
        try (PreparedStatement world =
            connection.prepareStatement(
                "INSERT INTO world_instance (tenant_id, game_instance_id, game_template_id, "
                    + "control_plane_request_id, launch_descriptor_id, version_id, "
                    + "generation_config_revision, release_bundle_id, "
                    + "published_release_bundle_ref, version_state_epoch, status) "
                    + "VALUES (?, ?, 23, 'retained-v26-request', 'retained-v26-descriptor', "
                    + "37, 'retained-v26-config', 41, 'retained-v26-release', 4, 'ACTIVE')")) {
          world.setLong(1, legacyTenantKey);
          world.setLong(2, legacyGameInstanceId);
          world.executeUpdate();
        }
      }

      migrateFixture(schema, null);

      try (Connection connection = fixtureConnection(schema);
          Statement statement = connection.createStatement()) {
        try (PreparedStatement regions =
            connection.prepareStatement(
                "SELECT tenant_id, name FROM region WHERE tenant_id IN (?, ?) "
                    + "ORDER BY tenant_id")) {
          regions.setLong(1, legacyTenantKey);
          regions.setLong(2, legacyTenantKey + 1);
          try (ResultSet result = regions.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong("tenant_id")).isEqualTo(legacyTenantKey);
            assertThat(result.getString("name")).isEqualTo(firstRegionName);
            assertThat(result.next()).isTrue();
            assertThat(result.getLong("tenant_id")).isEqualTo(legacyTenantKey + 1);
            assertThat(result.getString("name")).isEqualTo(secondRegionName);
            assertThat(result.next()).isFalse();
          }
        }
        try (PreparedStatement worlds =
            connection.prepareStatement(
                "SELECT tenant_id, game_instance_id, version_id, status FROM world_instance "
                    + "WHERE tenant_id = ?")) {
          worlds.setLong(1, legacyTenantKey);
          try (ResultSet result = worlds.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong("tenant_id")).isEqualTo(legacyTenantKey);
            assertThat(result.getLong("game_instance_id")).isEqualTo(legacyGameInstanceId);
            assertThat(result.getLong("version_id")).isEqualTo(37L);
            assertThat(result.getString("status")).isEqualTo("ACTIVE");
            assertThat(result.next()).isFalse();
          }
        }
        assertCount(statement, "world_authored_version_identity", 0L);
        assertCount(statement, "world_authored_source_tenant_association", 0L);
        try (PreparedStatement reservations =
            connection.prepareStatement(
                "SELECT tenant_key, claim_kind, target_namespace, canonical_tenant_id "
                    + "FROM world_authored_source_tenant_key_reservation "
                    + "WHERE tenant_key IN (?, ?) ORDER BY tenant_key")) {
          reservations.setLong(1, legacyTenantKey);
          reservations.setLong(2, legacyTenantKey + 1);
          try (ResultSet result = reservations.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong("tenant_key")).isEqualTo(legacyTenantKey);
            assertThat(result.getString("claim_kind")).isEqualTo("LEGACY_NUMERIC");
            assertThat(result.getString("target_namespace")).isNull();
            assertThat(result.getObject("canonical_tenant_id")).isNull();
            assertThat(result.next()).isTrue();
            assertThat(result.getLong("tenant_key")).isEqualTo(legacyTenantKey + 1);
            assertThat(result.getString("claim_kind")).isEqualTo("LEGACY_NUMERIC");
            assertThat(result.getString("target_namespace")).isNull();
            assertThat(result.getObject("canonical_tenant_id")).isNull();
            assertThat(result.next()).isFalse();
          }
        }
      }
    } finally {
      dropFixtureSchema(schema);
    }
  }

  private WorldAuthoredSourceIntakeReceipt acceptSource(AuthoredWorldSourceEvidence source) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    return Objects.requireNonNull(
        transaction.execute(
            status -> sourceRepository.acceptFresh(NAMESPACE, UUID.randomUUID(), source)),
        "World source intake returned no receipt");
  }

  private WorldAuthoredVersionIdentityService service(
      AuthoredWorldVersionStateClient client,
      WorldAuthoredVersionIdentityRepository repository,
      PlatformTransactionManager manager) {
    return new WorldAuthoredVersionIdentityService(
        client, repository, sourceRepository, manager, NAMESPACE);
  }

  private static WorldAuthoredVersionIdentityReceipt associate(
      WorldAuthoredVersionIdentityService service,
      AuthoredWorldSourceEvidence source,
      UUID canonicalVersionId,
      long gameDesignVersionId,
      UUID readRequestId) {
    return service.associate(
        NAMESPACE,
        source.canonicalTenantId(),
        source.worldSlug(),
        source.operationId(),
        source.evidenceDigest(),
        canonicalVersionId,
        gameDesignVersionId,
        readRequestId);
  }

  private Map<String, Long> worldContentAndLifecycleCounts() {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String table : WORLD_CONTENT_AND_LIFECYCLE_TABLES) {
      counts.put(
          table,
          Objects.requireNonNull(
                  dsl.fetchOne("SELECT COUNT(*) FROM " + table),
                  "World content count query returned no row")
              .get(0, Long.class));
    }
    return Map.copyOf(counts);
  }

  private long identityCount(AuthoredWorldSourceEvidence source) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT COUNT(*) FROM world_authored_version_identity WHERE target_namespace = ? "
                    + "AND canonical_tenant_id = ? AND world_slug = ?",
                NAMESPACE,
                source.canonicalTenantId(),
                source.worldSlug()),
            "World authored-Version identity count query returned no row")
        .get(0, Long.class);
  }

  private static AuthoredWorldSourceEvidence source(UUID tenant, String worldSlug) {
    UUID registrationRequest = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    String tenantSlug = "tenant-" + tenant.toString().replace("-", "");
    String displayName = "Source " + tenant.toString().substring(0, 8);
    long sourceGameRowId = Math.max(1L, tenant.getLeastSignificantBits() & Long.MAX_VALUE);
    String sourceGameTenantKey = "source-" + tenant.toString().replace("-", "").substring(0, 24);
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationRequest, tenant, tenantSlug, worldSlug, displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequest,
            sourceOperationId,
            requestDigest,
            tenant,
            tenantSlug,
            worldSlug,
            displayName,
            sourceGameRowId,
            sourceGameTenantKey,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationRequest,
        sourceOperationId,
        requestDigest,
        tenant,
        tenantSlug,
        worldSlug,
        displayName,
        sourceGameRowId,
        sourceGameTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static AuthoredWorldVersionStateEvidence.Request request(
      AuthoredWorldSourceEvidence source, UUID readRequestId, long gameDesignVersionId) {
    return new AuthoredWorldVersionStateEvidence.Request(
        1,
        NAMESPACE,
        readRequestId,
        source.canonicalTenantId(),
        source.worldSlug(),
        source.operationId(),
        source.evidenceDigest(),
        gameDesignVersionId);
  }

  private static AuthoredWorldVersionStateEvidence versionEvidence(
      AuthoredWorldVersionStateEvidence.Request request,
      AuthoredWorldSourceEvidence source,
      UUID canonicalVersionId,
      VersionLifecycleState state,
      long epoch) {
    return AuthoredWorldVersionStateEvidence.create(
        request, source, canonicalVersionId, state, epoch);
  }

  private static String randomWorld() {
    return "world-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
  }

  private static long positiveLong() {
    return Math.max(1L, UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE);
  }

  private static <T> T withGameDesign(java.util.function.Supplier<T> action) {
    return withPeer(NAMESPACE, "game-design-service", action);
  }

  private static <T> T withPeer(
      String namespace, String service, java.util.function.Supplier<T> action) {
    GrpcPeerIdentity peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  private static void awaitReadBarrier(CountDownLatch latch) {
    latch.countDown();
    try {
      if (!latch.await(15, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent Version identity read barrier timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Concurrent Version identity read was interrupted", exception);
    }
  }

  private static void migrateFixture(String schema, MigrationVersion target) {
    var configuration =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private static Connection fixtureConnection(String schema) throws SQLException {
    Connection connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    connection.setSchema(schema);
    return connection;
  }

  private static void dropFixtureSchema(String schema) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static void assertCount(Statement statement, String table, long expected)
      throws SQLException {
    try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      assertThat(result.next()).isTrue();
      assertThat(result.getLong(1)).isEqualTo(expected);
    }
  }

  private static final class LostCommitAcknowledgmentTransactionManager
      implements PlatformTransactionManager {
    private final PlatformTransactionManager delegate;
    private boolean firstCommit = true;

    private LostCommitAcknowledgmentTransactionManager(PlatformTransactionManager delegate) {
      this.delegate = delegate;
    }

    @Override
    public TransactionStatus getTransaction(
        org.springframework.transaction.TransactionDefinition definition) {
      return delegate.getTransaction(definition);
    }

    @Override
    public void commit(TransactionStatus status) {
      delegate.commit(status);
      if (firstCommit) {
        firstCommit = false;
        throw new IllegalStateException("simulated lost World identity commit acknowledgment");
      }
    }

    @Override
    public void rollback(TransactionStatus status) {
      if (!status.isCompleted()) {
        delegate.rollback(status);
      }
    }
  }
}
