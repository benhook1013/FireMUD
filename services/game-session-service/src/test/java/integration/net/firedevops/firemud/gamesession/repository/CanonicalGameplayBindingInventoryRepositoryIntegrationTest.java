package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.UUID;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerReservationFenceEvidence;
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
class CanonicalGameplayBindingInventoryRepositoryIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID NAMESPACE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID CHARACTER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID INSTANCE_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final long RUNTIME_GAME_INSTANCE_ID = 23L;
  private static final UUID REGION_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID ISSUER_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final UUID TRANSITION_ID = UUID.fromString("88888888-8888-4888-8888-888888888888");
  private static final UUID ACCOUNT_INDEX_FENCE =
      UUID.fromString("99999999-9999-4999-8999-999999999999");
  private static final UUID RESERVATION_ID =
      UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID RESERVATION_FENCE =
      UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID ISSUER_COVERAGE_OPERATION_ID =
      UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID PRIOR_RESERVATION_ID =
      UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
  private static final UUID PRIOR_RESERVATION_FENCE =
      UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
  private static final UUID PRIOR_TRANSITION_ID =
      UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void failsClosedWhenInventoryAbsenceCannotProveLegacyControllerAbsence() {
    String schema = "gs_binding_inventory_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      CanonicalGameplayBindingInventoryRepository repository =
          new CanonicalGameplayBindingInventoryRepository(dsl);
      assertThatThrownBy(() -> repository.prepare(request(candidateSession(1))))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining("legacy-index migration fence");

      var inventory = repository.readSnapshot();
      assertThat(inventory.inventoryRevision()).isZero();
      assertThat(inventory.bindings()).isEmpty();
      assertThat(inventory.transitions()).isEmpty();
      assertThat(inventory.reservations()).isEmpty();
      var generationCount =
          java.util.Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT count(*) AS row_count FROM game_session_canonical_binding_generation"),
              "binding generation count row");
      assertThat(generationCount.get("row_count", Long.class)).isZero();
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void preparesFirstBindingOnlyBehindVerifiedDispositionAndRetainsNoPriorShape() {
    String schema = "gs_binding_first_inventory_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      seedSyntheticVerifiedLegacyDisposition(dsl);
      CanonicalGameplayBindingInventoryRepository repository =
          new CanonicalGameplayBindingInventoryRepository(dsl);

      var prepared = repository.prepare(request(candidateSession(1)));
      var evidence = repository.readProvisionalCasEvidence(TRANSITION_ID);

      assertThat(prepared.bindingGeneration()).isEqualTo(BigInteger.ONE);
      assertThat(prepared.expectedPriorBindingRef()).isNull();
      assertThat(prepared.expectedPriorBindingGeneration()).isNull();
      assertThat(evidence.expectedPrior()).isNull();
      assertThat(evidence.expectedPriorReservation()).isNull();
      assertThat(evidence.initialBindingMigrationDisposition()).isNotNull();

      // This synthetic migration fixture exercises the repository guard, not live migration proof.
      var beforeLeaseFreeAttempt = repository.readSnapshot();
      assertThatThrownBy(() -> repository.markProvisional(evidence))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining("requires the exact Account lease decision");
      var afterLeaseFreeAttempt = repository.readSnapshot();
      assertThat(afterLeaseFreeAttempt.inventoryRevision())
          .isEqualTo(beforeLeaseFreeAttempt.inventoryRevision());
      assertThat(afterLeaseFreeAttempt.bindings())
          .singleElement()
          .satisfies(
              candidate ->
                  assertThat(candidate.lifecycle())
                      .isEqualTo(
                          net.firedevops.firemud.gamesession.binding
                              .CanonicalGameplayBindingInventoryEntry.Lifecycle
                              .CANDIDATE_PREPARED));
      var storedDecision =
          dsl.fetchOne(
              "SELECT admission_request_id, admission_lease_id"
                  + " FROM game_session_canonical_binding_transition WHERE transition_id = ?",
              TRANSITION_ID);
      assertThat(storedDecision).isNotNull();
      assertThat(storedDecision.get("admission_request_id", UUID.class)).isNull();
      assertThat(storedDecision.get("admission_lease_id", UUID.class)).isNull();

      assertThat(repository.readSnapshot().accountIndexObligations())
          .singleElement()
          .satisfies(obligation -> assertThat(obligation.expectedPriorGeneration()).isNull());
      assertThat(repository.readSnapshot().bindings())
          .singleElement()
          .satisfies(
              candidate ->
                  assertThat(candidate.lifecycle())
                      .isEqualTo(
                          net.firedevops.firemud.gamesession.binding
                              .CanonicalGameplayBindingInventoryEntry.Lifecycle
                              .CANDIDATE_PREPARED));
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void unresolvedPreparedTransitionFencesASecondTransitionForTheSameController() {
    String schema = "gs_binding_pending_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      CanonicalGameplayBindingInventoryRepository repository =
          new CanonicalGameplayBindingInventoryRepository(dsl);
      CanonicalGameplayBindingIdentity prior = identity(sourceSession());
      seedActiveSource(dsl, prior);
      repository.prepare(transferRequest(candidateSession(1), prior));

      CanonicalGameplayBindingTransitionRequest otherOperation =
          new CanonicalGameplayBindingTransitionRequest(
              UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
              UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"),
              identity(candidateSession(2)),
              reservation(UUID.fromString("abababab-abab-4bab-8bab-abababababab")),
              prior.bindingRef(),
              BigInteger.valueOf(5));
      assertThatThrownBy(() -> repository.prepare(otherOperation))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
      assertThat(repository.readSnapshot().inventoryRevision()).isEqualTo(BigInteger.valueOf(9));
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void advancesGenerationAtomicallyAndKeepsPriorControllerActiveUntilExternalCas() {
    String schema = "gs_binding_generation_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      CanonicalGameplayBindingIdentity prior = identity(sourceSession());
      seedActiveSource(dsl, prior);

      var prepared =
          new CanonicalGameplayBindingInventoryRepository(dsl)
              .prepare(
                  new CanonicalGameplayBindingTransitionRequest(
                      TRANSITION_ID,
                      ACCOUNT_INDEX_FENCE,
                      identity(candidateSession(2)),
                      reservation(RESERVATION_FENCE),
                      prior.bindingRef(),
                      BigInteger.valueOf(5)));

      CanonicalGameplayBindingTransitionRequest changedRetry =
          new CanonicalGameplayBindingTransitionRequest(
              TRANSITION_ID,
              UUID.fromString("abababab-abab-4bab-8bab-abababababab"),
              identity(candidateSession(2)),
              reservation(RESERVATION_FENCE),
              prior.bindingRef(),
              BigInteger.valueOf(5));
      assertThatThrownBy(
              () -> new CanonicalGameplayBindingInventoryRepository(dsl).prepare(changedRetry))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
      assertThat(
              new CanonicalGameplayBindingInventoryRepository(dsl)
                  .readSnapshot()
                  .inventoryRevision())
          .isEqualTo(BigInteger.valueOf(9));

      assertThat(prepared.bindingGeneration()).isEqualTo(BigInteger.valueOf(6));
      assertThat(prepared.inventoryRevision()).isEqualTo(BigInteger.valueOf(9));
      var snapshot = new CanonicalGameplayBindingInventoryRepository(dsl).readSnapshot();
      assertThat(snapshot.bindings()).hasSize(2);
      assertThat(snapshot.bindings())
          .anySatisfy(
              row ->
                  assertThat(row.lifecycle())
                      .isEqualTo(
                          net.firedevops.firemud.gamesession.binding
                              .CanonicalGameplayBindingInventoryEntry.Lifecycle.ACTIVE));
      assertThat(snapshot.bindings())
          .anySatisfy(
              row ->
                  assertThat(row.lifecycle())
                      .isEqualTo(
                          net.firedevops.firemud.gamesession.binding
                              .CanonicalGameplayBindingInventoryEntry.Lifecycle
                              .CANDIDATE_PREPARED));
      assertThat(snapshot.accountIndexObligations()).hasSize(2);
      assertThat(snapshot.accountIndexObligations().get(0).expectedPriorGeneration())
          .isEqualTo(BigInteger.valueOf(5));
      assertThat(snapshot.accountIndexObligations().get(1).executionPhase())
          .isEqualTo(
              net.firedevops.firemud.gamesession.binding
                  .CanonicalGameplayBindingAccountIndexObligation.ExecutionPhase.AFTER_FINAL_CAS);
    } finally {
      dropSchema(schema);
    }
  }

  private static CanonicalGameplayBindingTransitionRequest request(String sessionId) {
    return new CanonicalGameplayBindingTransitionRequest(
        TRANSITION_ID,
        ACCOUNT_INDEX_FENCE,
        identity(sessionId),
        reservation(RESERVATION_FENCE),
        null,
        null);
  }

  private static CanonicalGameplayBindingTransitionRequest transferRequest(
      String candidateSessionId, CanonicalGameplayBindingIdentity prior) {
    return new CanonicalGameplayBindingTransitionRequest(
        TRANSITION_ID,
        ACCOUNT_INDEX_FENCE,
        identity(candidateSessionId),
        reservation(RESERVATION_FENCE),
        prior.bindingRef(),
        BigInteger.valueOf(5));
  }

  static CanonicalGameplayBindingIdentity identity(String sessionId) {
    return new CanonicalGameplayBindingIdentity(
        ACCOUNT_ID,
        TENANT_ID,
        NAMESPACE_ID,
        CHARACTER_ID,
        "SHARED",
        INSTANCE_ID,
        RUNTIME_GAME_INSTANCE_ID,
        sessionId,
        REGION_ID,
        BigInteger.valueOf(12),
        ISSUER_ID,
        BigInteger.valueOf(7),
        BigInteger.valueOf(3),
        BigInteger.valueOf(16),
        BigInteger.valueOf(32));
  }

  private static String sourceSession() {
    return "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
  }

  private static String candidateSession(int number) {
    return number == 1
        ? "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"
        : "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2";
  }

  static void seedActiveSource(DSLContext dsl, CanonicalGameplayBindingIdentity prior) {
    byte[] priorRef = prior.bindingRef().bytes();
    BigInteger priorPartition = prior.bindingRef().partitionId(prior.issuerIndexPartitionCount());
    UUID priorAccountFence = UUID.fromString("12121212-1212-4212-8212-121212121212");
    dsl.execute(
        "UPDATE game_session_canonical_binding_inventory_clock SET inventory_revision = 8"
            + " WHERE singleton_id = 1");
    dsl.execute(
        "INSERT INTO game_session_canonical_binding_generation"
            + " (tenant_id, playable_state_namespace_id, character_id, last_issued_generation)"
            + " VALUES (?, ?, ?, 5)",
        TENANT_ID,
        NAMESPACE_ID,
        CHARACTER_ID);
    dsl.execute(
        "INSERT INTO game_session_canonical_gameplay_binding_inventory"
            + " (binding_ref, account_id, tenant_id, playable_state_namespace_id, character_id,"
            + " playable_state_scope, game_instance_id, runtime_game_instance_id, session_id,"
            + " binding_generation, region_id, region_epoch, issuer_id, issuer_auth_generation,"
            + " issuer_index_layout_version, issuer_index_partition_count,"
            + " issuer_index_partition_capacity, issuer_partition_id, account_index_fence,"
            + " issuer_reservation_id, transition_id, lifecycle, account_index_state,"
            + " issuer_index_state, inventory_revision)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 5, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " 'ACTIVE', 'ACKNOWLEDGED', 'ACKNOWLEDGED', 8)",
        priorRef,
        prior.accountId(),
        prior.tenantId(),
        prior.playableStateNamespaceId(),
        prior.characterId(),
        prior.playableStateScope(),
        prior.gameInstanceId(),
        prior.runtimeGameInstanceId(),
        prior.sessionId(),
        prior.regionId(),
        BigInteger.valueOf(12),
        prior.issuerId(),
        BigInteger.valueOf(7),
        BigInteger.valueOf(3),
        BigInteger.valueOf(16),
        BigInteger.valueOf(32),
        priorPartition,
        priorAccountFence,
        PRIOR_RESERVATION_ID,
        PRIOR_TRANSITION_ID);
    dsl.execute(
        "INSERT INTO game_session_canonical_issuer_partition_reservation"
            + " (reservation_id, binding_ref, account_id, tenant_id, game_instance_id,"
            + " runtime_game_instance_id, session_id, binding_generation, transition_id, issuer_id,"
            + " issuer_auth_generation, playable_state_namespace_id, character_id,"
            + " playable_state_scope, region_id, region_epoch, partition_id,"
            + " issuer_index_layout_version, issuer_index_partition_count,"
            + " issuer_index_partition_capacity, lifecycle, reservation_fence,"
            + " issuer_coverage_operation_id, issuer_coverage_operation_fence, coverage_fence,"
            + " inventory_snapshot_revision, inventory_revision)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, 5, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'BOUND', ?, ?, 1, 1, 7, 8)",
        PRIOR_RESERVATION_ID,
        priorRef,
        prior.accountId(),
        prior.tenantId(),
        prior.gameInstanceId(),
        prior.runtimeGameInstanceId(),
        prior.sessionId(),
        PRIOR_TRANSITION_ID,
        prior.issuerId(),
        BigInteger.valueOf(7),
        prior.playableStateNamespaceId(),
        prior.characterId(),
        prior.playableStateScope(),
        prior.regionId(),
        BigInteger.valueOf(12),
        priorPartition,
        BigInteger.valueOf(3),
        BigInteger.valueOf(16),
        BigInteger.valueOf(32),
        PRIOR_RESERVATION_FENCE,
        ISSUER_COVERAGE_OPERATION_ID);
    dsl.execute(
        "INSERT INTO game_session_canonical_binding_transition"
            + " (transition_id, tenant_id, playable_state_namespace_id, character_id,"
            + " expected_prior_binding_ref, expected_prior_binding_generation, candidate_binding_ref,"
            + " candidate_binding_generation, candidate_account_index_fence, issuer_reservation_id,"
            + " status, inventory_revision)"
            + " VALUES (?, ?, ?, ?, NULL, NULL, ?, 5, ?, ?, 'COMMITTED', 8)",
        PRIOR_TRANSITION_ID,
        TENANT_ID,
        NAMESPACE_ID,
        CHARACTER_ID,
        priorRef,
        priorAccountFence,
        PRIOR_RESERVATION_ID);
  }

  /**
   * Synthetic storage fixture for branch mechanics only; it is not evidence that the real legacy
   * index was fenced, inventoried, rebuilt, or read back.
   */
  static void seedSyntheticVerifiedLegacyDisposition(DSLContext dsl) {
    dsl.execute(
        "UPDATE game_session_canonical_binding_inventory_clock"
            + " SET inventory_revision = 1 WHERE singleton_id = 1");
    dsl.execute(
        "UPDATE game_session_canonical_binding_legacy_disposition"
            + " SET disposition_state = 'VERIFIED',"
            + " cohort_id = '10101010-1010-4010-8010-101010101010',"
            + " owner_operation_id = '20202020-2020-4020-8020-202020202020',"
            + " legacy_writer_fence = '30303030-3030-4030-8030-303030303030',"
            + " source_snapshot_revision = 1, canonical_inventory_revision = 1,"
            + " namespace_index_readback_revision = 1,"
            + " evidence_digest = 'sha256:"
            + "0000000000000000000000000000000000000000000000000000000000000000'"
            + " WHERE singleton_id = 1");
  }

  private static CanonicalIssuerReservationFenceEvidence reservation(UUID reservationFence) {
    return new CanonicalIssuerReservationFenceEvidence(
        RESERVATION_ID,
        reservationFence,
        ISSUER_COVERAGE_OPERATION_ID,
        BigInteger.valueOf(13),
        BigInteger.valueOf(17),
        BigInteger.valueOf(41));
  }

  private static DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl() + "?currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static void dropSchema(String schema) {
    try (var connection =
            java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    } catch (Exception failure) {
      throw new IllegalStateException("Failed to dispose canonical binding test schema", failure);
    }
  }
}
