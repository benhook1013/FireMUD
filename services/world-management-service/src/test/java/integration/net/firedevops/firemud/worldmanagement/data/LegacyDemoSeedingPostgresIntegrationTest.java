package net.firedevops.firemud.worldmanagement.data;

import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.migration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.WorldManagementServiceApplication;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Physical PostgreSQL proof for opt-in legacy demo seeding and its shared canonical allocator
 * fence. Controlled source evidence and SQL fixtures prove World storage behavior only; they do not
 * establish Human production authority or an authenticated Game Design producer.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = {"spring.grpc.server.port=0", "firemud.smoke.seed-demo-runtime.enabled=false"})
class LegacyDemoSeedingPostgresIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final long DEMO_TENANT_ID = 1L;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "world_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private WorldAuthoredSourceIntakeRepository sourceIntakeRepository;
  @Autowired private RegionRepository regionRepository;
  @Autowired private ZoneRepository zoneRepository;
  @Autowired private RoomRepository roomRepository;
  @Autowired private RoomExitRepository roomExitRepository;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  private String schema;
  private DriverManagerDataSource migrationDataSource;
  private TestDataSeeder seeder;

  @BeforeEach
  void createIsolatedMigratedSchema() {
    schema = "legacy_demo_" + UUID.randomUUID().toString().replace("-", "");
    migrationDataSource = new DriverManagerDataSource();
    migrationDataSource.setDriverClassName("org.postgresql.Driver");
    migrationDataSource.setUrl(postgres.getJdbcUrl());
    migrationDataSource.setUsername(postgres.getUsername());
    migrationDataSource.setPassword(postgres.getPassword());
    Flyway flyway = migration(migrationDataSource, schema, null);
    flyway.migrate();
    seeder =
        new TestDataSeeder(
            regionRepository,
            zoneRepository,
            roomRepository,
            roomExitRepository,
            sourceIntakeRepository);
  }

  @AfterEach
  void dropIsolatedSchema() throws SQLException {
    if (schema == null) {
      return;
    }
    try (Connection connection = fixtureConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }
  }

  @Test
  void freshSeedAndReplayPreserveLegacyClaimAndTopologyContents() {
    runSeeder();

    List<TopologyRow> firstTopology = topologyRows();
    ReservationSnapshot firstClaim = reservation(DEMO_TENANT_ID);
    assertThat(firstTopology).hasSize(6);
    assertThat(firstTopology.stream().map(TopologyRow::tableName).toList())
        .containsExactly("region", "room", "room", "room_exit", "room_exit", "zone");
    assertThat(firstClaim.claimKind()).isEqualTo("LEGACY_NUMERIC");
    assertThat(firstClaim.targetNamespace()).isNull();
    assertThat(firstClaim.canonicalTenantId()).isNull();

    runSeeder();

    // The legacy seeder refreshes optimistic versions on replay; the immutable CLAIM must stay
    // unchanged while the topology's business contents remain identical.
    assertThat(topologyRows()).isEqualTo(firstTopology);
    assertThat(reservation(DEMO_TENANT_ID)).isEqualTo(firstClaim);
  }

  @Test
  void canonicalKeyOneReservationRefusesDemoBeforeTopologyEffects() {
    UUID canonicalTenantId = UUID.randomUUID();
    WorldAuthoredSourceIntakeReceipt receipt =
        acceptFresh(source(canonicalTenantId, "canonical-collision-world"));
    assertThat(receipt.localTenantKey()).isEqualTo(DEMO_TENANT_ID);

    ReservationSnapshot canonicalClaim = reservation(DEMO_TENANT_ID);
    assertThat(canonicalClaim.claimKind()).isEqualTo("CANONICAL_AUTHORED_SOURCE");
    assertThat(canonicalClaim.targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(canonicalClaim.canonicalTenantId()).isEqualTo(canonicalTenantId);
    Map<String, Long> retainedOwnerCounts = ownerCounts();

    assertThatThrownBy(this::runSeeder)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("DEMO_WORLD_SEEDING_UNAVAILABLE:");

    assertThat(topologyRows()).isEmpty();
    assertThat(ownerCounts()).isEqualTo(retainedOwnerCounts);
    assertThat(reservation(DEMO_TENANT_ID)).isEqualTo(canonicalClaim);
  }

  @Test
  void legacyClaimSerializesWithTheExistingCanonicalTenantAllocator() throws Exception {
    CountDownLatch claimCommittedToTransaction = new CountDownLatch(1);
    CountDownLatch allowClaimCommit = new CountDownLatch(1);
    CountDownLatch allocationStarted = new CountDownLatch(1);
    AtomicInteger allocatorBackendPid = new AtomicInteger();
    UUID canonicalTenantId = UUID.randomUUID();
    AuthoredWorldSourceEvidence source = source(canonicalTenantId, "allocator-race-world");
    UUID requestId = UUID.randomUUID();
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<?> legacyClaim =
          executor.submit(
              () ->
                  transactionTemplate()
                      .executeWithoutResult(
                          status -> {
                            setSearchPath();
                            sourceIntakeRepository.claimLegacyDemoTenant();
                            claimCommittedToTransaction.countDown();
                            await(allowClaimCommit);
                          }));
      assertThat(claimCommittedToTransaction.await(5, TimeUnit.SECONDS)).isTrue();

      Future<WorldAuthoredSourceIntakeReceipt> canonicalAllocation =
          executor.submit(
              () ->
                  transactionTemplate()
                      .execute(
                          status -> {
                            setSearchPath();
                            allocatorBackendPid.set(backendPid());
                            allocationStarted.countDown();
                            return sourceIntakeRepository.acceptFresh(NAMESPACE, requestId, source);
                          }));

      try {
        assertThat(allocationStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitAllocatorLockWait(allocatorBackendPid.get(), canonicalAllocation)).isTrue();
      } finally {
        allowClaimCommit.countDown();
      }

      legacyClaim.get(5, TimeUnit.SECONDS);
      WorldAuthoredSourceIntakeReceipt receipt = canonicalAllocation.get(5, TimeUnit.SECONDS);
      assertThat(receipt.localTenantKey()).isEqualTo(2L);
      assertThat(reservation(1L).claimKind()).isEqualTo("LEGACY_NUMERIC");
      assertThat(reservation(2L).claimKind()).isEqualTo("CANONICAL_AUTHORED_SOURCE");
      assertThat(reservation(2L).canonicalTenantId()).isEqualTo(canonicalTenantId);
    } finally {
      allowClaimCommit.countDown();
      executor.shutdownNow();
    }
  }

  private void runSeeder() {
    transactionTemplate()
        .executeWithoutResult(
            status -> {
              setSearchPath();
              seeder.run(new DefaultApplicationArguments(new String[0]));
            });
  }

  private WorldAuthoredSourceIntakeReceipt acceptFresh(AuthoredWorldSourceEvidence source) {
    return transactionTemplate()
        .execute(
            status -> {
              setSearchPath();
              return sourceIntakeRepository.acceptFresh(NAMESPACE, UUID.randomUUID(), source);
            });
  }

  private TransactionTemplate transactionTemplate() {
    return new TransactionTemplate(transactionManager);
  }

  private void setSearchPath() {
    dsl.fetchOne("SELECT set_config('search_path', ?, true)", schema);
  }

  private int backendPid() {
    return Objects.requireNonNull(dsl.fetchOne("SELECT pg_backend_pid()")).get(0, Integer.class);
  }

  private boolean awaitAllocatorLockWait(
      int backendPid, Future<WorldAuthoredSourceIntakeReceipt> allocation) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline && !allocation.isDone()) {
      Record activity =
          dsl.fetchOne(
              "SELECT wait_event_type, query FROM pg_stat_activity WHERE pid = ?", backendPid);
      if (activity != null
          && "Lock".equals(activity.get("wait_event_type", String.class))
          && activity.get("query", String.class) != null
          && activity
              .get("query", String.class)
              .startsWith(
                  "LOCK TABLE world_authored_source_tenant_association IN SHARE ROW EXCLUSIVE")) {
        return true;
      }
      java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
    }
    return false;
  }

  private List<TopologyRow> topologyRows() {
    try (Connection connection = fixtureConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO \"" + schema + "\"");
      try (var rows =
          statement.executeQuery(
              "SELECT table_name, row_bytes FROM ("
                  + "SELECT 'region'::text AS table_name, "
                  + "convert_to((to_jsonb(r) - 'version')::text, 'UTF8') AS row_bytes "
                  + "FROM region r WHERE r.tenant_id = 1 AND r.version_id = 1 "
                  + "AND r.name = 'Demo Region' UNION ALL "
                  + "SELECT 'zone', convert_to((to_jsonb(z) - 'version')::text, 'UTF8') "
                  + "FROM zone z WHERE z.tenant_id = 1 AND z.version_id = 1 "
                  + "AND z.name = 'Demo Zone' UNION ALL "
                  + "SELECT 'room', convert_to((to_jsonb(r) - 'version')::text, 'UTF8') "
                  + "FROM room r WHERE r.tenant_id = 1 AND r.version_id = 1 "
                  + "AND r.id IN (1021, 2045) UNION ALL "
                  + "SELECT 'room_exit', convert_to((to_jsonb(e) - 'version')::text, 'UTF8') "
                  + "FROM room_exit e WHERE e.tenant_id = 1 AND e.version_id = 1 "
                  + "AND ((e.from_room_id = 1021 AND e.to_room_id = 2045) "
                  + "OR (e.from_room_id = 2045 AND e.to_room_id = 1021))"
                  + ") AS topology_rows ORDER BY table_name, row_bytes")) {
        List<TopologyRow> result = new ArrayList<>();
        while (rows.next()) {
          result.add(
              new TopologyRow(
                  rows.getString("table_name"),
                  HexFormat.of().formatHex(rows.getBytes("row_bytes"))));
        }
        return result;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to read controlled demo topology snapshot", exception);
    }
  }

  private ReservationSnapshot reservation(long tenantKey) {
    try (Connection connection = fixtureConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO \"" + schema + "\"");
      try (var query =
          connection.prepareStatement(
              "SELECT claim_kind, target_namespace, canonical_tenant_id::text, "
                  + "xmin::text FROM world_authored_source_tenant_key_reservation "
                  + "WHERE tenant_key = ?")) {
        query.setLong(1, tenantKey);
        try (var rows = query.executeQuery()) {
          if (rows.next()) {
            return new ReservationSnapshot(
                rows.getString(1),
                rows.getString(2),
                rows.getString(3) == null ? null : UUID.fromString(rows.getString(3)),
                rows.getString(4));
          }
          throw new IllegalStateException("Expected tenant-key reservation is missing");
        }
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to read tenant-key reservation snapshot", exception);
    }
  }

  private Map<String, Long> ownerCounts() {
    try (Connection connection = fixtureConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO \"" + schema + "\"");
      try (var rows =
          statement.executeQuery(
              "SELECT (SELECT COUNT(*) FROM world_authored_source_tenant_association) "
                  + "AS associations, (SELECT COUNT(*) FROM world_authored_source_intake) "
                  + "AS intakes, (SELECT COUNT(*) FROM "
                  + "world_authored_source_tenant_key_reservation) AS reservations")) {
        rows.next();
        return Map.of(
            "associations", rows.getLong("associations"),
            "intakes", rows.getLong("intakes"),
            "reservations", rows.getLong("reservations"));
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to read controlled owner row counts", exception);
    }
  }

  private Connection fixtureConnection() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private static AuthoredWorldSourceEvidence source(UUID tenantId, String worldSlug) {
    String tenantSlug = "tenant-" + tenantId.toString().replace("-", "");
    long sourceRowId = tenantId.getLeastSignificantBits() & Long.MAX_VALUE;
    if (sourceRowId == 0L) {
      sourceRowId = 1L;
    }
    UUID registrationId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String displayName = "Controlled " + worldSlug;
    String gameTenantKey = "gd-row-" + sourceRowId;
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationId, tenantId, tenantSlug, worldSlug, displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationId,
            operationId,
            requestDigest,
            tenantId,
            tenantSlug,
            worldSlug,
            displayName,
            sourceRowId,
            gameTenantKey,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationId,
        operationId,
        requestDigest,
        tenantId,
        tenantSlug,
        worldSlug,
        displayName,
        sourceRowId,
        gameTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for controlled PostgreSQL race gate");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting for PostgreSQL race gate", exception);
    }
  }

  private record TopologyRow(String tableName, String rowBytes) {}

  private record ReservationSnapshot(
      String claimKind, String targetNamespace, UUID canonicalTenantId, String xmin) {}
}
