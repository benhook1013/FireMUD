package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationReadback;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerReservationFenceEvidence;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayAccountAdmissionBarrierRepositoryIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID OTHER_ACCOUNT_ID =
      UUID.fromString("12121212-1212-4212-8212-121212121212");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID NAMESPACE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID OTHER_TENANT_ID =
      UUID.fromString("23232323-2323-4323-8323-232323232323");
  private static final UUID OTHER_NAMESPACE_ID =
      UUID.fromString("34343434-3434-4434-8434-343434343434");
  private static final UUID GAME_INSTANCE_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID REGION_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void intentRetryRestartAndNewPrepareUseOneFrozenActualOwnerCohort() {
    TestSchema testSchema = newTestSchema("gs_account_fence_retry_");
    try {
      establishActualLegacyDisposition(testSchema.dsl());
      CanonicalGameplayBindingInventoryRepository inventory =
          new CanonicalGameplayBindingInventoryRepository(testSchema.dsl());
      var first = prepare(inventory, ACCOUNT_ID, TENANT_ID, NAMESPACE_ID, 1);
      var owner = new CanonicalGameplayAccountAdmissionBarrierRepository(testSchema.dsl());
      UUID requestId = UUID.randomUUID();

      var intent = owner.beginFenceIntent(ACCOUNT_ID, requestId);

      assertThat(intent.state())
          .isEqualTo(CanonicalGameplayAccountAdmissionFenceIntent.State.ACQUISITION_INTENT);
      assertThat(intent.operationFence()).isEqualTo(BigInteger.ONE);
      assertThat(intent.inventorySnapshotRevision()).isEqualTo(first.inventoryRevision());
      var currentByOperationId = owner.readCurrentFenceIntent(intent.operationId());
      assertThat(currentByOperationId.operationId()).isEqualTo(intent.operationId());
      assertThat(currentByOperationId.requestId()).isEqualTo(requestId);
      assertThat(currentByOperationId.cohortDigest()).isEqualTo(intent.cohortDigest());
      assertThat(intent.cohortRows())
          .extracting(CanonicalGameplayAccountAdmissionFenceIntent.CohortRow::kind)
          .contains(
              CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.BINDING,
              CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.TRANSITION,
              CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.RESERVATION,
              CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.ACCOUNT_OBLIGATION,
              CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.ISSUER_OBLIGATION,
              CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.REGION_OBLIGATION);
      assertThat(intent.barrierTargets())
          .singleElement()
          .satisfies(
              target -> {
                assertThat(target.tenantId()).isEqualTo(TENANT_ID);
                assertThat(target.playableStateNamespaceId()).isEqualTo(NAMESPACE_ID);
              });
      assertNoInstalledOrCoverageClaim(testSchema.dsl(), intent);
      var originalCohortRow = intent.cohortRows().get(0);
      assertThatThrownBy(
              () ->
                  testSchema
                      .dsl()
                      .execute(
                          "INSERT INTO"
                              + " game_session_canonical_account_admission_fence_intent_cohort_row"
                              + " (operation_id, account_id, row_ordinal, source_kind, source_key,"
                              + " tenant_id, playable_state_namespace_id, source_row)"
                              + " VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))",
                          intent.operationId(),
                          intent.accountId(),
                          intent.cohortRows().size() + 1L,
                          originalCohortRow.kind().name(),
                          originalCohortRow.sourceKey(),
                          originalCohortRow.tenantId(),
                          originalCohortRow.playableStateNamespaceId(),
                          originalCohortRow.sourceRecordJson()))
          .isInstanceOfSatisfying(
              org.jooq.exception.DataAccessException.class,
              failure -> assertThat(failure.sqlState()).isEqualTo("23505"));
      var afterDuplicateCohortInsert = owner.readFenceIntent(ACCOUNT_ID, requestId);
      assertThat(afterDuplicateCohortInsert.cohortRows()).hasSameSizeAs(intent.cohortRows());
      assertThat(afterDuplicateCohortInsert.cohortDigest()).isEqualTo(intent.cohortDigest());
      assertThat(afterDuplicateCohortInsert.barrierTargetDigest())
          .isEqualTo(intent.barrierTargetDigest());
      assertCheckConstraintViolation(
          testSchema.dsl(),
          "UPDATE game_session_canonical_account_coverage_control"
              + " SET current_operation_fence = NULL WHERE account_id = ?",
          ACCOUNT_ID);
      assertCheckConstraintViolation(
          testSchema.dsl(),
          "UPDATE game_session_canonical_account_coverage_control"
              + " SET inventory_snapshot_revision = NULL WHERE account_id = ?",
          ACCOUNT_ID);
      var afterRejectedNulls = owner.readFenceIntent(ACCOUNT_ID, requestId);
      assertThat(afterRejectedNulls.operationId()).isEqualTo(intent.operationId());
      assertThat(afterRejectedNulls.cohortDigest()).isEqualTo(intent.cohortDigest());
      assertThat(afterRejectedNulls.barrierTargetDigest()).isEqualTo(intent.barrierTargetDigest());
      assertNoInstalledOrCoverageClaim(testSchema.dsl(), afterRejectedNulls);

      // A later unrelated account write advances the global clock but cannot replace this account's
      // original frozen cohort or tenant/namespace target list.
      prepare(inventory, OTHER_ACCOUNT_ID, OTHER_TENANT_ID, OTHER_NAMESPACE_ID, 3);
      var restartedOwner = new CanonicalGameplayAccountAdmissionBarrierRepository(testSchema.dsl());
      var retried = restartedOwner.beginFenceIntent(ACCOUNT_ID, requestId);
      var read = restartedOwner.readFenceIntent(ACCOUNT_ID, requestId);

      assertThat(retried.operationId()).isEqualTo(intent.operationId());
      assertThat(retried.operationFence()).isEqualTo(intent.operationFence());
      assertThat(retried.plannedAccountAdmissionFence())
          .isEqualTo(intent.plannedAccountAdmissionFence());
      assertThat(retried.cohortDigest()).isEqualTo(intent.cohortDigest());
      assertThat(retried.barrierTargetDigest()).isEqualTo(intent.barrierTargetDigest());
      assertThat(read.operationId()).isEqualTo(intent.operationId());
      assertThat(read.cohortRows()).hasSameSizeAs(intent.cohortRows());
      assertThat(read.cohortDigest()).isEqualTo(intent.cohortDigest());
      assertNoInstalledOrCoverageClaim(testSchema.dsl(), read);

      var beforeRejectedPrepare = inventory.readSnapshot();
      assertThatThrownBy(() -> prepare(inventory, ACCOUNT_ID, TENANT_ID, NAMESPACE_ID, 2))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining("Account-fence acquisition intent blocks a new binding transition");
      assertThat(inventory.readSnapshot()).isEqualTo(beforeRejectedPrepare);
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void intentCapturesEveryExistingTenantNamespaceForTheAccountOnly() {
    TestSchema testSchema = newTestSchema("gs_account_fence_tenants_");
    try {
      establishActualLegacyDisposition(testSchema.dsl());
      CanonicalGameplayBindingInventoryRepository inventory =
          new CanonicalGameplayBindingInventoryRepository(testSchema.dsl());
      prepare(inventory, ACCOUNT_ID, TENANT_ID, NAMESPACE_ID, 1);
      prepare(inventory, ACCOUNT_ID, OTHER_TENANT_ID, OTHER_NAMESPACE_ID, 2);
      prepare(inventory, OTHER_ACCOUNT_ID, OTHER_TENANT_ID, OTHER_NAMESPACE_ID, 3);
      var owner = new CanonicalGameplayAccountAdmissionBarrierRepository(testSchema.dsl());

      var intent = owner.beginFenceIntent(ACCOUNT_ID, UUID.randomUUID());
      var otherAccountIntent = owner.beginFenceIntent(OTHER_ACCOUNT_ID, UUID.randomUUID());

      assertThat(intent.barrierTargets())
          .extracting(
              CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget::tenantId,
              CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget::playableStateNamespaceId)
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple(TENANT_ID, NAMESPACE_ID),
              org.assertj.core.groups.Tuple.tuple(OTHER_TENANT_ID, OTHER_NAMESPACE_ID));
      assertThat(intent.cohortRows())
          .filteredOn(
              row -> row.kind() == CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.BINDING)
          .hasSize(2);
      assertThat(otherAccountIntent.accountId()).isEqualTo(OTHER_ACCOUNT_ID);
      assertThat(otherAccountIntent.barrierTargets())
          .singleElement()
          .satisfies(
              target -> {
                assertThat(target.tenantId()).isEqualTo(OTHER_TENANT_ID);
                assertThat(target.playableStateNamespaceId()).isEqualTo(OTHER_NAMESPACE_ID);
              });
      assertThat(otherAccountIntent.cohortRows())
          .filteredOn(
              row -> row.kind() == CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.BINDING)
          .hasSize(1);
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void preparationRacingIntentIsEitherCapturedOrRefused() throws Exception {
    TestSchema testSchema = newTestSchema("gs_account_fence_race_");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      establishActualLegacyDisposition(testSchema.dsl());
      var inventory = new CanonicalGameplayBindingInventoryRepository(testSchema.dsl());
      prepare(inventory, ACCOUNT_ID, TENANT_ID, NAMESPACE_ID, 1);
      var owner = new CanonicalGameplayAccountAdmissionBarrierRepository(testSchema.dsl());
      CountDownLatch start = new CountDownLatch(1);
      Future<CanonicalGameplayBindingTransitionSnapshot> prepareFuture =
          executor.submit(
              () -> {
                start.await();
                return prepare(inventory, ACCOUNT_ID, OTHER_TENANT_ID, OTHER_NAMESPACE_ID, 2);
              });
      Future<CanonicalGameplayAccountAdmissionFenceIntent> intentFuture =
          executor.submit(
              () -> {
                start.await();
                return owner.beginFenceIntent(ACCOUNT_ID, UUID.randomUUID());
              });
      start.countDown();

      var intent = intentFuture.get(30, TimeUnit.SECONDS);
      CanonicalGameplayBindingTransitionSnapshot racedPrepare = null;
      try {
        racedPrepare = prepareFuture.get(30, TimeUnit.SECONDS);
      } catch (ExecutionException refusedAfterIntent) {
        assertThat(refusedAfterIntent.getCause())
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
            .hasMessageContaining(
                "Account-fence acquisition intent blocks a new binding transition");
      }

      if (racedPrepare != null) {
        UUID racedTransitionId = racedPrepare.transitionId();
        assertThat(intent.cohortRows())
            .anySatisfy(
                row -> assertThat(row.sourceRecordJson()).contains(racedTransitionId.toString()));
        assertThat(intent.barrierTargets())
            .extracting(
                CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget::tenantId,
                CanonicalGameplayAccountAdmissionFenceIntent.BarrierTarget
                    ::playableStateNamespaceId)
            .contains(org.assertj.core.groups.Tuple.tuple(OTHER_TENANT_ID, OTHER_NAMESPACE_ID));
      }
      assertNoInstalledOrCoverageClaim(testSchema.dsl(), intent);
    } finally {
      executor.shutdownNow();
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void missingPositiveControlAndUnverifiedLegacyDispositionCannotStartIntent() {
    TestSchema testSchema = newTestSchema("gs_account_fence_missing_");
    try {
      var owner = new CanonicalGameplayAccountAdmissionBarrierRepository(testSchema.dsl());
      // The owner refuses revision zero before looking up the account control row. Establish the
      // actual complete-empty legacy disposition first so this assertion reaches the missing-row
      // gate rather than failing at the earlier inventory-clock precondition.
      establishActualLegacyDisposition(testSchema.dsl());

      assertThatThrownBy(() -> owner.beginFenceIntent(ACCOUNT_ID, UUID.randomUUID()))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining("Positive account-control row is missing");
      assertThat(
              testSchema
                  .dsl()
                  .fetchSingle(
                      "SELECT disposition_state FROM game_session_canonical_binding_legacy_disposition"
                          + " WHERE singleton_id = 1")
                  .get("disposition_state", String.class))
          .isEqualTo("VERIFIED");
      assertThat(
              testSchema
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) AS row_count FROM "
                          + "game_session_canonical_account_admission_fence_intent")
                  .get("row_count", Long.class))
          .isZero();

      var inventory = new CanonicalGameplayBindingInventoryRepository(testSchema.dsl());
      prepare(inventory, ACCOUNT_ID, TENANT_ID, NAMESPACE_ID, 1);
      testSchema
          .dsl()
          .execute(
              "UPDATE game_session_canonical_binding_legacy_disposition"
                  + " SET disposition_state = 'REQUIRED', cohort_id = NULL, owner_operation_id = NULL,"
                  + " legacy_writer_fence = NULL, source_snapshot_revision = NULL,"
                  + " canonical_inventory_revision = NULL, namespace_index_readback_revision = NULL,"
                  + " evidence_digest = NULL WHERE singleton_id = 1");
      assertThatThrownBy(() -> owner.beginFenceIntent(ACCOUNT_ID, UUID.randomUUID()))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining("VERIFIED legacy-index migration disposition");
      assertThat(
              testSchema
                  .dsl()
                  .fetchSingle(
                      "SELECT state FROM game_session_canonical_account_coverage_control"
                          + " WHERE account_id = ?",
                      ACCOUNT_ID)
                  .get("state", String.class))
          .isEqualTo("NO_ACTIVE_ACCOUNT_WIDE_FLOW");
      assertThat(
              testSchema
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) AS row_count FROM"
                          + " game_session_canonical_account_admission_fence_intent")
                  .get("row_count", Long.class))
          .isZero();
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  private static void assertNoInstalledOrCoverageClaim(
      DSLContext dsl, CanonicalGameplayAccountAdmissionFenceIntent intent) {
    var control =
        dsl.fetchSingle(
            "SELECT state, current_operation_id, current_fence_intent_id,"
                + " current_operation_fence, account_admission_fence, coverage_fence,"
                + " coverage_generation FROM game_session_canonical_account_coverage_control"
                + " WHERE account_id = ?",
            intent.accountId());
    assertThat(control.get("state", String.class)).isEqualTo("ACQUISITION_INTENT");
    assertThat(control.get("current_operation_id", UUID.class)).isNull();
    assertThat(control.get("current_fence_intent_id", UUID.class)).isEqualTo(intent.operationId());
    assertThat(control.get("current_operation_fence", BigDecimal.class).toBigIntegerExact())
        .isEqualTo(intent.operationFence());
    assertThat(control.get("account_admission_fence", UUID.class))
        .isEqualTo(intent.plannedAccountAdmissionFence());
    assertThat(control.get("coverage_fence", UUID.class)).isNull();
    assertThat(control.get("coverage_generation", BigDecimal.class)).isNull();
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) AS row_count FROM"
                        + " game_session_canonical_account_coverage_operation"
                        + " WHERE account_id = ?",
                    intent.accountId())
                .get("row_count", Long.class))
        .isZero();
  }

  private static CanonicalGameplayBindingTransitionSnapshot prepare(
      CanonicalGameplayBindingInventoryRepository repository,
      UUID accountId,
      UUID tenantId,
      UUID namespaceId,
      int number) {
    UUID characterId =
        UUID.nameUUIDFromBytes(("character-" + number).getBytes(StandardCharsets.UTF_8));
    UUID transitionId =
        UUID.nameUUIDFromBytes(("transition-" + number).getBytes(StandardCharsets.UTF_8));
    UUID reservationId =
        UUID.nameUUIDFromBytes(("reservation-" + number).getBytes(StandardCharsets.UTF_8));
    UUID reservationFence =
        UUID.nameUUIDFromBytes(("reservation-fence-" + number).getBytes(StandardCharsets.UTF_8));
    UUID issuerCoverageOperationId =
        UUID.nameUUIDFromBytes(("issuer-operation-" + number).getBytes(StandardCharsets.UTF_8));
    var candidate =
        new CanonicalGameplayBindingIdentity(
            accountId,
            tenantId,
            namespaceId,
            characterId,
            "SHARED",
            GAME_INSTANCE_ID,
            23L,
            UUID.nameUUIDFromBytes(("session-" + number).getBytes(StandardCharsets.UTF_8))
                .toString(),
            REGION_ID,
            BigInteger.valueOf(12),
            GameSessionAccountDelegationProfile.ISSUER,
            BigInteger.valueOf(7),
            BigInteger.valueOf(3),
            BigInteger.valueOf(16),
            BigInteger.valueOf(32));
    var reservation =
        new CanonicalIssuerReservationFenceEvidence(
            reservationId,
            reservationFence,
            issuerCoverageOperationId,
            BigInteger.ONE,
            BigInteger.ONE,
            BigInteger.ONE);
    return repository.prepare(
        new CanonicalGameplayBindingTransitionRequest(
            transitionId, UUID.randomUUID(), candidate, reservation, null, null));
  }

  private static void establishActualLegacyDisposition(DSLContext dsl) {
    var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
    var migration = new CanonicalGameplayLegacyMigrationRepository(dsl, inventory);
    UUID cohortId = UUID.randomUUID();
    UUID writerFence = UUID.randomUUID();
    var storageIdentity =
        new CanonicalGameplayLegacyMigrationStorageIdentity(
            "test-cluster-uid",
            "test-namespace-uid",
            "test-producer-pod-uid",
            "test-container-id",
            "test-node-uid",
            "123456789",
            42L,
            "test-postgres-volume-uid",
            "test-redis-run-id",
            "test-redis-volume-uid");
    var fencedCohort =
        new CanonicalGameplayLegacyMigrationOwner.FencedCohort() {
          @Override
          public UUID cohortId() {
            return cohortId;
          }

          @Override
          public UUID legacyWriterFence() {
            return writerFence;
          }

          @Override
          public CanonicalGameplayLegacyMigrationStorageIdentity storageIdentity() {
            return storageIdentity;
          }

          @Override
          public void requireStillFenced(
              CanonicalGameplayLegacyMigrationStorageIdentity expectedIdentity) {
            if (!storageIdentity.equals(expectedIdentity)) {
              throw new IllegalStateException("Test fixture storage identity changed");
            }
          }
        };
    var owner =
        new CanonicalGameplayLegacyMigrationOwner(
            migration,
            inventory,
            () -> fencedCohort,
            // Controlled complete-empty-source adapter: the actual legacy owner and SQL publisher
            // run, while physical Redis/storage incarnation evidence remains outside this test.
            ignored ->
                new CanonicalGameplayLegacyMigrationSourceSnapshot(
                    EnumSet.of(
                        CanonicalGameplayLegacyMigrationSourceSnapshot.Family
                            .TENANT_SESSION_CONTEXT,
                        CanonicalGameplayLegacyMigrationSourceSnapshot.Family.SESSION_ALIAS_CONTEXT,
                        CanonicalGameplayLegacyMigrationSourceSnapshot.Family
                            .GAMEPLAY_IDENTITY_CONTEXT,
                        CanonicalGameplayLegacyMigrationSourceSnapshot.Family.GAMEPLAY_NAME_CONTEXT,
                        CanonicalGameplayLegacyMigrationSourceSnapshot.Family.MOVEMENT_EFFECT,
                        CanonicalGameplayLegacyMigrationSourceSnapshot.Family.DURABLE_EFFECT),
                    List.of()),
            new CanonicalGameplayLegacyMigrationOwner.NamespaceIndexOwner() {
              @Override
              public void rebuildExact(
                  CanonicalGameplayLegacyMigrationOwner.FencedCohort ignoredCohort,
                  CanonicalGameplayLegacyMigrationSourceSnapshot source,
                  net.firedevops.firemud.gamesession.binding
                          .CanonicalGameplayBindingInventorySnapshot
                      canonical) {
                assertThat(canonical.bindings()).isEmpty();
                assertThat(canonical.transitions()).isEmpty();
                assertThat(canonical.reservations()).isEmpty();
              }

              @Override
              public CanonicalGameplayLegacyMigrationReadback readBackExact(
                  CanonicalGameplayLegacyMigrationOwner.FencedCohort ignoredCohort,
                  net.firedevops.firemud.gamesession.binding
                          .CanonicalGameplayBindingInventorySnapshot
                      canonical) {
                assertThat(canonical.bindings()).isEmpty();
                return new CanonicalGameplayLegacyMigrationReadback(
                    List.of(), List.of(), List.of(), List.of());
              }
            });
    assertThat(owner.migrate().state())
        .isEqualTo(CanonicalGameplayLegacyMigrationOperation.State.VERIFIED);
  }

  private static TestSchema newTestSchema(String prefix) {
    String schema = prefix + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    return new TestSchema(schema, dsl);
  }

  private static DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    String jdbcUrl = postgres.getJdbcUrl();
    String separator = jdbcUrl.contains("?") ? "&" : "?";
    dataSource.setUrl(jdbcUrl + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static void dropSchema(String schema) {
    try (Connection connection = dataSource("public").getConnection()) {
      DSL.using(connection, SQLDialect.POSTGRES)
          .execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    } catch (SQLException failure) {
      throw new IllegalStateException("Could not drop the owned test schema " + schema, failure);
    }
  }

  private static void assertCheckConstraintViolation(
      DSLContext dsl, String statement, Object... values) {
    assertThatThrownBy(() -> dsl.execute(statement, values))
        .isInstanceOfSatisfying(
            org.jooq.exception.DataAccessException.class,
            failure -> assertThat(failure.sqlState()).isEqualTo("23514"));
  }

  private record TestSchema(String schema, DSLContext dsl) {}
}
