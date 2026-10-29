package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionAbortSource;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerReservationFenceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListener;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayIssuerPartitionBuildRepositoryIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID NAMESPACE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID GAME_INSTANCE_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID REGION_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID CHARACTER_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final BigInteger LAYOUT_VERSION = BigInteger.valueOf(3);
  private static final BigInteger PARTITION_COUNT = BigInteger.valueOf(16);
  private static final BigInteger PARTITION_CAPACITY = BigInteger.valueOf(32);

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void v1037RemovesOnlyDuplicateFirstNewReleaseGuards() {
    TestSchema testSchema = newTestSchemaAtVersion("gs_issuer_release_guard_", "1036");
    try {
      assertThat(
              triggerExists(
                  testSchema.dsl(),
                  "game_session_canonical_issuer_partition_reservation",
                  "game_session_first_new_issuer_release_reservation"))
          .isTrue();
      assertThat(functionExists(testSchema.dsl(), "require_first_new_issuer_release_reservation()"))
          .isTrue();
      assertThat(
              triggerExists(
                  testSchema.dsl(),
                  "game_session_canonical_binding_account_index_obligation",
                  "game_session_first_new_issuer_release_account_atomic"))
          .isTrue();

      migrateThrough1037(testSchema);

      assertThat(
              triggerExists(
                  testSchema.dsl(),
                  "game_session_canonical_issuer_partition_reservation",
                  "game_session_first_new_issuer_release_reservation"))
          .isFalse();
      assertThat(functionExists(testSchema.dsl(), "require_first_new_issuer_release_reservation()"))
          .isFalse();
      assertThat(
              triggerExists(
                  testSchema.dsl(),
                  "game_session_canonical_binding_account_index_obligation",
                  "game_session_first_new_issuer_release_account_atomic"))
          .isFalse();

      assertThat(
              triggerExists(
                  testSchema.dsl(),
                  "game_session_canonical_issuer_partition_reservation",
                  "game_session_first_new_abort_reservation_release"))
          .isTrue();
      assertThat(
              triggerExists(
                  testSchema.dsl(),
                  "game_session_canonical_binding_account_index_obligation",
                  "game_session_first_new_abort_account_phase_atomic"))
          .isTrue();
      assertThat(
              triggerExists(
                  testSchema.dsl(),
                  "game_session_canonical_issuer_partition_reservation",
                  "game_session_first_new_issuer_release_reservation_atomic"))
          .isTrue();

      String retainedReservationGuard =
          testSchema
              .dsl()
              .fetchSingle(
                  "SELECT pg_get_functiondef(to_regprocedure(?))",
                  "require_first_new_abort_reservation_release()")
              .get(0, String.class);
      assertThat(retainedReservationGuard)
          .contains("OLD.lifecycle = 'RELEASE_PENDING' AND NEW.lifecycle = 'RELEASED'")
          .contains("release.physical_readback_field_set_bytes IS NOT NULL")
          .contains(
              "First-NEW reservation release requires its exact completed physical issuer proof");
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void v1038PhysicalIssuerHistoryRejectsTruncateCascade() {
    TestSchema testSchema = newTestSchemaAtVersion("gs_issuer_physical_history_", "1037");
    try {
      migrateThrough1038(testSchema);
      assertThat(
              triggerExists(
                  testSchema.dsl(),
                  "game_session_canonical_issuer_partition_mutation_head",
                  "trg_gs_issuer_partition_mutation_head_no_truncate"))
          .isTrue();
      assertThat(
              triggerExists(
                  testSchema.dsl(),
                  "game_session_canonical_issuer_partition_mutation",
                  "trg_gs_issuer_partition_mutation_no_truncate"))
          .isTrue();
      assertThat(
              triggerExists(
                  testSchema.dsl(),
                  "game_session_canonical_issuer_binding_local_retirement",
                  "trg_gs_issuer_local_retirement_no_truncate"))
          .isTrue();
      assertThat(
              functionExists(
                  testSchema.dsl(), "reject_canonical_issuer_physical_history_truncate()"))
          .isTrue();
      assertThat(
              triggerDefinition(
                  testSchema.dsl(),
                  "game_session_canonical_issuer_partition_mutation",
                  "trg_gs_issuer_partition_mutation_history"))
          .contains("INSERT")
          .contains("UPDATE")
          .contains("DELETE")
          .contains("guard_canonical_issuer_partition_mutation_history");
      assertThat(
              triggerDefinition(
                  testSchema.dsl(),
                  "game_session_canonical_issuer_binding_local_retirement",
                  "trg_gs_issuer_binding_local_retirement"))
          .contains("INSERT")
          .contains("UPDATE")
          .contains("DELETE")
          .contains("guard_canonical_issuer_binding_local_retirement");

      var fixture = pendingFixture(testSchema);
      UUID initialRevision = UUID.randomUUID();
      testSchema
          .dsl()
          .execute(
              "INSERT INTO game_session_canonical_issuer_partition_mutation_head"
                  + " (issuer_id, partition_id, bootstrap_operation_id, initial_revision,"
                  + " current_revision, state) VALUES (?, 0, ?, ?, ?, 'INITIAL_INSTALL_PENDING')",
              ISSUER,
              fixture.intent().operationId(),
              initialRevision,
              initialRevision);
      assertThatThrownBy(
              () ->
                  testSchema
                      .dsl()
                      .execute(
                          "INSERT INTO game_session_canonical_issuer_partition_mutation"
                              + " (mutation_id, issuer_id, partition_id, mutation_kind, owner_operation_id,"
                              + " expected_revision, successor_revision, exact_intended_change_bytes,"
                              + " exact_intended_change_digest, status)"
                              + " VALUES (?, ?, 0, 'ADD_CAPACITY', ?, ?, ?, ?, ?, 'PENDING_PHYSICAL_PROOF')",
                          UUID.randomUUID(),
                          ISSUER,
                          UUID.randomUUID(),
                          initialRevision,
                          UUID.randomUUID(),
                          new byte[] {1},
                          "sha256:" + "a".repeat(64)))
          .isInstanceOfSatisfying(
              DataAccessException.class,
              exception -> assertThat(exception.sqlState()).isEqualTo("23514"));

      List<String> headBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_mutation_head");
      List<String> mutationsBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_mutation");
      List<String> retirementsBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_issuer_binding_local_retirement");
      List<String> migrationHistoryBefore = migrationHistorySnapshot(testSchema.dsl());
      assertThat(headBefore).hasSize(1);
      assertThat(mutationsBefore).isEmpty();
      assertThat(retirementsBefore).isEmpty();

      assertThatThrownBy(
              () ->
                  testSchema
                      .dsl()
                      .execute(
                          "TRUNCATE TABLE"
                              + " game_session_canonical_issuer_partition_mutation_head,"
                              + " game_session_canonical_issuer_partition_mutation,"
                              + " game_session_canonical_issuer_binding_local_retirement CASCADE"))
          .isInstanceOfSatisfying(
              DataAccessException.class,
              exception -> assertThat(exception.sqlState()).isEqualTo("23514"));

      assertThat(
              rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_mutation_head"))
          .containsExactlyElementsOf(headBefore);
      assertThat(rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_mutation"))
          .containsExactlyElementsOf(mutationsBefore);
      assertThat(
              rowImages(testSchema.dsl(), "game_session_canonical_issuer_binding_local_retirement"))
          .containsExactlyElementsOf(retirementsBefore);
      assertThat(migrationHistorySnapshot(testSchema.dsl()))
          .containsExactlyElementsOf(migrationHistoryBefore);
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void v1038RetainsLegacyBootstrapProofWithoutQualifyingCurrentRevision() {
    TestSchema historicalSchema = newTestSchemaAtVersion("gs_issuer_legacy_proof_", "1037");
    TestSchema newProofSchema = newTestSchemaAtVersion("gs_issuer_new_proof_", "1037");
    try {
      var historicalFixture = pendingFixture(historicalSchema);
      var historicalPlan =
          new CanonicalGameplayIssuerPartitionBuildRepository(historicalSchema.dsl())
              .prepareInitialBuildPlan(
                  historicalFixture.intent().operationId(), historicalFixture.requestId());
      assertThat(historicalPlan.state())
          .isEqualTo(CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED);
      insertControlledBootstrapProof(historicalSchema.dsl(), historicalFixture, historicalPlan);
      List<String> legacyProofBefore =
          rowImages(
              historicalSchema.dsl(), "game_session_canonical_issuer_bootstrap_partition_proof");
      assertThat(legacyProofBefore).hasSize(1);

      migrateThrough1038(historicalSchema);
      String mutationHeadGuard =
          historicalSchema
              .dsl()
              .fetchSingle(
                  "SELECT pg_get_functiondef(to_regprocedure(?))",
                  "guard_canonical_issuer_partition_mutation_head()")
              .get(0, String.class);
      assertThat(mutationHeadGuard)
          .contains("proof.initial_physical_mutation_revision = OLD.initial_revision");
      assertThat(
              historicalSchema
                  .dsl()
                  .fetchSingle(
                      "SELECT initial_physical_mutation_revision"
                          + " FROM game_session_canonical_issuer_bootstrap_partition_proof"
                          + " WHERE bootstrap_operation_id = ? AND partition_id = 0",
                      historicalFixture.intent().operationId())
                  .get(0, UUID.class))
          .isNull();
      assertThat(
              historicalSchema
                  .dsl()
                  .fetch(
                      "SELECT (to_jsonb(proof) - 'initial_physical_mutation_revision')::text"
                          + " AS row_image FROM game_session_canonical_issuer_bootstrap_partition_proof proof"
                          + " ORDER BY partition_id")
                  .getValues("row_image", String.class))
          .containsExactlyElementsOf(legacyProofBefore);

      UUID historicalInitialRevision = UUID.randomUUID();
      assertThatThrownBy(
              () ->
                  historicalSchema
                      .dsl()
                      .execute(
                          "INSERT INTO game_session_canonical_issuer_partition_mutation_head"
                              + " (issuer_id, partition_id, bootstrap_operation_id, initial_revision,"
                              + " current_revision, state)"
                              + " VALUES (?, 0, ?, ?, ?, 'INITIAL_INSTALL_PENDING')",
                          ISSUER,
                          historicalFixture.intent().operationId(),
                          historicalInitialRevision,
                          historicalInitialRevision))
          .isInstanceOfSatisfying(
              DataAccessException.class,
              exception -> assertThat(exception.sqlState()).isEqualTo("P0001"));
      assertThat(
              rowImages(
                  historicalSchema.dsl(), "game_session_canonical_issuer_partition_mutation_head"))
          .isEmpty();

      migrateThrough1038(newProofSchema);
      var newProofFixture = pendingFixture(newProofSchema);
      var newProofPlan =
          new CanonicalGameplayIssuerPartitionBuildRepository(newProofSchema.dsl())
              .prepareInitialBuildPlan(
                  newProofFixture.intent().operationId(), newProofFixture.requestId());
      assertThat(newProofPlan.state())
          .isEqualTo(CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED);
      UUID newInitialRevision = UUID.randomUUID();
      newProofSchema
          .dsl()
          .execute(
              "INSERT INTO game_session_canonical_issuer_partition_mutation_head"
                  + " (issuer_id, partition_id, bootstrap_operation_id, initial_revision,"
                  + " current_revision, state) VALUES (?, 0, ?, ?, ?, 'INITIAL_INSTALL_PENDING')",
              ISSUER,
              newProofFixture.intent().operationId(),
              newInitialRevision,
              newInitialRevision);
      assertThatThrownBy(
              () ->
                  insertControlledBootstrapProof(
                      newProofSchema.dsl(), newProofFixture, newProofPlan, null))
          .isInstanceOfSatisfying(
              DataAccessException.class,
              exception -> assertThat(exception.sqlState()).isEqualTo("23514"));
      assertThatThrownBy(
              () ->
                  insertControlledBootstrapProof(
                      newProofSchema.dsl(), newProofFixture, newProofPlan, UUID.randomUUID()))
          .isInstanceOfSatisfying(
              DataAccessException.class,
              exception -> {
                assertThat(exception.sqlState()).isEqualTo("23514");
                assertThat(exception.getMessage()).contains("exact SQL-installed initial revision");
              });
      assertThat(
              rowImages(
                  newProofSchema.dsl(), "game_session_canonical_issuer_bootstrap_partition_proof"))
          .isEmpty();
    } finally {
      dropSchema(historicalSchema.schema());
      dropSchema(newProofSchema.schema());
    }
  }

  @Test
  void cohortOrdinalsFollowCanonicalSortedPositionForUnsortedRows() {
    String digestA = "sha256:" + "a".repeat(64);
    String digestB = "sha256:" + "b".repeat(64);
    String digestC = "sha256:" + "c".repeat(64);
    List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> unsortedRows =
        List.of(
            new CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow(
                29L,
                CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.TRANSITION,
                "z-transition",
                BigInteger.valueOf(7),
                "{\"source\":\"transition\"}",
                digestC),
            new CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow(
                13L,
                CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.RESERVATION,
                "b-reservation",
                BigInteger.valueOf(6),
                "{\"source\":\"reservation-b\"}",
                digestB),
            new CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow(
                4L,
                CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.RESERVATION,
                "a-reservation",
                BigInteger.valueOf(5),
                "{\"source\":\"reservation-a\"}",
                digestA));

    List<CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow> numberedRows =
        CanonicalGameplayIssuerPartitionBuildRepository.assignCanonicalCohortOrdinals(unsortedRows);

    assertThat(numberedRows)
        .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::rowOrdinal)
        .containsExactly(0L, 1L, 2L);
    assertThat(numberedRows)
        .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::kind)
        .containsExactly(
            CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.RESERVATION,
            CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.RESERVATION,
            CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.TRANSITION);
    assertThat(numberedRows)
        .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceKey)
        .containsExactly("a-reservation", "b-reservation", "z-transition");
    assertThat(numberedRows)
        .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceInventoryRevision)
        .containsExactly(BigInteger.valueOf(5), BigInteger.valueOf(6), BigInteger.valueOf(7));
    assertThat(numberedRows)
        .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowJson)
        .containsExactly(
            "{\"source\":\"reservation-a\"}",
            "{\"source\":\"reservation-b\"}",
            "{\"source\":\"transition\"}");
    assertThat(numberedRows)
        .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowDigest)
        .containsExactly(digestA, digestB, digestC);
  }

  @Test
  void populatedPendingIntentRetainsCompleteCohortAndBothCoverageTuplesAcrossConcurrentRetry()
      throws Exception {
    TestSchema testSchema = newTestSchemaAtVersion("gs_issuer_build_retry_", "1030");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      establishActualLegacyDisposition(testSchema.dsl());
      var inventory = new CanonicalGameplayBindingInventoryRepository(testSchema.dsl());
      var prepared = prepareActualBinding(inventory);
      UUID reservationOnlyId = UUID.randomUUID();
      insertReservationOnly(
          testSchema.dsl(),
          prepared.candidate(),
          prepared.inventoryRevision(),
          reservationOnlyId,
          BigInteger.valueOf(17),
          BigInteger.valueOf(19));

      var bootstrapOwner = new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl());
      UUID requestId = UUID.randomUUID();
      var intent =
          bootstrapOwner.beginInitialBootstrapIntent(
              requestId,
              descriptor(LAYOUT_VERSION, PARTITION_COUNT, PARTITION_CAPACITY),
              sourceCheckpoint(BigInteger.valueOf(7)));
      List<String> bindingRowsBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_gameplay_binding_inventory");
      List<String> reservationRowsBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_reservation");
      List<String> intentRowsBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_intent");
      List<String> controlRowsBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_control");
      List<String> cohortRowsBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_cohort_row");
      List<String> transitionRowsBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_binding_transition");
      List<String> accountObligationsBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_binding_account_index_obligation");
      List<String> issuerObligationsBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_binding_issuer_index_obligation");
      List<String> regionObligationsBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_binding_region_bridge_obligation");
      List<String> inventoryClockBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_binding_inventory_clock");
      List<String> migrationHistoryBefore = migrationHistorySnapshot(testSchema.dsl());
      String retainedRegionBridgeTable = "game_session_canonical_binding_region_bridge_obligation";
      List<String> regionBridgeRowsWithXminBefore =
          rowImagesWithXmin(testSchema.dsl(), retainedRegionBridgeTable);
      List<String> retainedSourceTables =
          List.of(
              "game_session_canonical_gameplay_binding_inventory",
              "game_session_canonical_issuer_partition_reservation",
              "game_session_canonical_issuer_layout_bootstrap_intent",
              "game_session_canonical_issuer_layout_bootstrap_control",
              "game_session_canonical_issuer_layout_bootstrap_cohort_row",
              "game_session_canonical_binding_transition",
              "game_session_canonical_binding_account_index_obligation",
              "game_session_canonical_binding_issuer_index_obligation",
              "game_session_canonical_binding_region_bridge_obligation",
              "game_session_canonical_binding_inventory_clock");
      List<List<String>> retainedRowsWithXminBefore =
          retainedSourceTables.stream()
              .map(table -> rowImagesWithXmin(testSchema.dsl(), table))
              .toList();

      migrateThrough1031(testSchema);
      List<String> migrationHistoryAfter1031 = migrationHistorySnapshot(testSchema.dsl());
      assertThat(migrationHistoryAfter1031).hasSize(migrationHistoryBefore.size() + 1);
      assertThat(migrationHistoryAfter1031.subList(0, migrationHistoryBefore.size()))
          .containsExactlyElementsOf(migrationHistoryBefore);
      assertThat(
              testSchema
                  .dsl()
                  .fetchSingle("SELECT script FROM flyway_schema_history WHERE version = '1031'")
                  .get(0, String.class))
          .isEqualTo("V1031__canonical_issuer_partition_build_plan.sql");
      assertThat(
              retainedSourceTables.stream()
                  .map(table -> rowImagesWithXmin(testSchema.dsl(), table))
                  .toList())
          .isEqualTo(retainedRowsWithXminBefore);

      List<List<String>> retainedRowsWithXminAt1031 =
          retainedSourceTables.stream()
              .map(table -> rowImagesWithXmin(testSchema.dsl(), table))
              .toList();
      migrateThrough1032(testSchema);
      List<String> migrationHistoryAfter1032 = migrationHistorySnapshot(testSchema.dsl());
      assertThat(migrationHistoryAfter1032).hasSize(migrationHistoryAfter1031.size() + 1);
      assertThat(migrationHistoryAfter1032.subList(0, migrationHistoryAfter1031.size()))
          .containsExactlyElementsOf(migrationHistoryAfter1031);
      assertThat(
              testSchema
                  .dsl()
                  .fetchSingle("SELECT script FROM flyway_schema_history WHERE version = '1032'")
                  .get(0, String.class))
          .isEqualTo("V1032__canonical_issuer_bootstrap_member_operation.sql");
      assertThat(
              retainedSourceTables.stream()
                  .map(table -> rowImagesWithXmin(testSchema.dsl(), table))
                  .toList())
          .isEqualTo(retainedRowsWithXminAt1031);

      migrateThrough1044_1(testSchema);
      List<String> migrationHistoryBeforeExecutorFenceConversion =
          migrationHistorySnapshot(testSchema.dsl());
      List<String> regionBridgeRowsBeforeExecutorFenceConversion =
          rowImages(testSchema.dsl(), retainedRegionBridgeTable);
      assertThat(rowImagesWithXmin(testSchema.dsl(), retainedRegionBridgeTable))
          .containsExactlyElementsOf(regionBridgeRowsWithXminBefore);

      migrateThrough1044_2(testSchema);
      List<String> migrationHistoryAfterExecutorFenceConversion =
          migrationHistorySnapshot(testSchema.dsl());
      assertThat(migrationHistoryAfterExecutorFenceConversion)
          .hasSize(migrationHistoryBeforeExecutorFenceConversion.size() + 1);
      assertThat(
              migrationHistoryAfterExecutorFenceConversion.subList(
                  0, migrationHistoryBeforeExecutorFenceConversion.size()))
          .containsExactlyElementsOf(migrationHistoryBeforeExecutorFenceConversion);
      assertThat(
              testSchema
                  .dsl()
                  .fetchSingle("SELECT script FROM flyway_schema_history WHERE version = '1044.2'")
                  .get(0, String.class))
          .isEqualTo("V1044.2__region_executor_and_human_runtime_fence.sql");
      assertThat(
              testSchema
                  .dsl()
                  .fetchSingle(
                      "SELECT data_type FROM information_schema.columns"
                          + " WHERE table_schema = current_schema()"
                          + " AND table_name = 'game_session_canonical_binding_region_bridge_obligation'"
                          + " AND column_name = 'executor_fence'")
                  .get(0, String.class))
          .isEqualTo("bigint");
      assertThat(rowImages(testSchema.dsl(), retainedRegionBridgeTable))
          .containsExactlyElementsOf(regionBridgeRowsBeforeExecutorFenceConversion);
      assertThat(rowImagesWithXmin(testSchema.dsl(), retainedRegionBridgeTable))
          .isNotEqualTo(regionBridgeRowsWithXminBefore);

      migrateRemaining(testSchema);
      List<String> migrationHistoryAfterCurrent = migrationHistorySnapshot(testSchema.dsl());
      assertThat(migrationHistoryAfterCurrent.subList(0, migrationHistoryAfter1032.size()))
          .containsExactlyElementsOf(migrationHistoryAfter1032);
      assertThat(
              testSchema
                  .dsl()
                  .fetchSingle("SELECT script FROM flyway_schema_history WHERE version = '1032'")
                  .get(0, String.class))
          .isEqualTo("V1032__canonical_issuer_bootstrap_member_operation.sql");
      assertThat(
              testSchema
                  .dsl()
                  .fetchSingle("SELECT script FROM flyway_schema_history WHERE version = '1033'")
                  .get(0, String.class))
          .isEqualTo("V1033__canonical_issuer_bootstrap_coverage.sql");

      var ready = new CountDownLatch(2);
      var start = new CountDownLatch(1);
      var first =
          executor.submit(
              () -> prepareAtGate(testSchema.dsl(), intent.operationId(), requestId, ready, start));
      var second =
          executor.submit(
              () -> prepareAtGate(testSchema.dsl(), intent.operationId(), requestId, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var firstPlan = first.get(30, TimeUnit.SECONDS);
      var secondPlan = second.get(30, TimeUnit.SECONDS);

      assertThat(firstPlan.state())
          .isEqualTo(CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED);
      assertThat(firstPlan.coverageGenerationState())
          .isEqualTo(
              CanonicalGameplayIssuerPartitionBuildPlan.CoverageGenerationState.NOT_YET_ISSUED);
      assertThat(firstPlan.coverageGeneration()).isNull();
      assertThat(firstPlan.cohortRows())
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::rowOrdinal)
          .containsExactlyElementsOf(
              intent.cohortRows().stream()
                  .map(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::rowOrdinal)
                  .toList());
      assertThat(firstPlan.cohortRows())
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowDigest)
          .containsExactlyElementsOf(
              intent.cohortRows().stream()
                  .map(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowDigest)
                  .toList());
      assertThat(firstPlan.memberWorks()).hasSize(2);
      assertThat(firstPlan.memberWorks())
          .extracting(CanonicalGameplayIssuerPartitionBuildPlan.MemberWork::reservationId)
          .containsExactlyInAnyOrder(prepared.issuerReservationId(), reservationOnlyId);
      var reservationOnlyWork =
          firstPlan.memberWorks().stream()
              .filter(work -> work.reservationId().equals(reservationOnlyId))
              .findFirst()
              .orElseThrow();
      assertThat(reservationOnlyWork.bindingSourceRowOrdinal()).isNull();
      assertThat(reservationOnlyWork.bindingSourceRowDigest()).isNull();
      var preparedBindingWork =
          firstPlan.memberWorks().stream()
              .filter(work -> work.reservationId().equals(prepared.issuerReservationId()))
              .findFirst()
              .orElseThrow();
      assertThat(preparedBindingWork.reservationLifecycle()).isEqualTo("RESERVED");
      assertThat(preparedBindingWork.bindingSourceRowOrdinal()).isNotNull();
      assertThat(
              firstPlan
                  .cohortRows()
                  .get(Math.toIntExact(preparedBindingWork.bindingSourceRowOrdinal()))
                  .sourceRowJson())
          .contains("CANDIDATE_PREPARED");
      assertThat(reservationOnlyWork.originalIssuerCoverageOperationId())
          .isNotEqualTo(reservationOnlyWork.plannedAnchorOperationId());
      assertThat(reservationOnlyWork.originalIssuerCoverageOperationFence())
          .isEqualTo(BigInteger.valueOf(17));
      assertThat(reservationOnlyWork.originalCoverageFence()).isEqualTo(BigInteger.valueOf(19));
      assertThat(reservationOnlyWork.originalInventorySnapshotRevision())
          .isEqualTo(intent.inventorySnapshotRevision());
      assertThat(reservationOnlyWork.plannedAnchorOperationId()).isEqualTo(intent.operationId());
      assertThat(reservationOnlyWork.plannedAnchorOperationFence())
          .isEqualTo(intent.operationFence());
      assertThat(reservationOnlyWork.plannedAnchorCoverageFence())
          .isEqualTo(intent.coverageFence());
      assertThat(reservationOnlyWork.plannedAnchorInventorySnapshotRevision())
          .isEqualTo(intent.inventorySnapshotRevision());
      assertThat(reservationOnlyWork.plannedPartitionId())
          .isEqualTo(
              CanonicalGameplayBindingRef.fromExactBytes(reservationOnlyWork.bindingRef())
                  .partitionId(PARTITION_COUNT));
      assertThat(firstPlan.memberWorks())
          .extracting(CanonicalGameplayIssuerPartitionBuildPlan.MemberWork::memberOperationId)
          .doesNotContain(prepared.transitionId());
      assertThat(firstPlan.memberWorks())
          .extracting(CanonicalGameplayIssuerPartitionBuildPlan.MemberWork::memberOperationId)
          .doesNotHaveDuplicates();
      assertThat(firstPlan.memberWorks())
          .extracting(
              CanonicalGameplayIssuerPartitionBuildPlan.MemberWork::memberPreparationFenceId)
          .doesNotHaveDuplicates();
      assertThat(secondPlan.buildOperationId()).isEqualTo(firstPlan.buildOperationId());
      assertThat(secondPlan.buildPreparationFenceId())
          .isEqualTo(firstPlan.buildPreparationFenceId());
      assertThat(secondPlan.planDigest()).isEqualTo(firstPlan.planDigest());
      assertThat(secondPlan.memberWorks())
          .extracting(CanonicalGameplayIssuerPartitionBuildPlan.MemberWork::memberOperationId)
          .containsExactlyElementsOf(
              firstPlan.memberWorks().stream()
                  .map(CanonicalGameplayIssuerPartitionBuildPlan.MemberWork::memberOperationId)
                  .toList());
      var restarted =
          new CanonicalGameplayIssuerPartitionBuildRepository(testSchema.dsl())
              .prepareInitialBuildPlan(intent.operationId(), requestId);
      assertThat(restarted.planDigest()).isEqualTo(firstPlan.planDigest());
      assertThat(restarted.memberWorks())
          .extracting(
              CanonicalGameplayIssuerPartitionBuildPlan.MemberWork::memberPreparationFenceId)
          .containsExactlyElementsOf(
              firstPlan.memberWorks().stream()
                  .map(
                      CanonicalGameplayIssuerPartitionBuildPlan.MemberWork
                          ::memberPreparationFenceId)
                  .toList());
      assertThat(rowImages(testSchema.dsl(), "game_session_canonical_gameplay_binding_inventory"))
          .containsExactlyElementsOf(bindingRowsBefore);
      assertThat(rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_reservation"))
          .containsExactlyElementsOf(reservationRowsBefore);
      assertThat(
              rowImages(testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_intent"))
          .containsExactlyElementsOf(intentRowsBefore);
      assertThat(
              rowImages(testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_control"))
          .containsExactlyElementsOf(controlRowsBefore);
      assertThat(
              rowImages(
                  testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_cohort_row"))
          .containsExactlyElementsOf(cohortRowsBefore);
      assertThat(rowImages(testSchema.dsl(), "game_session_canonical_binding_transition"))
          .containsExactlyElementsOf(transitionRowsBefore);
      assertThat(
              rowImages(
                  testSchema.dsl(), "game_session_canonical_binding_account_index_obligation"))
          .containsExactlyElementsOf(accountObligationsBefore);
      assertThat(
              rowImages(testSchema.dsl(), "game_session_canonical_binding_issuer_index_obligation"))
          .containsExactlyElementsOf(issuerObligationsBefore);
      assertThat(
              rowImages(
                  testSchema.dsl(), "game_session_canonical_binding_region_bridge_obligation"))
          .containsExactlyElementsOf(regionObligationsBefore);
      assertThat(rowImages(testSchema.dsl(), "game_session_canonical_binding_inventory_clock"))
          .containsExactlyElementsOf(inventoryClockBefore);
      List<String> migrationHistoryAfter = migrationHistorySnapshot(testSchema.dsl());
      assertThat(migrationHistoryAfter).containsExactlyElementsOf(migrationHistoryAfterCurrent);
      assertThat(
              retainedSourceTables.stream()
                  .filter(table -> !table.equals(retainedRegionBridgeTable))
                  .map(table -> rowImagesWithXmin(testSchema.dsl(), table))
                  .toList())
          .containsExactlyElementsOf(
              retainedSourceTables.stream()
                  .filter(table -> !table.equals(retainedRegionBridgeTable))
                  .map(table -> retainedRowsWithXminBefore.get(retainedSourceTables.indexOf(table)))
                  .toList());
    } finally {
      executor.shutdownNow();
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void frozenPlanPreservesNativeSourceValuesAcrossSessionRenderings() {
    for (String captureFormat : List.of("hex", "escape")) {
      TestSchema testSchema = newTestSchema("gs_build_native_");
      try {
        var captureSchema = sessionSchema(testSchema, "UTC", captureFormat);
        var fixture = pendingFixture(captureSchema, 24);
        var prepareSchema =
            sessionSchema(
                testSchema, "Pacific/Auckland", captureFormat.equals("hex") ? "escape" : "hex");
        var owner = new CanonicalGameplayIssuerPartitionBuildRepository(prepareSchema.dsl());
        List<String> sourceBefore =
            rowImagesWithXmin(
                testSchema.dsl(), "game_session_canonical_issuer_partition_reservation");
        List<String> intentCohortBefore =
            rowImages(
                testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_cohort_row");
        var plan =
            owner.prepareInitialBuildPlan(fixture.intent().operationId(), fixture.requestId());
        assertThat(plan.state())
            .isEqualTo(CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED);
        assertThat(plan.cohortRows())
            .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowJson)
            .containsExactlyElementsOf(
                fixture.intent().cohortRows().stream()
                    .map(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowJson)
                    .toList());
        List<String> planBefore =
            rowImagesWithXmin(
                testSchema.dsl(), "game_session_canonical_issuer_partition_build_plan");
        List<String> buildCohortBefore =
            rowImagesWithXmin(
                testSchema.dsl(), "game_session_canonical_issuer_partition_build_cohort_row");
        List<String> workBefore =
            rowImagesWithXmin(
                testSchema.dsl(), "game_session_canonical_issuer_partition_build_member_work");
        var readSchema = sessionSchema(testSchema, "America/New_York", captureFormat);
        AtomicInteger readQueries = new AtomicInteger();
        var measuredConfiguration = readSchema.dsl().configuration().derive();
        measuredConfiguration.set(
            new DefaultExecuteListenerProvider(
                new DefaultExecuteListener() {
                  @Override
                  public void executeStart(ExecuteContext context) {
                    readQueries.incrementAndGet();
                  }
                }));
        DSLContext measuredReader = DSL.using(measuredConfiguration);
        var readPlan =
            measuredReader.transactionResult(
                configuration -> {
                  DSLContext transaction = DSL.using(configuration);
                  return new CanonicalGameplayIssuerPartitionBuildRepository(transaction)
                      .readPreparedPlanInTransaction(
                          transaction, fixture.intent().operationId(), fixture.requestId());
                });
        // Count all actual SQL executions, not query strings: even the complete owner read
        // must use fewer round trips than this cohort has rows.
        assertThat(plan.cohortRows().size()).isGreaterThan(24);
        assertThat(readQueries.get()).isPositive().isLessThan(plan.cohortRows().size());
        readQueries.set(0);
        var member =
            measuredReader.transactionResult(
                configuration -> {
                  DSLContext transaction = DSL.using(configuration);
                  return new CanonicalGameplayIssuerPartitionBuildRepository(transaction)
                      .readPreparedMemberWorkInTransaction(
                          transaction, fixture.intent().operationId(), fixture.requestId(), 0);
                });
        assertThat(member.work().memberOperationId())
            .isEqualTo(plan.memberWorks().get(0).memberOperationId());
        assertThat(readQueries.get()).isPositive().isLessThan(plan.cohortRows().size());
        assertThat(readPlan.buildOperationId()).isEqualTo(plan.buildOperationId());
        assertThat(readPlan.planDigest()).isEqualTo(plan.planDigest());
        assertThat(readPlan.cohortRows())
            .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowDigest)
            .containsExactlyElementsOf(
                plan.cohortRows().stream()
                    .map(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowDigest)
                    .toList());
        var retry =
            new CanonicalGameplayIssuerPartitionBuildRepository(captureSchema.dsl())
                .prepareInitialBuildPlan(fixture.intent().operationId(), fixture.requestId());
        assertThat(retry.buildOperationId()).isEqualTo(plan.buildOperationId());
        assertThat(retry.planDigest()).isEqualTo(plan.planDigest());
        assertThatThrownBy(
                () ->
                    prepareSchema
                        .dsl()
                        .transaction(
                            configuration -> {
                              DSLContext transaction = DSL.using(configuration);
                              assertThat(
                                      transaction.execute(
                                          "UPDATE game_session_canonical_issuer_partition_reservation"
                                              + " SET coverage_fence = coverage_fence + 1"
                                              + " WHERE reservation_id = ?",
                                          fixture.reservationId()))
                                  .isOne();
                              new CanonicalGameplayIssuerPartitionBuildRepository(transaction)
                                  .readPreparedPlanInTransaction(
                                      transaction,
                                      fixture.intent().operationId(),
                                      fixture.requestId());
                            }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Current source row differs");
        for (String changedKeys :
            List.of("ADD COLUMN unexpected_attribute text", "DROP COLUMN created_at")) {
          assertThatThrownBy(
                  () ->
                      prepareSchema
                          .dsl()
                          .transaction(
                              configuration -> {
                                DSLContext transaction = DSL.using(configuration);
                                transaction.execute(
                                    "ALTER TABLE game_session_canonical_issuer_partition_reservation "
                                        + changedKeys);
                                new CanonicalGameplayIssuerPartitionBuildRepository(transaction)
                                    .readPreparedPlanInTransaction(
                                        transaction,
                                        fixture.intent().operationId(),
                                        fixture.requestId());
                              }))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("Current source row differs");
        }
        assertThat(
                rowImagesWithXmin(
                    testSchema.dsl(), "game_session_canonical_issuer_partition_reservation"))
            .containsExactlyElementsOf(sourceBefore);
        assertThat(
                rowImages(
                    testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_cohort_row"))
            .containsExactlyElementsOf(intentCohortBefore);
        assertThat(
                rowImagesWithXmin(
                    testSchema.dsl(), "game_session_canonical_issuer_partition_build_plan"))
            .containsExactlyElementsOf(planBefore);
        assertThat(
                rowImagesWithXmin(
                    testSchema.dsl(), "game_session_canonical_issuer_partition_build_cohort_row"))
            .containsExactlyElementsOf(buildCohortBefore);
        assertThat(
                rowImagesWithXmin(
                    testSchema.dsl(), "game_session_canonical_issuer_partition_build_member_work"))
            .containsExactlyElementsOf(workBefore);
      } finally {
        dropSchema(testSchema.schema());
      }
    }
  }

  @Test
  void exactOwnerRejectsMissingWrongRequestMissingControlAndChangedFrozenSources() {
    TestSchema testSchema = newTestSchema("gs_issuer_build_intent_fence_");
    try {
      var fixture = pendingFixture(testSchema);
      var owner = new CanonicalGameplayIssuerPartitionBuildRepository(testSchema.dsl());
      assertThatThrownBy(
              () -> owner.prepareInitialBuildPlan(UUID.randomUUID(), fixture.requestId()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Exact pending issuer-bootstrap operation is missing");
      assertThatThrownBy(
              () ->
                  owner.prepareInitialBuildPlan(fixture.intent().operationId(), UUID.randomUUID()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("request or exact current control tuple changed");

      testSchema
          .dsl()
          .execute(
              "DELETE FROM game_session_canonical_issuer_layout_bootstrap_control WHERE issuer_id = ?",
              ISSUER);
      assertThatThrownBy(
              () ->
                  owner.prepareInitialBuildPlan(
                      fixture.intent().operationId(), fixture.requestId()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("lost its positive current control row");
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void immutableBuildArtifactsRejectTruncateAndRetainTheirCompletePostimage() {
    TestSchema testSchema = newTestSchema("gs_issuer_build_immutable_");
    try {
      var fixture =
          pendingLayoutFixture(
              testSchema,
              LAYOUT_VERSION.add(BigInteger.ONE),
              PARTITION_COUNT,
              PARTITION_CAPACITY,
              false);
      var owner = new CanonicalGameplayIssuerPartitionBuildRepository(testSchema.dsl());
      var plan = owner.prepareInitialBuildPlan(fixture.intent().operationId(), fixture.requestId());
      assertThat(plan.state()).isEqualTo(CanonicalGameplayIssuerPartitionBuildPlan.State.BLOCKED);

      List<String> planBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_build_plan");
      List<String> cohortBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_build_cohort_row");
      List<String> workBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_build_member_work");
      List<String> blockersBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_build_blocker");
      assertThat(planBefore).hasSize(1);
      assertThat(cohortBefore).isNotEmpty();
      assertThat(workBefore).isNotEmpty();
      assertThat(blockersBefore).isNotEmpty();

      assertThatThrownBy(
              () ->
                  testSchema
                      .dsl()
                      .execute(
                          "UPDATE game_session_canonical_issuer_partition_build_plan"
                              + " SET created_at = created_at + INTERVAL '1 second'"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("issuer partition build preparation artifacts are immutable");

      assertThatThrownBy(
              () ->
                  testSchema
                      .dsl()
                      .execute(
                          "TRUNCATE TABLE"
                              + " game_session_canonical_issuer_partition_build_plan,"
                              + " game_session_canonical_issuer_partition_build_cohort_row,"
                              + " game_session_canonical_issuer_partition_build_member_work,"
                              + " game_session_canonical_issuer_partition_build_blocker CASCADE"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("artifacts are immutable");

      assertThat(rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_build_plan"))
          .containsExactlyElementsOf(planBefore);
      assertThat(
              rowImages(
                  testSchema.dsl(), "game_session_canonical_issuer_partition_build_cohort_row"))
          .containsExactlyElementsOf(cohortBefore);
      assertThat(
              rowImages(
                  testSchema.dsl(), "game_session_canonical_issuer_partition_build_member_work"))
          .containsExactlyElementsOf(workBefore);
      assertThat(
              rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_build_blocker"))
          .containsExactlyElementsOf(blockersBefore);
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void changedLayoutCohortOrSourceCannotResumeAnOriginalPlan() {
    TestSchema layoutSchema = newTestSchema("gs_issuer_build_layout_drift_");
    try {
      var fixture = pendingFixture(layoutSchema);
      var owner = new CanonicalGameplayIssuerPartitionBuildRepository(layoutSchema.dsl());
      owner.prepareInitialBuildPlan(fixture.intent().operationId(), fixture.requestId());
      layoutSchema
          .dsl()
          .execute(
              "UPDATE game_session_canonical_issuer_layout_bootstrap_intent"
                  + " SET layout_version = layout_version + 1 WHERE operation_id = ?",
              fixture.intent().operationId());
      assertThatThrownBy(
              () ->
                  owner.prepareInitialBuildPlan(
                      fixture.intent().operationId(), fixture.requestId()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("exact pending bootstrap layout or anchor link");
    } finally {
      dropSchema(layoutSchema.schema());
    }

    TestSchema cohortSchema = newTestSchema("gs_issuer_build_cohort_drift_");
    try {
      var fixture = pendingFixture(cohortSchema);
      cohortSchema
          .dsl()
          .execute(
              "UPDATE game_session_canonical_issuer_layout_bootstrap_cohort_row"
                  + " SET source_row_digest = ? WHERE operation_id = ? AND row_ordinal = 0",
              "sha256:" + "0".repeat(64),
              fixture.intent().operationId());
      assertThatThrownBy(
              () ->
                  new CanonicalGameplayIssuerPartitionBuildRepository(cohortSchema.dsl())
                      .prepareInitialBuildPlan(fixture.intent().operationId(), fixture.requestId()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("changed source-row digest");
    } finally {
      dropSchema(cohortSchema.schema());
    }

    TestSchema sourceSchema = newTestSchema("gs_issuer_build_source_drift_");
    try {
      var fixture = pendingFixture(sourceSchema);
      sourceSchema
          .dsl()
          .execute(
              "UPDATE game_session_canonical_issuer_partition_reservation"
                  + " SET lifecycle = 'BOUND' WHERE reservation_id = ?",
              fixture.reservationId());
      assertThatThrownBy(
              () ->
                  new CanonicalGameplayIssuerPartitionBuildRepository(sourceSchema.dsl())
                      .prepareInitialBuildPlan(fixture.intent().operationId(), fixture.requestId()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Current source row differs");
    } finally {
      dropSchema(sourceSchema.schema());
    }

    TestSchema checkpointSchema = newTestSchema("gs_build_checkpoint_drift_");
    try {
      var fixture = pendingFixture(checkpointSchema);
      checkpointSchema
          .dsl()
          .execute(
              "UPDATE game_session_canonical_issuer_layout_bootstrap_intent"
                  + " SET source_checkpoint_bytes = convert_to('{}', 'UTF8') WHERE operation_id = ?",
              fixture.intent().operationId());
      assertThatThrownBy(
              () ->
                  new CanonicalGameplayIssuerPartitionBuildRepository(checkpointSchema.dsl())
                      .prepareInitialBuildPlan(fixture.intent().operationId(), fixture.requestId()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("source checkpoint digest is invalid");
    } finally {
      dropSchema(checkpointSchema.schema());
    }
  }

  @Test
  void retainedLegacyReservationProducesACompleteBlockedPlan() {
    TestSchema testSchema = newLegacyIssuerTestSchema("gs_issuer_build_legacy_");
    try {
      UUID requestId = UUID.randomUUID();
      var intent =
          new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl())
              .beginInitialBootstrapIntent(
                  requestId,
                  descriptor(LAYOUT_VERSION, PARTITION_COUNT, PARTITION_CAPACITY),
                  sourceCheckpoint(BigInteger.valueOf(7)));
      var plan =
          new CanonicalGameplayIssuerPartitionBuildRepository(testSchema.dsl())
              .prepareInitialBuildPlan(intent.operationId(), requestId);

      assertThat(plan.state()).isEqualTo(CanonicalGameplayIssuerPartitionBuildPlan.State.BLOCKED);
      assertThat(plan.coverageGenerationState())
          .isEqualTo(
              CanonicalGameplayIssuerPartitionBuildPlan.CoverageGenerationState.NOT_YET_ISSUED);
      assertThat(plan.coverageGeneration()).isNull();
      assertThat(plan.cohortRows())
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::kind)
          .contains(
              CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind
                  .UNRESOLVED_LEGACY_RESERVATION);
      assertThat(plan.memberWorks()).isEmpty();
      assertThat(plan.blockers())
          .extracting(CanonicalGameplayIssuerPartitionBuildPlan.Blocker::code)
          .containsExactly(
              CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode.UNRESOLVED_LEGACY_RESERVATION);
      var retry =
          new CanonicalGameplayIssuerPartitionBuildRepository(testSchema.dsl())
              .prepareInitialBuildPlan(intent.operationId(), requestId);
      assertThat(retry.buildOperationId()).isEqualTo(plan.buildOperationId());
      assertThat(retry.planDigest()).isEqualTo(plan.planDigest());
      assertThat(retry.blockers()).containsExactlyElementsOf(plan.blockers());
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void retainedTerminalUnresolvedAndUnlinkedBoundReservationsBlockMemberIssuance() {
    TestSchema testSchema = newTestSchema("gs_issuer_build_unresolved_");
    try {
      establishActualLegacyDisposition(testSchema.dsl());
      CanonicalGameplayAdmissionAbortSource abortSource =
          createActualRetainedTerminalUnresolved(testSchema);
      assertThat(abortSource.isAccountAborted()).isTrue();
      assertThat(abortSource.candidate().lifecycle())
          .isEqualTo(
              net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry
                  .Lifecycle.TERMINAL_UNRESOLVED);
      assertThat(abortSource.reservation().lifecycle())
          .isEqualTo(
              net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation
                  .Lifecycle.RELEASE_PENDING);

      // These standalone rows are controlled fixtures for the no-binding BOUND and
      // RELEASE_PENDING cases. The linked TERMINAL_UNRESOLVED source above is produced by the
      // existing binding inventory abort owner.
      UUID unlinkedBoundReservationId = UUID.randomUUID();
      UUID unlinkedReleasePendingReservationId = UUID.randomUUID();
      insertReservationOnly(
          testSchema.dsl(),
          abortSource.candidate().identity(),
          abortSource.transition().inventoryRevision(),
          unlinkedBoundReservationId,
          BigInteger.valueOf(17),
          BigInteger.valueOf(19),
          LAYOUT_VERSION,
          PARTITION_COUNT,
          PARTITION_CAPACITY,
          false,
          "BOUND",
          UUID.randomUUID());
      insertReservationOnly(
          testSchema.dsl(),
          abortSource.candidate().identity(),
          abortSource.transition().inventoryRevision(),
          unlinkedReleasePendingReservationId,
          BigInteger.valueOf(17),
          BigInteger.valueOf(19),
          LAYOUT_VERSION,
          PARTITION_COUNT,
          PARTITION_CAPACITY,
          false,
          "RELEASE_PENDING",
          UUID.randomUUID());

      UUID requestId = UUID.randomUUID();
      var intent =
          new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl())
              .beginInitialBootstrapIntent(
                  requestId,
                  descriptor(LAYOUT_VERSION, PARTITION_COUNT, PARTITION_CAPACITY),
                  sourceCheckpoint(BigInteger.valueOf(7)));
      List<String> bindingsBeforePlan =
          rowImages(testSchema.dsl(), "game_session_canonical_gameplay_binding_inventory");
      List<String> reservationsBeforePlan =
          rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_reservation");
      List<String> transitionsBeforePlan =
          rowImages(testSchema.dsl(), "game_session_canonical_binding_transition");

      var owner = new CanonicalGameplayIssuerPartitionBuildRepository(testSchema.dsl());
      var plan = owner.prepareInitialBuildPlan(intent.operationId(), requestId);

      assertThat(plan.state()).isEqualTo(CanonicalGameplayIssuerPartitionBuildPlan.State.BLOCKED);
      assertThat(plan.coverageGenerationState())
          .isEqualTo(
              CanonicalGameplayIssuerPartitionBuildPlan.CoverageGenerationState.NOT_YET_ISSUED);
      assertThat(plan.coverageGeneration()).isNull();
      assertThat(plan.cohortRows())
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::rowOrdinal)
          .containsExactlyElementsOf(
              intent.cohortRows().stream()
                  .map(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::rowOrdinal)
                  .toList());
      assertThat(plan.cohortRows())
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::kind)
          .containsExactlyElementsOf(
              intent.cohortRows().stream()
                  .map(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::kind)
                  .toList());
      assertThat(plan.cohortRows())
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceKey)
          .containsExactlyElementsOf(
              intent.cohortRows().stream()
                  .map(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceKey)
                  .toList());
      assertThat(plan.cohortRows())
          .extracting(
              CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceInventoryRevision)
          .containsExactlyElementsOf(
              intent.cohortRows().stream()
                  .map(
                      CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow
                          ::sourceInventoryRevision)
                  .toList());
      assertThat(plan.cohortRows())
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowJson)
          .containsExactlyElementsOf(
              intent.cohortRows().stream()
                  .map(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowJson)
                  .toList());
      assertThat(plan.cohortRows())
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowDigest)
          .containsExactlyElementsOf(
              intent.cohortRows().stream()
                  .map(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowDigest)
                  .toList());
      var retainedBindingRow =
          plan.cohortRows().stream()
              .filter(
                  row ->
                      row.kind() == CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.BINDING)
              .findFirst()
              .orElseThrow();
      assertThat(retainedBindingRow.sourceRowJson()).contains("TERMINAL_UNRESOLVED");
      assertThat(plan.blockers())
          .anySatisfy(
              blocker -> {
                assertThat(blocker.sourceRowOrdinal()).isEqualTo(retainedBindingRow.rowOrdinal());
                assertThat(blocker.sourceRowDigest())
                    .isEqualTo(retainedBindingRow.sourceRowDigest());
                assertThat(blocker.code())
                    .isEqualTo(
                        CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode
                            .UNREPRESENTABLE_BINDING);
              });
      for (UUID reservationId :
          List.of(unlinkedBoundReservationId, unlinkedReleasePendingReservationId)) {
        var reservationRow =
            plan.cohortRows().stream()
                .filter(
                    row ->
                        row.kind()
                                == CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind
                                    .RESERVATION
                            && row.sourceKey().equals(reservationId.toString()))
                .findFirst()
                .orElseThrow();
        assertThat(plan.blockers())
            .anySatisfy(
                blocker -> {
                  assertThat(blocker.sourceRowOrdinal()).isEqualTo(reservationRow.rowOrdinal());
                  assertThat(blocker.sourceRowDigest()).isEqualTo(reservationRow.sourceRowDigest());
                  assertThat(blocker.code())
                      .isEqualTo(
                          CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode
                              .CONFLICTING_RESERVATION_LINK);
                });
      }
      assertThatThrownBy(
              () ->
                  testSchema
                      .dsl()
                      .transactionResult(
                          configuration -> {
                            DSLContext transaction = DSL.using(configuration);
                            return new CanonicalGameplayIssuerPartitionBuildRepository(transaction)
                                .readPreparedPlanInTransaction(
                                    transaction, intent.operationId(), requestId);
                          }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(
              "Blocked issuer partition build plan cannot issue member operations");
      assertThat(
              testSchema
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM"
                          + " game_session_canonical_issuer_bootstrap_member_operation")
                  .get(0, Long.class))
          .isZero();
      assertThat(
              testSchema
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM"
                          + " game_session_canonical_issuer_bootstrap_member_op_counter")
                  .get(0, Long.class))
          .isZero();
      assertThat(rowImages(testSchema.dsl(), "game_session_canonical_gameplay_binding_inventory"))
          .containsExactlyElementsOf(bindingsBeforePlan);
      assertThat(rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_reservation"))
          .containsExactlyElementsOf(reservationsBeforePlan);
      assertThat(rowImages(testSchema.dsl(), "game_session_canonical_binding_transition"))
          .containsExactlyElementsOf(transitionsBeforePlan);
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void initialBootstrapBlocksReservationsFromAnotherLayoutOrNoncanonicalOriginalPartition() {
    TestSchema layoutSchema = newTestSchema("gs_issuer_build_layout_init_");
    try {
      var fixture =
          pendingLayoutFixture(
              layoutSchema,
              LAYOUT_VERSION.add(BigInteger.ONE),
              PARTITION_COUNT,
              PARTITION_CAPACITY,
              false);
      var owner = new CanonicalGameplayIssuerPartitionBuildRepository(layoutSchema.dsl());
      var plan = owner.prepareInitialBuildPlan(fixture.intent().operationId(), fixture.requestId());
      assertBlockedInitialReservation(owner, plan, fixture);
    } finally {
      dropSchema(layoutSchema.schema());
    }

    TestSchema mappingSchema = newTestSchema("gs_issuer_build_mapping_init_");
    try {
      var fixture =
          pendingLayoutFixture(
              mappingSchema, LAYOUT_VERSION, PARTITION_COUNT, PARTITION_CAPACITY, true);
      var owner = new CanonicalGameplayIssuerPartitionBuildRepository(mappingSchema.dsl());
      var plan = owner.prepareInitialBuildPlan(fixture.intent().operationId(), fixture.requestId());
      assertBlockedInitialReservation(owner, plan, fixture);
    } finally {
      dropSchema(mappingSchema.schema());
    }
  }

  @Test
  void initialBootstrapCountsNonterminalReservationCapacityAndExcludesReleasedRows() {
    TestSchema testSchema = newTestSchema("gs_build_capacity_");
    BigInteger partitionCount = BigInteger.TWO;
    BigInteger partitionCapacity = BigInteger.ONE;
    BigInteger originalOperationFence = BigInteger.valueOf(17);
    BigInteger originalCoverageFence = BigInteger.valueOf(19);
    try {
      establishActualLegacyDisposition(testSchema.dsl());
      var prepared =
          prepareActualBinding(
              new CanonicalGameplayBindingInventoryRepository(testSchema.dsl()),
              LAYOUT_VERSION,
              partitionCount,
              partitionCapacity,
              originalOperationFence,
              originalCoverageFence);
      BigInteger overCapacityPartition =
          CanonicalGameplayBindingRef.of(
                  prepared.candidate().tenantId().toString(),
                  prepared.candidate().gameInstanceId().toString(),
                  prepared.candidate().sessionId())
              .partitionId(partitionCount);
      BigInteger otherPartition =
          overCapacityPartition.equals(BigInteger.ZERO) ? BigInteger.ONE : BigInteger.ZERO;
      List<UUID> capacitySessionIds =
          new java.util.ArrayList<>(List.of(UUID.fromString(prepared.candidate().sessionId())));

      UUID reservedReservationId = UUID.randomUUID();
      UUID reservedSessionId =
          sessionUuidForPartition(
              prepared.candidate(), partitionCount, overCapacityPartition, capacitySessionIds);
      capacitySessionIds.add(reservedSessionId);
      insertReservationOnly(
          testSchema.dsl(),
          prepared.candidate(),
          prepared.inventoryRevision(),
          reservedReservationId,
          originalOperationFence,
          originalCoverageFence,
          LAYOUT_VERSION,
          partitionCount,
          partitionCapacity,
          false,
          "RESERVED",
          reservedSessionId);
      UUID otherReservedReservationId = UUID.randomUUID();
      UUID otherReservedSessionId =
          sessionUuidForPartition(
              prepared.candidate(), partitionCount, otherPartition, capacitySessionIds);
      capacitySessionIds.add(otherReservedSessionId);
      insertReservationOnly(
          testSchema.dsl(),
          prepared.candidate(),
          prepared.inventoryRevision(),
          otherReservedReservationId,
          originalOperationFence,
          originalCoverageFence,
          LAYOUT_VERSION,
          partitionCount,
          partitionCapacity,
          false,
          "RESERVED",
          otherReservedSessionId);
      UUID releasedReservationId = UUID.randomUUID();
      UUID releasedSessionId =
          sessionUuidForPartition(
              prepared.candidate(), partitionCount, overCapacityPartition, capacitySessionIds);
      insertReservationOnly(
          testSchema.dsl(),
          prepared.candidate(),
          prepared.inventoryRevision(),
          releasedReservationId,
          originalOperationFence,
          originalCoverageFence,
          LAYOUT_VERSION,
          partitionCount,
          partitionCapacity,
          false,
          "RELEASED",
          releasedSessionId);

      UUID requestId = UUID.randomUUID();
      var intent =
          new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl())
              .beginInitialBootstrapIntent(
                  requestId,
                  descriptor(LAYOUT_VERSION, partitionCount, partitionCapacity),
                  sourceCheckpoint(BigInteger.valueOf(7)));
      List<String> reservationsBeforePlan =
          rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_reservation");
      var owner = new CanonicalGameplayIssuerPartitionBuildRepository(testSchema.dsl());
      var plan = owner.prepareInitialBuildPlan(intent.operationId(), requestId);

      assertThat(plan.state()).isEqualTo(CanonicalGameplayIssuerPartitionBuildPlan.State.BLOCKED);
      assertThat(plan.coverageGenerationState())
          .isEqualTo(
              CanonicalGameplayIssuerPartitionBuildPlan.CoverageGenerationState.NOT_YET_ISSUED);
      assertThat(plan.coverageGeneration()).isNull();
      assertThat(plan.cohortRows())
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::rowOrdinal)
          .containsExactlyElementsOf(
              intent.cohortRows().stream()
                  .map(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::rowOrdinal)
                  .toList());
      assertThat(plan.cohortRows())
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::kind)
          .containsExactlyElementsOf(
              intent.cohortRows().stream()
                  .map(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::kind)
                  .toList());
      assertThat(plan.cohortRows())
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceKey)
          .containsExactlyElementsOf(
              intent.cohortRows().stream()
                  .map(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceKey)
                  .toList());
      assertThat(plan.cohortRows())
          .extracting(
              CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceInventoryRevision)
          .containsExactlyElementsOf(
              intent.cohortRows().stream()
                  .map(
                      CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow
                          ::sourceInventoryRevision)
                  .toList());
      assertThat(plan.cohortRows())
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowJson)
          .containsExactlyElementsOf(
              intent.cohortRows().stream()
                  .map(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowJson)
                  .toList());
      assertThat(plan.cohortRows())
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowDigest)
          .containsExactlyElementsOf(
              intent.cohortRows().stream()
                  .map(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowDigest)
                  .toList());

      assertThat(plan.memberWorks()).hasSize(4);
      assertThat(plan.memberWorks())
          .extracting(CanonicalGameplayIssuerPartitionBuildPlan.MemberWork::reservationLifecycle)
          .containsExactlyInAnyOrder("RESERVED", "RESERVED", "RESERVED", "RELEASED");
      assertThat(plan.memberWorks())
          .allSatisfy(
              work -> {
                assertThat(work.originalPartitionId())
                    .isEqualTo(
                        CanonicalGameplayBindingRef.fromExactBytes(work.bindingRef())
                            .partitionId(partitionCount));
                assertThat(work.originalLayoutVersion()).isEqualTo(LAYOUT_VERSION);
                assertThat(work.originalPartitionCount()).isEqualTo(partitionCount);
                assertThat(work.originalPartitionCapacity()).isEqualTo(partitionCapacity);
                assertThat(work.originalIssuerCoverageOperationId())
                    .isNotEqualTo(work.plannedAnchorOperationId());
                assertThat(work.originalIssuerCoverageOperationFence())
                    .isEqualTo(originalOperationFence)
                    .isNotEqualTo(intent.operationFence());
                assertThat(work.originalCoverageFence())
                    .isEqualTo(originalCoverageFence)
                    .isNotEqualTo(intent.coverageFence());
                if (work.reservationId().equals(prepared.issuerReservationId())) {
                  assertThat(work.originalInventorySnapshotRevision()).isEqualTo(BigInteger.ONE);
                } else {
                  assertThat(work.originalInventorySnapshotRevision())
                      .isEqualTo(intent.inventorySnapshotRevision());
                }
                assertThat(work.plannedPartitionId()).isEqualTo(work.originalPartitionId());
                assertThat(work.plannedAnchorOperationId()).isEqualTo(intent.operationId());
                assertThat(work.plannedAnchorOperationFence()).isEqualTo(intent.operationFence());
                assertThat(work.plannedAnchorCoverageFence()).isEqualTo(intent.coverageFence());
                assertThat(work.plannedAnchorInventorySnapshotRevision())
                    .isEqualTo(intent.inventorySnapshotRevision());
              });
      assertThat(plan.memberWorks())
          .filteredOn(CanonicalGameplayIssuerPartitionBuildPlan.MemberWork::countsTowardCapacity)
          .hasSize(3);
      var releasedWork =
          plan.memberWorks().stream()
              .filter(work -> work.reservationId().equals(releasedReservationId))
              .findFirst()
              .orElseThrow();
      var reservedWork =
          plan.memberWorks().stream()
              .filter(work -> work.reservationId().equals(reservedReservationId))
              .findFirst()
              .orElseThrow();
      var otherReservedWork =
          plan.memberWorks().stream()
              .filter(work -> work.reservationId().equals(otherReservedReservationId))
              .findFirst()
              .orElseThrow();
      var preparedWork =
          plan.memberWorks().stream()
              .filter(work -> work.reservationId().equals(prepared.issuerReservationId()))
              .findFirst()
              .orElseThrow();
      assertThat(releasedWork.reservationLifecycle()).isEqualTo("RELEASED");
      assertThat(releasedWork.countsTowardCapacity()).isFalse();
      assertThat(releasedWork.originalPartitionId()).isEqualTo(overCapacityPartition);
      assertThat(reservedWork.reservationLifecycle()).isEqualTo("RESERVED");
      assertThat(reservedWork.countsTowardCapacity()).isTrue();
      assertThat(reservedWork.originalPartitionId()).isEqualTo(overCapacityPartition);
      assertThat(otherReservedWork.reservationLifecycle()).isEqualTo("RESERVED");
      assertThat(otherReservedWork.countsTowardCapacity()).isTrue();
      assertThat(otherReservedWork.originalPartitionId()).isEqualTo(otherPartition);
      assertThat(preparedWork.reservationLifecycle()).isEqualTo("RESERVED");
      assertThat(preparedWork.countsTowardCapacity()).isTrue();
      assertThat(preparedWork.originalPartitionId()).isEqualTo(overCapacityPartition);

      assertThat(plan.blockers()).hasSize(5);
      assertThat(plan.blockers())
          .extracting(CanonicalGameplayIssuerPartitionBuildPlan.Blocker::code)
          .containsOnly(
              CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode.PARTITION_CAPACITY_EXCEEDED,
              CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode.AGGREGATE_CAPACITY_EXCEEDED);
      for (var work :
          plan.memberWorks().stream()
              .filter(CanonicalGameplayIssuerPartitionBuildPlan.MemberWork::countsTowardCapacity)
              .toList()) {
        var workBlockerCodes =
            plan.blockers().stream()
                .filter(blocker -> blocker.sourceRowOrdinal() == work.reservationSourceRowOrdinal())
                .map(CanonicalGameplayIssuerPartitionBuildPlan.Blocker::code)
                .toList();
        if (work.reservationId().equals(otherReservedWork.reservationId())) {
          assertThat(workBlockerCodes)
              .containsExactly(
                  CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode
                      .AGGREGATE_CAPACITY_EXCEEDED);
        } else {
          assertThat(workBlockerCodes)
              .containsExactlyInAnyOrder(
                  CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode.PARTITION_CAPACITY_EXCEEDED,
                  CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode
                      .AGGREGATE_CAPACITY_EXCEEDED);
        }
      }
      assertThat(plan.blockers())
          .noneMatch(
              blocker -> blocker.sourceRowOrdinal() == releasedWork.reservationSourceRowOrdinal());

      assertThat(rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_build_plan"))
          .hasSize(1);
      assertThat(
              rowImages(
                  testSchema.dsl(), "game_session_canonical_issuer_partition_build_cohort_row"))
          .hasSize(intent.cohortRows().size());
      assertThat(
              rowImages(
                  testSchema.dsl(), "game_session_canonical_issuer_partition_build_member_work"))
          .hasSize(4);
      assertThat(
              rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_build_blocker"))
          .hasSize(5);
      assertThat(rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_reservation"))
          .containsExactlyElementsOf(reservationsBeforePlan);

      var retry = owner.prepareInitialBuildPlan(intent.operationId(), requestId);
      assertThat(retry.buildOperationId()).isEqualTo(plan.buildOperationId());
      assertThat(retry.planDigest()).isEqualTo(plan.planDigest());
      assertThat(retry.blockers()).containsExactlyElementsOf(plan.blockers());
      assertThat(retry.memberWorks())
          .extracting(CanonicalGameplayIssuerPartitionBuildPlan.MemberWork::memberOperationId)
          .containsExactlyElementsOf(
              plan.memberWorks().stream()
                  .map(CanonicalGameplayIssuerPartitionBuildPlan.MemberWork::memberOperationId)
                  .toList());
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void initialBootstrapAllows256PhysicalReservationsIncludingReleasedLineage() {
    assertPhysicalPartitionPlanBoundary(256, BigInteger.ONE, "RELEASED", false);
  }

  @Test
  void initialBootstrapBlocks257PhysicalReservationsDespiteReleasedLogicalExclusion() {
    assertPhysicalPartitionPlanBoundary(257, BigInteger.ONE, "RELEASED", true);
  }

  @Test
  void initialBootstrapBlocks257PhysicalReservationsDespiteLargerLogicalCapacity() {
    assertPhysicalPartitionPlanBoundary(257, BigInteger.valueOf(512), "RESERVED", true);
  }

  private static void assertPhysicalPartitionPlanBoundary(
      int reservationCount, BigInteger partitionCapacity, String extraLifecycle, boolean blocked) {
    TestSchema testSchema = newTestSchema("gs_build_physical_bound_");
    try {
      establishActualLegacyDisposition(testSchema.dsl());
      var prepared =
          prepareActualBinding(
              new CanonicalGameplayBindingInventoryRepository(testSchema.dsl()),
              LAYOUT_VERSION,
              BigInteger.ONE,
              partitionCapacity,
              BigInteger.valueOf(17),
              BigInteger.valueOf(19));
      for (int index = 1; index < reservationCount; index++) {
        insertReservationOnly(
            testSchema.dsl(),
            prepared.candidate(),
            prepared.inventoryRevision(),
            UUID.randomUUID(),
            BigInteger.valueOf(17),
            BigInteger.valueOf(19),
            LAYOUT_VERSION,
            BigInteger.ONE,
            partitionCapacity,
            false,
            extraLifecycle,
            UUID.randomUUID());
      }
      var checkpoint = sourceCheckpoint(BigInteger.valueOf(7));
      UUID requestId = UUID.randomUUID();
      var intent =
          new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl())
              .beginInitialBootstrapIntent(
                  requestId,
                  descriptor(LAYOUT_VERSION, BigInteger.ONE, partitionCapacity),
                  checkpoint);
      List<String> reservationsBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_reservation");
      var owner = new CanonicalGameplayIssuerPartitionBuildRepository(testSchema.dsl());
      var plan = owner.prepareInitialBuildPlan(intent.operationId(), requestId);
      assertThat(plan.state())
          .isEqualTo(
              blocked
                  ? CanonicalGameplayIssuerPartitionBuildPlan.State.BLOCKED
                  : CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED);
      assertThat(plan.memberWorks()).hasSize(reservationCount);
      assertThat(plan.memberWorks())
          .filteredOn(CanonicalGameplayIssuerPartitionBuildPlan.MemberWork::countsTowardCapacity)
          .hasSize(extraLifecycle.equals("RELEASED") ? 1 : reservationCount);
      assertThat(plan.cohortRows())
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowJson)
          .containsExactlyElementsOf(
              intent.cohortRows().stream()
                  .map(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowJson)
                  .toList());
      var physicalOwner =
          new CanonicalGameplayIssuerBootstrapMemberOperationRepository(testSchema.dsl());
      if (blocked) {
        assertThat(plan.blockers()).hasSize(reservationCount);
        assertThat(plan.blockers())
            .extracting(CanonicalGameplayIssuerPartitionBuildPlan.Blocker::code)
            .containsOnly(
                CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode.PARTITION_CAPACITY_EXCEEDED);
        assertThatThrownBy(
                () ->
                    physicalOwner.preparePartitionWork(
                        intent.operationId(),
                        requestId,
                        BigInteger.ZERO,
                        checkpoint,
                        physicalBoundaryIncarnation()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(
                "Blocked issuer partition build plan cannot issue member operations");
        assertThatThrownBy(
                () ->
                    physicalOwner.beginOrResumePendingPhysicalProof(
                        intent.operationId(),
                        requestId,
                        0,
                        checkpoint,
                        physicalBoundaryIncarnation()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(
                "Blocked issuer partition build plan cannot issue member operations");
        assertThat(
                rowImages(
                    testSchema.dsl(), "game_session_canonical_issuer_bootstrap_member_operation"))
            .isEmpty();
        assertThat(
                rowImages(
                    testSchema.dsl(), "game_session_canonical_issuer_partition_mutation_head"))
            .isEmpty();
      } else {
        assertThat(plan.blockers()).isEmpty();
        assertThat(
                physicalOwner
                    .preparePartitionWork(
                        intent.operationId(),
                        requestId,
                        BigInteger.ZERO,
                        checkpoint,
                        physicalBoundaryIncarnation())
                    .memberOperations())
            .hasSize(reservationCount);
      }
      var retry = owner.prepareInitialBuildPlan(intent.operationId(), requestId);
      assertThat(retry.buildOperationId()).isEqualTo(plan.buildOperationId());
      assertThat(retry.planDigest()).isEqualTo(plan.planDigest());
      assertThat(retry.blockers()).containsExactlyElementsOf(plan.blockers());
      assertThat(rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_reservation"))
          .containsExactlyElementsOf(reservationsBefore);
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  private static CanonicalGameplayIssuerBootstrapMemberOperationRepository.TrustedStableIncarnation
      physicalBoundaryIncarnation() {
    String digest = "sha256:" + "a".repeat(64);
    return new CanonicalGameplayIssuerBootstrapMemberOperationRepository.TrustedStableIncarnation(
        "controlled-physical-bound-attestor",
        digest,
        new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
            "controlled-deployment",
            "generation",
            List.of("node"),
            "redis-build",
            "lua-registry",
            digest,
            "READY",
            "boot",
            "startup",
            "storage",
            "aof"),
        digest,
        digest);
  }

  private static void assertBlockedInitialReservation(
      CanonicalGameplayIssuerPartitionBuildRepository owner,
      CanonicalGameplayIssuerPartitionBuildPlan plan,
      LayoutFixture fixture) {
    assertThat(plan.state()).isEqualTo(CanonicalGameplayIssuerPartitionBuildPlan.State.BLOCKED);
    assertThat(plan.coverageGenerationState())
        .isEqualTo(
            CanonicalGameplayIssuerPartitionBuildPlan.CoverageGenerationState.NOT_YET_ISSUED);
    assertThat(plan.coverageGeneration()).isNull();
    var originalReservation =
        fixture.intent().cohortRows().stream()
            .filter(
                row ->
                    row.kind()
                            == CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.RESERVATION
                        && row.sourceKey().equals(fixture.extraReservationId().toString()))
            .findFirst()
            .orElseThrow();
    var retainedReservation =
        plan.cohortRows().stream()
            .filter(row -> row.rowOrdinal() == originalReservation.rowOrdinal())
            .findFirst()
            .orElseThrow();
    assertThat(retainedReservation.sourceRowJson()).isEqualTo(originalReservation.sourceRowJson());
    assertThat(retainedReservation.sourceRowDigest())
        .isEqualTo(originalReservation.sourceRowDigest());
    assertThat(plan.blockers())
        .anySatisfy(
            blocker -> {
              assertThat(blocker.sourceRowOrdinal()).isEqualTo(originalReservation.rowOrdinal());
              assertThat(blocker.code())
                  .isEqualTo(
                      CanonicalGameplayIssuerPartitionBuildPlan.BlockerCode
                          .CONFLICTING_RESERVATION_LINK);
            });
    assertThat(plan.memberWorks())
        .extracting(CanonicalGameplayIssuerPartitionBuildPlan.MemberWork::reservationId)
        .contains(fixture.bindingReservationId())
        .doesNotContain(fixture.extraReservationId());
    var retry = owner.prepareInitialBuildPlan(fixture.intent().operationId(), fixture.requestId());
    assertThat(retry.buildOperationId()).isEqualTo(plan.buildOperationId());
    assertThat(retry.planDigest()).isEqualTo(plan.planDigest());
    assertThat(retry.blockers()).containsExactlyElementsOf(plan.blockers());
  }

  private static CanonicalGameplayIssuerPartitionBuildPlan prepareAtGate(
      DSLContext dsl, UUID operationId, UUID requestId, CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS))
      throw new IllegalStateException("test start gate timed out");
    return new CanonicalGameplayIssuerPartitionBuildRepository(dsl)
        .prepareInitialBuildPlan(operationId, requestId);
  }

  private static Fixture pendingFixture(TestSchema testSchema) {
    return pendingFixture(testSchema, 0);
  }

  private static Fixture pendingFixture(TestSchema testSchema, int extraReservations) {
    establishActualLegacyDisposition(testSchema.dsl());
    var prepared =
        prepareActualBinding(new CanonicalGameplayBindingInventoryRepository(testSchema.dsl()));
    for (int index = 0; index < extraReservations; index++) {
      insertReservationOnly(
          testSchema.dsl(),
          prepared.candidate(),
          prepared.inventoryRevision(),
          UUID.randomUUID(),
          BigInteger.valueOf(17),
          BigInteger.valueOf(19),
          LAYOUT_VERSION,
          PARTITION_COUNT,
          PARTITION_CAPACITY,
          false,
          "RESERVED",
          UUID.randomUUID());
    }
    UUID requestId = UUID.randomUUID();
    var intent =
        new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl())
            .beginInitialBootstrapIntent(
                requestId,
                descriptor(LAYOUT_VERSION, PARTITION_COUNT, PARTITION_CAPACITY),
                sourceCheckpoint(BigInteger.valueOf(7)));
    return new Fixture(intent, requestId, prepared.issuerReservationId());
  }

  private static LayoutFixture pendingLayoutFixture(
      TestSchema testSchema,
      BigInteger reservationLayoutVersion,
      BigInteger reservationPartitionCount,
      BigInteger reservationPartitionCapacity,
      boolean wrongOriginalPartition) {
    establishActualLegacyDisposition(testSchema.dsl());
    var prepared =
        prepareActualBinding(new CanonicalGameplayBindingInventoryRepository(testSchema.dsl()));
    UUID extraReservationId = UUID.randomUUID();
    insertReservationOnly(
        testSchema.dsl(),
        prepared.candidate(),
        prepared.inventoryRevision(),
        extraReservationId,
        BigInteger.valueOf(17),
        BigInteger.valueOf(19),
        reservationLayoutVersion,
        reservationPartitionCount,
        reservationPartitionCapacity,
        wrongOriginalPartition);
    UUID requestId = UUID.randomUUID();
    var intent =
        new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl())
            .beginInitialBootstrapIntent(
                requestId,
                descriptor(LAYOUT_VERSION, PARTITION_COUNT, PARTITION_CAPACITY),
                sourceCheckpoint(BigInteger.valueOf(7)));
    return new LayoutFixture(intent, requestId, prepared.issuerReservationId(), extraReservationId);
  }

  private static CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedLayoutDescriptor
      descriptor(BigInteger version, BigInteger count, BigInteger capacity) {
    return CanonicalGameplayIssuerTestFixtures.descriptor(ISSUER, version, count, capacity);
  }

  private static CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint
      sourceCheckpoint(BigInteger issuerGeneration) {
    return CanonicalGameplayIssuerTestFixtures.sourceCheckpoint(ISSUER, issuerGeneration);
  }

  private static CanonicalGameplayAdmissionAbortSource createActualRetainedTerminalUnresolved(
      TestSchema testSchema) {
    var runtimeTarget =
        CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
            testSchema.dataSource(), TENANT_ID);
    CanonicalGameplayBindingIdentity candidate =
        new CanonicalGameplayBindingIdentity(
            ACCOUNT_ID,
            runtimeTarget.canonicalTenantId(),
            runtimeTarget.playableStateNamespaceId(),
            CHARACTER_ID,
            runtimeTarget.playableStateScope(),
            runtimeTarget.gameInstanceUuid(),
            runtimeTarget.gameInstanceId(),
            UUID.randomUUID().toString(),
            REGION_ID,
            BigInteger.valueOf(12),
            ISSUER,
            BigInteger.valueOf(7),
            LAYOUT_VERSION,
            PARTITION_COUNT,
            PARTITION_CAPACITY);
    UUID transitionId = UUID.randomUUID();
    var reservation =
        new CanonicalIssuerReservationFenceEvidence(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            BigInteger.ONE,
            BigInteger.TWO,
            BigInteger.ONE);
    var inventory = new CanonicalGameplayBindingInventoryRepository(testSchema.dsl());
    inventory.prepare(
        new CanonicalGameplayBindingTransitionRequest(
            transitionId, UUID.randomUUID(), candidate, reservation, null, null));
    var prepared = inventory.readProvisionalCasEvidence(transitionId);
    CanonicalPlayableTarget target = runtimeTarget.playableTarget();
    var lease =
        controlledLeaseEvidence(candidate, prepared.transition().bindingGeneration(), target);
    inventory.markProvisional(
        prepared,
        new CanonicalGameplayAdmissionDecision(
            transitionId,
            lease,
            candidate,
            prepared.transition().bindingGeneration(),
            null,
            target));
    var source = inventory.readFirstNewAdmissionAbortSource(transitionId);
    return inventory.recordFirstNewAccountAbort(source, UUID.randomUUID());
  }

  private static AccountGameplayAdmissionLeaseEvidence controlledLeaseEvidence(
      CanonicalGameplayBindingIdentity candidate,
      BigInteger bindingGeneration,
      CanonicalPlayableTarget target) {
    return CanonicalGameplayIssuerTestFixtures.controlledLeaseEvidence(
        candidate, bindingGeneration, target);
  }

  private static CanonicalGameplayBindingTransitionSnapshot prepareActualBinding(
      CanonicalGameplayBindingInventoryRepository inventory) {
    return prepareActualBinding(
        inventory,
        LAYOUT_VERSION,
        PARTITION_COUNT,
        PARTITION_CAPACITY,
        BigInteger.ONE,
        BigInteger.TWO);
  }

  private static CanonicalGameplayBindingTransitionSnapshot prepareActualBinding(
      CanonicalGameplayBindingInventoryRepository inventory,
      BigInteger layoutVersion,
      BigInteger partitionCount,
      BigInteger partitionCapacity,
      BigInteger originalOperationFence,
      BigInteger originalCoverageFence) {
    UUID transitionId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    UUID reservationFence = UUID.randomUUID();
    UUID issuerCoverageOperationId = UUID.randomUUID();
    var candidate =
        new CanonicalGameplayBindingIdentity(
            ACCOUNT_ID,
            TENANT_ID,
            NAMESPACE_ID,
            CHARACTER_ID,
            "SHARED",
            GAME_INSTANCE_ID,
            23L,
            UUID.fromString("77777777-7777-4777-8777-777777777777").toString(),
            REGION_ID,
            BigInteger.valueOf(12),
            ISSUER,
            BigInteger.valueOf(7),
            layoutVersion,
            partitionCount,
            partitionCapacity);
    var reservation =
        new CanonicalIssuerReservationFenceEvidence(
            reservationId,
            reservationFence,
            issuerCoverageOperationId,
            originalOperationFence,
            originalCoverageFence,
            BigInteger.ONE);
    return inventory.prepare(
        new CanonicalGameplayBindingTransitionRequest(
            transitionId, UUID.randomUUID(), candidate, reservation, null, null));
  }

  private static UUID sessionUuidForPartition(
      CanonicalGameplayBindingIdentity template,
      BigInteger partitionCount,
      BigInteger partitionId,
      List<UUID> excludedSessionIds) {
    for (int candidate = 1; candidate <= 10_000; candidate++) {
      UUID sessionUuid =
          UUID.nameUUIDFromBytes(
              ("issuer-build-capacity-session-" + candidate).getBytes(StandardCharsets.UTF_8));
      if (excludedSessionIds.contains(sessionUuid)) continue;
      byte[] bindingRef =
          CanonicalGameplayBindingRef.of(
                  template.tenantId().toString(),
                  template.gameInstanceId().toString(),
                  sessionUuid.toString())
              .bytes();
      if (CanonicalGameplayBindingRef.fromExactBytes(bindingRef)
          .partitionId(partitionCount)
          .equals(partitionId)) {
        return sessionUuid;
      }
    }
    throw new IllegalStateException("Could not prepare a fixture binding in the target partition");
  }

  private static void insertReservationOnly(
      DSLContext dsl,
      CanonicalGameplayBindingIdentity template,
      BigInteger revision,
      UUID reservationId,
      BigInteger originalOperationFence,
      BigInteger originalCoverageFence) {
    insertReservationOnly(
        dsl,
        template,
        revision,
        reservationId,
        originalOperationFence,
        originalCoverageFence,
        template.issuerIndexLayoutVersion(),
        template.issuerIndexPartitionCount(),
        template.issuerIndexPartitionCapacity(),
        false);
  }

  private static void insertReservationOnly(
      DSLContext dsl,
      CanonicalGameplayBindingIdentity template,
      BigInteger revision,
      UUID reservationId,
      BigInteger originalOperationFence,
      BigInteger originalCoverageFence,
      BigInteger layoutVersion,
      BigInteger partitionCount,
      BigInteger partitionCapacity,
      boolean wrongOriginalPartition) {
    insertReservationOnly(
        dsl,
        template,
        revision,
        reservationId,
        originalOperationFence,
        originalCoverageFence,
        layoutVersion,
        partitionCount,
        partitionCapacity,
        wrongOriginalPartition,
        "RESERVED",
        UUID.fromString("99999999-9999-4999-8999-999999999999"));
  }

  private static void insertReservationOnly(
      DSLContext dsl,
      CanonicalGameplayBindingIdentity template,
      BigInteger revision,
      UUID reservationId,
      BigInteger originalOperationFence,
      BigInteger originalCoverageFence,
      BigInteger layoutVersion,
      BigInteger partitionCount,
      BigInteger partitionCapacity,
      boolean wrongOriginalPartition,
      String lifecycle,
      UUID sessionUuid) {
    UUID characterId = UUID.fromString("88888888-8888-4888-8888-888888888888");
    UUID transitionId = UUID.randomUUID();
    UUID fence = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String sessionId = sessionUuid.toString();
    var bindingRef =
        CanonicalGameplayBindingRef.of(
            template.tenantId().toString(), template.gameInstanceId().toString(), sessionId);
    BigInteger canonicalOriginalPartition = bindingRef.partitionId(partitionCount);
    BigInteger partitionId =
        wrongOriginalPartition
            ? canonicalOriginalPartition.add(BigInteger.ONE).mod(partitionCount)
            : canonicalOriginalPartition;
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
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " ?, ?, ?, ?, ?, ?, ?)",
        reservationId,
        bindingRef.bytes(),
        template.accountId(),
        template.tenantId(),
        template.gameInstanceId(),
        template.runtimeGameInstanceId(),
        sessionId,
        BigDecimal.ONE,
        transitionId,
        ISSUER,
        new BigDecimal(template.issuerAuthGeneration()),
        template.playableStateNamespaceId(),
        characterId,
        template.playableStateScope(),
        template.regionId(),
        new BigDecimal(template.regionEpoch()),
        new BigDecimal(partitionId),
        new BigDecimal(layoutVersion),
        new BigDecimal(partitionCount),
        new BigDecimal(partitionCapacity),
        lifecycle,
        fence,
        operationId,
        new BigDecimal(originalOperationFence),
        new BigDecimal(originalCoverageFence),
        new BigDecimal(revision),
        new BigDecimal(revision));
  }

  private static void establishActualLegacyDisposition(DSLContext dsl) {
    CanonicalGameplayIssuerTestFixtures.establishActualLegacyDisposition(dsl);
  }

  private static TestSchema newTestSchema(String prefix) {
    String schema = CanonicalGameplayIssuerTestFixtures.schemaName(prefix);
    DriverManagerDataSource dataSource = dataSource(schema);
    CanonicalGameplayIssuerTestFixtures.migrateSchema(dataSource, schema, MIGRATION_LOCATION, null);
    return new TestSchema(schema, dataSource, DSL.using(dataSource, SQLDialect.POSTGRES));
  }

  private static TestSchema newTestSchemaAtVersion(String prefix, String version) {
    String schema = CanonicalGameplayIssuerTestFixtures.schemaName(prefix);
    DriverManagerDataSource dataSource = dataSource(schema);
    CanonicalGameplayIssuerTestFixtures.migrateSchema(
        dataSource, schema, MIGRATION_LOCATION, version);
    return new TestSchema(schema, dataSource, DSL.using(dataSource, SQLDialect.POSTGRES));
  }

  private static void migrateThrough1031(TestSchema testSchema) {
    CanonicalGameplayIssuerTestFixtures.migrateSchema(
        testSchema.dataSource(), testSchema.schema(), MIGRATION_LOCATION, "1031");
  }

  private static void migrateThrough1032(TestSchema testSchema) {
    CanonicalGameplayIssuerTestFixtures.migrateSchema(
        testSchema.dataSource(), testSchema.schema(), MIGRATION_LOCATION, "1032");
  }

  private static void migrateThrough1037(TestSchema testSchema) {
    Flyway.configure()
        .dataSource(testSchema.dataSource())
        .schemas(testSchema.schema())
        .defaultSchema(testSchema.schema())
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .target(MigrationVersion.fromVersion("1037"))
        .load()
        .migrate();
  }

  private static void migrateThrough1038(TestSchema testSchema) {
    Flyway.configure()
        .dataSource(testSchema.dataSource())
        .schemas(testSchema.schema())
        .defaultSchema(testSchema.schema())
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .target(MigrationVersion.fromVersion("1038"))
        .load()
        .migrate();
  }

  private static void migrateThrough1044_1(TestSchema testSchema) {
    Flyway.configure()
        .dataSource(testSchema.dataSource())
        .schemas(testSchema.schema())
        .defaultSchema(testSchema.schema())
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .target(MigrationVersion.fromVersion("1044.1"))
        .load()
        .migrate();
  }

  private static void migrateThrough1044_2(TestSchema testSchema) {
    Flyway.configure()
        .dataSource(testSchema.dataSource())
        .schemas(testSchema.schema())
        .defaultSchema(testSchema.schema())
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .target(MigrationVersion.fromVersion("1044.2"))
        .load()
        .migrate();
  }

  private static void migrateRemaining(TestSchema testSchema) {
    CanonicalGameplayIssuerTestFixtures.migrateSchema(
        testSchema.dataSource(), testSchema.schema(), MIGRATION_LOCATION, null);
  }

  private static TestSchema newLegacyIssuerTestSchema(String prefix) {
    String schema = CanonicalGameplayIssuerTestFixtures.schemaName(prefix);
    DriverManagerDataSource dataSource = dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .target(MigrationVersion.fromVersion("1022"))
        .load()
        .migrate();
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    establishActualLegacyDisposition(dsl);
    insertLegacyReservationBeforeIssuerStringConversion(dsl);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
    return new TestSchema(schema, dataSource, dsl);
  }

  private static boolean triggerExists(DSLContext dsl, String table, String trigger) {
    return Boolean.TRUE.equals(
        dsl.fetchSingle(
                "SELECT EXISTS (SELECT 1 FROM pg_trigger"
                    + " WHERE tgrelid = to_regclass(?) AND tgname = ? AND NOT tgisinternal)",
                table,
                trigger)
            .get(0, Boolean.class));
  }

  private static String triggerDefinition(DSLContext dsl, String table, String trigger) {
    return dsl.fetchSingle(
            "SELECT pg_get_triggerdef(trigger_row.oid) FROM pg_trigger trigger_row"
                + " WHERE trigger_row.tgrelid = to_regclass(?) AND trigger_row.tgname = ?"
                + " AND NOT trigger_row.tgisinternal",
            table,
            trigger)
        .get(0, String.class);
  }

  private static boolean functionExists(DSLContext dsl, String functionSignature) {
    return Boolean.TRUE.equals(
        dsl.fetchSingle("SELECT to_regprocedure(?) IS NOT NULL", functionSignature)
            .get(0, Boolean.class));
  }

  /** Inserts controlled, non-admitting bytes to exercise proof storage and migration only. */
  private static void insertControlledBootstrapProof(
      DSLContext dsl, Fixture fixture, CanonicalGameplayIssuerPartitionBuildPlan plan) {
    dsl.execute(
        "INSERT INTO game_session_canonical_issuer_bootstrap_partition_proof"
            + " (build_operation_id, bootstrap_operation_id, bootstrap_request_id, issuer_id,"
            + " build_plan_digest, bootstrap_operation_fence, bootstrap_coverage_fence,"
            + " issuer_inventory_snapshot_revision, layout_version, partition_count,"
            + " partition_capacity, mapping_identity, layout_descriptor_bytes,"
            + " layout_descriptor_digest, source_checkpoint_bytes, source_checkpoint_digest,"
            + " source_event_bytes, source_event_digest, incarnation_bytes, incarnation_digest,"
            + " partition_id, exact_metadata_bytes, exact_physical_field_set_bytes,"
            + " exact_physical_field_set_digest, physical_field_count, physical_reservation_count,"
            + " physical_binding_count, physical_counted_capacity_count,"
            + " planned_aggregate_capacity_count, proof_state)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, 0,"
            + " ?, ?, ?, 1, 0, 0, 0, 0, 'EXACT_PHYSICAL_READBACK')",
        controlledBootstrapProofBindings(fixture, plan));
  }

  /** Attempts a new proof with no original physical revision; V1038 must reject this insert. */
  private static void insertControlledBootstrapProof(
      DSLContext dsl,
      Fixture fixture,
      CanonicalGameplayIssuerPartitionBuildPlan plan,
      UUID initialPhysicalMutationRevision) {
    Object[] baseBindings = controlledBootstrapProofBindings(fixture, plan);
    Object[] bindings = java.util.Arrays.copyOf(baseBindings, baseBindings.length + 1);
    bindings[baseBindings.length] = initialPhysicalMutationRevision;
    dsl.execute(
        "INSERT INTO game_session_canonical_issuer_bootstrap_partition_proof"
            + " (build_operation_id, bootstrap_operation_id, bootstrap_request_id, issuer_id,"
            + " build_plan_digest, bootstrap_operation_fence, bootstrap_coverage_fence,"
            + " issuer_inventory_snapshot_revision, layout_version, partition_count,"
            + " partition_capacity, mapping_identity, layout_descriptor_bytes,"
            + " layout_descriptor_digest, source_checkpoint_bytes, source_checkpoint_digest,"
            + " source_event_bytes, source_event_digest, incarnation_bytes, incarnation_digest,"
            + " partition_id, exact_metadata_bytes, exact_physical_field_set_bytes,"
            + " exact_physical_field_set_digest, physical_field_count, physical_reservation_count,"
            + " physical_binding_count, physical_counted_capacity_count,"
            + " planned_aggregate_capacity_count, proof_state, initial_physical_mutation_revision)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, 0,"
            + " ?, ?, ?, 1, 0, 0, 0, 0, 'EXACT_PHYSICAL_READBACK', ?)",
        bindings);
  }

  private static Object[] controlledBootstrapProofBindings(
      Fixture fixture, CanonicalGameplayIssuerPartitionBuildPlan plan) {
    var intent = fixture.intent();
    String digest = "sha256:" + "a".repeat(64);
    return new Object[] {
      plan.buildOperationId(),
      intent.operationId(),
      intent.requestId(),
      intent.issuerId(),
      plan.planDigest(),
      intent.operationFence(),
      intent.coverageFence(),
      intent.inventorySnapshotRevision(),
      intent.layoutDescriptor().layoutVersion(),
      intent.layoutDescriptor().partitionCount(),
      intent.layoutDescriptor().partitionCapacity(),
      intent.layoutDescriptor().mappingIdentity(),
      "test-only-layout-descriptor".getBytes(StandardCharsets.UTF_8),
      digest,
      "test-only-source-checkpoint".getBytes(StandardCharsets.UTF_8),
      digest,
      "test-only-incarnation".getBytes(StandardCharsets.UTF_8),
      digest,
      "test-only-metadata".getBytes(StandardCharsets.UTF_8),
      "test-only-physical-field-set".getBytes(StandardCharsets.UTF_8),
      digest
    };
  }

  private static void insertLegacyReservationBeforeIssuerStringConversion(DSLContext dsl) {
    UUID reservationId = UUID.randomUUID();
    UUID transitionId = UUID.randomUUID();
    UUID reservationFence = UUID.randomUUID();
    UUID oldIssuerId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    String sessionId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").toString();
    var bindingRef =
        CanonicalGameplayBindingRef.of(
            TENANT_ID.toString(), GAME_INSTANCE_ID.toString(), sessionId);
    BigInteger partitionId = bindingRef.partitionId(PARTITION_COUNT);
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
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " 'RESERVED', ?, ?, ?, ?, ?, ?)",
        reservationId,
        bindingRef.bytes(),
        ACCOUNT_ID,
        TENANT_ID,
        GAME_INSTANCE_ID,
        23L,
        sessionId,
        BigDecimal.ONE,
        transitionId,
        oldIssuerId,
        BigDecimal.valueOf(7),
        NAMESPACE_ID,
        UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
        "SHARED",
        REGION_ID,
        BigDecimal.valueOf(12),
        new BigDecimal(partitionId),
        new BigDecimal(LAYOUT_VERSION),
        new BigDecimal(PARTITION_COUNT),
        new BigDecimal(PARTITION_CAPACITY),
        reservationFence,
        UUID.randomUUID(),
        BigDecimal.ONE,
        BigDecimal.TWO,
        BigDecimal.ONE,
        BigDecimal.ONE);
    dsl.execute(
        "UPDATE game_session_canonical_binding_inventory_clock"
            + " SET inventory_revision = 1 WHERE singleton_id = 1 AND inventory_revision = 0");
  }

  private static TestSchema sessionSchema(TestSchema schema, String timeZone, String byteaOutput) {
    DriverManagerDataSource sessionDataSource =
        CanonicalGameplayIssuerTestFixtures.withSessionRendering(
            dataSource(schema.schema()), timeZone, byteaOutput);
    DSLContext sessionDsl = DSL.using(sessionDataSource, SQLDialect.POSTGRES);
    var settings =
        sessionDsl.fetchSingle(
            "SELECT current_setting('TimeZone'), current_setting('bytea_output')");
    assertThat(settings.get(0, String.class)).isEqualTo(timeZone);
    assertThat(settings.get(1, String.class)).isEqualTo(byteaOutput);
    return new TestSchema(schema.schema(), sessionDataSource, sessionDsl);
  }

  private static DriverManagerDataSource dataSource(String schema) {
    return CanonicalGameplayIssuerTestFixtures.dataSource(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), schema);
  }

  private static String retainedPreBridgeRegionImage(DSLContext dsl) {
    // Keep the populated V1030 fixture's original source columns comparable across nullable
    // schema extensions; the explicit V1044.2 type conversion separately accounts for xmin.
    String originalFields =
        "ARRAY['transition_id', 'binding_ref', 'account_id', 'tenant_id', 'game_instance_id',"
            + " 'runtime_game_instance_id', 'session_id', 'binding_generation', 'region_id',"
            + " 'region_epoch', 'status', 'inventory_revision']";
    Long unexpectedMaterializedFields =
        dsl.fetchSingle(
                "SELECT count(*) FROM game_session_canonical_binding_region_bridge_obligation g"
                    + " WHERE jsonb_strip_nulls(to_jsonb(g) - "
                    + originalFields
                    + ") <> '{}'::jsonb")
            .get(0, Long.class);
    if (unexpectedMaterializedFields == null || unexpectedMaterializedFields != 0L) {
      throw new IllegalStateException("Historical fixture gained materialized bridge evidence");
    }
    return "to_jsonb(retained_row) - ARRAY(SELECT key FROM jsonb_each(to_jsonb(retained_row))"
        + " WHERE NOT (key = ANY("
        + originalFields
        + ")))";
  }

  private static List<String> rowImages(DSLContext dsl, String tableName) {
    String image = "to_jsonb(retained_row)";
    if (tableName.equals("game_session_canonical_binding_transition")) {
      // V1035 adds only this nullable field. Keep every other source value and non-null abort UUID.
      image =
          "CASE WHEN to_jsonb(retained_row)->'admission_orphan_cleanup_id' = 'null'::jsonb"
              + " THEN to_jsonb(retained_row) - 'admission_orphan_cleanup_id' ELSE to_jsonb(retained_row) END";
      image =
          "("
              + image
              + ") - CASE WHEN to_jsonb(retained_row)->'original_new_candidate_bytes'"
              + " = 'null'::jsonb THEN ARRAY['original_new_candidate_bytes',"
              + " 'original_new_candidate_digest', 'original_new_candidate_state',"
              + " 'original_new_candidate_readback_digest'] ELSE ARRAY[]::text[] END";
      // Only the wholly absent V1049 extension is absence-equivalent across the upgrade.
      image =
          "("
              + image
              + ") - CASE WHEN to_jsonb(retained_row)->'original_new_final_session_sha256'"
              + " = 'null'::jsonb THEN ARRAY['original_new_final_session_sha256',"
              + " 'original_new_admission_at_ms', 'original_new_continuity_expires_at_ms',"
              + " 'original_new_session_duration_ms'] ELSE ARRAY[]::text[] END";
    } else if (tableName.equals("game_session_canonical_binding_issuer_index_obligation")) {
      // V1036's null release link is absent-equivalent; an actual release UUID stays significant.
      image =
          "CASE WHEN to_jsonb(retained_row)->'local_release_operation_id' = 'null'::jsonb"
              + " THEN to_jsonb(retained_row) - 'local_release_operation_id' ELSE to_jsonb(retained_row) END";
    } else if (tableName.equals("game_session_canonical_binding_region_bridge_obligation")) {
      image = retainedPreBridgeRegionImage(dsl);
    }
    return dsl.fetch(
            "SELECT ("
                + image
                + ")::text AS row_image FROM "
                + tableName
                + " retained_row ORDER BY 1")
        .getValues("row_image", String.class);
  }

  private static List<String> rowImagesWithXmin(DSLContext dsl, String tableName) {
    String image = "to_jsonb(retained_row)";
    if (tableName.equals("game_session_canonical_binding_transition")) {
      // Preserve xmin and all source data while comparing the one absent-equivalent V1035 field.
      image =
          "CASE WHEN to_jsonb(retained_row)->'admission_orphan_cleanup_id' = 'null'::jsonb"
              + " THEN to_jsonb(retained_row) - 'admission_orphan_cleanup_id' ELSE to_jsonb(retained_row) END";
      image =
          "("
              + image
              + ") - CASE WHEN to_jsonb(retained_row)->'original_new_candidate_bytes'"
              + " = 'null'::jsonb THEN ARRAY['original_new_candidate_bytes',"
              + " 'original_new_candidate_digest', 'original_new_candidate_state',"
              + " 'original_new_candidate_readback_digest'] ELSE ARRAY[]::text[] END";
      // Only the wholly absent V1049 extension is absence-equivalent across the upgrade.
      image =
          "("
              + image
              + ") - CASE WHEN to_jsonb(retained_row)->'original_new_final_session_sha256'"
              + " = 'null'::jsonb THEN ARRAY['original_new_final_session_sha256',"
              + " 'original_new_admission_at_ms', 'original_new_continuity_expires_at_ms',"
              + " 'original_new_session_duration_ms'] ELSE ARRAY[]::text[] END";
    } else if (tableName.equals("game_session_canonical_binding_issuer_index_obligation")) {
      // V1036's null release link is absent-equivalent; an actual release UUID stays significant.
      image =
          "CASE WHEN to_jsonb(retained_row)->'local_release_operation_id' = 'null'::jsonb"
              + " THEN to_jsonb(retained_row) - 'local_release_operation_id' ELSE to_jsonb(retained_row) END";
    } else if (tableName.equals("game_session_canonical_binding_region_bridge_obligation")) {
      image = retainedPreBridgeRegionImage(dsl);
    }
    return dsl.fetch(
            "SELECT xmin::text || ':' || ("
                + image
                + ")::text AS row_image FROM "
                + tableName
                + " retained_row ORDER BY 1")
        .getValues("row_image", String.class);
  }

  private static List<String> migrationHistorySnapshot(DSLContext dsl) {
    return dsl.fetch(
            "SELECT to_jsonb(history_row)::text AS row_image FROM flyway_schema_history history_row"
                + " ORDER BY installed_rank")
        .getValues("row_image", String.class);
  }

  private static void dropSchema(String schema) {
    CanonicalGameplayIssuerTestFixtures.dropSchema(
        dataSource("public"), schema, "Could not drop the owned issuer-build test schema");
  }

  private record Fixture(
      CanonicalGameplayIssuerLayoutBootstrapIntent intent, UUID requestId, UUID reservationId) {}

  private record LayoutFixture(
      CanonicalGameplayIssuerLayoutBootstrapIntent intent,
      UUID requestId,
      UUID bindingReservationId,
      UUID extraReservationId) {}

  private record TestSchema(String schema, DriverManagerDataSource dataSource, DSLContext dsl) {}
}
