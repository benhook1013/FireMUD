package net.firedevops.firemud.worldmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
class WorldAuthoredSourceIntakeIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final Set<String> PROTECTED_TENANT_TABLES =
      Set.of(
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
  private static final String OCCUPIED_LEGACY_KEYS =
      "SELECT COUNT(*) FROM ("
          + "SELECT tenant_id AS occupied_key FROM generation_rule UNION ALL "
          + "SELECT tenant_id FROM instance UNION ALL "
          + "SELECT tenant_id FROM region UNION ALL "
          + "SELECT tenant_id FROM region_instance UNION ALL "
          + "SELECT tenant_id FROM room UNION ALL "
          + "SELECT tenant_id FROM room_exit UNION ALL "
          + "SELECT tenant_id FROM room_instance UNION ALL "
          + "SELECT tenant_id FROM room_instance_exit UNION ALL "
          + "SELECT tenant_id FROM world_design_aggregate_epoch UNION ALL "
          + "SELECT tenant_id FROM world_design_revision_ledger UNION ALL "
          + "SELECT tenant_id FROM world_design_scope_epoch UNION ALL "
          + "SELECT tenant_id FROM world_entity_spawn_binding UNION ALL "
          + "SELECT tenant_id FROM world_event UNION ALL "
          + "SELECT tenant_id FROM world_instance UNION ALL "
          + "SELECT tenant_id FROM zone UNION ALL "
          + "SELECT tenant_id FROM zone_instance"
          + ") AS occupied_world_keys WHERE occupied_key = ?";
  private static final String MAX_OCCUPIED_TENANT_KEY =
      "SELECT COALESCE(MAX(occupied_key), 0) FROM ("
          + "SELECT tenant_id AS occupied_key FROM generation_rule UNION ALL "
          + "SELECT tenant_id FROM instance UNION ALL "
          + "SELECT tenant_id FROM region UNION ALL "
          + "SELECT tenant_id FROM region_instance UNION ALL "
          + "SELECT tenant_id FROM room UNION ALL "
          + "SELECT tenant_id FROM room_exit UNION ALL "
          + "SELECT tenant_id FROM room_instance UNION ALL "
          + "SELECT tenant_id FROM room_instance_exit UNION ALL "
          + "SELECT tenant_id FROM world_design_aggregate_epoch UNION ALL "
          + "SELECT tenant_id FROM world_design_revision_ledger UNION ALL "
          + "SELECT tenant_id FROM world_design_scope_epoch UNION ALL "
          + "SELECT tenant_id FROM world_entity_spawn_binding UNION ALL "
          + "SELECT tenant_id FROM world_event UNION ALL "
          + "SELECT tenant_id FROM world_instance UNION ALL "
          + "SELECT tenant_id FROM zone UNION ALL "
          + "SELECT tenant_id FROM zone_instance UNION ALL "
          + "SELECT tenant_key FROM world_authored_source_tenant_key_reservation UNION ALL "
          + "SELECT local_tenant_key FROM world_authored_source_tenant_association"
          + ") AS occupied_tenant_keys";

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

  @Autowired private WorldAuthoredSourceIntakeRepository repository;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;
  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void persistsCompleteFreshReceiptAndReusesExactTenantAssociationAcrossWorlds() {
    long existingLegacyTenantKey =
        8_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000L);
    Long legacyRegionId =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "INSERT INTO region (tenant_id, name) VALUES (?, ?) RETURNING id",
                    existingLegacyTenantKey,
                    "retained-world-allocation-guard"),
                "retained region insert did not return its row")
            .get(0, Long.class);
    dsl.execute(
        "INSERT INTO region (tenant_id, name) VALUES (?, ?)",
        existingLegacyTenantKey,
        "same-legacy-selector-remains-valid");
    dsl.execute(
        "UPDATE region SET tenant_id = ? WHERE id = ?", existingLegacyTenantKey, legacyRegionId);

    UUID canonicalTenantId = UUID.randomUUID();
    String tenantSlug = tenantSlug(canonicalTenantId);
    long sourceRowId = sourceRowId(canonicalTenantId);
    AuthoredWorldSourceEvidence firstSource =
        source(canonicalTenantId, tenantSlug, "violet-wilds", sourceRowId, "Café 🐉");
    UUID firstRequestId = UUID.randomUUID();
    WorldAuthoredSourceIntakeReceipt first = accept(firstRequestId, firstSource);

    assertThat(first.source()).isEqualTo(firstSource);
    assertThat(first.localTenantKey()).isGreaterThan(existingLegacyTenantKey);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(OCCUPIED_LEGACY_KEYS, first.localTenantKey()),
                    "legacy key occupancy query returned no row")
                .get(0, Long.class))
        .isZero();
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT COUNT(*) FROM world_authored_source_tenant_key_reservation "
                            + "WHERE tenant_key = ? AND claim_kind = 'CANONICAL_AUTHORED_SOURCE' "
                            + "AND target_namespace = ? AND canonical_tenant_id = ?",
                        first.localTenantKey(),
                        NAMESPACE,
                        canonicalTenantId),
                    "canonical tenant-key reservation count query returned no row")
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThat(read(firstRequestId)).contains(first);

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO region (tenant_id, name) VALUES (?, ?)",
                    first.localTenantKey(),
                    "cannot-claim-canonical-selector"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(this::assertReservedCanonicalSelectorRejected);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE region SET tenant_id = ? WHERE id = ?",
                    first.localTenantKey(),
                    legacyRegionId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(this::assertReservedCanonicalSelectorRejected);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne("SELECT tenant_id FROM region WHERE id = ?", legacyRegionId),
                    "retained region tenant readback returned no row")
                .get(0, Long.class))
        .isEqualTo(existingLegacyTenantKey);

    AuthoredWorldSourceEvidence secondSource =
        source(canonicalTenantId, tenantSlug, "amber-coast", sourceRowId, "Amber Coast");
    WorldAuthoredSourceIntakeReceipt second = accept(UUID.randomUUID(), secondSource);

    assertThat(second.localTenantKey()).isEqualTo(first.localTenantKey());
    assertThat(second.source().tenantSlug()).isEqualTo(first.source().tenantSlug());
    assertThat(second.source().sourceGameRowId()).isEqualTo(first.source().sourceGameRowId());
    assertThat(second.source().sourceGameTenantKey())
        .isEqualTo(first.source().sourceGameTenantKey());
    assertThat(second.source().provenanceKind()).isEqualTo("NEW_GAME_ROW");
  }

  @Test
  void deniesRetainedEvidenceAndChangedRequestOrTenantScopeBeforeAddingClaims() {
    UUID tenant = UUID.randomUUID();
    String tenantSlug = tenantSlug(tenant);
    long sourceRowId = sourceRowId(tenant);
    AuthoredWorldSourceEvidence firstSource =
        source(tenant, tenantSlug, "violet-wilds", sourceRowId, "Violet Wilds");
    UUID requestId = UUID.randomUUID();
    WorldAuthoredSourceIntakeReceipt first = accept(requestId, firstSource);

    AuthoredWorldSourceEvidence changedRequestSource =
        source(tenant, tenantSlug, "amber-coast", sourceRowId, "Amber Coast");
    assertThatThrownBy(() -> accept(requestId, changedRequestSource))
        .isInstanceOf(WorldAuthoredSourceIntakeRepository.RegistrationConflictException.class);
    assertThatThrownBy(
            () ->
                accept(
                    requestId,
                    source(
                        UUID.randomUUID(),
                        "unrelated-tenant-selector",
                        "violet-wilds",
                        7_456_321_987L,
                        "Violet Wilds")))
        .isInstanceOf(WorldAuthoredSourceIntakeRepository.RegistrationConflictException.class);

    AuthoredWorldSourceEvidence changedWorldSource =
        source(tenant, tenantSlug, "violet-wilds", sourceRowId, "Changed Display");
    assertThatThrownBy(() -> accept(UUID.randomUUID(), changedWorldSource))
        .isInstanceOf(WorldAuthoredSourceIntakeRepository.RegistrationConflictException.class);
    assertThatThrownBy(() -> accept(UUID.randomUUID(), firstSource))
        .isInstanceOf(WorldAuthoredSourceIntakeRepository.RegistrationConflictException.class);

    AuthoredWorldSourceEvidence changedTenantSource =
        source(
            tenant,
            "renamed-" + tenant.toString().replace("-", ""),
            "new-world",
            sourceRowId,
            "New World");
    assertThatThrownBy(() -> accept(UUID.randomUUID(), changedTenantSource))
        .isInstanceOf(WorldAuthoredSourceIntakeRepository.RegistrationConflictException.class);

    UUID retainedTenantId = UUID.randomUUID();
    AuthoredWorldSourceEvidence retainedSource =
        source(
            retainedTenantId,
            tenantSlug(retainedTenantId),
            "retained-world",
            sourceRowId(retainedTenantId),
            "Retained",
            "RETAINED_GAME_V30");
    assertThatThrownBy(() -> accept(UUID.randomUUID(), retainedSource))
        .isInstanceOf(WorldAuthoredSourceIntakeRepository.InvalidIntakeEvidenceException.class);
    assertThat(read(requestId)).contains(first);
    assertThat(countForCanonicalTenant(tenant)).isEqualTo(1L);
  }

  @Test
  void ownerTransactionRollbackLeavesNeitherAssociationNorReceipt() {
    UUID tenant = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    AuthoredWorldSourceEvidence source =
        source(
            tenant,
            tenantSlug(tenant),
            "rolled-back-world",
            sourceRowId(tenant),
            "Rolled Back World");
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transactionTemplate.execute(
                    status -> {
                      repository.acceptFresh(NAMESPACE, requestId, source);
                      throw new ForcedOwnerRollbackException();
                    }))
        .isInstanceOf(ForcedOwnerRollbackException.class);

    assertThat(read(requestId)).isEmpty();
    assertThat(countForCanonicalTenant(tenant)).isZero();
    assertThat(countReservationsForCanonicalTenant(tenant)).isZero();
  }

  @Test
  void rejectsImmutableHistoryMutationAndTruncation() {
    UUID tenant = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    AuthoredWorldSourceEvidence source =
        source(
            tenant, tenantSlug(tenant), "immutable-world", sourceRowId(tenant), "Immutable World");
    WorldAuthoredSourceIntakeReceipt receipt = accept(requestId, source);

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE world_authored_source_intake SET world_display_name = ? "
                        + "WHERE operation_id = ?",
                    "substituted",
                    receipt.operationId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM world_authored_source_intake WHERE operation_id = ?",
                    receipt.operationId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> dsl.execute("TRUNCATE world_authored_source_intake CASCADE"))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE world_authored_source_tenant_association SET tenant_slug = ? "
                        + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
                    "changed-selector",
                    NAMESPACE,
                    tenant))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM world_authored_source_tenant_association "
                        + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
                    NAMESPACE,
                    tenant))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> dsl.execute("TRUNCATE world_authored_source_tenant_association CASCADE"))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE world_authored_source_tenant_key_reservation "
                        + "SET claim_kind = 'LEGACY_NUMERIC' WHERE tenant_key = ?",
                    receipt.localTenantKey()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM world_authored_source_tenant_key_reservation "
                        + "WHERE tenant_key = ?",
                    receipt.localTenantKey()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> dsl.execute("TRUNCATE world_authored_source_tenant_key_reservation CASCADE"))
        .isInstanceOf(DataAccessException.class);

    assertThat(read(requestId)).contains(receipt);
    assertThat(countForCanonicalTenant(tenant)).isEqualTo(1L);
    assertThat(countReservationsForCanonicalTenant(tenant)).isEqualTo(1L);
  }

  @Test
  void canonicalReservationsGuardEveryWorldRowMutationTrigger() throws SQLException {
    String schema = "world_canonical_guard_" + UUID.randomUUID().toString().replace("-", "");
    UUID canonicalTenantId = UUID.randomUUID();
    long canonicalTenantKey =
        80_000_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000L);
    long legacyTenantKey = canonicalTenantKey + 1L;

    try {
      migrateFixture(schema, MigrationVersion.fromVersion("23"));
      long canonicalRegionId =
          seedCanonicalRegionBeforeGuard(schema, canonicalTenantId, canonicalTenantKey);
      migrateFixture(schema, null);

      try (Connection connection = fixtureConnection(schema)) {
        assertProtectedReservationTriggerCatalog(connection);
        assertProtectedTenantTruncateTriggerCatalog(connection);

        for (String table : PROTECTED_TENANT_TABLES) {
          assertSqlState("23514", () -> insertTenantKeyRow(connection, table, canonicalTenantKey));
          long expectedRows = table.equals("region") ? 1L : 0L;
          assertThat(countRowsForTenant(connection, table, canonicalTenantKey))
              .as("canonical-key insert side effects for %s", table)
              .isEqualTo(expectedRows);
        }

        long legacyRegionId = insertRegion(connection, legacyTenantKey, "legacy-before-update");
        assertSqlState(
            "23514",
            () ->
                updateRegion(
                    connection,
                    canonicalRegionId,
                    legacyTenantKey,
                    "attempted move from canonical key"));
        assertCanonicalRegion(
            connection, canonicalRegionId, canonicalTenantKey, "test-only canonical row");
        assertThat(countRowsForTenant(connection, "region", legacyTenantKey)).isEqualTo(1L);
        assertThat(readReservationClaimKind(connection, legacyTenantKey))
            .isEqualTo("LEGACY_NUMERIC");

        assertSqlState(
            "23514",
            () -> updateRegionPayload(connection, canonicalRegionId, "attempted payload update"));
        assertCanonicalRegion(
            connection, canonicalRegionId, canonicalTenantKey, "test-only canonical row");

        assertSqlState("23514", () -> deleteRegion(connection, canonicalRegionId));
        assertCanonicalRegion(
            connection, canonicalRegionId, canonicalTenantKey, "test-only canonical row");
        assertThat(readReservationClaimKind(connection, canonicalTenantKey))
            .isEqualTo("CANONICAL_AUTHORED_SOURCE");

        updateRegionPayload(connection, legacyRegionId, "legacy payload update remains allowed");
        assertThat(readRegionName(connection, legacyRegionId))
            .isEqualTo("legacy payload update remains allowed");
        deleteRegion(connection, legacyRegionId);
        assertThat(countRowsForTenant(connection, "region", legacyTenantKey)).isZero();
        assertThat(readReservationClaimKind(connection, legacyTenantKey))
            .isEqualTo("LEGACY_NUMERIC");

        assertSqlState("23514", () -> truncateTenantTable(connection, "region"));
        assertCanonicalRegion(
            connection, canonicalRegionId, canonicalTenantKey, "test-only canonical row");
        assertThat(countRowsForTenant(connection, "region", canonicalTenantKey)).isEqualTo(1L);
        assertThat(readReservationClaimKind(connection, canonicalTenantKey))
            .isEqualTo("CANONICAL_AUTHORED_SOURCE");
        assertThat(countCanonicalAssociations(connection, canonicalTenantId)).isEqualTo(1L);
      }
    } finally {
      dropFixtureSchema(schema);
    }
  }

  @Test
  void rejectsReadOnlyAndNonReadCommittedOwnerTransactionsWithoutClaims() {
    UUID tenant = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    AuthoredWorldSourceEvidence source =
        source(
            tenant,
            tenantSlug(tenant),
            "unsupported-transaction-world",
            sourceRowId(tenant),
            "Unsupported Transaction World");

    TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);
    assertThatThrownBy(
            () -> readOnly.execute(status -> repository.acceptFresh(NAMESPACE, requestId, source)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable owner transaction");
    assertThat(countForCanonicalTenant(tenant)).isZero();
    assertThat(countReservationsForCanonicalTenant(tenant)).isZero();
    assertThat(read(requestId)).isEmpty();

    TransactionTemplate repeatableRead = new TransactionTemplate(transactionManager);
    repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThatThrownBy(
            () ->
                repeatableRead.execute(
                    status -> repository.acceptFresh(NAMESPACE, requestId, source)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("READ COMMITTED");
    assertThat(countForCanonicalTenant(tenant)).isZero();
    assertThat(countReservationsForCanonicalTenant(tenant)).isZero();
    assertThat(read(requestId)).isEmpty();

    assertThatThrownBy(() -> repository.acceptFresh(NAMESPACE, requestId, source))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(countForCanonicalTenant(tenant)).isZero();
    assertThat(countReservationsForCanonicalTenant(tenant)).isZero();
    assertThat(read(requestId)).isEmpty();
  }

  @Test
  void concurrentExactRetriesReturnTheSingleCommittedReceipt() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    AuthoredWorldSourceEvidence source =
        source(
            tenant,
            tenantSlug(tenant),
            "concurrent-world",
            sourceRowId(tenant),
            "Concurrent World");
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<WorldAuthoredSourceIntakeReceipt> first =
          executor.submit(
              () -> acceptWhenReleased(transactionTemplate, ready, start, requestId, source));
      Future<WorldAuthoredSourceIntakeReceipt> second =
          executor.submit(
              () -> acceptWhenReleased(transactionTemplate, ready, start, requestId, source));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      WorldAuthoredSourceIntakeReceipt firstReceipt = first.get(20, TimeUnit.SECONDS);
      WorldAuthoredSourceIntakeReceipt secondReceipt = second.get(20, TimeUnit.SECONDS);
      assertThat(firstReceipt).isEqualTo(secondReceipt);
      assertThat(read(requestId)).contains(firstReceipt);
    }
  }

  @Test
  void concurrentLegacyNumericClaimSerializesBeforeFreshSelectorAllocation() throws Exception {
    long legacyTenantKey = 6_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000L);
    UUID tenant = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    AuthoredWorldSourceEvidence source =
        source(
            tenant,
            tenantSlug(tenant),
            "concurrent-numeric-allocation-world",
            sourceRowId(tenant),
            "Concurrent Numeric Allocation World");
    TransactionTemplate legacyTransaction = new TransactionTemplate(transactionManager);
    TransactionTemplate intakeTransaction = new TransactionTemplate(transactionManager);
    CountDownLatch legacyInserted = new CountDownLatch(1);
    CountDownLatch allowLegacyCommit = new CountDownLatch(1);
    CountDownLatch intakeStarted = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<?> legacyWrite =
          executor.submit(
              () ->
                  legacyTransaction.execute(
                      status -> {
                        dsl.execute(
                            "INSERT INTO region (tenant_id, name) VALUES (?, ?)",
                            legacyTenantKey,
                            "concurrent-legacy-selector");
                        legacyInserted.countDown();
                        await(allowLegacyCommit, 20);
                        return null;
                      }));
      assertThat(legacyInserted.await(5, TimeUnit.SECONDS)).isTrue();

      Future<WorldAuthoredSourceIntakeReceipt> intake =
          executor.submit(
              () -> {
                intakeStarted.countDown();
                return intakeTransaction.execute(
                    status -> repository.acceptFresh(NAMESPACE, requestId, source));
              });
      assertThat(intakeStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitAllocatorLockWait();
      allowLegacyCommit.countDown();

      legacyWrite.get(20, TimeUnit.SECONDS);
      WorldAuthoredSourceIntakeReceipt receipt = intake.get(20, TimeUnit.SECONDS);
      assertThat(receipt.localTenantKey()).isGreaterThan(legacyTenantKey);
      assertThat(
              Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT COUNT(*) FROM region WHERE tenant_id = ?", legacyTenantKey),
                      "legacy region count query returned no row")
                  .get(0, Long.class))
          .isEqualTo(1L);
      assertThat(
              Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT COUNT(*) FROM world_authored_source_tenant_key_reservation "
                              + "WHERE tenant_key = ? AND claim_kind = 'LEGACY_NUMERIC'",
                          legacyTenantKey),
                      "legacy tenant-key reservation count query returned no row")
                  .get(0, Long.class))
          .isEqualTo(1L);
      assertThat(countForCanonicalTenant(tenant)).isEqualTo(1L);
      assertThat(read(requestId)).contains(receipt);
    }
  }

  @Test
  void concurrentLegacyClaimAfterAllocationCannotTakeTheReservedSelector() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    AuthoredWorldSourceEvidence source =
        source(
            tenant,
            tenantSlug(tenant),
            "concurrent-reserved-selector-world",
            sourceRowId(tenant),
            "Concurrent Reserved Selector World");
    TransactionTemplate ownerTransaction = new TransactionTemplate(transactionManager);
    TransactionTemplate legacyTransaction = new TransactionTemplate(transactionManager);
    CountDownLatch intakePrepared = new CountDownLatch(1);
    CountDownLatch allowIntakeCommit = new CountDownLatch(1);
    AtomicReference<WorldAuthoredSourceIntakeReceipt> preparedReceipt = new AtomicReference<>();

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<WorldAuthoredSourceIntakeReceipt> intake =
          executor.submit(
              () ->
                  ownerTransaction.execute(
                      status -> {
                        WorldAuthoredSourceIntakeReceipt receipt =
                            repository.acceptFresh(NAMESPACE, requestId, source);
                        preparedReceipt.set(receipt);
                        intakePrepared.countDown();
                        await(allowIntakeCommit, 20);
                        return receipt;
                      }));
      assertThat(intakePrepared.await(5, TimeUnit.SECONDS)).isTrue();
      WorldAuthoredSourceIntakeReceipt receipt = preparedReceipt.get();
      assertThat(receipt).isNotNull();

      Future<Boolean> legacyWrite =
          executor.submit(
              () -> {
                try {
                  legacyTransaction.execute(
                      status -> {
                        dsl.execute(
                            "INSERT INTO region (tenant_id, name) VALUES (?, ?)",
                            receipt.localTenantKey(),
                            "cannot-race-private-selector");
                        return null;
                      });
                  return true;
                } catch (DataIntegrityViolationException expectedReservedKeyRejection) {
                  assertReservedCanonicalSelectorRejected(expectedReservedKeyRejection);
                  return false;
                }
              });

      awaitLegacyWriterLockWait();
      allowIntakeCommit.countDown();
      assertThat(intake.get(20, TimeUnit.SECONDS)).isEqualTo(receipt);
      assertThat(legacyWrite.get(20, TimeUnit.SECONDS)).isFalse();
      assertThat(read(requestId)).contains(receipt);
      assertThat(countForCanonicalTenant(tenant)).isEqualTo(1L);
      assertThat(
              Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT COUNT(*) FROM region WHERE tenant_id = ?",
                          receipt.localTenantKey()),
                      "canonical-selector region count query returned no row")
                  .get(0, Long.class))
          .isZero();
    }
  }

  private static void migrateFixture(String schema, MigrationVersion target) {
    var configuration =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private static long seedCanonicalRegionBeforeGuard(
      String schema, UUID canonicalTenantId, long canonicalTenantKey) throws SQLException {
    try (Connection connection = fixtureConnection(schema);
        Statement controls = connection.createStatement()) {
      // Test-only bypass in this isolated V23 schema; restore triggers before applying V24.
      controls.execute("SET session_replication_role = replica");
      try {
        String tenantSlug = tenantSlug(canonicalTenantId);
        String sourceKey = "src-" + canonicalTenantId.toString().replace("-", "");
        try (PreparedStatement association =
            connection.prepareStatement(
                "INSERT INTO world_authored_source_tenant_association "
                    + "(target_namespace, canonical_tenant_id, tenant_slug, "
                    + "source_game_row_id, source_game_tenant_key, "
                    + "source_provenance_kind, local_tenant_key) "
                    + "VALUES (?, ?, ?, ?, ?, 'NEW_GAME_ROW', ?)")) {
          association.setString(1, NAMESPACE);
          association.setObject(2, canonicalTenantId);
          association.setString(3, tenantSlug);
          association.setLong(4, sourceRowId(canonicalTenantId));
          association.setString(5, sourceKey);
          association.setLong(6, canonicalTenantKey);
          association.executeUpdate();
        }
        try (PreparedStatement reservation =
            connection.prepareStatement(
                "INSERT INTO world_authored_source_tenant_key_reservation "
                    + "(tenant_key, claim_kind, target_namespace, canonical_tenant_id) "
                    + "VALUES (?, 'CANONICAL_AUTHORED_SOURCE', ?, ?)")) {
          reservation.setLong(1, canonicalTenantKey);
          reservation.setString(2, NAMESPACE);
          reservation.setObject(3, canonicalTenantId);
          reservation.executeUpdate();
        }
        try (PreparedStatement region =
            connection.prepareStatement(
                "INSERT INTO region (tenant_id, name) VALUES (?, ?) RETURNING id")) {
          region.setLong(1, canonicalTenantKey);
          region.setString(2, "test-only canonical row");
          try (ResultSet result = region.executeQuery()) {
            if (!result.next()) {
              throw new SQLException("Test-only canonical region seed returned no row");
            }
            return result.getLong(1);
          }
        }
      } finally {
        controls.execute("SET session_replication_role = origin");
      }
    }
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

  private static void assertProtectedReservationTriggerCatalog(Connection connection)
      throws SQLException {
    Set<String> actualTables = new HashSet<>();
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT table_row.relname, trigger_row.tgname, function_row.proname, "
                    + "trigger_row.tgtype, trigger_row.tgenabled "
                    + "FROM pg_trigger AS trigger_row "
                    + "JOIN pg_class AS table_row ON table_row.oid = trigger_row.tgrelid "
                    + "JOIN pg_namespace AS namespace_row "
                    + "ON namespace_row.oid = table_row.relnamespace "
                    + "JOIN pg_proc AS function_row ON function_row.oid = trigger_row.tgfoid "
                    + "WHERE namespace_row.nspname = current_schema() "
                    + "AND trigger_row.tgname LIKE 'trg_reserve_%_tenant_key' "
                    + "AND NOT trigger_row.tgisinternal")) {
      while (result.next()) {
        String table = result.getString(1);
        actualTables.add(table);
        assertThat(result.getString(2)).isEqualTo("trg_reserve_" + table + "_tenant_key");
        assertThat(result.getString(3)).isEqualTo("world_claim_legacy_numeric_tenant_key");
        assertThat(result.getInt(4)).isEqualTo(31);
        assertThat(result.getString(5)).isEqualTo("O");
      }
    }
    assertThat(actualTables).containsExactlyInAnyOrderElementsOf(PROTECTED_TENANT_TABLES);
  }

  private static void assertProtectedTenantTruncateTriggerCatalog(Connection connection)
      throws SQLException {
    Set<String> actualTables = new HashSet<>();
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT table_row.relname, trigger_row.tgname, trigger_row.tgtype, "
                    + "trigger_row.tgenabled "
                    + "FROM pg_trigger AS trigger_row "
                    + "JOIN pg_class AS table_row ON table_row.oid = trigger_row.tgrelid "
                    + "JOIN pg_namespace AS namespace_row "
                    + "ON namespace_row.oid = table_row.relnamespace "
                    + "JOIN pg_proc AS function_row ON function_row.oid = trigger_row.tgfoid "
                    + "WHERE namespace_row.nspname = current_schema() "
                    + "AND function_row.proname = 'world_reject_tenant_table_truncate' "
                    + "AND NOT trigger_row.tgisinternal")) {
      while (result.next()) {
        String table = result.getString(1);
        actualTables.add(table);
        assertThat(result.getString(2)).isEqualTo("trg_" + table + "_no_truncate");
        assertThat(result.getInt(3)).isEqualTo(34);
        assertThat(result.getString(4)).isEqualTo("O");
      }
    }
    assertThat(actualTables).containsExactlyInAnyOrderElementsOf(PROTECTED_TENANT_TABLES);
  }

  private static void truncateTenantTable(Connection connection, String table) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE " + table + " CASCADE");
    }
  }

  private static void insertTenantKeyRow(Connection connection, String table, long tenantKey)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("INSERT INTO " + table + " (tenant_id) VALUES (?)")) {
      statement.setLong(1, tenantKey);
      statement.executeUpdate();
    }
  }

  private static long insertRegion(Connection connection, long tenantKey, String name)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO region (tenant_id, name) VALUES (?, ?) RETURNING id")) {
      statement.setLong(1, tenantKey);
      statement.setString(2, name);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("Region fixture insert returned no row");
        }
        return result.getLong(1);
      }
    }
  }

  private static void updateRegion(
      Connection connection, long regionId, long tenantKey, String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("UPDATE region SET tenant_id = ?, name = ? WHERE id = ?")) {
      statement.setLong(1, tenantKey);
      statement.setString(2, name);
      statement.setLong(3, regionId);
      statement.executeUpdate();
    }
  }

  private static void updateRegionPayload(Connection connection, long regionId, String name)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("UPDATE region SET name = ? WHERE id = ?")) {
      statement.setString(1, name);
      statement.setLong(2, regionId);
      statement.executeUpdate();
    }
  }

  private static void deleteRegion(Connection connection, long regionId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM region WHERE id = ?")) {
      statement.setLong(1, regionId);
      statement.executeUpdate();
    }
  }

  private static long countRowsForTenant(Connection connection, String table, long tenantKey)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?")) {
      statement.setLong(1, tenantKey);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private static String readRegionName(Connection connection, long regionId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT name FROM region WHERE id = ?")) {
      statement.setLong(1, regionId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("Expected region fixture row was missing");
        }
        return result.getString(1);
      }
    }
  }

  private static void assertCanonicalRegion(
      Connection connection, long regionId, long tenantKey, String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT tenant_id, name FROM region WHERE id = ?")) {
      statement.setLong(1, regionId);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getLong(1)).isEqualTo(tenantKey);
        assertThat(result.getString(2)).isEqualTo(name);
      }
    }
  }

  private static String readReservationClaimKind(Connection connection, long tenantKey)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT claim_kind FROM world_authored_source_tenant_key_reservation "
                + "WHERE tenant_key = ?")) {
      statement.setLong(1, tenantKey);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getString(1) : null;
      }
    }
  }

  private static long countCanonicalAssociations(Connection connection, UUID canonicalTenantId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT COUNT(*) FROM world_authored_source_tenant_association "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ?")) {
      statement.setString(1, NAMESPACE);
      statement.setObject(2, canonicalTenantId);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private static void assertSqlState(String expected, SqlAction action) throws SQLException {
    SQLException rejected = null;
    try {
      action.execute();
    } catch (SQLException exception) {
      rejected = exception;
    }
    if (rejected == null) {
      throw new AssertionError("Expected PostgreSQL rejection with SQLSTATE " + expected);
    }
    assertThat(rejected.getSQLState()).isEqualTo(expected);
  }

  @FunctionalInterface
  private interface SqlAction {
    void execute() throws SQLException;
  }

  private WorldAuthoredSourceIntakeReceipt accept(
      UUID requestId, AuthoredWorldSourceEvidence source) {
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    return transactionTemplate.execute(
        status -> repository.acceptFresh(NAMESPACE, requestId, source));
  }

  private WorldAuthoredSourceIntakeReceipt acceptWhenReleased(
      TransactionTemplate transactionTemplate,
      CountDownLatch ready,
      CountDownLatch start,
      UUID requestId,
      AuthoredWorldSourceEvidence source) {
    ready.countDown();
    await(start);
    return transactionTemplate.execute(
        status -> repository.acceptFresh(NAMESPACE, requestId, source));
  }

  private java.util.Optional<WorldAuthoredSourceIntakeReceipt> read(UUID requestId) {
    return repository.read(NAMESPACE, requestId);
  }

  private long countForCanonicalTenant(UUID tenantId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT COUNT(*) FROM world_authored_source_tenant_association WHERE "
                    + "target_namespace = ? AND canonical_tenant_id = ?",
                NAMESPACE,
                tenantId),
            "canonical tenant association count query returned no row")
        .get(0, Long.class);
  }

  private void assertReservedCanonicalSelectorRejected(Throwable throwable) {
    assertThat(throwable).hasStackTraceContaining("is reserved for a canonical authored source");

    Throwable cause = throwable;
    while (cause != null && !(cause instanceof SQLException)) {
      cause = cause.getCause();
    }
    assertThat(cause).isInstanceOf(SQLException.class);
    assertThat(((SQLException) cause).getSQLState()).isEqualTo("23514");
  }

  private long countReservationsForCanonicalTenant(UUID tenantId) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT COUNT(*) FROM world_authored_source_tenant_key_reservation "
                    + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
                NAMESPACE,
                tenantId),
            "canonical tenant-key reservation count query returned no row")
        .get(0, Long.class);
  }

  private void awaitAllocatorLockWait() {
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadlineNanos) {
      Long waiting =
          Objects.requireNonNull(
                  dsl.fetchOne(
                      "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() "
                          + "AND wait_event_type = 'Lock' "
                          + "AND lower(query) LIKE 'lock table generation_rule%'"),
                  "allocator lock-wait query returned no row")
              .get(0, Long.class);
      if (waiting != null && waiting > 0L) {
        return;
      }
      try {
        TimeUnit.MILLISECONDS.sleep(25);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Interrupted while observing PostgreSQL lock wait", exception);
      }
    }
    throw new IllegalStateException(
        "Fresh intake did not wait for the retained tenant-table writer");
  }

  private void awaitLegacyWriterLockWait() {
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadlineNanos) {
      Long waiting =
          Objects.requireNonNull(
                  dsl.fetchOne(
                      "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() "
                          + "AND wait_event_type = 'Lock' "
                          + "AND lower(query) LIKE 'insert into region%'"),
                  "legacy writer lock-wait query returned no row")
              .get(0, Long.class);
      if (waiting != null && waiting > 0L) {
        return;
      }
      try {
        TimeUnit.MILLISECONDS.sleep(25);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Interrupted while observing PostgreSQL legacy-writer lock wait", exception);
      }
    }
    throw new IllegalStateException(
        "Legacy numeric writer did not wait for the fresh intake owner transaction");
  }

  private static final class ForcedOwnerRollbackException extends RuntimeException {
    private ForcedOwnerRollbackException() {
      super("Force the test owner transaction to roll back");
    }
  }

  private static AuthoredWorldSourceEvidence source(
      UUID tenantId, String tenantSlug, String worldSlug, long sourceRowId, String displayName) {
    return source(tenantId, tenantSlug, worldSlug, sourceRowId, displayName, "NEW_GAME_ROW");
  }

  private static String tenantSlug(UUID tenantId) {
    return "tenant-" + tenantId.toString().replace("-", "");
  }

  private static long sourceRowId(UUID tenantId) {
    long positive = tenantId.getLeastSignificantBits() & Long.MAX_VALUE;
    return positive == 0L ? 1L : positive;
  }

  private static AuthoredWorldSourceEvidence source(
      UUID tenantId,
      String tenantSlug,
      String worldSlug,
      long sourceRowId,
      String displayName,
      String provenance) {
    UUID registrationId = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    String sourceGameTenantKey = "gd-row-" + sourceRowId;
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationId, tenantId, tenantSlug, worldSlug, displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationId,
            sourceOperationId,
            requestDigest,
            tenantId,
            tenantSlug,
            worldSlug,
            displayName,
            sourceRowId,
            sourceGameTenantKey,
            provenance);
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationId,
        sourceOperationId,
        requestDigest,
        tenantId,
        tenantSlug,
        worldSlug,
        displayName,
        sourceRowId,
        sourceGameTenantKey,
        provenance,
        evidenceDigest);
  }

  private static void await(CountDownLatch latch) {
    await(latch, 5);
  }

  private static void await(CountDownLatch latch, long timeoutSeconds) {
    try {
      if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent intake test gate");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent intake test was interrupted", exception);
    }
  }
}
