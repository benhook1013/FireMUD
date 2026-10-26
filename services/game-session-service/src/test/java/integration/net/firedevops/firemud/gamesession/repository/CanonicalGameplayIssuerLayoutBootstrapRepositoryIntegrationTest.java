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
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationReadback;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot.Family;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
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
class CanonicalGameplayIssuerLayoutBootstrapRepositoryIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID NAMESPACE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID GAME_INSTANCE_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID REGION_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID PREPARED_CHARACTER_ID =
      UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID BLOCKED_CHARACTER_ID =
      UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final BigInteger LAYOUT_VERSION = BigInteger.valueOf(3);
  private static final BigInteger PARTITION_COUNT = BigInteger.valueOf(16);
  private static final BigInteger PARTITION_CAPACITY = BigInteger.valueOf(32);

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void absenceAndRequiredLegacyDispositionCannotCreateAnIntent() {
    TestSchema testSchema = newTestSchema("gs_issuer_boot_legacy_");
    try {
      var owner = new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl());
      var descriptor = descriptor(LAYOUT_VERSION, PARTITION_COUNT, PARTITION_CAPACITY);
      var source = sourceCheckpoint(BigInteger.valueOf(7));
      UUID requestId = UUID.randomUUID();

      assertThatThrownBy(() -> owner.beginInitialBootstrapIntent(requestId, descriptor, source))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("actual VERIFIED legacy disposition");
      assertThat(
              countRows(testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_intent"))
          .isZero();
      assertThat(
              countRows(testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_control"))
          .isZero();

      testSchema
          .dsl()
          .execute(
              "DELETE FROM game_session_canonical_binding_legacy_disposition WHERE singleton_id = 1");
      assertThatThrownBy(
              () -> owner.beginInitialBootstrapIntent(UUID.randomUUID(), descriptor, source))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("actual VERIFIED legacy disposition");
      assertThat(
              countRows(testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_intent"))
          .isZero();
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void pendingIntentFreezesActualInventoryAndExactInputsAcrossRetryAndRestart() {
    TestSchema testSchema = newTestSchema("gs_issuer_boot_retry_");
    try {
      establishActualLegacyDisposition(testSchema.dsl());
      var inventory = new CanonicalGameplayBindingInventoryRepository(testSchema.dsl());
      var prepared = prepareActualBinding(inventory);
      UUID reservationOnlyId = UUID.randomUUID();
      insertReservationOnly(
          testSchema.dsl(), prepared.candidate(), prepared.inventoryRevision(), reservationOnlyId);

      var owner = new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl());
      UUID requestId = UUID.randomUUID();
      var descriptor = descriptor(LAYOUT_VERSION, PARTITION_COUNT, PARTITION_CAPACITY);
      var source = sourceCheckpoint(BigInteger.valueOf(7));
      var intent = owner.beginInitialBootstrapIntent(requestId, descriptor, source);

      assertThat(intent.state())
          .isEqualTo(CanonicalGameplayIssuerLayoutBootstrapIntent.State.INITIAL_BOOTSTRAP_INTENT);
      assertThat(intent.operationFence()).isPositive();
      assertThat(intent.coverageFence()).isPositive().isNotEqualTo(intent.operationFence());
      assertThat(intent.inventorySnapshotRevision()).isEqualTo(prepared.inventoryRevision());
      assertThat(intent.sourceCheckpoint().issuerId()).isEqualTo(ISSUER);
      assertThat(intent.sourceCheckpoint().issuerAuthGeneration()).isEqualTo(BigInteger.valueOf(7));
      assertThat(intent.cohortRows())
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::kind)
          .contains(
              CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.BINDING,
              CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.TRANSITION,
              CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.RESERVATION,
              CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.ACCOUNT_OBLIGATION,
              CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.ISSUER_OBLIGATION,
              CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.REGION_OBLIGATION);
      assertThat(intent.cohortRows())
          .filteredOn(
              row ->
                  row.kind() == CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.RESERVATION)
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceKey)
          .contains(reservationOnlyId.toString());

      var exactRetry = owner.beginInitialBootstrapIntent(requestId, descriptor, source);
      var restartedRead =
          new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl())
              .readCurrentInitialBootstrapIntent(intent.operationId());
      assertThat(exactRetry.operationId()).isEqualTo(intent.operationId());
      assertThat(exactRetry.operationFence()).isEqualTo(intent.operationFence());
      assertThat(exactRetry.coverageFence()).isEqualTo(intent.coverageFence());
      assertThat(exactRetry.cohortDigest()).isEqualTo(intent.cohortDigest());
      assertThat(restartedRead.operationId()).isEqualTo(intent.operationId());
      assertThat(restartedRead.cohortDigest()).isEqualTo(intent.cohortDigest());
      assertThat(restartedRead.cohortRows())
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowDigest)
          .containsExactlyElementsOf(
              intent.cohortRows().stream()
                  .map(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowDigest)
                  .toList());

      var changedDescriptor = descriptor(LAYOUT_VERSION, PARTITION_COUNT, BigInteger.valueOf(33));
      assertThatThrownBy(
              () -> owner.beginInitialBootstrapIntent(requestId, changedDescriptor, source))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("changed its original trusted inputs");
      var changedSource = sourceCheckpoint(BigInteger.valueOf(8));
      assertThatThrownBy(
              () -> owner.beginInitialBootstrapIntent(requestId, descriptor, changedSource))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("changed its original trusted inputs");
      var malformedSource =
          new CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint(
              ISSUER,
              BigInteger.valueOf(7),
              source.checkpointBytes(),
              "{".getBytes(StandardCharsets.UTF_8));
      assertThatThrownBy(
              () -> owner.beginInitialBootstrapIntent(requestId, descriptor, malformedSource))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not codec-verified");
      assertThat(owner.readCurrentInitialBootstrapIntent(intent.operationId()).cohortDigest())
          .isEqualTo(intent.cohortDigest());
      assertThat(
              countRows(testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_control"))
          .isEqualTo(1);
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void shapeOnlyMemberRoundTripPreservesPopulatedReservationTupleBesideBootstrapAnchor() {
    TestSchema testSchema = newTestSchema("gs_issuer_boot_member_shape_");
    try {
      establishActualLegacyDisposition(testSchema.dsl());
      var inventory = new CanonicalGameplayBindingInventoryRepository(testSchema.dsl());
      var prepared = prepareActualBinding(inventory);
      List<String> reservationRowsBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_reservation");
      var durableReservationRow =
          testSchema
              .dsl()
              .fetchSingle(
                  "SELECT * FROM game_session_canonical_issuer_partition_reservation"
                      + " WHERE reservation_id = ?",
                  prepared.issuerReservationId());
      byte[] originalBindingRefBytes = durableReservationRow.get("binding_ref", byte[].class);
      var originalReservation =
          new CanonicalIssuerPartitionReservation(
              prepared.issuerReservationId(),
              prepared.candidate(),
              decimal(durableReservationRow.get("binding_generation", BigDecimal.class)),
              durableReservationRow.get("transition_id", UUID.class),
              decimal(durableReservationRow.get("partition_id", BigDecimal.class)),
              CanonicalIssuerPartitionReservation.Lifecycle.valueOf(
                  durableReservationRow.get("lifecycle", String.class)),
              durableReservationRow.get("reservation_fence", UUID.class),
              durableReservationRow.get("issuer_coverage_operation_id", UUID.class),
              decimal(
                  durableReservationRow.get("issuer_coverage_operation_fence", BigDecimal.class)),
              decimal(durableReservationRow.get("coverage_fence", BigDecimal.class)),
              decimal(durableReservationRow.get("inventory_snapshot_revision", BigDecimal.class)),
              decimal(durableReservationRow.get("inventory_revision", BigDecimal.class)));

      var bootstrapOwner = new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl());
      var intent =
          bootstrapOwner.beginInitialBootstrapIntent(
              UUID.randomUUID(),
              descriptor(LAYOUT_VERSION, PARTITION_COUNT, PARTITION_CAPACITY),
              sourceCheckpoint(BigInteger.valueOf(7)));
      assertThat(intent.state())
          .isEqualTo(CanonicalGameplayIssuerLayoutBootstrapIntent.State.INITIAL_BOOTSTRAP_INTENT);
      var anchor =
          new CanonicalGameplayIssuerMemberV2Value.Anchor(
              intent.operationId(),
              intent.operationFence(),
              intent.coverageFence(),
              intent.inventorySnapshotRevision());
      assertThat(
              new CanonicalGameplayIssuerMemberV2Value.Anchor(
                  originalReservation.issuerCoverageOperationId(),
                  originalReservation.issuerCoverageOperationFence(),
                  originalReservation.coverageFence(),
                  originalReservation.inventorySnapshotRevision()))
          .isNotEqualTo(anchor);

      // These member-operation and incarnation values are explicit shape fixtures; they do not
      // advance bootstrap state or establish trusted issuer, Redis, or admission evidence.
      var controlledMemberOperation =
          new CanonicalGameplayIssuerMemberV2Value.LocalMemberOperation(
              UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
              BigInteger.valueOf(101),
              BigInteger.valueOf(102));
      var controlledIncarnation = controlledIncarnationForShapeOnly();
      var value =
          new CanonicalGameplayIssuerMemberV2Value(
              prepared.candidate(),
              prepared.bindingGeneration(),
              originalReservation,
              anchor,
              controlledMemberOperation,
              intent.controlEpoch(),
              intent.layoutDescriptor(),
              intent.sourceCheckpoint(),
              controlledIncarnation);

      byte[] encoded = CanonicalGameplayIssuerMemberV2Codec.encode(value);
      CanonicalGameplayIssuerMemberV2Value decoded =
          CanonicalGameplayIssuerMemberV2Codec.decode(encoded);
      assertThat(decoded).isEqualTo(value);
      assertThat(decoded.reservation()).isEqualTo(originalReservation);
      assertThat(decoded.reservation().owner().bindingRef().bytes())
          .containsExactly(originalBindingRefBytes);
      assertThat(decoded.anchor()).isEqualTo(anchor);
      assertThat(decoded.memberOperation()).isEqualTo(controlledMemberOperation);
      assertThat(decoded.incarnation()).isEqualTo(controlledIncarnation);

      var reservationCohortRow =
          intent.cohortRows().stream()
              .filter(
                  row ->
                      row.kind()
                          == CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.RESERVATION)
              .findFirst()
              .orElseThrow();
      assertThat(reservationCohortRow.sourceKey())
          .isEqualTo(prepared.issuerReservationId().toString());
      var frozenReadback = bootstrapOwner.readCurrentInitialBootstrapIntent(intent.operationId());
      assertThat(frozenReadback.state())
          .isEqualTo(CanonicalGameplayIssuerLayoutBootstrapIntent.State.INITIAL_BOOTSTRAP_INTENT);
      assertThat(frozenReadback.cohortRows())
          .filteredOn(
              row ->
                  row.kind() == CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.RESERVATION)
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowDigest)
          .containsExactly(reservationCohortRow.sourceRowDigest());
      assertThat(rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_reservation"))
          .containsExactlyElementsOf(reservationRowsBefore);
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void pendingIntentBlocksLaterPreparationWithoutChangingInventoryOrItsCohort() {
    TestSchema testSchema = newTestSchema("gs_issuer_boot_blocks_new_");
    try {
      establishActualLegacyDisposition(testSchema.dsl());
      var inventory = new CanonicalGameplayBindingInventoryRepository(testSchema.dsl());
      prepareActualBinding(inventory);

      var owner = new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl());
      UUID requestId = UUID.randomUUID();
      var descriptor = descriptor(LAYOUT_VERSION, PARTITION_COUNT, PARTITION_CAPACITY);
      var source = sourceCheckpoint(BigInteger.valueOf(7));
      var originalIntent = owner.beginInitialBootstrapIntent(requestId, descriptor, source);
      List<String> originalCohortRowDigests =
          originalIntent.cohortRows().stream()
              .map(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowDigest)
              .toList();
      BigInteger originalInventoryRevision = currentInventoryRevision(testSchema.dsl());
      int bindingCount =
          countRows(testSchema.dsl(), "game_session_canonical_gameplay_binding_inventory");
      int transitionCount =
          countRows(testSchema.dsl(), "game_session_canonical_binding_transition");
      int reservationCount =
          countRows(testSchema.dsl(), "game_session_canonical_issuer_partition_reservation");
      int generationCount =
          countRows(testSchema.dsl(), "game_session_canonical_binding_generation");

      assertThatThrownBy(() -> prepareActualBinding(inventory, BLOCKED_CHARACTER_ID))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining(
              "Initial issuer-layout bootstrap intent blocks new binding preparation");

      assertThat(currentInventoryRevision(testSchema.dsl())).isEqualTo(originalInventoryRevision);
      assertThat(countRows(testSchema.dsl(), "game_session_canonical_gameplay_binding_inventory"))
          .isEqualTo(bindingCount);
      assertThat(countRows(testSchema.dsl(), "game_session_canonical_binding_transition"))
          .isEqualTo(transitionCount);
      assertThat(countRows(testSchema.dsl(), "game_session_canonical_issuer_partition_reservation"))
          .isEqualTo(reservationCount);
      assertThat(countRows(testSchema.dsl(), "game_session_canonical_binding_generation"))
          .isEqualTo(generationCount);

      var exactRetry = owner.beginInitialBootstrapIntent(requestId, descriptor, source);
      var currentIntent = owner.readCurrentInitialBootstrapIntent(originalIntent.operationId());
      assertThat(exactRetry.operationId()).isEqualTo(originalIntent.operationId());
      assertThat(exactRetry.operationFence()).isEqualTo(originalIntent.operationFence());
      assertThat(exactRetry.coverageFence()).isEqualTo(originalIntent.coverageFence());
      assertThat(exactRetry.cohortDigest()).isEqualTo(originalIntent.cohortDigest());
      assertThat(currentIntent.cohortRows())
          .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceRowDigest)
          .containsExactlyElementsOf(originalCohortRowDigests);
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void orphanedPendingIntentBlocksPreparationWithoutChangingItsFrozenSources() {
    TestSchema testSchema = newTestSchema("gs_issuer_boot_orphan_");
    try {
      establishActualLegacyDisposition(testSchema.dsl());
      var inventory = new CanonicalGameplayBindingInventoryRepository(testSchema.dsl());
      prepareActualBinding(inventory);
      var owner = new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl());
      var intent =
          owner.beginInitialBootstrapIntent(
              UUID.randomUUID(),
              descriptor(LAYOUT_VERSION, PARTITION_COUNT, PARTITION_CAPACITY),
              sourceCheckpoint(BigInteger.valueOf(7)));

      List<String> intentBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_intent");
      List<String> cohortBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_cohort_row");
      List<String> inventoryBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_gameplay_binding_inventory");
      List<String> generationBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_binding_generation");
      List<String> transitionBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_binding_transition");
      List<String> reservationBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_reservation");
      List<String> accountObligationBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_binding_account_index_obligation");
      List<String> issuerObligationBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_binding_issuer_index_obligation");
      List<String> regionObligationBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_binding_region_bridge_obligation");
      List<String> accountControlBefore =
          rowImages(testSchema.dsl(), "game_session_canonical_account_coverage_control");
      BigInteger inventoryRevisionBefore = currentInventoryRevision(testSchema.dsl());

      testSchema
          .dsl()
          .execute(
              "DELETE FROM game_session_canonical_issuer_layout_bootstrap_control "
                  + "WHERE issuer_id = ?",
              ISSUER);

      assertThatThrownBy(() -> prepareActualBinding(inventory, BLOCKED_CHARACTER_ID))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessageContaining(
              "Issuer bootstrap intent exists without its positive current control row");

      assertThat(
              countRows(testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_control"))
          .isZero();
      assertThat(
              rowImages(testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_intent"))
          .containsExactlyElementsOf(intentBefore);
      assertThat(
              rowImages(
                  testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_cohort_row"))
          .containsExactlyElementsOf(cohortBefore);
      assertThat(rowImages(testSchema.dsl(), "game_session_canonical_gameplay_binding_inventory"))
          .containsExactlyElementsOf(inventoryBefore);
      assertThat(rowImages(testSchema.dsl(), "game_session_canonical_binding_generation"))
          .containsExactlyElementsOf(generationBefore);
      assertThat(rowImages(testSchema.dsl(), "game_session_canonical_binding_transition"))
          .containsExactlyElementsOf(transitionBefore);
      assertThat(rowImages(testSchema.dsl(), "game_session_canonical_issuer_partition_reservation"))
          .containsExactlyElementsOf(reservationBefore);
      assertThat(
              rowImages(
                  testSchema.dsl(), "game_session_canonical_binding_account_index_obligation"))
          .containsExactlyElementsOf(accountObligationBefore);
      assertThat(
              rowImages(testSchema.dsl(), "game_session_canonical_binding_issuer_index_obligation"))
          .containsExactlyElementsOf(issuerObligationBefore);
      assertThat(
              rowImages(
                  testSchema.dsl(), "game_session_canonical_binding_region_bridge_obligation"))
          .containsExactlyElementsOf(regionObligationBefore);
      assertThat(rowImages(testSchema.dsl(), "game_session_canonical_account_coverage_control"))
          .containsExactlyElementsOf(accountControlBefore);
      assertThat(currentInventoryRevision(testSchema.dsl())).isEqualTo(inventoryRevisionBefore);
      assertThat(intent.state())
          .isEqualTo(CanonicalGameplayIssuerLayoutBootstrapIntent.State.INITIAL_BOOTSTRAP_INTENT);
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void preparationQueuedBeforeBootstrapIntentIsCapturedByTheIntent() throws Exception {
    TestSchema testSchema = newTestSchema("gs_issuer_boot_order_prep_");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      establishActualLegacyDisposition(testSchema.dsl());
      var inventory = new CanonicalGameplayBindingInventoryRepository(testSchema.dsl());
      var owner = new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl());
      UUID requestId = UUID.randomUUID();
      var descriptor = descriptor(LAYOUT_VERSION, PARTITION_COUNT, PARTITION_CAPACITY);
      var source = sourceCheckpoint(BigInteger.valueOf(7));
      CountDownLatch prepareStarted = new CountDownLatch(1);
      CountDownLatch intentStarted = new CountDownLatch(1);

      try (Connection blocker = testSchema.dataSource().getConnection()) {
        blocker.setAutoCommit(false);
        DSL.using(blocker, SQLDialect.POSTGRES)
            .fetchSingle(
                "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock"
                    + " WHERE singleton_id = 1 FOR UPDATE");

        Future<CanonicalGameplayBindingTransitionSnapshot> preparedFuture =
            executor.submit(
                () -> {
                  prepareStarted.countDown();
                  return prepareActualBinding(inventory);
                });
        assertThat(prepareStarted.await(10, TimeUnit.SECONDS)).isTrue();
        awaitInventoryClockWaiters(testSchema, 1);

        Future<CanonicalGameplayIssuerLayoutBootstrapIntent> intentFuture =
            executor.submit(
                () -> {
                  intentStarted.countDown();
                  return owner.beginInitialBootstrapIntent(requestId, descriptor, source);
                });
        assertThat(intentStarted.await(10, TimeUnit.SECONDS)).isTrue();
        awaitInventoryClockWaiters(testSchema, 2);

        blocker.commit();
        CanonicalGameplayBindingTransitionSnapshot prepared =
            preparedFuture.get(30, TimeUnit.SECONDS);
        CanonicalGameplayIssuerLayoutBootstrapIntent intent =
            intentFuture.get(30, TimeUnit.SECONDS);
        assertCapturedPreparationCohort(intent, prepared);
      }
    } finally {
      executor.shutdownNow();
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void bootstrapIntentQueuedBeforePreparationLeavesNoAllocatedRows() throws Exception {
    TestSchema testSchema = newTestSchema("gs_issuer_boot_order_intent_");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      establishActualLegacyDisposition(testSchema.dsl());
      var inventory = new CanonicalGameplayBindingInventoryRepository(testSchema.dsl());
      CanonicalGameplayBindingTransitionSnapshot retainedPreparation =
          prepareActualBinding(inventory);
      var owner = new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl());
      UUID requestId = UUID.randomUUID();
      var descriptor = descriptor(LAYOUT_VERSION, PARTITION_COUNT, PARTITION_CAPACITY);
      var source = sourceCheckpoint(BigInteger.valueOf(7));
      int bindingCount =
          countRows(testSchema.dsl(), "game_session_canonical_gameplay_binding_inventory");
      int generationCount =
          countRows(testSchema.dsl(), "game_session_canonical_binding_generation");
      int transitionCount =
          countRows(testSchema.dsl(), "game_session_canonical_binding_transition");
      int reservationCount =
          countRows(testSchema.dsl(), "game_session_canonical_issuer_partition_reservation");
      int accountObligationCount =
          countRows(testSchema.dsl(), "game_session_canonical_binding_account_index_obligation");
      int issuerObligationCount =
          countRows(testSchema.dsl(), "game_session_canonical_binding_issuer_index_obligation");
      int regionObligationCount =
          countRows(testSchema.dsl(), "game_session_canonical_binding_region_bridge_obligation");
      int accountControlCount =
          countRows(testSchema.dsl(), "game_session_canonical_account_coverage_control");
      CountDownLatch intentStarted = new CountDownLatch(1);
      CountDownLatch prepareStarted = new CountDownLatch(1);

      try (Connection blocker = testSchema.dataSource().getConnection()) {
        blocker.setAutoCommit(false);
        DSL.using(blocker, SQLDialect.POSTGRES)
            .fetchSingle(
                "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock"
                    + " WHERE singleton_id = 1 FOR UPDATE");

        Future<CanonicalGameplayIssuerLayoutBootstrapIntent> intentFuture =
            executor.submit(
                () -> {
                  intentStarted.countDown();
                  return owner.beginInitialBootstrapIntent(requestId, descriptor, source);
                });
        assertThat(intentStarted.await(10, TimeUnit.SECONDS)).isTrue();
        awaitInventoryClockWaiters(testSchema, 1);

        Future<PreparationAttempt> prepareFuture =
            executor.submit(
                () -> {
                  prepareStarted.countDown();
                  try {
                    return new PreparationAttempt(
                        prepareActualBinding(inventory, BLOCKED_CHARACTER_ID), null);
                  } catch (RuntimeException failure) {
                    return new PreparationAttempt(null, failure);
                  }
                });
        assertThat(prepareStarted.await(10, TimeUnit.SECONDS)).isTrue();
        awaitInventoryClockWaiters(testSchema, 2);

        blocker.commit();
        CanonicalGameplayIssuerLayoutBootstrapIntent intent =
            intentFuture.get(30, TimeUnit.SECONDS);
        PreparationAttempt preparation = prepareFuture.get(30, TimeUnit.SECONDS);
        assertThat(preparation.transition()).isNull();
        assertThat(preparation.failure())
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
            .hasMessageContaining(
                "Initial issuer-layout bootstrap intent blocks new binding preparation");
        assertCapturedPreparationCohort(intent, retainedPreparation);
        assertThat(currentInventoryRevision(testSchema.dsl()))
            .isEqualTo(retainedPreparation.inventoryRevision())
            .isEqualTo(intent.inventorySnapshotRevision());
        assertThat(countRows(testSchema.dsl(), "game_session_canonical_gameplay_binding_inventory"))
            .isEqualTo(bindingCount);
        assertThat(countRows(testSchema.dsl(), "game_session_canonical_binding_generation"))
            .isEqualTo(generationCount);
        assertThat(countRows(testSchema.dsl(), "game_session_canonical_binding_transition"))
            .isEqualTo(transitionCount);
        assertThat(
                countRows(testSchema.dsl(), "game_session_canonical_issuer_partition_reservation"))
            .isEqualTo(reservationCount);
        assertThat(
                countRows(
                    testSchema.dsl(), "game_session_canonical_binding_account_index_obligation"))
            .isEqualTo(accountObligationCount);
        assertThat(
                countRows(
                    testSchema.dsl(), "game_session_canonical_binding_issuer_index_obligation"))
            .isEqualTo(issuerObligationCount);
        assertThat(
                countRows(
                    testSchema.dsl(), "game_session_canonical_binding_region_bridge_obligation"))
            .isEqualTo(regionObligationCount);
        assertThat(countRows(testSchema.dsl(), "game_session_canonical_account_coverage_control"))
            .isEqualTo(accountControlCount);
        var retry = owner.beginInitialBootstrapIntent(requestId, descriptor, source);
        assertThat(retry.operationId()).isEqualTo(intent.operationId());
        assertThat(retry.cohortDigest()).isEqualTo(intent.cohortDigest());
      }
    } finally {
      executor.shutdownNow();
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void shiftedStoredCohortOrdinalIsRejectedWithoutChangingPendingIntentHeader() {
    TestSchema testSchema = newTestSchema("gs_issuer_boot_ordinal_");
    try {
      establishActualLegacyDisposition(testSchema.dsl());
      var inventory = new CanonicalGameplayBindingInventoryRepository(testSchema.dsl());
      prepareActualBinding(inventory);
      var owner = new CanonicalGameplayIssuerLayoutBootstrapRepository(testSchema.dsl());
      var intent =
          owner.beginInitialBootstrapIntent(
              UUID.randomUUID(),
              descriptor(LAYOUT_VERSION, PARTITION_COUNT, PARTITION_CAPACITY),
              sourceCheckpoint(BigInteger.valueOf(7)));
      assertThat(intent.cohortRows()).isNotEmpty();

      testSchema
          .dsl()
          .execute(
              "UPDATE game_session_canonical_issuer_layout_bootstrap_cohort_row "
                  + "SET row_ordinal = row_ordinal + 1000 "
                  + "WHERE operation_id = ? AND row_ordinal = 0",
              intent.operationId());

      assertThatThrownBy(() -> owner.readCurrentInitialBootstrapIntent(intent.operationId()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("row ordinals are not contiguous from zero");
      assertThat(
              testSchema
                  .dsl()
                  .fetchSingle(
                      "SELECT cohort_digest, intent_state FROM "
                          + "game_session_canonical_issuer_layout_bootstrap_intent "
                          + "WHERE operation_id = ?",
                      intent.operationId())
                  .get("cohort_digest", String.class))
          .isEqualTo(intent.cohortDigest());
      assertThat(
              testSchema
                  .dsl()
                  .fetchSingle(
                      "SELECT intent_state FROM "
                          + "game_session_canonical_issuer_layout_bootstrap_intent "
                          + "WHERE operation_id = ?",
                      intent.operationId())
                  .get("intent_state", String.class))
          .isEqualTo("INITIAL_BOOTSTRAP_INTENT");
      assertThat(
              testSchema
                  .dsl()
                  .fetchSingle(
                      "SELECT state FROM game_session_canonical_issuer_layout_bootstrap_control "
                          + "WHERE issuer_id = ?",
                      ISSUER)
                  .get("state", String.class))
          .isEqualTo("INITIAL_BOOTSTRAP_INTENT");
    } finally {
      dropSchema(testSchema.schema());
    }
  }

  @Test
  void concurrentDifferentBootstrapRequestsSerializeToOneImmutableIntent() throws Exception {
    TestSchema testSchema = newTestSchema("gs_issuer_boot_race_");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      establishActualLegacyDisposition(testSchema.dsl());
      var inventory = new CanonicalGameplayBindingInventoryRepository(testSchema.dsl());
      prepareActualBinding(inventory);
      var descriptor = descriptor(LAYOUT_VERSION, PARTITION_COUNT, PARTITION_CAPACITY);
      var source = sourceCheckpoint(BigInteger.valueOf(7));
      var ready = new CountDownLatch(2);
      var start = new CountDownLatch(1);
      Future<Attempt> first =
          executor.submit(
              () -> attempt(testSchema.dsl(), UUID.randomUUID(), descriptor, source, ready, start));
      Future<Attempt> second =
          executor.submit(
              () -> attempt(testSchema.dsl(), UUID.randomUUID(), descriptor, source, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      Attempt left = first.get(20, TimeUnit.SECONDS);
      Attempt right = second.get(20, TimeUnit.SECONDS);
      assertThat(List.of(left, right).stream().filter(attempt -> attempt.intent() != null).count())
          .isEqualTo(1L);
      assertThat(List.of(left, right).stream().filter(attempt -> attempt.failure() != null).count())
          .isEqualTo(1L);
      Attempt failed = left.failure() == null ? right : left;
      assertThat(failed.failure())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("different initial issuer-layout bootstrap");
      assertThat(
              countRows(testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_intent"))
          .isEqualTo(1);
      assertThat(
              countRows(testSchema.dsl(), "game_session_canonical_issuer_layout_bootstrap_control"))
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
      dropSchema(testSchema.schema());
    }
  }

  private static Attempt attempt(
      DSLContext dsl,
      UUID requestId,
      CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedLayoutDescriptor descriptor,
      CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint source,
      CountDownLatch ready,
      CountDownLatch start) {
    ready.countDown();
    try {
      if (!start.await(10, TimeUnit.SECONDS)) {
        return new Attempt(null, new IllegalStateException("test start gate timed out"));
      }
      var intent =
          new CanonicalGameplayIssuerLayoutBootstrapRepository(dsl)
              .beginInitialBootstrapIntent(requestId, descriptor, source);
      return new Attempt(intent, null);
    } catch (RuntimeException failure) {
      return new Attempt(null, failure);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new Attempt(
          null, new IllegalStateException("test worker was interrupted", interrupted));
    }
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

  private static CanonicalGameplayBindingTransitionSnapshot prepareActualBinding(
      CanonicalGameplayBindingInventoryRepository inventory) {
    return prepareActualBinding(inventory, PREPARED_CHARACTER_ID);
  }

  private static CanonicalGameplayBindingTransitionSnapshot prepareActualBinding(
      CanonicalGameplayBindingInventoryRepository inventory, UUID characterId) {
    UUID transitionId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    UUID reservationFence = UUID.randomUUID();
    UUID issuerCoverageOperationId = UUID.randomUUID();
    var candidate =
        new CanonicalGameplayBindingIdentity(
            ACCOUNT_ID,
            TENANT_ID,
            NAMESPACE_ID,
            characterId,
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
            PARTITION_CAPACITY);
    var reservation =
        new CanonicalIssuerReservationFenceEvidence(
            reservationId,
            reservationFence,
            issuerCoverageOperationId,
            BigInteger.ONE,
            BigInteger.TWO,
            BigInteger.ONE);
    return inventory.prepare(
        new CanonicalGameplayBindingTransitionRequest(
            transitionId, UUID.randomUUID(), candidate, reservation, null, null));
  }

  private static void insertReservationOnly(
      DSLContext dsl,
      CanonicalGameplayBindingIdentity template,
      BigInteger revision,
      UUID reservationId) {
    UUID characterId = UUID.fromString("88888888-8888-4888-8888-888888888888");
    UUID transitionId = UUID.randomUUID();
    UUID fence = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String sessionId = UUID.fromString("99999999-9999-4999-8999-999999999999").toString();
    var bindingRef =
        net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef.of(
            template.tenantId().toString(), template.gameInstanceId().toString(), sessionId);
    BigInteger partitionId = bindingRef.partitionId(template.issuerIndexPartitionCount());
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
        template.accountId(),
        template.tenantId(),
        template.gameInstanceId(),
        template.runtimeGameInstanceId(),
        sessionId,
        BigDecimal.valueOf(1),
        transitionId,
        ISSUER,
        new BigDecimal(template.issuerAuthGeneration()),
        template.playableStateNamespaceId(),
        characterId,
        template.playableStateScope(),
        template.regionId(),
        new BigDecimal(template.regionEpoch()),
        new BigDecimal(partitionId),
        new BigDecimal(template.issuerIndexLayoutVersion()),
        new BigDecimal(template.issuerIndexPartitionCount()),
        new BigDecimal(template.issuerIndexPartitionCapacity()),
        fence,
        operationId,
        BigDecimal.ONE,
        BigDecimal.TWO,
        new BigDecimal(revision),
        new BigDecimal(revision));
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
          public void requireStillFenced(
              CanonicalGameplayLegacyMigrationStorageIdentity expectedIdentity) {
            if (!storageIdentity.equals(expectedIdentity)) {
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
                  CanonicalGameplayLegacyMigrationOwner.FencedCohort ignoredCohort,
                  CanonicalGameplayLegacyMigrationSourceSnapshot source,
                  net.firedevops.firemud.gamesession.binding
                          .CanonicalGameplayBindingInventorySnapshot
                      canonical) {
                assertThat(canonical.bindings()).isEmpty();
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
    return new TestSchema(schema, dataSource, DSL.using(dataSource, SQLDialect.POSTGRES));
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
      throw new IllegalStateException(
          "Could not drop the owned issuer-bootstrap test schema", failure);
    }
  }

  private static int countRows(DSLContext dsl, String tableName) {
    return dsl.fetchCount(DSL.table(tableName));
  }

  private static BigInteger decimal(BigDecimal value) {
    return Objects.requireNonNull(value, "durable reservation counter").toBigIntegerExact();
  }

  private static CanonicalGameplayIssuerMemberV2Value.Incarnation
      controlledIncarnationForShapeOnly() {
    var deployment =
        new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
            "coordination-controlled",
            "deployment-controlled",
            List.of("coord-controlled-a", "coord-controlled-b"),
            "redis-controlled",
            "lua-registry-controlled",
            digest("lua-registry-controlled"),
            "READY",
            "boot-controlled",
            "startup-controlled",
            "storage-controlled",
            "aof-controlled");
    return new CanonicalGameplayIssuerMemberV2Value.Incarnation(
        "controlled-shape-only-workload",
        digest("attestation-controlled"),
        deployment,
        digest("coord-ops-ro-acl-controlled"),
        digest("coord-redis-config-controlled"));
  }

  private static String digest(String value) {
    return CanonicalGameplayIssuerMemberV2Codec.sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static BigInteger currentInventoryRevision(DSLContext dsl) {
    BigDecimal revision =
        Objects.requireNonNull(
            dsl.fetchSingle(
                    "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock"
                        + " WHERE singleton_id = 1")
                .get("inventory_revision", BigDecimal.class),
            "inventory clock row must exist");
    return revision.toBigIntegerExact();
  }

  private static void assertCapturedPreparationCohort(
      CanonicalGameplayIssuerLayoutBootstrapIntent intent,
      CanonicalGameplayBindingTransitionSnapshot prepared) {
    String bindingRefHex = HexFormat.of().formatHex(prepared.bindingRef().bytes());
    String transitionId = prepared.transitionId().toString();
    assertThat(intent.inventorySnapshotRevision()).isEqualTo(prepared.inventoryRevision());
    assertThat(intent.cohortRows()).hasSize(6);
    assertThat(intent.cohortRows())
        .allSatisfy(
            row ->
                assertThat(row.sourceInventoryRevision()).isEqualTo(prepared.inventoryRevision()));
    assertThat(intent.cohortRows())
        .filteredOn(
            row -> row.kind() == CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.BINDING)
        .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceKey)
        .containsExactly(bindingRefHex);
    assertThat(intent.cohortRows())
        .filteredOn(
            row -> row.kind() == CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.TRANSITION)
        .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceKey)
        .containsExactly(transitionId);
    assertThat(intent.cohortRows())
        .filteredOn(
            row ->
                row.kind() == CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.RESERVATION)
        .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceKey)
        .containsExactly(prepared.issuerReservationId().toString());
    assertThat(intent.cohortRows())
        .filteredOn(
            row ->
                row.kind()
                    == CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.ACCOUNT_OBLIGATION)
        .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceKey)
        .containsExactly(transitionId + ":0");
    assertThat(intent.cohortRows())
        .filteredOn(
            row ->
                row.kind()
                    == CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.ISSUER_OBLIGATION)
        .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceKey)
        .containsExactly(bindingRefHex + ":" + transitionId);
    assertThat(intent.cohortRows())
        .filteredOn(
            row ->
                row.kind()
                    == CanonicalGameplayIssuerLayoutBootstrapIntent.CohortKind.REGION_OBLIGATION)
        .extracting(CanonicalGameplayIssuerLayoutBootstrapIntent.CohortRow::sourceKey)
        .containsExactly(transitionId);
  }

  private static List<String> rowImages(DSLContext dsl, String tableName) {
    return dsl.fetch(
            "SELECT to_jsonb(retained_row)::text AS row_image FROM "
                + tableName
                + " retained_row ORDER BY to_jsonb(retained_row)::text")
        .getValues("row_image", String.class);
  }

  private static void awaitInventoryClockWaiters(TestSchema testSchema, long expectedWaiters) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    long observedWaiters = 0;
    do {
      observedWaiters =
          Objects.requireNonNull(
              testSchema
                  .dsl()
                  .fetchSingle(
                      "SELECT count(DISTINCT activity.pid) AS waiting_count "
                          + "FROM pg_stat_activity activity "
                          + "JOIN pg_locks relation_lock ON relation_lock.pid = activity.pid "
                          + "WHERE activity.wait_event_type = 'Lock' "
                          + "AND activity.query LIKE '%SELECT inventory_revision FROM "
                          + "game_session_canonical_binding_inventory_clock%' "
                          + "AND relation_lock.relation = to_regclass(?) "
                          + "AND relation_lock.mode = 'RowShareLock' AND relation_lock.granted",
                      testSchema.schema() + ".game_session_canonical_binding_inventory_clock")
                  .get("waiting_count", Long.class),
              "PostgreSQL lock waiter count");
      if (observedWaiters == expectedWaiters) return;
      if (observedWaiters > expectedWaiters) {
        throw new AssertionError(
            "Observed more inventory-clock waiters than the controlled queue: " + observedWaiters);
      }
      Thread.onSpinWait();
      Thread.yield();
    } while (System.nanoTime() < deadline);
    throw new AssertionError(
        "Timed out waiting for "
            + expectedWaiters
            + " PostgreSQL inventory-clock waiters; observed "
            + observedWaiters);
  }

  private record TestSchema(String schema, DriverManagerDataSource dataSource, DSLContext dsl) {}

  private record Attempt(
      CanonicalGameplayIssuerLayoutBootstrapIntent intent, RuntimeException failure) {}

  private record PreparationAttempt(
      CanonicalGameplayBindingTransitionSnapshot transition, RuntimeException failure) {}
}
