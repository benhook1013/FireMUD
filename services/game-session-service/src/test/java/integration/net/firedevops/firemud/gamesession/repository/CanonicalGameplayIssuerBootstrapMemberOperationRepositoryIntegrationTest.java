package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationReadback;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot.Family;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerReservationFenceEvidence;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof for the SQL-issued pending initial-bootstrap member-operation boundary. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayIssuerBootstrapMemberOperationRepositoryIntegrationTest {
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
  private static final List<String> RETAINED_TABLES =
      List.of(
          "game_session_canonical_binding_inventory_clock",
          "game_session_canonical_binding_legacy_disposition",
          "game_session_canonical_gameplay_binding_inventory",
          "game_session_canonical_issuer_partition_reservation",
          "game_session_canonical_issuer_layout_bootstrap_intent",
          "game_session_canonical_issuer_layout_bootstrap_control",
          "game_session_canonical_issuer_layout_bootstrap_cohort_row",
          "game_session_canonical_issuer_partition_build_plan",
          "game_session_canonical_issuer_partition_build_cohort_row",
          "game_session_canonical_issuer_partition_build_member_work",
          "game_session_canonical_issuer_partition_build_blocker");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void actualPreparedPlanIssuesExactPendingV2OperationAndPreservesV1031RowsAcrossRestart()
      throws Exception {
    Fixture fixture =
        pendingFixture(
            "gs_bootstrap_member_issue_", PARTITION_CAPACITY, LAYOUT_VERSION, false, true);
    try {
      assertThat(fixture.plan().state())
          .isEqualTo(CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED);
      Map<String, List<String>> retainedBefore = rowSnapshots(fixture.testSchema().dsl());
      List<String> historyBefore = historySnapshot(fixture.testSchema().dsl());
      migrateRemaining(fixture.testSchema());

      assertThat(rowSnapshots(fixture.testSchema().dsl())).isEqualTo(retainedBefore);
      List<String> historyAfterMigration = historySnapshot(fixture.testSchema().dsl());
      assertThat(historyAfterMigration).hasSize(historyBefore.size() + 1);
      assertThat(historyAfterMigration.subList(0, historyBefore.size()))
          .containsExactlyElementsOf(historyBefore);
      assertThat(
              fixture
                  .testSchema()
                  .dsl()
                  .fetchSingle("SELECT script FROM flyway_schema_history WHERE version = '1032'")
                  .get(0, String.class))
          .isEqualTo("V1032__canonical_issuer_bootstrap_member_operation.sql");
      assertThat(
              fixture
                  .testSchema()
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_canonical_issuer_bootstrap_member_operation")
                  .get(0, Long.class))
          .isZero();

      var owner =
          new CanonicalGameplayIssuerBootstrapMemberOperationRepository(fixture.testSchema().dsl());
      var operation =
          owner.beginOrResumePendingPhysicalProof(
              fixture.intent().operationId(),
              fixture.requestId(),
              0,
              fixture.currentAccountCheckpoint(),
              stableIncarnation("stable-a"));
      var value = operation.expectedMemberValue();
      assertThat(operation.status())
          .isEqualTo(CanonicalGameplayIssuerBootstrapMemberOperation.Status.PENDING_PHYSICAL_PROOF);
      assertThat(operation.operationId())
          .isNotEqualTo(fixture.plan().memberWorks().getFirst().memberOperationId())
          .isNotEqualTo(fixture.plan().memberWorks().getFirst().memberPreparationFenceId());
      assertThat(operation.operationFence()).isGreaterThan(value.anchor().coverageFence());
      assertThat(operation.localMemberGeneration()).isGreaterThan(operation.operationFence());
      assertThat(value.memberOperation().coverageGeneration())
          .isEqualTo(operation.localMemberGeneration());
      assertThat(value.anchor().issuerCoverageOperationId())
          .isEqualTo(fixture.intent().operationId());
      assertThat(value.anchor().operationFence()).isEqualTo(fixture.intent().operationFence());
      assertThat(value.anchor().coverageFence()).isEqualTo(fixture.intent().coverageFence());
      assertThat(value.anchor().issuerInventorySnapshotRevision())
          .isEqualTo(fixture.intent().inventorySnapshotRevision());
      assertThat(value.reservation().issuerCoverageOperationId())
          .isNotEqualTo(value.anchor().issuerCoverageOperationId());
      assertThat(value.reservation().issuerCoverageOperationFence())
          .isEqualTo(BigInteger.valueOf(17));
      assertThat(value.reservation().coverageFence()).isEqualTo(BigInteger.valueOf(19));
      var originalReservationRow =
          fixture
              .testSchema()
              .dsl()
              .fetchOne(
                  "SELECT issuer_coverage_operation_id, issuer_coverage_operation_fence,"
                      + " coverage_fence, inventory_snapshot_revision"
                      + " FROM game_session_canonical_issuer_partition_reservation"
                      + " WHERE reservation_id = ?",
                  fixture.preparedBinding().issuerReservationId());
      assertThat(originalReservationRow).isNotNull();
      assertThat(value.reservation().issuerCoverageOperationId())
          .isEqualTo(originalReservationRow.get("issuer_coverage_operation_id", UUID.class));
      assertThat(value.reservation().issuerCoverageOperationFence())
          .isEqualTo(
              originalReservationRow
                  .get("issuer_coverage_operation_fence", BigDecimal.class)
                  .toBigIntegerExact());
      assertThat(value.reservation().coverageFence())
          .isEqualTo(
              originalReservationRow.get("coverage_fence", BigDecimal.class).toBigIntegerExact());
      assertThat(value.reservation().inventorySnapshotRevision())
          .isEqualTo(
              originalReservationRow
                  .get("inventory_snapshot_revision", BigDecimal.class)
                  .toBigIntegerExact());
      assertThat(operation.bindingLifecycle())
          .isEqualTo(CanonicalGameplayBindingInventoryEntry.Lifecycle.CANDIDATE_PREPARED);
      assertThat(operation.expectedBindingPresent()).isFalse();
      assertThat(operation.countsTowardCapacity()).isTrue();
      assertThat(CanonicalGameplayIssuerMemberV2Codec.decode(operation.exactMemberValueBytes()))
          .isEqualTo(value);

      var restarted =
          new CanonicalGameplayIssuerBootstrapMemberOperationRepository(fixture.testSchema().dsl())
              .beginOrResumePendingPhysicalProof(
                  fixture.intent().operationId(),
                  fixture.requestId(),
                  0,
                  fixture.currentAccountCheckpoint(),
                  stableIncarnation("stable-a"));
      assertThat(restarted.operationId()).isEqualTo(operation.operationId());
      assertThat(restarted.operationFence()).isEqualTo(operation.operationFence());
      assertThat(restarted.localMemberGeneration()).isEqualTo(operation.localMemberGeneration());
      assertThat(restarted.exactMemberValueBytes())
          .containsExactly(operation.exactMemberValueBytes());
      assertThat(rowSnapshots(fixture.testSchema().dsl())).isEqualTo(retainedBefore);

      assertThatThrownBy(
              () ->
                  owner.beginOrResumePendingPhysicalProof(
                      fixture.intent().operationId(),
                      fixture.requestId(),
                      0,
                      fixture.currentAccountCheckpoint(),
                      stableIncarnation("changed-incarnation")))
          .isInstanceOf(IllegalStateException.class);
      assertThat(
              rowImages(
                  fixture.testSchema().dsl(),
                  "game_session_canonical_issuer_bootstrap_member_operation"))
          .hasSize(1);

      assertThatThrownBy(
              () ->
                  fixture
                      .testSchema()
                      .dsl()
                      .execute(
                          "UPDATE game_session_canonical_issuer_bootstrap_member_operation"
                              + " SET member_status = 'PHYSICAL_PROOF_COMPLETE' WHERE member_operation_id = ?",
                          operation.operationId()))
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(
              () ->
                  fixture
                      .testSchema()
                      .dsl()
                      .execute(
                          "DELETE FROM game_session_canonical_issuer_bootstrap_member_operation"
                              + " WHERE member_operation_id = ?",
                          operation.operationId()))
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(
              () ->
                  fixture
                      .testSchema()
                      .dsl()
                      .execute("TRUNCATE game_session_canonical_issuer_bootstrap_member_operation"))
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(
              () ->
                  fixture
                      .testSchema()
                      .dsl()
                      .execute(
                          "UPDATE game_session_canonical_issuer_bootstrap_member_op_counter"
                              + " SET last_issued_operation_fence = last_issued_operation_fence + 1"
                              + " WHERE issuer_id = ?",
                          ISSUER))
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(
              () ->
                  fixture
                      .testSchema()
                      .dsl()
                      .execute(
                          "DELETE FROM game_session_canonical_issuer_bootstrap_member_op_counter"
                              + " WHERE issuer_id = ?",
                          ISSUER))
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(
              () ->
                  fixture
                      .testSchema()
                      .dsl()
                      .execute(
                          "TRUNCATE game_session_canonical_issuer_bootstrap_member_op_counter"))
          .isInstanceOf(DataAccessException.class);
      assertThat(
              rowImages(
                  fixture.testSchema().dsl(),
                  "game_session_canonical_issuer_bootstrap_member_operation"))
          .hasSize(1);
      assertThat(
              fixture
                  .testSchema()
                  .dsl()
                  .fetchSingle(
                      "SELECT counter_version FROM game_session_canonical_issuer_bootstrap_member_op_counter"
                          + " WHERE issuer_id = ?",
                      ISSUER)
                  .get(0, Long.class))
          .isEqualTo(1L);
      assertThat(historySnapshot(fixture.testSchema().dsl()))
          .containsExactlyElementsOf(historyAfterMigration);
    } finally {
      dropSchema(fixture.testSchema().schema());
    }
  }

  @Test
  void groupedPartitionWorkIssuesAllExactOperationsConcurrentlyAndLeavesEmptyPartitionsUnallocated()
      throws Exception {
    Fixture fixture =
        pendingFixture(
            "gs_bootstrap_member_group_", PARTITION_CAPACITY, LAYOUT_VERSION, true, true);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      assertThat(fixture.plan().state())
          .isEqualTo(CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED);
      assertThat(fixture.plan().memberWorks()).hasSize(2);
      migrateRemaining(fixture.testSchema());

      var ready = new CountDownLatch(2);
      var start = new CountDownLatch(1);
      var first = executor.submit(() -> preparePartitionAtGate(fixture, ready, start));
      var second = executor.submit(() -> preparePartitionAtGate(fixture, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var firstWork = first.get(30, TimeUnit.SECONDS);
      var secondWork = second.get(30, TimeUnit.SECONDS);

      assertThat(firstWork.buildPlan().planDigest()).isEqualTo(fixture.plan().planDigest());
      assertThat(firstWork.partitionId()).isEqualTo(fixture.partitionId());
      assertThat(firstWork.memberOperations()).hasSize(2);
      assertThat(secondWork.memberOperations())
          .extracting(CanonicalGameplayIssuerBootstrapMemberOperation::operationId)
          .containsExactlyElementsOf(
              firstWork.memberOperations().stream()
                  .map(CanonicalGameplayIssuerBootstrapMemberOperation::operationId)
                  .toList());
      assertThat(firstWork.memberOperations())
          .extracting(CanonicalGameplayIssuerBootstrapMemberOperation::operationId)
          .doesNotHaveDuplicates();
      assertThat(firstWork.memberOperations())
          .extracting(CanonicalGameplayIssuerBootstrapMemberOperation::expectedBindingPresent)
          .containsOnly(false);
      assertThat(firstWork.memberOperations().get(1).operationFence())
          .isGreaterThan(firstWork.memberOperations().getFirst().operationFence());
      assertThat(firstWork.memberOperations().get(1).localMemberGeneration())
          .isGreaterThan(firstWork.memberOperations().getFirst().localMemberGeneration());
      for (var operation : firstWork.memberOperations()) {
        assertThat(operation.expectedMemberValue().reservation().partitionId())
            .isEqualTo(firstWork.partitionId());
        assertThat(operation.status())
            .isEqualTo(
                CanonicalGameplayIssuerBootstrapMemberOperation.Status.PENDING_PHYSICAL_PROOF);
      }
      assertThat(
              fixture
                  .testSchema()
                  .dsl()
                  .fetchSingle(
                      "SELECT counter_version FROM game_session_canonical_issuer_bootstrap_member_op_counter"
                          + " WHERE issuer_id = ?",
                      ISSUER)
                  .get(0, Long.class))
          .isEqualTo(2L);

      BigInteger emptyPartition = fixture.partitionId().add(BigInteger.ONE).mod(PARTITION_COUNT);
      var empty =
          new CanonicalGameplayIssuerBootstrapMemberOperationRepository(fixture.testSchema().dsl())
              .preparePartitionWork(
                  fixture.intent().operationId(),
                  fixture.requestId(),
                  emptyPartition,
                  fixture.currentAccountCheckpoint(),
                  stableIncarnation("stable-a"));
      assertThat(empty.buildPlan().planDigest()).isEqualTo(fixture.plan().planDigest());
      assertThat(empty.partitionId()).isEqualTo(emptyPartition);
      assertThat(empty.memberOperations()).isEmpty();
      assertThat(
              fixture
                  .testSchema()
                  .dsl()
                  .fetchSingle(
                      "SELECT counter_version FROM game_session_canonical_issuer_bootstrap_member_op_counter"
                          + " WHERE issuer_id = ?",
                      ISSUER)
                  .get(0, Long.class))
          .isEqualTo(2L);
    } finally {
      executor.shutdownNow();
      dropSchema(fixture.testSchema().schema());
    }
  }

  @Test
  void wrongRequestCheckpointAndChangedFrozenSourceRefuseWithoutAllocatingCounters() {
    Fixture fixture =
        pendingFixture(
            "gs_bootstrap_member_refusal_", PARTITION_CAPACITY, LAYOUT_VERSION, false, true);
    try {
      migrateRemaining(fixture.testSchema());
      var owner =
          new CanonicalGameplayIssuerBootstrapMemberOperationRepository(fixture.testSchema().dsl());
      assertThatThrownBy(
              () ->
                  owner.preparePartitionWork(
                      fixture.intent().operationId(),
                      UUID.randomUUID(),
                      fixture.partitionId(),
                      fixture.currentAccountCheckpoint(),
                      stableIncarnation("stable-a")))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(
              () ->
                  owner.preparePartitionWork(
                      fixture.intent().operationId(),
                      fixture.requestId(),
                      fixture.partitionId(),
                      sourceCheckpoint(BigInteger.valueOf(8)),
                      stableIncarnation("stable-a")))
          .isInstanceOf(IllegalStateException.class);
      assertThat(
              fixture
                  .testSchema()
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_canonical_issuer_bootstrap_member_op_counter")
                  .get(0, Long.class))
          .isZero();

      fixture
          .testSchema()
          .dsl()
          .execute(
              "UPDATE game_session_canonical_issuer_partition_reservation"
                  + " SET coverage_fence = coverage_fence + 1 WHERE reservation_id = ?",
              fixture.preparedBinding().issuerReservationId());
      assertThatThrownBy(
              () ->
                  owner.preparePartitionWork(
                      fixture.intent().operationId(),
                      fixture.requestId(),
                      fixture.partitionId(),
                      fixture.currentAccountCheckpoint(),
                      stableIncarnation("stable-a")))
          .isInstanceOf(IllegalStateException.class);
      assertThat(
              rowImages(
                  fixture.testSchema().dsl(),
                  "game_session_canonical_issuer_bootstrap_member_operation"))
          .isEmpty();
      assertThat(
              fixture
                  .testSchema()
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_canonical_issuer_bootstrap_member_op_counter")
                  .get(0, Long.class))
          .isZero();
    } finally {
      dropSchema(fixture.testSchema().schema());
    }
  }

  @Test
  void missingPlanMissingControlAndBlockedLayoutRefuseWithoutMemberOperation() {
    Fixture missingPlan =
        pendingFixture(
            "gs_bootstrap_member_no_plan_", PARTITION_CAPACITY, LAYOUT_VERSION, false, false);
    try {
      migrateRemaining(missingPlan.testSchema());
      assertThatThrownBy(
              () ->
                  new CanonicalGameplayIssuerBootstrapMemberOperationRepository(
                          missingPlan.testSchema().dsl())
                      .preparePartitionWork(
                          missingPlan.intent().operationId(),
                          missingPlan.requestId(),
                          missingPlan.partitionId(),
                          missingPlan.currentAccountCheckpoint(),
                          stableIncarnation("stable-a")))
          .isInstanceOf(IllegalStateException.class);
      assertNoIssuedRows(missingPlan.testSchema().dsl());
    } finally {
      dropSchema(missingPlan.testSchema().schema());
    }

    Fixture missingControl =
        pendingFixture(
            "gs_bootstrap_member_no_control_", PARTITION_CAPACITY, LAYOUT_VERSION, false, true);
    try {
      migrateRemaining(missingControl.testSchema());
      missingControl
          .testSchema()
          .dsl()
          .execute(
              "ALTER TABLE game_session_canonical_issuer_layout_bootstrap_control"
                  + " DROP CONSTRAINT fk_gs_issuer_layout_bootstrap_control_intent");
      missingControl
          .testSchema()
          .dsl()
          .execute(
              "DELETE FROM game_session_canonical_issuer_layout_bootstrap_control WHERE issuer_id = ?",
              ISSUER);
      assertThatThrownBy(
              () ->
                  new CanonicalGameplayIssuerBootstrapMemberOperationRepository(
                          missingControl.testSchema().dsl())
                      .preparePartitionWork(
                          missingControl.intent().operationId(),
                          missingControl.requestId(),
                          missingControl.partitionId(),
                          missingControl.currentAccountCheckpoint(),
                          stableIncarnation("stable-a")))
          .isInstanceOf(IllegalStateException.class);
      assertNoIssuedRows(missingControl.testSchema().dsl());
    } finally {
      dropSchema(missingControl.testSchema().schema());
    }

    Fixture blockedLayout =
        pendingFixture(
            "gs_bootstrap_member_bad_layout_",
            PARTITION_CAPACITY,
            LAYOUT_VERSION.add(BigInteger.ONE),
            false,
            true);
    try {
      assertThat(blockedLayout.plan().state())
          .isEqualTo(CanonicalGameplayIssuerPartitionBuildPlan.State.BLOCKED);
      migrateRemaining(blockedLayout.testSchema());
      assertThatThrownBy(
              () ->
                  new CanonicalGameplayIssuerBootstrapMemberOperationRepository(
                          blockedLayout.testSchema().dsl())
                      .preparePartitionWork(
                          blockedLayout.intent().operationId(),
                          blockedLayout.requestId(),
                          blockedLayout.partitionId(),
                          blockedLayout.currentAccountCheckpoint(),
                          stableIncarnation("stable-a")))
          .isInstanceOf(IllegalStateException.class);
      assertNoIssuedRows(blockedLayout.testSchema().dsl());
    } finally {
      dropSchema(blockedLayout.testSchema().schema());
    }
  }

  private static CanonicalGameplayIssuerBootstrapPartitionWork preparePartitionAtGate(
      Fixture fixture, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Test start gate timed out");
    }
    return new CanonicalGameplayIssuerBootstrapMemberOperationRepository(fixture.testSchema().dsl())
        .preparePartitionWork(
            fixture.intent().operationId(),
            fixture.requestId(),
            fixture.partitionId(),
            fixture.currentAccountCheckpoint(),
            stableIncarnation("stable-a"));
  }

  private static Fixture pendingFixture(
      String prefix,
      BigInteger reservationCapacity,
      BigInteger descriptorLayoutVersion,
      boolean addReservationOnly,
      boolean createPlan) {
    TestSchema testSchema = newTestSchemaAtVersion(prefix, "1030");
    establishActualLegacyDisposition(testSchema.dsl());
    CanonicalGameplayBindingTransitionSnapshot preparedBinding =
        prepareActualBinding(
            new CanonicalGameplayBindingInventoryRepository(testSchema.dsl()), reservationCapacity);
    BigInteger partitionId = preparedBinding.candidate().bindingRef().partitionId(PARTITION_COUNT);
    if (addReservationOnly) {
      insertReservationOnly(
          testSchema.dsl(),
          preparedBinding.candidate(),
          preparedBinding.inventoryRevision(),
          reservationCapacity,
          PARTITION_COUNT,
          partitionId);
    }
    UUID requestId = UUID.randomUUID();
    var checkpoint = sourceCheckpoint(BigInteger.valueOf(7));
    var intent =
        new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl())
            .beginInitialBootstrapIntent(
                requestId,
                descriptor(descriptorLayoutVersion, PARTITION_COUNT, reservationCapacity),
                checkpoint);
    migrateThrough1031(testSchema);
    CanonicalGameplayIssuerPartitionBuildPlan plan =
        createPlan
            ? new CanonicalGameplayIssuerPartitionBuildRepository(testSchema.dsl())
                .prepareInitialBuildPlan(intent.operationId(), requestId)
            : null;
    return new Fixture(
        testSchema, preparedBinding, intent, requestId, checkpoint, partitionId, plan);
  }

  private static CanonicalGameplayBindingTransitionSnapshot prepareActualBinding(
      CanonicalGameplayBindingInventoryRepository inventory, BigInteger reservationCapacity) {
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
            LAYOUT_VERSION,
            PARTITION_COUNT,
            reservationCapacity);
    return inventory.prepare(
        new CanonicalGameplayBindingTransitionRequest(
            transitionId,
            UUID.randomUUID(),
            candidate,
            new CanonicalIssuerReservationFenceEvidence(
                reservationId,
                reservationFence,
                issuerCoverageOperationId,
                BigInteger.valueOf(17),
                BigInteger.valueOf(19),
                BigInteger.ONE),
            null,
            null));
  }

  private static void insertReservationOnly(
      DSLContext dsl,
      CanonicalGameplayBindingIdentity template,
      BigInteger revision,
      BigInteger capacity,
      BigInteger partitionCount,
      BigInteger partitionId) {
    UUID sessionUuid = sessionUuidForPartition(template, partitionCount, partitionId);
    UUID reservationId = UUID.randomUUID();
    UUID transitionId = UUID.randomUUID();
    UUID reservationFence = UUID.randomUUID();
    UUID originalCoverageOperationId = UUID.randomUUID();
    String sessionId = sessionUuid.toString();
    byte[] bindingRef =
        CanonicalGameplayBindingRef.of(
                template.tenantId().toString(), template.gameInstanceId().toString(), sessionId)
            .bytes();
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
            + " VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED',"
            + " ?, ?, 17, 19, ?, ?)",
        reservationId,
        bindingRef,
        template.accountId(),
        template.tenantId(),
        template.gameInstanceId(),
        template.runtimeGameInstanceId(),
        sessionId,
        transitionId,
        ISSUER,
        new BigDecimal(template.issuerAuthGeneration()),
        template.playableStateNamespaceId(),
        UUID.fromString("88888888-8888-4888-8888-888888888888"),
        template.playableStateScope(),
        template.regionId(),
        new BigDecimal(template.regionEpoch()),
        new BigDecimal(partitionId),
        new BigDecimal(LAYOUT_VERSION),
        new BigDecimal(partitionCount),
        new BigDecimal(capacity),
        reservationFence,
        originalCoverageOperationId,
        new BigDecimal(revision),
        new BigDecimal(revision));
  }

  private static UUID sessionUuidForPartition(
      CanonicalGameplayBindingIdentity template,
      BigInteger partitionCount,
      BigInteger targetPartition) {
    for (int candidate = 1; candidate <= 10_000; candidate++) {
      UUID sessionUuid =
          UUID.nameUUIDFromBytes(
              ("issuer-bootstrap-member-session-" + candidate).getBytes(StandardCharsets.UTF_8));
      byte[] bindingRef =
          CanonicalGameplayBindingRef.of(
                  template.tenantId().toString(),
                  template.gameInstanceId().toString(),
                  sessionUuid.toString())
              .bytes();
      if (CanonicalGameplayBindingRef.fromExactBytes(bindingRef)
          .partitionId(partitionCount)
          .equals(targetPartition)) {
        return sessionUuid;
      }
    }
    throw new IllegalStateException("Could not prepare a reservation in the target partition");
  }

  private static CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedLayoutDescriptor
      descriptor(BigInteger version, BigInteger count, BigInteger capacity) {
    String encoded =
        "issuer-layout-descriptor/v1|"
            + ISSUER
            + "|"
            + version
            + "|"
            + count
            + "|"
            + capacity
            + "|"
            + CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY;
    return new CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedLayoutDescriptor(
        ISSUER,
        version,
        count,
        capacity,
        CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY,
        encoded.getBytes(StandardCharsets.UTF_8));
  }

  private static CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint
      sourceCheckpoint(BigInteger issuerGeneration) {
    long sequence = issuerGeneration.longValueExact() - 1L;
    var event =
        AccountAuthoritySourceEventV1Codec.sealIssuer(
            new AccountAuthoritySourceEventV1Codec.IssuerPreimage(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                AccountAuthoritySourceEventV1Codec.EVENT_STREAM_PREFIX + "issuer/" + ISSUER,
                Long.toString(sequence),
                ISSUER,
                issuerGeneration.toString(),
                issuerGeneration.toString(),
                "ISSUER_SECURITY_CHANGE"));
    byte[] eventBytes = event.canonicalJsonUtf8();
    return new CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint(
        ISSUER, issuerGeneration, eventBytes, eventBytes);
  }

  private static CanonicalGameplayIssuerBootstrapMemberOperationRepository.TrustedStableIncarnation
      stableIncarnation(String seed) {
    String digest = digest(seed);
    return new CanonicalGameplayIssuerBootstrapMemberOperationRepository.TrustedStableIncarnation(
        "controlled-attestor-" + seed,
        digest,
        new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
            "controlled-deployment-" + seed,
            "deployment-generation-" + seed,
            List.of("controlled-node-" + seed),
            "controlled-redis-build-" + seed,
            "controlled-lua-registry-" + seed,
            digest,
            "READY",
            "boot-" + seed,
            "startup-" + seed,
            "storage-" + seed,
            "aof-" + seed),
        digest,
        digest);
  }

  private static String digest(String text) {
    try {
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void establishActualLegacyDisposition(DSLContext dsl) {
    var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
    var migration = new CanonicalGameplayLegacyMigrationRepository(dsl, inventory);
    UUID cohortId = UUID.randomUUID();
    UUID writerFence = UUID.randomUUID();
    var storageIdentity =
        new CanonicalGameplayLegacyMigrationStorageIdentity(
            "controlled-cluster-uid",
            "controlled-namespace-uid",
            "controlled-producer-pod-uid",
            "controlled-container-id",
            "controlled-node-uid",
            "123456789",
            42L,
            "controlled-postgres-volume-uid",
            "controlled-redis-run-id",
            "controlled-redis-volume-uid");
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
          public void requireStillFenced(CanonicalGameplayLegacyMigrationStorageIdentity expected) {
            if (!storageIdentity.equals(expected)) {
              throw new IllegalStateException("Controlled fixture storage identity changed");
            }
          }
        };
    var owner =
        new CanonicalGameplayLegacyMigrationOwner(
            migration,
            inventory,
            () -> fencedCohort,
            ignored ->
                new CanonicalGameplayLegacyMigrationSourceSnapshot(
                    EnumSet.of(
                        Family.TENANT_SESSION_CONTEXT,
                        Family.SESSION_ALIAS_CONTEXT,
                        Family.GAMEPLAY_IDENTITY_CONTEXT,
                        Family.GAMEPLAY_NAME_CONTEXT,
                        Family.MOVEMENT_EFFECT,
                        Family.DURABLE_EFFECT),
                    List.of()),
            new CanonicalGameplayLegacyMigrationOwner.NamespaceIndexOwner() {
              @Override
              public void rebuildExact(
                  CanonicalGameplayLegacyMigrationOwner.FencedCohort ignored,
                  CanonicalGameplayLegacyMigrationSourceSnapshot source,
                  net.firedevops.firemud.gamesession.binding
                          .CanonicalGameplayBindingInventorySnapshot
                      canonical) {
                assertThat(canonical.bindings()).isEmpty();
                assertThat(canonical.reservations()).isEmpty();
              }

              @Override
              public CanonicalGameplayLegacyMigrationReadback readBackExact(
                  CanonicalGameplayLegacyMigrationOwner.FencedCohort ignored,
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

  private static void migrateThrough1031(TestSchema testSchema) {
    Flyway.configure()
        .dataSource(testSchema.dataSource())
        .schemas(testSchema.schema())
        .defaultSchema(testSchema.schema())
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .target(MigrationVersion.fromVersion("1031"))
        .load()
        .migrate();
  }

  private static void migrateRemaining(TestSchema testSchema) {
    Flyway.configure()
        .dataSource(testSchema.dataSource())
        .schemas(testSchema.schema())
        .defaultSchema(testSchema.schema())
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
  }

  private static TestSchema newTestSchemaAtVersion(String prefix, String version) {
    String schema = prefix + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history")
            .locations(MIGRATION_LOCATION);
    if (version != null) configuration.target(MigrationVersion.fromVersion(version));
    configuration.load().migrate();
    return new TestSchema(schema, dataSource, DSL.using(dataSource, SQLDialect.POSTGRES));
  }

  private static Map<String, List<String>> rowSnapshots(DSLContext dsl) {
    Map<String, List<String>> snapshots = new LinkedHashMap<>();
    for (String table : RETAINED_TABLES) snapshots.put(table, rowImages(dsl, table));
    return Map.copyOf(snapshots);
  }

  private static List<String> historySnapshot(DSLContext dsl) {
    return dsl.fetch(
            "SELECT to_jsonb(history_row)::text AS row_image FROM flyway_schema_history history_row"
                + " ORDER BY installed_rank")
        .getValues("row_image", String.class);
  }

  private static List<String> rowImages(DSLContext dsl, String tableName) {
    return dsl.fetch(
            "SELECT to_jsonb(retained_row)::text AS row_image FROM "
                + tableName
                + " retained_row ORDER BY to_jsonb(retained_row)::text")
        .getValues("row_image", String.class);
  }

  private static void assertNoIssuedRows(DSLContext dsl) {
    assertThat(rowImages(dsl, "game_session_canonical_issuer_bootstrap_member_operation"))
        .isEmpty();
    assertThat(rowImages(dsl, "game_session_canonical_issuer_bootstrap_member_op_counter"))
        .isEmpty();
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
    try (var connection = dataSource("public").getConnection()) {
      DSL.using(connection, SQLDialect.POSTGRES)
          .execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    } catch (Exception failure) {
      throw new IllegalStateException(
          "Could not drop the owned member-operation test schema", failure);
    }
  }

  private record Fixture(
      TestSchema testSchema,
      CanonicalGameplayBindingTransitionSnapshot preparedBinding,
      CanonicalGameplayIssuerLayoutBootstrapIntent intent,
      UUID requestId,
      CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint
          currentAccountCheckpoint,
      BigInteger partitionId,
      CanonicalGameplayIssuerPartitionBuildPlan plan) {}

  private record TestSchema(String schema, DriverManagerDataSource dataSource, DSLContext dsl) {}
}
