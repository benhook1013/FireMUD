package net.firedevops.firemud.entitymanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence;
import net.firedevops.firemud.common.world.CanonicalPlayerCreatedRosterEvidence;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository.ReferenceKind;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterActor;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterExecutionContext;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionBinding;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionPolicy;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSnapshot;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSnapshotReference;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterSourceIdentity;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Controlled SQL owner-family proof only. Fixture owner evidence is synthetic; these tests do not
 * authenticate upstream release/retirement authority or prove eligible disposal.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayRosterRetentionPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private String schema;
  private DSLContext dsl;
  private TransactionTemplate transaction;
  private CanonicalGameplayRosterRetentionRepository repository;
  private CanonicalGameplayRosterRetentionBinding binding;

  @BeforeEach
  void upgradeRetainedV1011ActorReceiptAndSealedSnapshot() {
    schema = "roster_retention_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, "1011");
    dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    binding =
        new CanonicalGameplayRosterRetentionBinding(
            UUID.randomUUID(),
            new CanonicalGameplayRosterSnapshotReference(UUID.randomUUID(), "a".repeat(64)),
            target());
    transaction.executeWithoutResult(
        ignored -> seedHistoricalV1011AssignmentActorAndSnapshotWithoutProjection());
    List<String> retained = retainedRows();
    migrate(dataSource, "latest");
    assertThat(retainedRows()).containsExactlyElementsOf(retained);
    assertInheritedNullableFieldsRemainNull();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.requireUnsealedTarget(localRequest())))
        .as("retained snapshot classification must be initialized before acquisition")
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    assertThat(transaction.<Integer>execute(ignored -> repository.classifyRetainedDependencies(1)))
        .isZero();
    assertThat(retainedRows()).containsExactlyElementsOf(retained);
    assertThat(
            dsl.fetchSingle("SELECT entity_roster_target_classification_pending()")
                .get(0, Boolean.class))
        .isFalse();
  }

  @Test
  void oldProtectedSnapshotDoesNotContributeToOldestBlockingRetirement() {
    dsl.fetch("SELECT pg_sleep(1.1)");

    var health = transaction.execute(ignored -> repository.health());

    assertThat(health.oldestBlockingSeconds()).isZero();
    assertThat(health.cleanupLagSeconds()).isZero();
  }

  @Test
  void latestMigrationsRetainClassificationShapeAndSeparateProofBudgets() {
    transaction.executeWithoutResult(
        ignored -> {
          var liveChecks =
              dsl.fetch(
                  "SELECT t.relname AS table_name, pg_get_constraintdef(c.oid) AS definition "
                      + "FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid "
                      + "JOIN pg_namespace n ON n.oid = t.relnamespace "
                      + "WHERE c.contype = 'c' AND n.nspname = current_schema() "
                      + "AND t.relname IN ('entity_canonical_roster_target_classifications', "
                      + "'entity_canonical_roster_snapshot_targets', 'entity_canonical_roster_retired_targets')");
          assertThat(liveChecks.getValues("table_name", String.class))
              .contains(
                  "entity_canonical_roster_target_classifications",
                  "entity_canonical_roster_snapshot_targets",
                  "entity_canonical_roster_retired_targets");
          assertThat(liveChecks.getValues("definition", String.class))
              .anySatisfy(
                  definition ->
                      assertThat(definition)
                          .contains("classification_state", "target_binding", "retirement_proof")
                          .doesNotContain("octet_length(retirement_proof)"));
          assertProofBudget(
              liveChecks,
              "entity_canonical_roster_target_classifications",
              "octet_length(retirement_proof)",
              "203423744",
              "131072");
          assertProofBudget(
              liveChecks,
              "entity_canonical_roster_snapshot_targets",
              "octet_length(source_retirement_proof)",
              "203423744",
              "131072");
          assertProofBudget(
              liveChecks,
              "entity_canonical_roster_retired_targets",
              "octet_length(owner_retirement_proof)",
              "203423744");

          // This clone inherits the actual latest-migration CHECKs. It contains synthetic bytes
          // only: no source triggers or owner evidence are copied, so these probes prove storage
          // shape constraints and do not qualify an authenticated source or retirement authority.
          dsl.execute(
              "CREATE TEMP TABLE roster_classification_constraint_probe "
                  + "(LIKE entity_canonical_roster_target_classifications INCLUDING CONSTRAINTS INCLUDING DEFAULTS) "
                  + "ON COMMIT DROP");
          byte[] target = "target-binding".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          byte[] proof = "synthetic-proof".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          insertClassificationProbe("ADMISSION", "UNKNOWN", null, null, null);
          insertClassificationProbe("ADMISSION", "KNOWN", target, digest(target), null);

          assertRejectedClassificationProbe("ADMISSION", "UNKNOWN", target, digest(target), null);
          assertRejectedClassificationProbe("ADMISSION", "KNOWN", target, null, null);
          assertRejectedClassificationProbe("ADMISSION", "KNOWN", target, "0".repeat(64), null);
          assertRejectedClassificationProbe("ADMISSION", "KNOWN", target, digest(target), proof);
          assertRejectedClassificationProbe("TRANSFER", "KNOWN", target, digest(target), proof);
          assertRejectedClassificationProbe(
              "TRANSFER_RETIREMENT", "KNOWN", target, digest(target), null);
        });
  }

  private void assertProofBudget(
      org.jooq.Result<org.jooq.Record> checks,
      String tableName,
      String columnExpression,
      String... bounds) {
    assertThat(checks)
        .anySatisfy(
            row -> {
              assertThat(row.get("table_name", String.class)).isEqualTo(tableName);
              String definition = row.get("definition", String.class);
              assertThat(definition).contains(columnExpression).contains(bounds);
            });
  }

  private void assertRejectedClassificationProbe(
      String sourceKind, String state, byte[] target, String targetDigest, byte[] proof) {
    dsl.execute("SAVEPOINT roster_shape_probe");
    assertThatThrownBy(
            () -> insertClassificationProbe(sourceKind, state, target, targetDigest, proof))
        .isInstanceOf(DataAccessException.class);
    dsl.execute("ROLLBACK TO SAVEPOINT roster_shape_probe");
    dsl.execute("RELEASE SAVEPOINT roster_shape_probe");
  }

  private void insertClassificationProbe(
      String sourceKind, String state, byte[] target, String targetDigest, byte[] proof) {
    dsl.execute(
        "INSERT INTO roster_classification_constraint_probe "
            + "(source_kind, operation_uuid, admission_uuid, source_binding, classification_state, "
            + "target_binding, target_digest, retirement_proof) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        sourceKind,
        UUID.randomUUID(),
        UUID.randomUUID(),
        new byte[] {1},
        state,
        target,
        targetDigest,
        proof);
  }

  private String digest(byte[] value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
    }
  }

  @Test
  void retirementHealthStartsAfterStoredHorizonAndKeepsPayloadAndCleanupLag() {
    var local = localRequest();
    var owner = recoveryOwner(recovery(local));
    var snapshot = retainedSnapshot();
    bindSnapshot(local, owner, snapshot);
    var candidate = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    transaction.executeWithoutResult(
        ignored ->
            assertThat(
                    repository.observeRetirement(
                        candidate, retirement(owner, snapshot), policy(Duration.ofSeconds(2))))
                .isTrue());

    assertThat(
            dsl.fetchSingle(
                    "SELECT retirement_observed_at + minimum_retention_millis * interval '1 millisecond' > clock_timestamp() "
                        + "FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ?",
                    snapshot.snapshotUuid())
                .get(0, Boolean.class))
        .isTrue();
    var beforeHorizon = transaction.execute(ignored -> repository.health());
    assertThat(beforeHorizon.oldestBlockingSeconds()).isZero();
    assertThat(beforeHorizon.cleanupLagSeconds()).isZero();

    dsl.fetch("SELECT pg_sleep(3.1)");

    var afterHorizon = transaction.execute(ignored -> repository.health());
    assertThat(afterHorizon.oldestBlockingSeconds()).isPositive();
    assertThat(afterHorizon.cleanupLagSeconds()).isPositive();
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?",
                    snapshot.snapshotUuid())
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.fetchSingle(
                    "SELECT lifecycle_state FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ?",
                    snapshot.snapshotUuid())
                .get(0, String.class))
        .isEqualTo("RETIRED");
  }

  @AfterEach
  void dropOwnedFixture() {
    if (schema != null) {
      DSL.using(dataSource(null), SQLDialect.POSTGRES)
          .execute("DROP SCHEMA \"" + schema.replaceAll("[^a-zA-Z0-9_]", "") + "\" CASCADE");
    }
  }

  @Test
  void observedRetirementCommitsStableActualNoncesAndHeldSnapshotsCannotRemintTransfers() {
    var first = localRequest();
    var second = localRequest();
    var firstOwner = recoveryOwner(recovery(first));
    var secondOwner =
        CanonicalGameplayRosterReferenceEvidence.response(
            recovery(second).getOriginalSelection(),
            firstOwner.getOwnerFence(),
            UUID.fromString(firstOwner.getTargetFenceUuid()),
            null);
    var snapshot = retainedSnapshot();
    UUID firstNonce = bindSnapshot(first, firstOwner, snapshot);
    UUID secondNonce = bindSnapshot(second, secondOwner, snapshot);
    UUID hold = UUID.randomUUID();
    transaction.executeWithoutResult(
        ignored -> repository.acquire(binding, UUID.randomUUID(), hold, ReferenceKind.HOLD));
    var candidate = transaction.execute(ignored -> repository.candidates(null, 2)).getFirst();
    var proof =
        retirement(
            candidate.ownerRequest().equals(firstOwner.getRequest()) ? firstOwner : secondOwner,
            snapshot);
    transaction.executeWithoutResult(
        ignored ->
            assertThat(repository.observeRetirement(candidate, proof, policy(Duration.ofMillis(1))))
                .isTrue());

    var sealed = transaction.execute(ignored -> repository.candidates(null, 2)).getFirst();
    assertThat(transaction.<Integer>execute(ignored -> repository.prepareRetirement(sealed, 2)))
        .isEqualTo(2);

    // Reconstruct the repository after the commit: no handler-local identity is needed to retry.
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    var transfers = transaction.execute(ignored -> repository.retirementTransfers(null, 2));
    assertThat(transfers).hasSize(2);
    assertThat(transfers)
        .allSatisfy(
            transfer -> {
              assertThat(transfer.state()).isEqualTo("PENDING_ACK");
              var request = transfer.acknowledgementRequest();
              assertThat(request.getObservedRetirement()).isEqualTo(proof);
              assertThat(request.getSnapshotUuid()).isEqualTo(snapshot.snapshotUuid().toString());
              assertThat(request.getSnapshotDigest()).isEqualTo(snapshot.snapshotDigest());
              UUID expectedNonce =
                  request
                          .getOriginalSelection()
                          .getOperationUuid()
                          .equals(first.requestUuid().toString())
                      ? firstNonce
                      : secondNonce;
              assertThat(request.getAdmissionUuid()).isEqualTo(expectedNonce.toString());
              assertThatThrownBy(
                      () ->
                          dsl.execute(
                              "DELETE FROM entity_canonical_roster_retirement_transfers WHERE observation_uuid = ?",
                              transfer.observationUuid()))
                  .isInstanceOf(DataAccessException.class);
              var acknowledgement =
                  CanonicalGameplayRosterRetirementEvidence.response(request, UUID.randomUUID(), 1);
              transaction.executeWithoutResult(
                  ignored -> repository.recordRetirementAcknowledgement(transfer, acknowledgement));
            });
    for (var acknowledged :
        transaction.execute(ignored -> repository.retirementTransfers(null, 2))) {
      var confirmation =
          CanonicalGameplayRosterRetirementEvidence.confirmation(
              acknowledged.acknowledgementRequest(), acknowledged.acknowledgement());
      transaction.executeWithoutResult(
          ignored ->
              repository.recordRetirementConfirmation(
                  acknowledged,
                  CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmation)));
    }
    dsl.fetch("SELECT pg_sleep(0.02)");
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementTransfers(2)))
        .isZero();
    var retired = transaction.execute(ignored -> repository.candidates(null, 2)).getFirst();
    assertThat(transaction.<Boolean>execute(ignored -> repository.dispose(retired))).isFalse();
    transaction.executeWithoutResult(
        ignored ->
            assertThat(repository.observeRetirement(retired, proof, policy(Duration.ofHours(1))))
                .isTrue());
    assertThat(
            dsl.fetch("SELECT observation_uuid FROM entity_canonical_roster_retirement_transfers")
                .getValues(0, UUID.class))
        .containsExactlyInAnyOrderElementsOf(
            transfers.stream().map(t -> t.observationUuid()).toList());
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.reserveAdmission(first)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.requireAdmission(second, secondNonce)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_roster_admissions SET snapshot_uuid = snapshot_uuid WHERE operation_uuid = ?",
                    first.requestUuid()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void forward1015To1016PreservesInstalledHistoryRetainedRowsAndOldSnapshotGuards() {
    String ordinarySchema = schema;
    var ordinaryDsl = dsl;
    var ordinaryTransaction = transaction;
    var ordinaryRepository = repository;
    var ordinaryBinding = binding;
    String forwardSchema = "roster_retention_1015_" + UUID.randomUUID().toString().replace("-", "");
    try {
      schema = forwardSchema;
      DriverManagerDataSource source = dataSource(schema);
      migrate(source, "1011");
      dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
      transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
      repository = new CanonicalGameplayRosterRetentionRepository(dsl);
      binding =
          new CanonicalGameplayRosterRetentionBinding(
              UUID.randomUUID(),
              new CanonicalGameplayRosterSnapshotReference(UUID.randomUUID(), "a".repeat(64)),
              target());
      transaction.executeWithoutResult(
          ignored -> seedHistoricalV1011AssignmentActorAndSnapshotWithoutProjection());
      List<String> retained1011Rows = retainedRows();
      migrate(source, "1014");
      assertThat(retainedRows()).containsExactlyElementsOf(retained1011Rows);
      migrate(source, "1015");
      dsl.execute(
          "INSERT INTO entity_canonical_roster_retention_lifecycle (snapshot_uuid, snapshot_digest) VALUES (?, ?)",
          binding.snapshot().snapshotUuid(),
          binding.snapshot().snapshotDigest());
      List<String> installed1015History = migrationHistory();
      List<String> retained1015Rows = retainedBeforeCreatedExtension();
      migrate(source, "1016");
      List<String> history1016 = migrationHistory();
      assertThat(history1016.subList(0, installed1015History.size()))
          .containsExactlyElementsOf(installed1015History);
      assertThat(history1016).hasSize(installed1015History.size() + 1);
      assertThat(history1016.getLast())
          .contains("1016", "V1016__player_created_roster_snapshot_receipts.sql");
      assertThat(retainedBeforeCreatedExtension()).containsExactlyElementsOf(retained1015Rows);
      var row =
          dsl.fetchSingle(
              "SELECT entry_policy, canonical_player_created_source_identity FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?",
              binding.snapshot().snapshotUuid());
      assertThat(row.get("entry_policy", String.class)).isEqualTo("PRESEEDED_ONLY");
      assertThat(row.get("canonical_player_created_source_identity", byte[].class)).isNull();
      assertThat(
              dsl.fetchSingle(
                      "SELECT entry_policy FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ?",
                      binding.snapshot().snapshotUuid())
                  .get(0, String.class))
          .isEqualTo("PRESEEDED_ONLY");
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE entity_canonical_gameplay_roster_snapshots SET canonical_player_created_source_identity = ? WHERE snapshot_uuid = ?",
                      new byte[] {1},
                      binding.snapshot().snapshotUuid()))
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "DELETE FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?",
                      binding.snapshot().snapshotUuid()))
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(
              () -> dsl.execute("TRUNCATE entity_canonical_gameplay_roster_snapshots CASCADE"))
          .isInstanceOf(DataAccessException.class);
    } finally {
      schema = ordinarySchema;
      dsl = ordinaryDsl;
      transaction = ordinaryTransaction;
      repository = ordinaryRepository;
      binding = ordinaryBinding;
      DSL.using(dataSource(null), SQLDialect.POSTGRES)
          .execute("DROP SCHEMA \"" + forwardSchema.replaceAll("[^a-zA-Z0-9_]", "") + "\" CASCADE");
    }
  }

  @Test
  void createdAdmissionAndOriginalRecoveryKeepExactSourceAndDistinctReceiptBranch() {
    var request = createdRequest();
    UUID nonce = transaction.execute(ignored -> repository.reserveAdmission(request));
    assertThat(transaction.<UUID>execute(ignored -> repository.reserveAdmission(request)))
        .isEqualTo(nonce);
    var changed =
        request.toBuilder()
            .setExpectedSource(
                request.getExpectedSource().toBuilder()
                    .setDescriptorRevisionUuid(UUID.randomUUID().toString()))
            .build();
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.reserveAdmission(changed)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_REQUEST_CONFLICT");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.requireAdmission(changed, nonce)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    var invalidPolicy = request.toBuilder().setExpectedTarget(binding.target().toWire()).build();
    assertThatThrownBy(
            () -> transaction.execute(ignored -> repository.reserveAdmission(invalidPolicy)))
        .isInstanceOf(IllegalArgumentException.class);
    var original = createdOriginal(request);
    var owner =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
            original, 1, UUID.randomUUID(), null);
    var receipt = createdReceipt(request);
    transaction.executeWithoutResult(
        ignored -> {
          assertThat(repository.originalPlayerCreatedReceipt(owner)).isEmpty();
          repository.requireAdmission(request, nonce);
          seedCreatedSnapshot(receipt, 0);
          repository.bindPlayerCreatedOriginal(owner, receipt);
          repository.bindAdmission(
              UUID.fromString(request.getRequestUuid()),
              nonce,
              UUID.fromString(receipt.getRoster().getSnapshotUuid()));
          assertThat(repository.originalPlayerCreatedReceipt(owner)).contains(receipt);
        });
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.originalReceipt(owner)))
        .isInstanceOf(IllegalArgumentException.class);
    var wrongSourceOwner =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
            createdOriginal(changed), 1, UUID.fromString(owner.getTargetFenceUuid()), null);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored -> repository.originalPlayerCreatedReceipt(wrongSourceOwner)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_ORIGINAL_REQUEST_CONFLICT");
    var recovery =
        CanonicalGameplayRosterRecoveryRequest.newBuilder()
            .setSchemaVersion(1)
            .setOriginalSelection(original)
            .setRecoveryUuid(UUID.randomUUID().toString())
            .build();
    var recovering = owner.toBuilder().setRecoveryUuid(recovery.getRecoveryUuid()).build();
    var closed =
        transaction.execute(
            ignored ->
                repository.closeAdmission(recovery, recovering, policy(Duration.ofHours(1))));
    assertThat(closed.getAdmissionUuid()).isEqualTo(nonce.toString());
    assertThat(closed.getCanonicalRosterReceipt()).isEmpty();
    assertThat(closed.getCanonicalPlayerCreatedRosterReceipt()).isEqualTo(receipt.toByteString());
    var replay =
        transaction.execute(
            ignored ->
                repository.closeAdmission(recovery, recovering, policy(Duration.ofHours(2))));
    assertThat(replay).isEqualTo(closed);
    var stored =
        dsl.fetchSingle(
            "SELECT entry_policy, canonical_roster_receipt, canonical_player_created_roster_receipt FROM entity_canonical_roster_original_selections WHERE operation_uuid = ?",
            UUID.fromString(request.getRequestUuid()));
    assertThat(stored.get("entry_policy", String.class)).isEqualTo("PLAYER_CREATED");
    assertThat(stored.get("canonical_roster_receipt", byte[].class)).isNull();
    assertThat(stored.get("canonical_player_created_roster_receipt", byte[].class))
        .isEqualTo(receipt.toByteArray());
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_gameplay_roster_snapshots SET canonical_player_created_source_identity = ? WHERE snapshot_uuid = ?",
                    changed.getExpectedSource().toByteArray(),
                    UUID.fromString(receipt.getRoster().getSnapshotUuid())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_roster_admissions SET entry_policy = 'PRESEEDED_ONLY' WHERE operation_uuid = ?",
                    UUID.fromString(request.getRequestUuid())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_roster_original_selections SET canonical_roster_receipt = ? WHERE operation_uuid = ?",
                    receipt.getRoster().toByteArray(),
                    UUID.fromString(request.getRequestUuid())))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void createdSnapshotRequiresSharedSourceAndRejectsPreseededActorSubstitution() {
    var request = createdRequest();
    var receipt = createdReceipt(request);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        seedCreatedSnapshot(receipt.toBuilder().clearSourceIdentity().build(), 0)))
        .isInstanceOf(DataAccessException.class);
    var oversized =
        receipt.toBuilder()
            .setSourceIdentity(
                receipt.getSourceIdentity().toBuilder().setDescriptorDigest("x".repeat(4097)))
            .build();
    assertThatThrownBy(
            () -> transaction.executeWithoutResult(ignored -> seedCreatedSnapshot(oversized, 0)))
        .isInstanceOf(DataAccessException.class);
    var isolated =
        receipt.toBuilder()
            .setRoster(
                receipt.getRoster().toBuilder()
                    .setTarget(
                        request.getExpectedTarget().toBuilder()
                            .setPlayableStateScope(
                                PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)))
            .build();
    assertThatThrownBy(
            () -> transaction.executeWithoutResult(ignored -> seedCreatedSnapshot(isolated, 0)))
        .isInstanceOf(DataAccessException.class);
    transaction.executeWithoutResult(ignored -> seedCreatedSnapshot(receipt, 1));
    UUID actor =
        dsl.fetchSingle(
                "SELECT character_uuid FROM entity_canonical_gameplay_roster_snapshot_actors WHERE snapshot_uuid = ?",
                binding.snapshot().snapshotUuid())
            .get(0, UUID.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO entity_canonical_gameplay_roster_snapshot_actors (snapshot_uuid, ordinal_position, character_uuid, display_name, actor_kind) VALUES (?, 0, ?, 'Retained Actor', 'PLAYER')",
                    UUID.fromString(receipt.getRoster().getSnapshotUuid()),
                    actor))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_gameplay_roster_snapshots SET construction_state = 'SEALED' WHERE snapshot_uuid = ?",
                    UUID.fromString(receipt.getRoster().getSnapshotUuid())))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void createdRetirementPreservesFullSourceThroughOutboxAndKeepsPreseededFamilyProtected() {
    var request = createdRequest();
    var original = createdOriginal(request);
    var owner =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
            original, 1, UUID.randomUUID(), null);
    var receipt = createdReceipt(request);
    UUID nonce = transaction.execute(ignored -> repository.reserveAdmission(request));
    transaction.executeWithoutResult(
        ignored -> {
          repository.requireAdmission(request, nonce);
          seedCreatedSnapshot(receipt, 0);
          repository.bindPlayerCreatedOriginal(owner, receipt);
          repository.bindAdmission(
              UUID.fromString(request.getRequestUuid()),
              nonce,
              UUID.fromString(receipt.getRoster().getSnapshotUuid()));
        });
    var recorded =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
            original, 1, UUID.fromString(owner.getTargetFenceUuid()), receipt);
    var proof = retirement(recorded, null);
    var candidate = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    transaction.executeWithoutResult(
        ignored ->
            assertThat(repository.observeRetirement(candidate, proof, policy(Duration.ofMillis(1))))
                .isTrue());
    var beforePreparation = transaction.execute(ignored -> repository.retirementTransfers(null, 1));
    assertThat(beforePreparation).isEmpty();
    assertThat(preparePage(1)).isEqualTo(1);
    var transfer =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    assertThat(transfer.acknowledgementRequest().getObservedRetirement()).isEqualTo(proof);
    assertThat(
            transfer.acknowledgementRequest().getObservedRetirement().getCanonicalRosterReceipt())
        .isEmpty();
    assertThat(
            transfer
                .acknowledgementRequest()
                .getObservedRetirement()
                .getCanonicalPlayerCreatedRosterReceipt())
        .isEqualTo(receipt.toByteString());
    assertThat(
            transfer
                .acknowledgementRequest()
                .getOriginalSelection()
                .getCanonicalPlayerCreatedSourceIdentity())
        .isEqualTo(request.getExpectedSource().toByteString());
    assertThat(
            dsl.fetchSingle(
                    "SELECT entry_policy FROM entity_canonical_roster_retirement_transfers WHERE observation_uuid = ?",
                    transfer.observationUuid())
                .get(0, String.class))
        .isEqualTo("PLAYER_CREATED");
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.reserveAdmission(request)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    assertThatThrownBy(
            () -> dsl.execute("DELETE FROM entity_canonical_roster_retirement_transfers"))
        .isInstanceOf(DataAccessException.class);
    transaction.executeWithoutResult(
        ignored -> assertThat(repository.protection(binding).disposalEligible()).isFalse());
    assertThat(preparePage(1)).isZero();
    // Preparation is bounded and non-authoritative; use a fresh candidate and let the stored
    // one-millisecond local fixture horizon pass before the final guarded disposal attempt.
    dsl.fetch("SELECT pg_sleep(0.02)");
    var retiredCandidate =
        transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    assertThat(transaction.<Boolean>execute(ignored -> repository.dispose(retiredCandidate)))
        .isTrue();
    var retainedTransfer =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    assertThat(retainedTransfer).isEqualTo(transfer);
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_gameplay_roster_snapshots")))
        .isEqualTo(1);
    assertThat(
            dsl.fetchSingle("SELECT snapshot_uuid FROM entity_canonical_gameplay_roster_snapshots")
                .get(0, UUID.class))
        .isEqualTo(binding.snapshot().snapshotUuid());
  }

  @Test
  void createdOutboxStorageBudgetDoesNotWidenPreseededOrAuthenticateOpaqueBytes() {
    var created = createdRequest();
    var createdOriginal = createdOriginal(created);
    var createdRecovery =
        CanonicalGameplayRosterRecoveryRequest.newBuilder()
            .setSchemaVersion(1)
            .setOriginalSelection(createdOriginal)
            .setRecoveryUuid(UUID.randomUUID().toString())
            .build();
    var createdOwner =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
                createdOriginal, 1, UUID.randomUUID(), null)
            .toBuilder()
            .setRecoveryUuid(createdRecovery.getRecoveryUuid())
            .build();
    transaction.executeWithoutResult(
        ignored ->
            repository.closeAdmission(createdRecovery, createdOwner, policy(Duration.ofHours(1))));
    var preseeded = localRequest();
    var preseededRecovery = recovery(preseeded);
    var preseededOwner = recoveryOwner(preseededRecovery);
    transaction.executeWithoutResult(
        ignored ->
            repository.closeAdmission(
                preseededRecovery, preseededOwner, policy(Duration.ofHours(1))));
    byte[] rawRequest = new byte[128 * 1024 + 1];
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        insertRawTransferForBudget(
                            preseeded.requestUuid(), retirement(preseededOwner, null), rawRequest)))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_entity_roster_transfer_policy_request");
    // Raw SQL tests only the policy-specific byte budget. Opaque request bytes remain invalid
    // to the repository's value parser and never become authenticated acknowledgement authority.
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> {
                      insertRawTransferForBudget(
                          UUID.fromString(created.getRequestUuid()),
                          retirement(createdOwner, null),
                          rawRequest);
                      assertThat(
                              dsl.fetchCount(
                                  DSL.table("entity_canonical_roster_retirement_transfers")))
                          .isEqualTo(1);
                      UUID malformedObservation =
                          dsl.fetchSingle(
                                  "SELECT observation_uuid FROM entity_canonical_roster_retirement_transfers")
                              .get(0, UUID.class);
                      var malformed = repository.retirementTransfers(null, 1);
                      assertThat(malformed).hasSize(1);
                      assertThat(malformed.getFirst().observationUuid())
                          .isEqualTo(malformedObservation);
                      assertThat(malformed.getFirst().valid()).isFalse();
                      assertThat(malformed.getFirst().original()).isNull();
                      assertThat(malformed.getFirst().acknowledgementRequest()).isNull();
                      assertThat(malformed.getFirst().acknowledgement()).isNull();
                      assertThat(
                              dsl.fetchCount(
                                  DSL.table("entity_canonical_roster_retirement_transfers")))
                          .isEqualTo(1);
                      throw new IllegalStateException("controlled byte-budget rollback");
                    }))
        .hasMessage("controlled byte-budget rollback");
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_retirement_transfers"))).isZero();
  }

  // Pure source values and empty persisted snapshots are controlled storage fixtures, not
  // authority.

  @Test
  void malformedOriginalPageKeepsItsKeyAndPassesLaterSnapshotWithoutChangingSource() {
    var firstSnapshot = retainedSnapshot();
    var firstLocal = localRequest();
    bindSnapshot(firstLocal, recoveryOwner(recovery(firstLocal)), firstSnapshot);
    UUID badSnapshot = binding.snapshot().snapshotUuid();
    binding =
        new CanonicalGameplayRosterRetentionBinding(
            UUID.randomUUID(),
            new CanonicalGameplayRosterSnapshotReference(
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), "b".repeat(64)),
            binding.target());
    transaction.executeWithoutResult(ignored -> seedAssignedActorAndSnapshot());
    var laterLocal = localRequest();
    bindSnapshot(laterLocal, recoveryOwner(recovery(laterLocal)), retainedSnapshot());
    corruptRestoredBytes(
        "entity_canonical_roster_original_selections", "owner_request", firstLocal.requestUuid());
    String retained =
        pageSourceRow(
            "entity_canonical_roster_original_selections",
            "operation_uuid",
            firstLocal.requestUuid());
    var firstPage = transaction.execute(ignored -> repository.candidates(null, 1));
    assertThat(firstPage).hasSize(1);
    assertThat(firstPage.getFirst().valid()).isFalse();
    assertThat(firstPage.getFirst().snapshotUuid()).isEqualTo(badSnapshot);
    var laterPage =
        transaction.execute(
            ignored -> repository.candidates(firstPage.getFirst().snapshotUuid(), 1));
    assertThat(laterPage).hasSize(1);
    assertThat(laterPage.getFirst().valid()).isTrue();
    assertThat(laterPage.getFirst().ownerRequest().getOperationUuid())
        .isEqualTo(laterLocal.requestUuid().toString());
    assertThat(
            pageSourceRow(
                "entity_canonical_roster_original_selections",
                "operation_uuid",
                firstLocal.requestUuid()))
        .isEqualTo(retained);
  }

  @Test
  void malformedRecoveryAndTransferPagesPassLaterRowsAndKeepInvalidSources() {
    for (int index = 0; index < 2; index++) {
      var recovery = recovery(localRequest());
      transaction.executeWithoutResult(
          ignored ->
              repository.closeAdmission(
                  recovery, recoveryOwner(recovery), policy(Duration.ofMillis(1))));
    }
    var closures = transaction.execute(ignored -> repository.closedAdmissionCandidates(null, 2));
    var bad = closures.getFirst();
    corruptRestoredBytes(
        "entity_canonical_roster_admissions", "recovery_response", bad.operationUuid());
    String retainedClosure =
        pageSourceRow("entity_canonical_roster_admissions", "operation_uuid", bad.operationUuid());
    var firstClosure =
        transaction.execute(ignored -> repository.closedAdmissionCandidates(null, 1)).getFirst();
    assertThat(firstClosure.valid()).isFalse();
    assertThat(firstClosure.operationUuid()).isEqualTo(bad.operationUuid());
    assertThat(
            transaction
                .execute(
                    ignored ->
                        repository.closedAdmissionCandidates(firstClosure.operationUuid(), 1))
                .getFirst()
                .valid())
        .isTrue();
    // Explicit inconsistent-restore fixtures: production never disables immutable source guards.
    transaction.executeWithoutResult(
        ignored -> {
          dsl.execute(
              "ALTER TABLE entity_canonical_roster_retirement_transfers DISABLE TRIGGER USER");
          for (int index = 0; index < closures.size(); index++) {
            var closure = closures.get(index);
            var response = closure.response();
            dsl.execute(
                "INSERT INTO entity_canonical_roster_retirement_transfers (observation_uuid, operation_uuid, original_request, admission_uuid, closed_admission_receipt, owner_fence, target_fence_uuid, minimum_retention_millis, transfer_state) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'WAITING_RETIREMENT')",
                new UUID(0, index + 1),
                closure.operationUuid(),
                response.getRequest().getOriginalSelection().toByteArray(),
                closure.admissionUuid(),
                response.toByteArray(),
                response.getOwnerFence(),
                UUID.fromString(response.getTargetFenceUuid()));
          }
          dsl.execute(
              "ALTER TABLE entity_canonical_roster_retirement_transfers ENABLE TRIGGER USER");
        });
    corruptRestoredBytes(
        "entity_canonical_roster_retirement_transfers", "original_request", bad.operationUuid());
    String retainedTransfer =
        pageSourceRow(
            "entity_canonical_roster_retirement_transfers", "operation_uuid", bad.operationUuid());
    var firstTransfer =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    assertThat(firstTransfer.valid()).isFalse();
    assertThat(firstTransfer.observationUuid()).isEqualTo(new UUID(0, 1));
    var laterTransfer =
        transaction
            .execute(ignored -> repository.retirementTransfers(firstTransfer.observationUuid(), 1))
            .getFirst();
    assertThat(laterTransfer.valid()).isTrue();
    assertThat(laterTransfer.observationUuid()).isEqualTo(new UUID(0, 2));
    assertThat(
            pageSourceRow(
                "entity_canonical_roster_admissions", "operation_uuid", bad.operationUuid()))
        .isEqualTo(retainedClosure);
    assertThat(
            pageSourceRow(
                "entity_canonical_roster_retirement_transfers",
                "operation_uuid",
                bad.operationUuid()))
        .isEqualTo(retainedTransfer);
  }

  @Test
  void conflictingTransferRemainsProtectedWhileDurablePreparationCursorPassesIt() {
    var snapshot = retainedSnapshot();
    var first = recoveryOwner(recovery(localRequest()));
    var owners = new java.util.ArrayList<GetCanonicalGameplayRosterReferenceResponse>();
    for (int index = 0; index < 5; index++) {
      var local = localRequest();
      var owner =
          CanonicalGameplayRosterReferenceEvidence.response(
              recovery(local).getOriginalSelection(),
              first.getOwnerFence(),
              UUID.fromString(first.getTargetFenceUuid()),
              null);
      owners.add(owner);
      bindSnapshot(local, owner, snapshot);
    }
    var candidate = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    var anchor =
        owners.stream()
            .filter(owner -> owner.getRequest().equals(candidate.ownerRequest()))
            .findFirst()
            .orElseThrow();
    var sealedProof = retirement(anchor, snapshot);
    transaction.executeWithoutResult(
        ignored ->
            repository.observeRetirement(candidate, sealedProof, policy(Duration.ofMillis(1))));
    UUID bad = UUID.fromString(candidate.ownerRequest().getOperationUuid());
    var operations =
        dsl.fetch(
                "SELECT operation_uuid FROM entity_canonical_roster_original_selections ORDER BY operation_uuid")
            .getValues(0, UUID.class);
    // Explicit inconsistent restore: nonce, positive proof and ACK intent conflicts stay protected.
    transaction.executeWithoutResult(
        ignored -> {
          dsl.execute(
              "ALTER TABLE entity_canonical_roster_retirement_transfers DISABLE TRIGGER USER");
          for (int index = 0; index < 4; index++) {
            UUID operation = operations.get(index);
            var original =
                owners.stream()
                    .filter(
                        owner -> owner.getRequest().getOperationUuid().equals(operation.toString()))
                    .findFirst()
                    .orElseThrow()
                    .getRequest();
            UUID actualNonce =
                dsl.fetchSingle(
                        "SELECT admission_uuid FROM entity_canonical_roster_admissions WHERE operation_uuid = ?",
                        operation)
                    .get(0, UUID.class);
            UUID observation = new UUID(0, index + 1);
            if (index == 0 || index == 3) {
              // The fourth original is a qualified WAITING carrier and must promote before release.
              dsl.execute(
                  "INSERT INTO entity_canonical_roster_retirement_transfers (observation_uuid, operation_uuid, original_request, admission_uuid, snapshot_uuid, snapshot_digest, owner_fence, target_fence_uuid, minimum_retention_millis, transfer_state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 'WAITING_RETIREMENT')",
                  observation,
                  operation,
                  original.toByteArray(),
                  index == 0 ? UUID.randomUUID() : actualNonce,
                  snapshot.snapshotUuid(),
                  snapshot.snapshotDigest(),
                  sealedProof.getOwnerFence(),
                  UUID.fromString(sealedProof.getTargetFenceUuid()));
            } else {
              var observed = index == 1 ? retirement(anchor, snapshot) : sealedProof;
              var intent =
                  net.firedevops.firemud.gamesession.v1
                      .CanonicalGameplayRosterRetirementAcknowledgementRequest.newBuilder()
                      .setSchemaVersion(1)
                      .setOriginalSelection(original)
                      .setObservedRetirement(observed)
                      .setObservationUuid(observation.toString())
                      .setAdmissionUuid((index == 2 ? UUID.randomUUID() : actualNonce).toString())
                      .setSnapshotUuid(snapshot.snapshotUuid().toString())
                      .setSnapshotDigest(snapshot.snapshotDigest())
                      .setMinimumRetentionMillis(1)
                      .build();
              CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(intent);
              dsl.execute(
                  "INSERT INTO entity_canonical_roster_retirement_transfers (observation_uuid, operation_uuid, original_request, admission_uuid, snapshot_uuid, snapshot_digest, owner_fence, target_fence_uuid, minimum_retention_millis, transfer_state, observed_retirement, acknowledgement_request, request_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 'PENDING_ACK', ?, ?, ?)",
                  observation,
                  operation,
                  original.toByteArray(),
                  actualNonce,
                  snapshot.snapshotUuid(),
                  snapshot.snapshotDigest(),
                  sealedProof.getOwnerFence(),
                  UUID.fromString(sealedProof.getTargetFenceUuid()),
                  observed.toByteArray(),
                  intent.toByteArray(),
                  CanonicalGameplayRosterRetirementEvidence.requestDigest(intent));
            }
          }
          dsl.execute(
              "ALTER TABLE entity_canonical_roster_retirement_transfers ENABLE TRIGGER USER");
        });
    var protectedSources =
        operations.subList(0, 3).stream()
            .map(
                operation ->
                    pageSourceRow(
                        "entity_canonical_roster_retirement_transfers",
                        "operation_uuid",
                        operation))
            .toList();
    String source =
        pageSourceRow("entity_canonical_roster_original_selections", "operation_uuid", bad);
    String transfer =
        pageSourceRow("entity_canonical_roster_retirement_transfers", "operation_uuid", bad);
    assertThat(preparePage(1)).isZero();
    assertThat(
            dsl.fetchSingle(
                    "SELECT retirement_preparation_cursor FROM entity_canonical_roster_retention_lifecycle")
                .get(0, UUID.class))
        .isEqualTo(bad);
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    assertThat(preparePage(1)).isZero();
    assertThat(preparePage(1)).isZero();
    assertThat(preparePage(1)).isEqualTo(1);
    assertThat(preparePage(1)).isEqualTo(1);
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_retention_releases")))
        .isEqualTo(2);
    assertThat(
            dsl.fetchExists(
                DSL.selectOne()
                    .from("entity_canonical_roster_retention_releases")
                    .where("reference_uuid = ?", bad)))
        .isFalse();
    dsl.fetch("SELECT pg_sleep(0.02)");
    var incomplete = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    assertThat(
            dsl.fetchSingle(
                    "SELECT retirement_prepared_count FROM entity_canonical_roster_retention_lifecycle")
                .get(0, Long.class))
        .isEqualTo(2L);
    assertThat(transaction.<Boolean>execute(ignored -> repository.dispose(incomplete))).isFalse();
    assertThat(pageSourceRow("entity_canonical_roster_original_selections", "operation_uuid", bad))
        .isEqualTo(source);
    assertThat(pageSourceRow("entity_canonical_roster_retirement_transfers", "operation_uuid", bad))
        .isEqualTo(transfer);
    assertThat(
            operations.subList(0, 3).stream()
                .map(
                    operation ->
                        pageSourceRow(
                            "entity_canonical_roster_retirement_transfers",
                            "operation_uuid",
                            operation))
                .toList())
        .containsExactlyElementsOf(protectedSources);
    assertThat(
            dsl.fetchSingle(
                    "SELECT transfer_state FROM entity_canonical_roster_retirement_transfers WHERE operation_uuid = ?",
                    operations.get(3))
                .get(0, String.class))
        .isEqualTo("PENDING_ACK");
  }

  private void corruptRestoredBytes(String table, String column, UUID operation) {
    // Explicit inconsistent-restore fixture; source immutability remains enabled in production.
    transaction.executeWithoutResult(
        ignored -> {
          dsl.execute("ALTER TABLE " + table + " DISABLE TRIGGER USER");
          dsl.execute(
              "UPDATE " + table + " SET " + column + " = ? WHERE operation_uuid = ?",
              new byte[] {(byte) 0x80},
              operation);
          dsl.execute("ALTER TABLE " + table + " ENABLE TRIGGER USER");
        });
  }

  private String pageSourceRow(String table, String key, UUID value) {
    return dsl.fetchSingle(
            "SELECT row_to_json(t)::text || ':' || t.xmin::text FROM "
                + table
                + " t WHERE "
                + key
                + " = ?",
            value)
        .get(0, String.class);
  }

  @Test
  void typedOnlySnapshotRecognitionReportsEveryBoundedPageBeforeDrainingToZero() {
    int pageSize = 2;
    int snapshotCount = 5;
    transaction.executeWithoutResult(
        ignored -> {
          for (int index = 0; index < snapshotCount; index++) {
            seedCreatedSnapshot(createdReceipt(createdRequest()), 0);
          }
        });
    // Sealing captures V1 inventory through the existing SQL trigger; only typed recognition is
    // pending.
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM entity_canonical_roster_snapshot_targets "
                        + "WHERE source_kind = 'SNAPSHOT' AND recognition_version = 1 "
                        + "AND source_snapshot_projection->>'entry_policy' = 'PLAYER_CREATED'")
                .get(0, Long.class))
        .isEqualTo((long) snapshotCount);
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_admissions"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_retirement_transfers"))).isZero();
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM entity_canonical_roster_snapshot_targets WHERE recognition_version = 2")
                .get(0, Long.class))
        .isZero();
    String retainedEvidenceSql =
        "SELECT row_to_json(s)::text || ':' || s.xmin::text FROM entity_canonical_gameplay_roster_snapshots s "
            + "UNION ALL SELECT row_to_json(m)::text || ':' || m.xmin::text FROM entity_canonical_roster_snapshot_targets m "
            + "WHERE recognition_version = 1 ORDER BY 1";
    List<String> retainedEvidence =
        dsl.fetch(retainedEvidenceSql).map(row -> row.get(0, String.class));

    for (int recognized = 0; recognized < snapshotCount; ) {
      int expectedPage = Math.min(pageSize, snapshotCount - recognized);
      assertThat(
              transaction.<Integer>execute(
                  ignored -> repository.classifyRetainedDependencies(pageSize)))
          .isEqualTo(expectedPage);
      recognized += expectedPage;
      assertThat(
              dsl.fetchSingle(
                      "SELECT count(*) FROM entity_canonical_roster_snapshot_targets "
                          + "WHERE source_kind = 'SNAPSHOT' AND recognition_version = 2 AND classification_state = 'KNOWN'")
                  .get(0, Long.class))
          .isEqualTo((long) recognized);
      assertThat(
              dsl.fetchSingle("SELECT entity_roster_target_classification_pending()")
                  .get(0, Boolean.class))
          .isEqualTo(recognized < snapshotCount);
      assertThat(dsl.fetch(retainedEvidenceSql).map(row -> row.get(0, String.class)))
          .containsExactlyElementsOf(retainedEvidence);
    }
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.classifyRetainedDependencies(pageSize)))
        .isZero();
    assertThat(dsl.fetch(retainedEvidenceSql).map(row -> row.get(0, String.class)))
        .containsExactlyElementsOf(retainedEvidence);
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_admissions"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_retirement_transfers"))).isZero();
  }

  @Test
  void boundedTargetClassificationPassesTrueUnknownAndKeepsEveryAdmissionBoundaryClosed() {
    assertThat(transaction.<Integer>execute(ignored -> repository.classifyRetainedDependencies(1)))
        .isZero();

    var unknownAccount = UUID.randomUUID();
    var unknownLocal =
        localRequest(
            unknownAccount, target(), UUID.fromString("00000000-0000-4000-8000-000000000001"));
    var laterKnownAccount = UUID.randomUUID();
    var laterKnownLocal =
        localRequest(
            laterKnownAccount, target(), UUID.fromString("00000000-0000-4000-8000-000000000002"));
    UUID unknownNonce = transaction.execute(ignored -> repository.reserveAdmission(unknownLocal));
    UUID knownNonce = transaction.execute(ignored -> repository.reserveAdmission(laterKnownLocal));
    transaction.executeWithoutResult(
        ignored ->
            dsl.execute(
                "ALTER TABLE entity_canonical_roster_target_classifications DISABLE TRIGGER USER"));
    try {
      transaction.executeWithoutResult(
          ignored ->
              dsl.execute(
                  "DELETE FROM entity_canonical_roster_target_classifications WHERE source_kind = 'ADMISSION' AND operation_uuid IN (?, ?)",
                  unknownLocal.requestUuid(),
                  laterKnownLocal.requestUuid()));
    } finally {
      transaction.executeWithoutResult(
          ignored ->
              dsl.execute(
                  "ALTER TABLE entity_canonical_roster_target_classifications ENABLE TRIGGER USER"));
    }
    corruptRestoredBytes(
        "entity_canonical_roster_admissions", "request_binding", unknownLocal.requestUuid());

    assertThat(transaction.<Integer>execute(ignored -> repository.classifyRetainedDependencies(1)))
        .isEqualTo(1);
    assertThat(transaction.<Integer>execute(ignored -> repository.classifyRetainedDependencies(1)))
        .isEqualTo(1);
    assertThat(
            dsl.fetchSingle(
                    "SELECT classification_state FROM entity_canonical_roster_target_classifications WHERE source_kind = 'ADMISSION' AND operation_uuid = ? AND admission_uuid = ?",
                    unknownLocal.requestUuid(),
                    unknownNonce)
                .get(0, String.class))
        .isEqualTo("UNKNOWN");
    assertThat(
            dsl.fetchSingle(
                    "SELECT classification_state FROM entity_canonical_roster_target_classifications WHERE source_kind = 'ADMISSION' AND operation_uuid = ? AND admission_uuid = ?",
                    laterKnownLocal.requestUuid(),
                    knownNonce)
                .get(0, String.class))
        .isEqualTo("KNOWN");
    assertThat(
            dsl.fetchSingle("SELECT entity_roster_target_classification_pending()")
                .get(0, Boolean.class))
        .isTrue();
    var anotherAccount = UUID.randomUUID();
    var blocked =
        localRequest(
            anotherAccount, target(), UUID.fromString("00000000-0000-4000-8000-000000000003"));
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.reserveAdmission(blocked)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    assertThat(
            dsl.fetchExists(
                dsl.selectOne()
                    .from("entity_canonical_roster_admissions")
                    .where("operation_uuid = ?", blocked.requestUuid())))
        .isFalse();
  }

  @Test
  void unrelatedKnownTargetAdmissionProgressesAfterBoundedInitializationCompletes() {
    assertThat(transaction.<Integer>execute(ignored -> repository.classifyRetainedDependencies(1)))
        .isZero();
    var unrelated = localRequest(UUID.randomUUID(), target(), UUID.randomUUID());
    UUID nonce = transaction.execute(ignored -> repository.reserveAdmission(unrelated));
    transaction.executeWithoutResult(ignored -> repository.requireAdmission(unrelated, nonce));
    assertThat(
            dsl.fetchSingle("SELECT entity_roster_target_classification_pending()")
                .get(0, Boolean.class))
        .isFalse();
  }

  @Test
  void sameTargetSiblingSealsReplayIndependentlyAfterRepositoryReconstruction() {
    UUID classificationOperation = UUID.randomUUID();
    UUID classificationHold = UUID.randomUUID();
    assertThat(transaction.<Integer>execute(ignored -> repository.classifyRetainedDependencies(1)))
        .isZero();
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM entity_canonical_roster_snapshot_targets "
                        + "WHERE snapshot_uuid = ? AND source_kind = 'SNAPSHOT' AND classification_state = 'KNOWN'",
                    binding.snapshot().snapshotUuid())
                .get(0, Long.class))
        .isEqualTo(1L);
    transaction.executeWithoutResult(
        ignored ->
            repository.acquire(
                binding, classificationOperation, classificationHold, ReferenceKind.RECOVERY));
    transaction.executeWithoutResult(
        ignored -> repository.release(binding, UUID.randomUUID(), classificationHold));

    var firstSnapshot = retainedSnapshot();
    var secondSnapshot = insertSiblingSnapshot(firstSnapshot);
    long ownerFence = 1L;
    UUID targetFence = UUID.randomUUID();
    UUID retirementUuid = UUID.randomUUID();
    var firstLocal = localRequest();
    var secondLocal = localRequest();
    var firstOwner =
        CanonicalGameplayRosterReferenceEvidence.response(
            recovery(firstLocal).getOriginalSelection(),
            ownerFence,
            targetFence,
            firstSnapshot.toWire());
    var secondOwner =
        CanonicalGameplayRosterReferenceEvidence.response(
            recovery(secondLocal).getOriginalSelection(),
            ownerFence,
            targetFence,
            secondSnapshot.toWire());
    bindSnapshot(firstLocal, firstOwner, firstSnapshot);
    bindSnapshot(secondLocal, secondOwner, secondSnapshot);
    String firstSource =
        pageSourceRow(
            "entity_canonical_roster_original_selections",
            "operation_uuid",
            firstLocal.requestUuid());
    String secondSource =
        pageSourceRow(
            "entity_canonical_roster_original_selections",
            "operation_uuid",
            secondLocal.requestUuid());

    var candidates = transaction.execute(ignored -> repository.candidates(null, 10));
    var firstCandidate =
        candidates.stream()
            .filter(candidate -> candidate.snapshotUuid().equals(firstSnapshot.snapshotUuid()))
            .findFirst()
            .orElseThrow();
    var secondCandidate =
        candidates.stream()
            .filter(candidate -> candidate.snapshotUuid().equals(secondSnapshot.snapshotUuid()))
            .findFirst()
            .orElseThrow();
    var firstProof = retirement(firstOwner, firstSnapshot, retirementUuid);
    var secondProof = retirement(secondOwner, secondSnapshot, retirementUuid);
    assertThat(firstProof.getCanonicalRosterReceipt())
        .isEqualTo(firstSnapshot.toWire().toByteString());
    assertThat(secondProof.getCanonicalRosterReceipt())
        .isEqualTo(secondSnapshot.toWire().toByteString());

    assertThat(
            transaction.<Boolean>execute(
                ignored ->
                    repository.observeRetirement(
                        firstCandidate, firstProof, policy(Duration.ofMillis(1)))))
        .isTrue();
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    assertThat(
            transaction.<Boolean>execute(
                ignored ->
                    repository.observeRetirement(
                        secondCandidate, secondProof, policy(Duration.ofMillis(1)))))
        .as("a reconstructed owner repository accepts the sibling's separate exact seal")
        .isTrue();
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    var replayCandidates = transaction.execute(ignored -> repository.candidates(null, 10));
    var replayFirst =
        replayCandidates.stream()
            .filter(value -> value.snapshotUuid().equals(firstSnapshot.snapshotUuid()))
            .findFirst()
            .orElseThrow();
    var replaySecond =
        replayCandidates.stream()
            .filter(value -> value.snapshotUuid().equals(secondSnapshot.snapshotUuid()))
            .findFirst()
            .orElseThrow();
    assertThat(
            transaction.<Boolean>execute(
                ignored ->
                    repository.observeRetirement(
                        replayFirst, firstProof, policy(Duration.ofMillis(1)))))
        .as("lost-reply replay at the current epoch returns the first sibling's persisted result")
        .isTrue();
    assertThat(
            transaction.<Boolean>execute(
                ignored ->
                    repository.observeRetirement(
                        replaySecond, secondProof, policy(Duration.ofMillis(1)))))
        .as("lost-reply replay at the current epoch returns the second sibling's persisted result")
        .isTrue();
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM entity_canonical_roster_retention_lifecycle "
                        + "WHERE snapshot_uuid IN (?, ?) AND lifecycle_state = 'RETIRED'",
                    firstSnapshot.snapshotUuid(),
                    secondSnapshot.snapshotUuid())
                .get(0, Long.class))
        .isEqualTo(2L);
    assertThat(
            pageSourceRow(
                "entity_canonical_roster_original_selections",
                "operation_uuid",
                firstLocal.requestUuid()))
        .isEqualTo(firstSource);
    assertThat(
            pageSourceRow(
                "entity_canonical_roster_original_selections",
                "operation_uuid",
                secondLocal.requestUuid()))
        .isEqualTo(secondSource);
  }

  @Test
  void retainedProtectedInventoryAndImmutableRowsSurviveUpgradeThenRetireNormally() {
    var legacy = rebuildLegacyCohort(false, 0, false, false);
    assertThat(
            dsl.fetchSingle(
                    "SELECT original_selection_count FROM entity_canonical_roster_retention_lifecycle")
                .get(0, Long.class))
        .isEqualTo(2L);
    classifyAllRetainedSources();
    assertThat(immutableRetentionRows()).containsExactlyElementsOf(legacy);
    var candidate = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    transaction.executeWithoutResult(
        ignored ->
            assertThat(
                    repository.observeRetirement(
                        candidate, legacyProof, policy(Duration.ofMillis(1))))
                .isTrue());
    assertThat(preparePage(1)).isEqualTo(1);
    assertThat(preparePage(1)).isEqualTo(1);
  }

  @Test
  void retainedRetiredProofAndPartialIntentInitializeAtomicallyWithoutOwnerRead() {
    var legacy = rebuildLegacyCohort(true, 1, false, false);
    assertThat(
            dsl.fetchSingle(
                    "SELECT original_selection_count, retirement_original_count, retirement_prepared_count FROM entity_canonical_roster_retention_lifecycle")
                .get("retirement_prepared_count", Long.class))
        .isEqualTo(1L);
    classifyAllRetainedSources();
    var candidate = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> {
                      assertThat(repository.initializeRetainedRetirement(candidate)).isTrue();
                      throw new IllegalStateException(
                          "controlled retained initialization rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_retired_targets"))).isZero();
    assertThat(immutableRetentionRows()).containsExactlyElementsOf(legacy);
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.initializeRetainedRetirement(candidate)))
        .isTrue();
    assertThat(preparePage(1)).isEqualTo(1);
    assertThat(preparePage(1)).isZero();
    assertThat(
            dsl.fetchSingle(
                    "SELECT owner_retirement_proof FROM entity_canonical_roster_retention_lifecycle")
                .get(0, byte[].class))
        .isEqualTo(legacyProof.toByteArray());
    dsl.fetch("SELECT pg_sleep(0.02)");
    var completed = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    assertThat(transaction.<Boolean>execute(ignored -> repository.dispose(completed))).isTrue();
  }

  @Test
  void inconsistentRetainedOriginalInventoryStaysUnknownWithoutRecreatingAuthority() {
    rebuildLegacyCohort(true, 1, false, false, true);
    classifyAllRetainedSources();
    var inventory =
        dsl.fetchSingle(
            "SELECT original_selection_count, retirement_original_count FROM entity_canonical_roster_retention_lifecycle");
    assertThat(inventory.get("original_selection_count", Long.class)).isNull();
    assertThat(inventory.get("retirement_original_count", Long.class)).isNull();
    var candidate = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.initializeRetainedRetirement(candidate)))
        .isFalse();
    assertThat(transaction.<Boolean>execute(ignored -> repository.dispose(candidate))).isFalse();
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_retired_targets"))).isZero();
  }

  @Test
  void retainedDisposedAdmissionFencesTargetWithoutSyntheticSeal() {
    assertRetainedDisposedLineage(false);
  }

  @Test
  void retainedTransferOnlyCohortFencesTargetAfterPayloadAndOldNonceAreAlreadyGone() {
    assertRetainedDisposedLineage(true);
  }

  private void assertRetainedDisposedLineage(boolean alreadyPurged) {
    var legacy = rebuildLegacyCohort(true, 1, true, alreadyPurged);
    classifyAllRetainedSources();
    assertThat(immutableRetentionRows()).containsExactlyElementsOf(legacy);
    if (!alreadyPurged)
      assertThat(
              dsl.fetchSingle("SELECT admission_state FROM entity_canonical_roster_admissions")
                  .get(0, String.class))
          .isEqualTo("DISPOSED");
    assertThatThrownBy(
            () -> transaction.execute(ignored -> repository.reserveAdmission(localRequest())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    dsl.fetch("SELECT pg_sleep(0.02)");
    transaction.executeWithoutResult(ignored -> repository.purgeDisposedTombstones(2));
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_admissions"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_retired_targets"))).isZero();
    assertThatThrownBy(
            () -> transaction.execute(ignored -> repository.reserveAdmission(localRequest())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    var transfer =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    var ack =
        CanonicalGameplayRosterRetirementEvidence.response(
            transfer.acknowledgementRequest(), UUID.randomUUID(), 1);
    transaction.executeWithoutResult(
        ignored -> repository.recordRetirementAcknowledgement(transfer, ack));
    var acknowledged =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    var confirmation =
        CanonicalGameplayRosterRetirementEvidence.confirmation(
            acknowledged.acknowledgementRequest(), acknowledged.acknowledgement());
    transaction.executeWithoutResult(
        ignored ->
            repository.recordRetirementConfirmation(
                acknowledged,
                CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmation)));
    dsl.fetch("SELECT pg_sleep(0.02)");
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementTransfers(1)))
        .isEqualTo(1);
    transaction.executeWithoutResult(ignored -> repository.purgeTargetClassifications(10));
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_target_classifications")))
        .isZero();
    var old = legacyRequests.getFirst();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.requireAdmission(old, legacyNonces.get(old.requestUuid()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
  }

  @Test
  void malformedRetainedBindingIsDurableUnknownAndCannotStarveLaterClassification() {
    UUID bad = new UUID(0, 1);
    dsl.execute(
        "INSERT INTO entity_canonical_roster_admissions (operation_uuid, admission_uuid, request_binding) VALUES (?, ?, ?)",
        bad,
        UUID.randomUUID(),
        new byte[] {1});
    for (int index = 0; index < 4; index++) {
      var local = localRequest();
      dsl.execute(
          "INSERT INTO entity_canonical_roster_admissions (operation_uuid, admission_uuid, request_binding) VALUES (?, ?, ?)",
          local.requestUuid(),
          UUID.randomUUID(),
          legacyAdmissionBinding(local));
    }
    var before = immutableRetentionRows();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> {
                      assertThat(repository.classifyRetainedDependencies(2)).isEqualTo(2);
                      throw new IllegalStateException("controlled classification rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_target_classifications")))
        .isZero();
    assertThat(transaction.<Integer>execute(ignored -> repository.classifyRetainedDependencies(2)))
        .isEqualTo(2);
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    assertThat(transaction.<Integer>execute(ignored -> repository.classifyRetainedDependencies(2)))
        .isEqualTo(2);
    assertThat(transaction.<Integer>execute(ignored -> repository.classifyRetainedDependencies(2)))
        .isEqualTo(1);
    assertThat(transaction.<Integer>execute(ignored -> repository.classifyRetainedDependencies(2)))
        .isZero();
    assertThat(
            dsl.fetchSingle(
                    "SELECT classification_state FROM entity_canonical_roster_target_classifications WHERE operation_uuid = ?",
                    bad)
                .get(0, String.class))
        .isEqualTo("UNKNOWN");
    assertThat(immutableRetentionRows()).containsExactlyElementsOf(before);
    assertThatThrownBy(
            () -> transaction.execute(ignored -> repository.reserveAdmission(localRequest())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM entity_canonical_roster_target_classifications WHERE operation_uuid = ?",
                    bad))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_roster_target_classifications SET source_binding = ? WHERE operation_uuid = ?",
                    new byte[] {2},
                    bad))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void immutableAcquireReleaseAndHoldOutcomesNeverGrantTerminality() {
    UUID acquireOperation = UUID.randomUUID();
    UUID reference = UUID.randomUUID();
    UUID hold = UUID.randomUUID();
    UUID releaseOperation = UUID.randomUUID();
    transaction.executeWithoutResult(
        ignored -> {
          repository.acquire(
              binding, acquireOperation, reference, ReferenceKind.ORIGINAL_SELECTION);
          repository.acquire(
              binding, acquireOperation, reference, ReferenceKind.ORIGINAL_SELECTION);
          repository.acquire(binding, UUID.randomUUID(), hold, ReferenceKind.HOLD);
          assertThat(repository.protection(binding).liveReferences()).isEqualTo(1);
          assertThat(repository.protection(binding).holds()).isEqualTo(1);
          repository.release(binding, releaseOperation, reference);
          repository.release(binding, releaseOperation, reference);
          assertThat(repository.protection(binding).liveReferences()).isZero();
          assertThat(repository.protection(binding).holds()).isEqualTo(1);
          assertThat(repository.protection(binding).disposalEligible()).isFalse();
          repository.release(binding, UUID.randomUUID(), hold);
          var zeroReferences = repository.protection(binding);
          assertThat(zeroReferences.liveReferences()).isZero();
          assertThat(zeroReferences.holds()).isZero();
          assertThat(zeroReferences.terminality())
              .isEqualTo("AUTHENTICATED_SUPERSESSION_UNAVAILABLE");
          assertThat(zeroReferences.disposalEligible()).isFalse();
        });
    List<String> retained = retainedRows();
    for (String table :
        List.of(
            "entity_canonical_roster_retention_lifecycle",
            "entity_canonical_roster_retention_references",
            "entity_canonical_roster_retention_releases",
            "entity_canonical_gameplay_roster_snapshots",
            "entity_canonical_gameplay_roster_snapshot_actors")) {
      assertThatThrownBy(() -> dsl.execute("DELETE FROM " + table))
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(() -> dsl.execute("TRUNCATE " + table + " CASCADE"))
          .isInstanceOf(DataAccessException.class);
    }
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_roster_retention_lifecycle "
                        + "SET lifecycle_state = 'RETIRED'"))
        .isInstanceOf(DataAccessException.class);
    assertThat(retainedRows()).containsExactlyElementsOf(retained);
  }

  @Test
  void staleTargetFenceUnknownReferenceAndIdentityReuseFailClosed() {
    UUID acquireOperation = UUID.randomUUID();
    UUID reference = UUID.randomUUID();
    transaction.executeWithoutResult(
        ignored ->
            repository.acquire(binding, acquireOperation, reference, ReferenceKind.RECOVERY));
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.acquire(
                            binding, UUID.randomUUID(), reference, ReferenceKind.RECOVERY)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_REFERENCE_IDEMPOTENCY_CONFLICT");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.release(binding, UUID.randomUUID(), UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_REFERENCE_UNAVAILABLE");
    var old = binding.target();
    var stale =
        new CanonicalGameplayRosterTarget(
            old.tenantUuid(),
            old.realmUuid(),
            old.worldSlug(),
            old.realmSlug(),
            old.gameInstanceUuid(),
            old.catalogRevision(),
            old.pointerVersion() + 1,
            old.activeWorldEpoch(),
            old.canonicalVersionUuid(),
            old.publishedPolicyDigest(),
            old.publishedReleaseBundleRef(),
            old.admissionPointerSnapshotDigest(),
            old.publishedOwnerProofDigest(),
            old.playableStateNamespaceId(),
            old.playableStateScope(),
            old.entryPolicy());
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.protection(
                            new CanonicalGameplayRosterRetentionBinding(
                                binding.canonicalAccountUuid(), binding.snapshot(), stale))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_RETENTION_TARGET_MISMATCH");
    assertThatThrownBy(() -> repository.protection(binding))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_RETENTION_OWNER_TRANSACTION_REQUIRED");
  }

  @Test
  void rollbackPreservesActorReceiptSnapshotAndDoesNotReleaseAReference() {
    UUID reference = UUID.randomUUID();
    transaction.executeWithoutResult(
        ignored ->
            repository.acquire(
                binding, UUID.randomUUID(), reference, ReferenceKind.ORIGINAL_SELECTION));
    List<String> retained = retainedRows();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> {
                      repository.release(binding, UUID.randomUUID(), reference);
                      throw new IllegalStateException("controlled rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    transaction.executeWithoutResult(
        ignored -> assertThat(repository.protection(binding).liveReferences()).isEqualTo(1));
    assertThat(retainedRows()).containsExactlyElementsOf(retained);
    assertThat(
            dsl.fetchSingle("SELECT count(*) FROM entity_canonical_roster_retention_releases")
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void concurrentAcquisitionRemainsAtomicWhileDirectDisposalIsRefused() throws Exception {
    CountDownLatch acquired = new CountDownLatch(1);
    CountDownLatch inspected = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var acquire =
          executor.submit(
              () ->
                  transaction.executeWithoutResult(
                      ignored -> {
                        repository.acquire(
                            binding, UUID.randomUUID(), UUID.randomUUID(), ReferenceKind.RECOVERY);
                        acquired.countDown();
                        await(inspected);
                      }));
      var dispose =
          executor.submit(
              () -> {
                await(acquired);
                try {
                  assertThatThrownBy(
                          () ->
                              transaction.executeWithoutResult(
                                  ignored -> {
                                    dsl.execute("SET LOCAL lock_timeout = '500ms'");
                                    dsl.execute(
                                        "DELETE FROM entity_canonical_gameplay_roster_snapshots "
                                            + "WHERE snapshot_uuid = ?",
                                        binding.snapshot().snapshotUuid());
                                  }))
                      .isInstanceOf(DataAccessException.class);
                } finally {
                  inspected.countDown();
                }
              });
      acquire.get(15, TimeUnit.SECONDS);
      dispose.get(15, TimeUnit.SECONDS);
    }
    transaction.executeWithoutResult(
        ignored -> {
          assertThat(repository.protection(binding).liveReferences()).isEqualTo(1);
          assertThat(repository.protection(binding).disposalEligible()).isFalse();
        });
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?",
                    binding.snapshot().snapshotUuid()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void closedNonceRejectsDelayedBindingAndExactRecoveryReplayIsImmutable() {
    var local = localRequest();
    var recovery = recovery(local);
    var owner = recoveryOwner(recovery);
    UUID nonce = transaction.execute(ignored -> repository.reserveAdmission(local));
    var response =
        transaction.execute(
            ignored -> repository.closeAdmission(recovery, owner, policy(Duration.ofHours(1))));
    assertThat(response.getAdmissionUuid()).isEqualTo(nonce.toString());
    assertThat(response.getCanonicalRosterReceipt()).isEmpty();
    assertThat(
            transaction
                .<net.firedevops.firemud.entitymanagement.v1
                        .CanonicalGameplayRosterRecoveryResponse>
                    execute(
                        ignored ->
                            repository.closeAdmission(
                                recovery, owner, policy(Duration.ofHours(2)))))
        .isEqualTo(response);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.requireAdmission(local, nonce)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.bindAdmission(
                            local.requestUuid(), nonce, binding.snapshot().snapshotUuid())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.reserveAdmission(local)))
        .isInstanceOf(IllegalStateException.class);
    var conflict = recovery.toBuilder().setRecoveryUuid(UUID.randomUUID().toString()).build();
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.closeAdmission(
                            conflict, recoveryOwner(conflict), policy(Duration.ofHours(1)))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_RECOVERY_CONFLICT");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_roster_admissions SET admission_state = 'OPEN', closed_at = NULL, minimum_retention_millis = NULL"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_roster_admissions SET recovery_response = ?",
                    new byte[] {1}))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> dsl.execute("DELETE FROM entity_canonical_roster_admissions"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void openAdmissionRejectsOwnerReceiptWithoutLocalOriginalAndPreservesAdmissionBytes() {
    var local = localRequest();
    var recovery = recovery(local);
    var owner = recoveryOwner(recovery, retainedSnapshot());
    assertThat(owner.getCanonicalRosterReceipt()).isNotEmpty();
    transaction.execute(ignored -> repository.reserveAdmission(local));
    String admissionBefore = admissionRow(local.requestUuid());
    assertThat(admissionBefore)
        .contains("\"admission_state\":\"OPEN\"")
        .contains("\"recovery_response\":null");

    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.closeAdmission(recovery, owner, policy(Duration.ofHours(1)))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_ORIGINAL_LOCAL_RECEIPT_UNAVAILABLE");

    assertThat(admissionRow(local.requestUuid())).isEqualTo(admissionBefore);
  }

  @Test
  void provisionalAdmissionInsertRollsBackWhenOwnerReceiptHasNoLocalOriginal() {
    var local = localRequest();
    var recovery = recovery(local);
    var owner = recoveryOwner(recovery, retainedSnapshot());
    assertThat(owner.getCanonicalRosterReceipt()).isNotEmpty();
    assertThat(admissionCount(local.requestUuid())).isZero();

    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.closeAdmission(recovery, owner, policy(Duration.ofHours(1)))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_ORIGINAL_LOCAL_RECEIPT_UNAVAILABLE");

    assertThat(admissionCount(local.requestUuid())).isZero();
  }

  @Test
  void createdOpenAdmissionPreservesBytesAndXminWhenOwnerReceiptIsUnavailableLocally() {
    var request = createdRequest();
    var original = createdOriginal(request);
    var receipt = createdReceipt(request);
    var recovery =
        CanonicalGameplayRosterRecoveryRequest.newBuilder()
            .setSchemaVersion(1)
            .setOriginalSelection(original)
            .setRecoveryUuid(UUID.randomUUID().toString())
            .build();
    var owner =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
                original, 1, UUID.randomUUID(), receipt)
            .toBuilder()
            .setRecoveryUuid(recovery.getRecoveryUuid())
            .build();
    assertThat(owner.getCanonicalPlayerCreatedRosterReceipt()).isNotEmpty();
    UUID operation = UUID.fromString(request.getRequestUuid());
    transaction.execute(ignored -> repository.reserveAdmission(request));
    String admissionBefore =
        dsl.fetchSingle(
                "SELECT row_to_json(a)::text || ':' || a.xmin::text "
                    + "FROM entity_canonical_roster_admissions a WHERE operation_uuid = ?",
                operation)
            .get(0, String.class);
    assertThat(admissionBefore)
        .contains("\"admission_state\":\"OPEN\"")
        .contains("\"recovery_response\":null");

    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.closeAdmission(recovery, owner, policy(Duration.ofHours(1)))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_ORIGINAL_LOCAL_RECEIPT_UNAVAILABLE");

    String admissionAfter =
        dsl.fetchSingle(
                "SELECT row_to_json(a)::text || ':' || a.xmin::text "
                    + "FROM entity_canonical_roster_admissions a WHERE operation_uuid = ?",
                operation)
            .get(0, String.class);
    assertThat(admissionAfter).isEqualTo(admissionBefore);
  }

  @Test
  void createdProvisionalAdmissionInsertRollsBackWhenOwnerReceiptHasNoLocalOriginal() {
    var request = createdRequest();
    var original = createdOriginal(request);
    var receipt = createdReceipt(request);
    var recovery =
        CanonicalGameplayRosterRecoveryRequest.newBuilder()
            .setSchemaVersion(1)
            .setOriginalSelection(original)
            .setRecoveryUuid(UUID.randomUUID().toString())
            .build();
    var owner =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
                original, 1, UUID.randomUUID(), receipt)
            .toBuilder()
            .setRecoveryUuid(recovery.getRecoveryUuid())
            .build();
    assertThat(owner.getCanonicalPlayerCreatedRosterReceipt()).isNotEmpty();
    UUID operation = UUID.fromString(request.getRequestUuid());
    assertThat(admissionCount(operation)).isZero();

    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.closeAdmission(recovery, owner, policy(Duration.ofHours(1)))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_ORIGINAL_LOCAL_RECEIPT_UNAVAILABLE");

    assertThat(admissionCount(operation)).isZero();
  }

  @Test
  void closedUnboundAdmissionRequiresExactPositiveOwnerAcknowledgementAndLocalHorizon() {
    var local = localRequest();
    var recovery = recovery(local);
    var owner = recoveryOwner(recovery);
    transaction.execute(
        ignored -> repository.closeAdmission(recovery, owner, policy(Duration.ofMillis(1))));
    var candidate =
        transaction.execute(ignored -> repository.closedAdmissionCandidates(null, 1)).getFirst();
    // Let the physical database's clock pass the deliberately tiny controlled fixture horizon.
    dsl.fetch("SELECT pg_sleep(0.02)");
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.purgeClosedAdmission(candidate, owner)))
        .isFalse();
    assertThatThrownBy(() -> dsl.execute("DELETE FROM entity_canonical_roster_admissions"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.purgeClosedAdmission(
                            candidate,
                            owner.toBuilder()
                                .setRecoveryUuid(UUID.randomUUID().toString())
                                .setTerminalOutcome("DENIED")
                                .build())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.purgeClosedAdmission(
                            candidate,
                            owner.toBuilder()
                                .setOwnerFence(2)
                                .setTerminalOutcome("DENIED")
                                .build())))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            transaction.<Boolean>execute(
                ignored ->
                    repository.purgeClosedAdmission(
                        candidate, owner.toBuilder().setTerminalOutcome("DENIED").build())))
        .isTrue();
    assertThat(
            transaction
                .<List<CanonicalGameplayRosterRetentionRepository.ClosedAdmissionCandidate>>execute(
                    ignored -> repository.closedAdmissionCandidates(null, 1)))
        .isEmpty();
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.reserveAdmission(local)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.requireAdmission(local, candidate.admissionUuid())))
        .isInstanceOf(IllegalStateException.class);
    assertThat(retainedRows()).hasSize(4);

    var heldLocal = localRequest();
    var heldRecovery = recovery(heldLocal);
    var heldOwner = recoveryOwner(heldRecovery);
    transaction.execute(
        ignored -> repository.closeAdmission(heldRecovery, heldOwner, policy(Duration.ofHours(1))));
    var held =
        transaction.execute(ignored -> repository.closedAdmissionCandidates(null, 1)).getFirst();
    assertThat(
            transaction.<Boolean>execute(
                ignored ->
                    repository.purgeClosedAdmission(
                        held, heldOwner.toBuilder().setTerminalOutcome("DENIED").build())))
        .isFalse();
  }

  @Test
  void longerEntityClosureHorizonPreventsEarlyTransferEvenWhenOwnerAlreadyRetired() {
    var local = localRequest();
    var recovery = recovery(local);
    var owner = recoveryOwner(recovery);
    var minimum = Duration.ofSeconds(3);
    var closed =
        transaction.execute(ignored -> repository.closeAdmission(recovery, owner, policy(minimum)));
    var candidate =
        transaction.execute(ignored -> repository.closedAdmissionCandidates(null, 1)).getFirst();
    var denied = owner.toBuilder().setTerminalOutcome("DENIED").build();
    var retired = retirement(owner, null);
    // A GS owner may already have retired under a shorter independent local horizon. Neither
    // its positive proof nor elapsed GS time may expose an ACK intent before Entity can purge.
    dsl.fetch("SELECT pg_sleep(0.02)");
    for (var terminalOwner : List.of(denied, retired)) {
      assertThat(
              transaction.<Boolean>execute(
                  ignored -> repository.purgeClosedAdmission(candidate, terminalOwner)))
          .isFalse();
      var pendingTransfers =
          transaction.execute(ignored -> repository.retirementTransfers(null, 1));
      assertThat(pendingTransfers).isEmpty();
      assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_retirement_transfers")))
          .isZero();
      assertThat(
              dsl.fetchSingle(
                      "SELECT recovery_response FROM entity_canonical_roster_admissions WHERE operation_uuid = ?",
                      local.requestUuid())
                  .get(0, byte[].class))
          .isEqualTo(closed.toByteArray());
    }
    assertThatThrownBy(() -> dsl.execute("DELETE FROM entity_canonical_roster_admissions"))
        .isInstanceOf(DataAccessException.class);

    dsl.fetch("SELECT pg_sleep(3.1)");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> {
                      assertThat(repository.purgeClosedAdmission(candidate, retired)).isTrue();
                      assertThat(repository.retirementTransfers(null, 1)).hasSize(1);
                      assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_admissions")))
                          .isZero();
                      throw new IllegalStateException("controlled transfer/purge rollback");
                    }))
        .hasMessage("controlled transfer/purge rollback");
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_admissions"))).isEqualTo(1);
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_retirement_transfers"))).isZero();
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.purgeClosedAdmission(candidate, retired)))
        .isTrue();
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_admissions"))).isZero();
    var transfer =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    assertThat(transfer.state()).isEqualTo("PENDING_ACK");
    assertThat(transfer.original()).isEqualTo(recovery.getOriginalSelection());
    assertThat(transfer.acknowledgementRequest().getAdmissionUuid())
        .isEqualTo(closed.getAdmissionUuid());
    assertThat(transfer.acknowledgementRequest().getObservedRetirement()).isEqualTo(retired);
    assertThat(transfer.acknowledgementRequest().getMinimumRetentionMillis())
        .isEqualTo(minimum.toMillis());
  }

  @Test
  void qualifiedDisposalExtendsClosedAdmissionRetentionWhenRetirementHorizonIncreases() {
    var local = localRequest();
    var recovery = recovery(local);
    var owner = recoveryOwner(recovery);
    UUID nonce = transaction.execute(ignored -> repository.reserveAdmission(local));
    var actor =
        dsl.fetchSingle(
                "SELECT character_uuid FROM entity_canonical_gameplay_roster_snapshot_actors WHERE snapshot_uuid = ?",
                binding.snapshot().snapshotUuid())
            .get("character_uuid", UUID.class);
    var snapshot =
        new CanonicalGameplayRosterSnapshot(
            binding.canonicalAccountUuid(),
            binding.snapshot().snapshotUuid(),
            binding.snapshot().snapshotDigest(),
            binding.target(),
            List.of(new CanonicalGameplayRosterActor(actor, "Retained Actor")));
    transaction.executeWithoutResult(
        ignored -> {
          repository.requireAdmission(local, nonce);
          repository.bindOriginal(owner, snapshot);
          repository.bindAdmission(local.requestUuid(), nonce, snapshot.snapshotUuid());
        });
    var closed =
        transaction.execute(
            ignored -> repository.closeAdmission(recovery, owner, policy(Duration.ofMillis(1))));
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_roster_admissions SET minimum_retention_millis = 2 WHERE operation_uuid = ?",
                    local.requestUuid()))
        .isInstanceOf(DataAccessException.class);
    var proof =
        CanonicalGameplayRosterReferenceEvidence.sealRetirement(
            owner.toBuilder()
                .setCanonicalRosterReceipt(snapshot.toWire().toByteString())
                .setState(
                    net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState
                        .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED)
                .setTerminalOutcome("DENIED")
                .setRetirementUuid(UUID.randomUUID().toString())
                .setSupersedingPointerDigest("f".repeat(64))
                .setSupersedingPointerVersion(binding.target().pointerVersion() + 1)
                .setSupersedingCatalogRevision(binding.target().catalogRevision())
                .setSupersedingWorldEpoch(binding.target().activeWorldEpoch())
                .build());
    var protectedCandidate =
        transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    assertThat(
            transaction.<Boolean>execute(
                ignored ->
                    repository.observeRetirement(
                        protectedCandidate, proof, policy(Duration.ofMillis(20)))))
        .isTrue();
    assertThat(preparePage(2)).isEqualTo(1);
    dsl.fetch("SELECT pg_sleep(0.03)");
    var retiredCandidate =
        transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    assertThat(transaction.<Boolean>execute(ignored -> repository.dispose(retiredCandidate)))
        .isTrue();
    var disposed =
        dsl.fetchSingle(
            "SELECT * FROM entity_canonical_roster_admissions WHERE operation_uuid = ?",
            local.requestUuid());
    assertThat(disposed.get("admission_state", String.class)).isEqualTo("DISPOSED");
    assertThat(disposed.get("minimum_retention_millis", Long.class)).isEqualTo(20L);
    assertThat(disposed.get("recovery_uuid", UUID.class).toString())
        .isEqualTo(recovery.getRecoveryUuid());
    assertThat(disposed.get("recovery_request", byte[].class)).isEqualTo(recovery.toByteArray());
    assertThat(disposed.get("recovery_response", byte[].class)).isEqualTo(closed.toByteArray());
    assertThat(disposed.get("recovery_owner_request", byte[].class))
        .isEqualTo(owner.getRequest().toByteArray());
    assertThat(disposed.get("recovery_owner_fence", Long.class)).isEqualTo(owner.getOwnerFence());
    assertThat(disposed.get("recovery_target_fence_uuid", UUID.class).toString())
        .isEqualTo(owner.getTargetFenceUuid());
    assertThat(disposed.get("admission_uuid", UUID.class)).isEqualTo(nonce);
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_gameplay_roster_snapshots"))).isZero();
  }

  @Test
  void retirementSealPreparesMoreThanOnePageWhileHoldProtectsPayloadAndReplayKeepsIdentities() {
    var snapshot = retainedSnapshot();
    var owners = new java.util.ArrayList<GetCanonicalGameplayRosterReferenceResponse>();
    var nonces = new java.util.HashMap<String, UUID>();
    var firstOwner = recoveryOwner(recovery(localRequest()));
    for (int index = 0; index < 5; index++) {
      var local = localRequest();
      var owner =
          CanonicalGameplayRosterReferenceEvidence.response(
              recovery(local).getOriginalSelection(),
              firstOwner.getOwnerFence(),
              UUID.fromString(firstOwner.getTargetFenceUuid()),
              null);
      owners.add(owner);
      nonces.put(local.requestUuid().toString(), bindSnapshot(local, owner, snapshot));
    }
    // This nonce has already crossed reservation but has not captured any snapshot.
    var late = localRequest();
    UUID lateNonce = transaction.execute(ignored -> repository.reserveAdmission(late));
    UUID hold = UUID.randomUUID();
    transaction.executeWithoutResult(
        ignored -> {
          repository.acquire(binding, UUID.randomUUID(), hold, ReferenceKind.HOLD);
          repository.acquire(binding, UUID.randomUUID(), UUID.randomUUID(), ReferenceKind.RECOVERY);
        });
    var candidate = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    var anchor =
        owners.stream()
            .filter(owner -> owner.getRequest().equals(candidate.ownerRequest()))
            .findFirst()
            .orElseThrow();
    var proof = retirement(anchor, snapshot);
    transaction.executeWithoutResult(
        ignored ->
            assertThat(repository.observeRetirement(candidate, proof, policy(Duration.ofMillis(1))))
                .isTrue());
    assertThat(
            transaction
                .<List<CanonicalGameplayRosterRetentionRepository.RetirementTransfer>>execute(
                    ignored -> repository.retirementTransfers(null, 10)))
        .isEmpty();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.requireAdmission(late, lateNonce)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    assertThatThrownBy(
            () -> transaction.execute(ignored -> repository.reserveAdmission(localRequest())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.acquire(
                            binding, UUID.randomUUID(), UUID.randomUUID(), ReferenceKind.RECOVERY)))
        .isInstanceOf(IllegalStateException.class);

    var sealedCandidate = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> {
                      assertThat(repository.prepareRetirement(sealedCandidate, 2)).isEqualTo(2);
                      throw new IllegalStateException("controlled page rollback");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("controlled page rollback");
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_retirement_transfers"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_retention_releases"))).isZero();
    var alternate =
        new CanonicalGameplayRosterRetentionBinding(
            binding.canonicalAccountUuid(),
            new CanonicalGameplayRosterSnapshotReference(
                UUID.randomUUID(), binding.snapshot().snapshotDigest()),
            binding.target());
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.acquire(
                            alternate,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            ReferenceKind.ORIGINAL_SELECTION)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    assertThat(preparePage(2)).isEqualTo(2);
    var firstPage = transaction.execute(ignored -> repository.retirementTransfers(null, 10));
    assertThat(firstPage).hasSize(2);
    var stale = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    transaction.executeWithoutResult(
        ignored ->
            repository.acquire(binding, UUID.randomUUID(), UUID.randomUUID(), ReferenceKind.HOLD));
    assertThat(transaction.<Integer>execute(ignored -> repository.prepareRetirement(stale, 2)))
        .isZero();
    assertThat(transaction.<Boolean>execute(ignored -> repository.dispose(stale))).isFalse();
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    assertThat(preparePage(2)).isEqualTo(2);
    assertThat(preparePage(2)).isEqualTo(1);
    var transfers = transaction.execute(ignored -> repository.retirementTransfers(null, 10));
    assertThat(transfers)
        .hasSize(5)
        .allSatisfy(
            transfer -> {
              var request = transfer.acknowledgementRequest();
              assertThat(request.getObservedRetirement()).isEqualTo(proof);
              assertThat(request.getAdmissionUuid())
                  .isEqualTo(
                      nonces.get(request.getOriginalSelection().getOperationUuid()).toString());
              assertThat(request.getSnapshotUuid()).isEqualTo(snapshot.snapshotUuid().toString());
              assertThat(request.getSnapshotDigest()).isEqualTo(snapshot.snapshotDigest());
            });
    assertThat(preparePage(2)).isZero();
    assertThat(preparePage(2)).isZero();
    assertThat(
            transaction
                .<List<CanonicalGameplayRosterRetentionRepository.RetirementTransfer>>execute(
                    ignored -> repository.retirementTransfers(null, 10)))
        .isEqualTo(transfers);
    assertThat(transfers).containsAll(firstPage);
    for (var transfer : transfers) {
      var acknowledgement =
          CanonicalGameplayRosterRetirementEvidence.response(
              transfer.acknowledgementRequest(), UUID.randomUUID(), 1);
      transaction.executeWithoutResult(
          ignored -> repository.recordRetirementAcknowledgement(transfer, acknowledgement));
    }
    for (var transfer : transaction.execute(ignored -> repository.retirementTransfers(null, 10))) {
      var confirmation =
          CanonicalGameplayRosterRetirementEvidence.confirmation(
              transfer.acknowledgementRequest(), transfer.acknowledgement());
      transaction.executeWithoutResult(
          ignored ->
              repository.recordRetirementConfirmation(
                  transfer,
                  CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmation)));
    }
    dsl.fetch("SELECT pg_sleep(0.02)");
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementTransfers(10)))
        .isZero();
    var retired = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    assertThat(transaction.<Boolean>execute(ignored -> repository.dispose(retired))).isFalse();
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_gameplay_roster_snapshots")))
        .isEqualTo(1);
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_original_selections")))
        .isEqualTo(5);
    assertThat(transaction.<Integer>execute(ignored -> repository.purgeRetiredTargets(2))).isZero();
  }

  @Test
  void concurrentHoldWaitsForPreparationThenInvalidatesItsEpochAndProtectsFinalDisposal()
      throws Exception {
    var local = localRequest();
    var owner = recoveryOwner(recovery(local));
    var snapshot = retainedSnapshot();
    bindSnapshot(local, owner, snapshot);
    var candidate = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    transaction.executeWithoutResult(
        ignored ->
            repository.observeRetirement(
                candidate, retirement(owner, snapshot), policy(Duration.ofMillis(1))));
    var sealed = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    var preparationPid = new java.util.concurrent.atomic.AtomicInteger();
    var holdPid = new java.util.concurrent.atomic.AtomicInteger();
    CountDownLatch prepared = new CountDownLatch(1);
    CountDownLatch holdStarted = new CountDownLatch(1);
    CountDownLatch allowCommit = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var preparation =
          executor.submit(
              () ->
                  transaction.executeWithoutResult(
                      ignored -> {
                        preparationPid.set(
                            dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                        assertThat(repository.prepareRetirement(sealed, 2)).isEqualTo(1);
                        prepared.countDown();
                        await(allowCommit);
                      }));
      await(prepared);
      var hold =
          executor.submit(
              () ->
                  transaction.executeWithoutResult(
                      ignored -> {
                        holdPid.set(
                            dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                        holdStarted.countDown();
                        repository.acquire(
                            binding, UUID.randomUUID(), UUID.randomUUID(), ReferenceKind.HOLD);
                      }));
      await(holdStarted);
      try {
        awaitBackendBlockedBy(holdPid.get(), preparationPid.get());
      } finally {
        allowCommit.countDown();
      }
      preparation.get(15, TimeUnit.SECONDS);
      hold.get(15, TimeUnit.SECONDS);
    } finally {
      allowCommit.countDown();
    }
    assertThat(transaction.<Boolean>execute(ignored -> repository.dispose(sealed))).isFalse();
    var held = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    assertThat(held.localEpoch()).isGreaterThan(sealed.localEpoch());
    assertThat(transaction.<Boolean>execute(ignored -> repository.dispose(held))).isFalse();
    assertThat(
            transaction
                .<List<CanonicalGameplayRosterRetentionRepository.RetirementTransfer>>execute(
                    ignored -> repository.retirementTransfers(null, 2)))
        .hasSize(1);
  }

  @Test
  void missingAdmissionCannotCompleteAndDoesNotStarveLaterOriginalPages() {
    var snapshot = retainedSnapshot();
    var owners = new java.util.ArrayList<GetCanonicalGameplayRosterReferenceResponse>();
    var first = recoveryOwner(recovery(localRequest()));
    for (int index = 0; index < 5; index++) {
      var local = localRequest();
      var owner =
          CanonicalGameplayRosterReferenceEvidence.response(
              recovery(local).getOriginalSelection(),
              first.getOwnerFence(),
              UUID.fromString(first.getTargetFenceUuid()),
              null);
      owners.add(owner);
      bindSnapshot(local, owner, snapshot);
    }
    var candidate = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    var anchor =
        owners.stream()
            .filter(owner -> owner.getRequest().equals(candidate.ownerRequest()))
            .findFirst()
            .orElseThrow();
    transaction.executeWithoutResult(
        ignored ->
            repository.observeRetirement(
                candidate, retirement(anchor, snapshot), policy(Duration.ofMillis(1))));
    UUID missing = UUID.fromString(candidate.ownerRequest().getOperationUuid());
    // Controlled restore inconsistency: missing rows are not positive completion evidence.
    transaction.executeWithoutResult(
        ignored -> {
          dsl.execute("ALTER TABLE entity_canonical_roster_admissions DISABLE TRIGGER USER");
          dsl.execute(
              "DELETE FROM entity_canonical_roster_admissions WHERE operation_uuid = ?", missing);
          dsl.execute("ALTER TABLE entity_canonical_roster_admissions ENABLE TRIGGER USER");
        });
    assertThat(preparePage(2)).isEqualTo(1);
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    assertThat(preparePage(2)).isEqualTo(2);
    assertThat(preparePage(2)).isEqualTo(1);
    assertThat(
            transaction
                .<List<CanonicalGameplayRosterRetentionRepository.RetirementTransfer>>execute(
                    ignored -> repository.retirementTransfers(null, 10)))
        .hasSize(4);
    var incomplete = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    assertThat(transaction.<Boolean>execute(ignored -> repository.dispose(incomplete))).isFalse();
    // Even after losing that original and releasing its orphaned local reference, the stored
    // positive inventory count prevents absence from becoming completion.
    transaction.executeWithoutResult(
        ignored -> {
          dsl.execute(
              "ALTER TABLE entity_canonical_roster_original_selections DISABLE TRIGGER USER");
          dsl.execute(
              "DELETE FROM entity_canonical_roster_original_selections WHERE operation_uuid = ?",
              missing);
          dsl.execute(
              "ALTER TABLE entity_canonical_roster_original_selections ENABLE TRIGGER USER");
          repository.release(binding, UUID.randomUUID(), missing);
        });
    dsl.fetch("SELECT pg_sleep(0.02)");
    var missingOriginal = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    assertThat(transaction.<Boolean>execute(ignored -> repository.dispose(missingOriginal)))
        .isFalse();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        dsl.execute(
                            "UPDATE entity_canonical_roster_retention_lifecycle SET lifecycle_state = 'DISPOSING', local_epoch = local_epoch + 1 WHERE snapshot_uuid = ?",
                            snapshot.snapshotUuid())))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void
      pendingAndActualAcknowledgedTransfersSurvivePayloadDisposalUntilConfirmationAndEffectiveHorizon() {
    var local = localRequest();
    var recovery = recovery(local);
    var owner = recoveryOwner(recovery);
    var snapshot = retainedSnapshot();
    UUID nonce = bindSnapshot(local, owner, snapshot);
    transaction.executeWithoutResult(
        ignored -> repository.closeAdmission(recovery, owner, policy(Duration.ofMillis(1))));
    var proof = retirement(owner, snapshot);
    var candidate = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    transaction.executeWithoutResult(
        ignored -> repository.observeRetirement(candidate, proof, policy(Duration.ofMillis(1))));
    assertThat(preparePage(2)).isEqualTo(1);
    var committed =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    byte[] exactRequest = committed.acknowledgementRequest().toByteArray();
    dsl.fetch("SELECT pg_sleep(0.02)");
    var retired = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    assertThat(transaction.<Boolean>execute(ignored -> repository.dispose(retired))).isTrue();
    dsl.fetch("SELECT pg_sleep(0.02)");
    transaction.executeWithoutResult(ignored -> repository.purgeDisposedTombstones(1));
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_original_selections"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_admissions"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_gameplay_roster_snapshots"))).isZero();
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    var retried =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    assertThat(retried).isEqualTo(committed);
    assertThat(retried.acknowledgementRequest().toByteArray()).isEqualTo(exactRequest);
    assertThat(retried.acknowledgementRequest().getAdmissionUuid()).isEqualTo(nonce.toString());
    assertThat(transaction.<Integer>execute(ignored -> repository.purgeRetiredTargets(1))).isZero();
    assertThatThrownBy(() -> dsl.execute("DELETE FROM entity_canonical_roster_retired_targets"))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementTransfers(1)))
        .isZero();
    assertThatThrownBy(
            () -> dsl.execute("DELETE FROM entity_canonical_roster_retirement_transfers"))
        .isInstanceOf(DataAccessException.class);

    var acknowledgement =
        CanonicalGameplayRosterRetirementEvidence.response(
            retried.acknowledgementRequest(), UUID.randomUUID(), 2000);
    transaction.executeWithoutResult(
        ignored ->
            assertThat(repository.recordRetirementAcknowledgement(retried, acknowledgement))
                .isTrue());
    // A lost ACK reply reuses the exact persisted request and response; a changed ACK conflicts.
    transaction.executeWithoutResult(
        ignored ->
            assertThat(repository.recordRetirementAcknowledgement(retried, acknowledgement))
                .isFalse());
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.recordRetirementAcknowledgement(
                            retried,
                            CanonicalGameplayRosterRetirementEvidence.response(
                                retried.acknowledgementRequest(), UUID.randomUUID(), 2000))))
        .isInstanceOf(IllegalStateException.class);
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    var acknowledged =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    assertThat(acknowledged.state()).isEqualTo("ACKNOWLEDGED");
    assertThat(acknowledged.acknowledgement()).isEqualTo(acknowledgement);
    assertThatThrownBy(
            () -> dsl.execute("DELETE FROM entity_canonical_roster_retirement_transfers"))
        .isInstanceOf(DataAccessException.class);
    var confirmation =
        CanonicalGameplayRosterRetirementEvidence.confirmation(
            acknowledged.acknowledgementRequest(), acknowledgement);
    var confirmed = CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmation);
    String retainedTransfer =
        pageSourceRow(
            "entity_canonical_roster_retirement_transfers",
            "observation_uuid",
            acknowledged.observationUuid());
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.recordRetirementConfirmation(
                            acknowledged,
                            confirmed.toBuilder().setRequestDigest("0".repeat(64)).build())))
        .isInstanceOf(IllegalArgumentException.class);
    var changedRequest =
        acknowledged.acknowledgementRequest().toBuilder().setSnapshotDigest("0".repeat(64)).build();
    var changedCandidate =
        new CanonicalGameplayRosterRetentionRepository.RetirementTransfer(
            acknowledged.observationUuid(),
            acknowledged.state(),
            acknowledged.original(),
            changedRequest,
            acknowledged.acknowledgement());
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.recordRetirementConfirmation(changedCandidate, confirmed)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            pageSourceRow(
                "entity_canonical_roster_retirement_transfers",
                "observation_uuid",
                acknowledged.observationUuid()))
        .isEqualTo(retainedTransfer);
    transaction.executeWithoutResult(
        ignored ->
            assertThat(repository.recordRetirementConfirmation(acknowledged, confirmed)).isTrue());
    transaction.executeWithoutResult(
        ignored ->
            assertThat(repository.recordRetirementConfirmation(acknowledged, confirmed)).isFalse());
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementTransfers(1)))
        .isZero();
    assertThatThrownBy(
            () -> dsl.execute("DELETE FROM entity_canonical_roster_retirement_transfers"))
        .isInstanceOf(DataAccessException.class);
    dsl.fetch("SELECT pg_sleep(2.1)");
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementTransfers(1)))
        .isEqualTo(1);
    assertThat(transaction.<Integer>execute(ignored -> repository.purgeRetiredTargets(1)))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.requireAdmission(local, nonce)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
  }

  @Test
  void deniedUnboundNonceLeavesExactWaitingIntentUntilIndependentPositiveRetirement() {
    var local = localRequest();
    var recovery = recovery(local);
    var owner = recoveryOwner(recovery);
    var closed =
        transaction.execute(
            ignored -> repository.closeAdmission(recovery, owner, policy(Duration.ofMillis(1))));
    var closure =
        transaction.execute(ignored -> repository.closedAdmissionCandidates(null, 1)).getFirst();
    dsl.fetch("SELECT pg_sleep(0.02)");
    var denied = owner.toBuilder().setTerminalOutcome("DENIED").build();
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.purgeClosedAdmission(closure, denied)))
        .isTrue();
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_admissions"))).isZero();
    var row = dsl.fetchSingle("SELECT * FROM entity_canonical_roster_retirement_transfers");
    assertThat(row.get("admission_uuid", UUID.class).toString())
        .isEqualTo(closed.getAdmissionUuid());
    assertThat(row.get("closed_admission_receipt", byte[].class)).isEqualTo(closed.toByteArray());
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    var waiting =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    assertThat(waiting.state()).isEqualTo("WAITING_RETIREMENT");
    assertThat(waiting.acknowledgementRequest()).isNull();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.observeWaitingRetirement(waiting, denied)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.reserveAdmission(local)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> dsl.execute("DELETE FROM entity_canonical_roster_retirement_transfers"))
        .isInstanceOf(DataAccessException.class);
    var retired = retirement(owner, null);
    transaction.executeWithoutResult(
        ignored -> assertThat(repository.observeWaitingRetirement(waiting, retired)).isTrue());
    transaction.executeWithoutResult(
        ignored -> assertThat(repository.observeWaitingRetirement(waiting, retired)).isFalse());
    var pending =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    assertThat(pending.observationUuid()).isEqualTo(waiting.observationUuid());
    assertThat(pending.acknowledgementRequest().getOriginalSelection())
        .isEqualTo(recovery.getOriginalSelection());
    assertThat(pending.acknowledgementRequest().getAdmissionUuid())
        .isEqualTo(closed.getAdmissionUuid());
    assertThat(pending.acknowledgementRequest().getSnapshotUuid()).isEmpty();
    assertThat(pending.acknowledgementRequest().getObservedRetirement()).isEqualTo(retired);
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_retirement_transfers")))
        .isEqualTo(1);
  }

  @Test
  void committedObservationSerializesTheDelayedNonceAndDeniesCaptureAfterItsLockReleases()
      throws Exception {
    var local = localRequest();
    var owner = recoveryOwner(recovery(local));
    var snapshot = retainedSnapshot();
    UUID nonce = bindSnapshot(local, owner, snapshot);
    var proof = retirement(owner, snapshot);
    var candidate = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    var observationPid = new java.util.concurrent.atomic.AtomicInteger();
    var delayedPid = new java.util.concurrent.atomic.AtomicInteger();
    CountDownLatch observed = new CountDownLatch(1);
    CountDownLatch delayedStarted = new CountDownLatch(1);
    CountDownLatch allowCommit = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var observation =
          executor.submit(
              () ->
                  transaction.executeWithoutResult(
                      ignored -> {
                        observationPid.set(
                            dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                        assertThat(
                                repository.observeRetirement(
                                    candidate, proof, policy(Duration.ofHours(1))))
                            .isTrue();
                        observed.countDown();
                        await(allowCommit);
                      }));
      await(observed);
      var delayed =
          executor.submit(
              () ->
                  assertThatThrownBy(
                          () ->
                              transaction.executeWithoutResult(
                                  ignored -> {
                                    delayedPid.set(
                                        dsl.fetchSingle("SELECT pg_backend_pid()")
                                            .get(0, Integer.class));
                                    delayedStarted.countDown();
                                    repository.requireAdmission(local, nonce);
                                    repository.bindOriginal(owner, snapshot);
                                    repository.bindAdmission(
                                        local.requestUuid(), nonce, snapshot.snapshotUuid());
                                  }))
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED"));
      await(delayedStarted);
      try {
        awaitBackendBlockedBy(delayedPid.get(), observationPid.get());
      } finally {
        allowCommit.countDown();
      }
      observation.get(15, TimeUnit.SECONDS);
      delayed.get(15, TimeUnit.SECONDS);
    } finally {
      allowCommit.countDown();
    }
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_original_selections")))
        .isEqualTo(1);
    assertThat(preparePage(2)).isEqualTo(1);
    var transfer =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    assertThat(transfer.acknowledgementRequest().getAdmissionUuid()).isEqualTo(nonce.toString());
  }

  private void awaitBackendBlockedBy(int waiter, int holder) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    boolean blocked = false;
    while (System.nanoTime() < deadline) {
      blocked =
          Boolean.TRUE.equals(
              dsl.fetchSingle("SELECT ? = ANY(pg_blocking_pids(?))", holder, waiter)
                  .get(0, Boolean.class));
      if (blocked) break;
      TimeUnit.MILLISECONDS.sleep(10);
    }
    assertThat(blocked)
        .as("Delayed admission waits on the exact retirement observation transaction")
        .isTrue();
  }

  @Test
  void transferGuardUsesDatabaseClockInsteadOfCallerBackdatedLifecycleTimes() {
    var local = localRequest();
    var recovery = recovery(local);
    var owner = recoveryOwner(recovery);
    transaction.executeWithoutResult(
        ignored -> repository.closeAdmission(recovery, owner, policy(Duration.ofMillis(1))));
    UUID observation = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO entity_canonical_roster_retirement_transfers "
            + "(observation_uuid, operation_uuid, original_request, admission_uuid, closed_admission_receipt, owner_fence, target_fence_uuid, minimum_retention_millis, transfer_state, recorded_at) "
            + "SELECT ?, operation_uuid, recovery_owner_request, admission_uuid, recovery_response, recovery_owner_fence, recovery_target_fence_uuid, minimum_retention_millis, 'WAITING_RETIREMENT', clock_timestamp() - interval '1 day' "
            + "FROM entity_canonical_roster_admissions WHERE operation_uuid = ?",
        observation,
        local.requestUuid());
    assertThat(
            dsl.fetchSingle(
                    "SELECT recorded_at > clock_timestamp() - interval '1 minute' FROM entity_canonical_roster_retirement_transfers")
                .get(0, Boolean.class))
        .isTrue();
    var waiting =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    transaction.executeWithoutResult(
        ignored -> repository.observeWaitingRetirement(waiting, retirement(owner, null)));
    var pending =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    var acknowledgement =
        CanonicalGameplayRosterRetirementEvidence.response(
            pending.acknowledgementRequest(), UUID.randomUUID(), 2000);
    dsl.execute(
        "UPDATE entity_canonical_roster_retirement_transfers SET transfer_state = 'ACKNOWLEDGED', acknowledgement_response = ?, effective_minimum_retention_millis = ?, acknowledged_at = clock_timestamp() - interval '1 day' WHERE observation_uuid = ?",
        acknowledgement.toByteArray(),
        acknowledgement.getMinimumRetentionMillis(),
        observation);
    assertThat(
            dsl.fetchSingle(
                    "SELECT acknowledged_at > clock_timestamp() - interval '1 minute' FROM entity_canonical_roster_retirement_transfers")
                .get(0, Boolean.class))
        .isTrue();
    var confirmation =
        CanonicalGameplayRosterRetirementEvidence.confirmation(
            pending.acknowledgementRequest(), acknowledgement);
    dsl.execute(
        "UPDATE entity_canonical_roster_retirement_transfers SET transfer_state = 'CONFIRMED', confirmation_response = ?, confirmed_at = clock_timestamp() - interval '1 day' WHERE observation_uuid = ?",
        CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmation).toByteArray(),
        observation);
    assertThat(
            dsl.fetchSingle(
                    "SELECT confirmed_at > clock_timestamp() - interval '1 minute' FROM entity_canonical_roster_retirement_transfers")
                .get(0, Boolean.class))
        .isTrue();
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementTransfers(1)))
        .isZero();
    assertThatThrownBy(
            () -> dsl.execute("DELETE FROM entity_canonical_roster_retirement_transfers"))
        .isInstanceOf(DataAccessException.class);
  }

  private CanonicalGameplayRosterSnapshot retainedSnapshot() {
    UUID actor =
        dsl.fetchSingle(
                "SELECT character_uuid FROM entity_canonical_gameplay_roster_snapshot_actors WHERE snapshot_uuid = ?",
                binding.snapshot().snapshotUuid())
            .get("character_uuid", UUID.class);
    return new CanonicalGameplayRosterSnapshot(
        binding.canonicalAccountUuid(),
        binding.snapshot().snapshotUuid(),
        binding.snapshot().snapshotDigest(),
        binding.target(),
        List.of(new CanonicalGameplayRosterActor(actor, "Retained Actor")));
  }

  private CanonicalGameplayRosterSnapshot insertSiblingSnapshot(
      CanonicalGameplayRosterSnapshot source) {
    var sibling =
        new CanonicalGameplayRosterSnapshot(
            source.canonicalAccountUuid(),
            UUID.randomUUID(),
            UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""),
            source.target(),
            source.actors());
    var target = source.target();
    dsl.execute(
        "INSERT INTO entity_canonical_gameplay_roster_snapshots "
            + "(snapshot_uuid, snapshot_digest, canonical_account_uuid, tenant_uuid, realm_uuid, "
            + "world_slug, realm_slug, game_instance_uuid, catalog_revision, canonical_version_uuid, "
            + "pointer_version, active_world_epoch, published_policy_digest, published_release_bundle_ref, "
            + "admission_pointer_snapshot_digest, published_owner_proof_digest, "
            + "playable_state_namespace_uuid, playable_state_scope, entry_policy, roster_count) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        sibling.snapshotUuid(),
        sibling.snapshotDigest(),
        sibling.canonicalAccountUuid(),
        target.tenantUuid(),
        target.realmUuid(),
        target.worldSlug(),
        target.realmSlug(),
        target.gameInstanceUuid(),
        target.catalogRevision(),
        target.canonicalVersionUuid(),
        target.pointerVersion(),
        target.activeWorldEpoch(),
        target.publishedPolicyDigest(),
        target.publishedReleaseBundleRef(),
        target.admissionPointerSnapshotDigest(),
        target.publishedOwnerProofDigest(),
        target.playableStateNamespaceId(),
        target.playableStateScope().name(),
        target.entryPolicy().name(),
        sibling.actors().size());
    for (int index = 0; index < sibling.actors().size(); index++) {
      var actor = sibling.actors().get(index);
      dsl.execute(
          "INSERT INTO entity_canonical_gameplay_roster_snapshot_actors "
              + "(snapshot_uuid, ordinal_position, character_uuid, display_name, actor_kind) "
              + "VALUES (?, ?, ?, ?, ?)",
          sibling.snapshotUuid(),
          index,
          actor.characterUuid(),
          actor.displayName(),
          "PLAYER");
    }
    dsl.execute(
        "UPDATE entity_canonical_gameplay_roster_snapshots SET construction_state = 'SEALED' "
            + "WHERE snapshot_uuid = ?",
        sibling.snapshotUuid());
    return sibling;
  }

  private int preparePage(int limit) {
    var candidate = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    return transaction.execute(ignored -> repository.prepareRetirement(candidate, limit));
  }

  private UUID bindSnapshot(
      CanonicalGameplayRosterReadRequest local,
      GetCanonicalGameplayRosterReferenceResponse owner,
      CanonicalGameplayRosterSnapshot snapshot) {
    return transaction.execute(
        ignored -> {
          UUID nonce = repository.reserveAdmission(local);
          repository.requireAdmission(local, nonce);
          repository.bindOriginal(owner, snapshot);
          repository.bindAdmission(local.requestUuid(), nonce, snapshot.snapshotUuid());
          return nonce;
        });
  }

  private GetCanonicalGameplayRosterReferenceResponse retirement(
      GetCanonicalGameplayRosterReferenceResponse owner, CanonicalGameplayRosterSnapshot snapshot) {
    return retirement(owner, snapshot, UUID.randomUUID());
  }

  private GetCanonicalGameplayRosterReferenceResponse retirement(
      GetCanonicalGameplayRosterReferenceResponse owner,
      CanonicalGameplayRosterSnapshot snapshot,
      UUID retirementUuid) {
    var builder =
        owner.toBuilder()
            .setState(
                net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState
                    .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED)
            .setTerminalOutcome("DENIED")
            .setRetirementUuid(retirementUuid.toString())
            .setSupersedingPointerDigest("f".repeat(64))
            .setSupersedingPointerVersion(binding.target().pointerVersion() + 1)
            .setSupersedingCatalogRevision(binding.target().catalogRevision())
            .setSupersedingWorldEpoch(binding.target().activeWorldEpoch());
    if (snapshot != null) builder.setCanonicalRosterReceipt(snapshot.toWire().toByteString());
    return CanonicalGameplayRosterReferenceEvidence.sealRetirement(builder.build());
  }

  @Test
  void orphanCleanupRevokesOnlyExpiredUnboundOpenNonces() {
    var expired = localRequest();
    var fresh = localRequest();
    var bound = localRequest();
    UUID expiredNonce = insertAgedAdmission(expired, Duration.ofHours(2));
    UUID boundNonce = insertAgedAdmission(bound, Duration.ofHours(2));
    UUID freshNonce = transaction.execute(ignored -> repository.reserveAdmission(fresh));
    transaction.executeWithoutResult(
        ignored ->
            repository.bindAdmission(
                bound.requestUuid(), boundNonce, binding.snapshot().snapshotUuid()));
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_roster_admissions SET admission_state = 'EXPIRED', minimum_retention_millis = 3600000 WHERE operation_uuid = ?",
                    fresh.requestUuid()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_roster_admissions SET admission_state = 'EXPIRED', minimum_retention_millis = 3600000 WHERE operation_uuid = ?",
                    bound.requestUuid()))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeOrphanOpenAdmissions(policy(Duration.ofHours(1)), 1)))
        .isEqualTo(1);
    transaction.executeWithoutResult(
        ignored -> {
          repository.requireAdmission(fresh, freshNonce);
          repository.requireAdmission(bound, boundNonce);
        });
    UUID newNonce = transaction.execute(ignored -> repository.reserveAdmission(expired));
    assertThat(newNonce).isNotEqualTo(expiredNonce);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.bindAdmission(
                            expired.requestUuid(),
                            expiredNonce,
                            binding.snapshot().snapshotUuid())))
        .isInstanceOf(IllegalStateException.class);
    assertThat(retainedRows()).hasSize(4);
  }

  @Test
  void closeBeforeBindSerializesAndDeniesTheDelayedNonce() throws Exception {
    raceClosureAndBind(true);
  }

  @Test
  void bindBeforeCloseSerializesAndReturnsTheExactOriginalReceipt() throws Exception {
    raceClosureAndBind(false);
  }

  @Test
  void orphanCleanupBeforeBindRevokesTheDelayedNonce() throws Exception {
    raceOrphanCleanupAndBind(true);
  }

  @Test
  void bindingBeforeOrphanCleanupPreservesTheBoundNonce() throws Exception {
    raceOrphanCleanupAndBind(false);
  }

  private void raceOrphanCleanupAndBind(boolean cleanupFirst) throws Exception {
    var local = localRequest();
    UUID nonce = insertAgedAdmission(local, Duration.ofHours(2));
    CountDownLatch firstLocked = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    CountDownLatch allowCommit = new CountDownLatch(1);
    Runnable bind =
        () ->
            transaction.executeWithoutResult(
                ignored -> {
                  repository.requireAdmission(local, nonce);
                  repository.bindAdmission(
                      local.requestUuid(), nonce, binding.snapshot().snapshotUuid());
                  if (!cleanupFirst) {
                    firstLocked.countDown();
                    await(allowCommit);
                  }
                });
    Runnable cleanup =
        () ->
            transaction.executeWithoutResult(
                ignored -> {
                  assertThat(repository.purgeOrphanOpenAdmissions(policy(Duration.ofHours(1)), 1))
                      .isEqualTo(cleanupFirst ? 1 : 0);
                  if (cleanupFirst) {
                    firstLocked.countDown();
                    await(allowCommit);
                  }
                });
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(cleanupFirst ? cleanup : bind);
      await(firstLocked);
      var second =
          executor.submit(
              () -> {
                secondStarted.countDown();
                if (cleanupFirst) {
                  assertThatThrownBy(bind::run)
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
                } else {
                  cleanup.run();
                }
              });
      await(secondStarted);
      allowCommit.countDown();
      first.get(15, TimeUnit.SECONDS);
      second.get(15, TimeUnit.SECONDS);
    } finally {
      allowCommit.countDown();
    }
    if (cleanupFirst) {
      UUID freshNonce = transaction.execute(ignored -> repository.reserveAdmission(local));
      assertThat(freshNonce).isNotEqualTo(nonce);
    } else {
      transaction.executeWithoutResult(ignored -> repository.requireAdmission(local, nonce));
    }
    assertThat(retainedRows()).hasSize(4);
  }

  private void raceClosureAndBind(boolean closeFirst) throws Exception {
    var local = localRequest();
    var recovery = recovery(local);
    var owner = recoveryOwner(recovery);
    UUID nonce = transaction.execute(ignored -> repository.reserveAdmission(local));
    var actor =
        dsl.fetchSingle(
                "SELECT character_uuid FROM entity_canonical_gameplay_roster_snapshot_actors WHERE snapshot_uuid = ?",
                binding.snapshot().snapshotUuid())
            .get("character_uuid", UUID.class);
    var snapshot =
        new CanonicalGameplayRosterSnapshot(
            binding.canonicalAccountUuid(),
            binding.snapshot().snapshotUuid(),
            binding.snapshot().snapshotDigest(),
            binding.target(),
            List.of(new CanonicalGameplayRosterActor(actor, "Retained Actor")));
    var expected = snapshot.toWire();
    var response =
        new java.util.concurrent.atomic.AtomicReference<
            net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryResponse>();
    CountDownLatch firstLocked = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    CountDownLatch allowCommit = new CountDownLatch(1);
    Runnable bind =
        () ->
            transaction.executeWithoutResult(
                ignored -> {
                  repository.requireAdmission(local, nonce);
                  repository.bindOriginal(owner, snapshot);
                  repository.bindAdmission(local.requestUuid(), nonce, snapshot.snapshotUuid());
                  if (!closeFirst) {
                    firstLocked.countDown();
                    await(allowCommit);
                  }
                });
    Runnable close =
        () ->
            transaction.executeWithoutResult(
                ignored -> {
                  response.set(
                      repository.closeAdmission(recovery, owner, policy(Duration.ofHours(1))));
                  if (closeFirst) {
                    firstLocked.countDown();
                    await(allowCommit);
                  }
                });
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(closeFirst ? close : bind);
      await(firstLocked);
      var second =
          executor.submit(
              () -> {
                secondStarted.countDown();
                if (closeFirst) {
                  assertThatThrownBy(bind::run)
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
                } else {
                  close.run();
                }
              });
      await(secondStarted);
      allowCommit.countDown();
      first.get(15, TimeUnit.SECONDS);
      second.get(15, TimeUnit.SECONDS);
    } finally {
      allowCommit.countDown();
    }
    assertThat(response.get().getAdmissionUuid()).isEqualTo(nonce.toString());
    if (closeFirst) {
      assertThat(response.get().getCanonicalRosterReceipt()).isEmpty();
      assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_original_selections"))).isZero();
    } else {
      assertThat(response.get().getCanonicalRosterReceipt()).isEqualTo(expected.toByteString());
      transaction.executeWithoutResult(
          ignored -> {
            assertThat(repository.originalReceipt(owner)).contains(expected);
            assertThat(repository.protection(binding).liveReferences()).isEqualTo(1);
            assertThat(repository.protection(binding).disposalEligible()).isFalse();
          });
      assertThat(
              transaction
                  .<List<CanonicalGameplayRosterRetentionRepository.ClosedAdmissionCandidate>>
                      execute(ignored -> repository.closedAdmissionCandidates(null, 2)))
          .isEmpty();
    }
    assertThat(retainedRows()).hasSize(4);
  }

  private UUID insertAgedAdmission(CanonicalGameplayRosterReadRequest request, Duration age) {
    UUID nonce = UUID.randomUUID();
    byte[] bindingBytes =
        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRequest.newBuilder()
            .setRequestUuid(request.requestUuid().toString())
            .setCanonicalAccountUuid(request.canonicalAccountUuid().toString())
            .setExpectedTarget(request.expectedTarget().toWire())
            .setPlayerExecutionContext(
                net.firedevops.firemud.shared.v1.PlayerExecutionContext.newBuilder()
                    .setSessionId(request.playerExecutionContext().sessionUuid().toString()))
            .build()
            .toByteArray();
    dsl.execute(
        "INSERT INTO entity_canonical_roster_admissions (operation_uuid, admission_uuid, request_binding, recorded_at) VALUES (?, ?, ?, clock_timestamp() - ? * interval '1 millisecond')",
        request.requestUuid(),
        nonce,
        bindingBytes,
        age.toMillis());
    transaction.executeWithoutResult(ignored -> repository.classifyRetainedDependencies(2));
    return nonce;
  }

  private CanonicalGameplayRosterReadRequest localRequest() {
    return localRequest(binding.canonicalAccountUuid(), binding.target(), UUID.randomUUID());
  }

  private static CanonicalGameplayRosterReadRequest localRequest(
      UUID canonicalAccount, CanonicalGameplayRosterTarget target, UUID operation) {
    return new CanonicalGameplayRosterReadRequest(
        operation,
        canonicalAccount,
        target,
        new CanonicalGameplayRosterExecutionContext(
            canonicalAccount,
            target.tenantUuid(),
            target.playableStateNamespaceId(),
            target.gameInstanceUuid(),
            null,
            UUID.randomUUID(),
            target.realmUuid(),
            operation,
            target.playableStateScope()));
  }

  private static CanonicalGameplayRosterRecoveryRequest recovery(
      CanonicalGameplayRosterReadRequest local) {
    return CanonicalGameplayRosterRecoveryRequest.newBuilder()
        .setSchemaVersion(1)
        .setRecoveryUuid(UUID.randomUUID().toString())
        .setOriginalSelection(
            CanonicalGameplayRosterReferenceEvidence.request(
                local.requestUuid(),
                local.canonicalAccountUuid(),
                local.playerExecutionContext().sessionUuid(),
                "gameplay",
                local.expectedTarget().toWire()))
        .build();
  }

  private static GetCanonicalGameplayRosterReferenceResponse recoveryOwner(
      CanonicalGameplayRosterRecoveryRequest request) {
    return CanonicalGameplayRosterReferenceEvidence.response(
            request.getOriginalSelection(), 1, UUID.randomUUID(), null)
        .toBuilder()
        .setRecoveryUuid(request.getRecoveryUuid())
        .build();
  }

  private static GetCanonicalGameplayRosterReferenceResponse recoveryOwner(
      CanonicalGameplayRosterRecoveryRequest request, CanonicalGameplayRosterSnapshot snapshot) {
    return CanonicalGameplayRosterReferenceEvidence.response(
            request.getOriginalSelection(), 1, UUID.randomUUID(), snapshot.toWire())
        .toBuilder()
        .setRecoveryUuid(request.getRecoveryUuid())
        .build();
  }

  private String admissionRow(UUID operation) {
    return dsl.fetchSingle(
            "SELECT row_to_json(a)::text FROM entity_canonical_roster_admissions a WHERE operation_uuid = ?",
            operation)
        .get(0, String.class);
  }

  private int admissionCount(UUID operation) {
    return dsl.fetchSingle(
            "SELECT count(*) FROM entity_canonical_roster_admissions WHERE operation_uuid = ?",
            operation)
        .get(0, Integer.class);
  }

  private static CanonicalGameplayRosterRetentionPolicy policy(Duration duration) {
    return new CanonicalGameplayRosterRetentionPolicy(
        duration, duration, duration, Duration.ofMinutes(1), 2);
  }

  private void seedAssignedActorAndSnapshot() {
    var target = binding.target();
    UUID assignment = UUID.randomUUID();
    var request = assignmentRequest(assignment);
    var evidence =
        new PreseededActorAssignmentOwnerEvidence(
            assignment,
            binding.canonicalAccountUuid(),
            PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE,
            Instant.now(),
            4,
            "1".repeat(64),
            target.tenantUuid(),
            target.realmUuid(),
            target.worldSlug(),
            target.realmSlug(),
            target.gameInstanceUuid().toString(),
            target.catalogRevision(),
            target.canonicalVersionUuid(),
            target.publishedPolicyDigest(),
            target.playableStateNamespaceId(),
            target.playableStateScope(),
            PreseededActorAssignmentOwnerEvidence.PublishedEntryPolicy.PRESEEDED_ONLY,
            PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING,
            PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION,
            "2".repeat(64),
            target.publishedOwnerProofDigest(),
            target.publishedReleaseBundleRef());
    UUID actor =
        new CharacterRepository(dsl)
            .assignPreseededActor(
                request,
                evidence,
                new RuntimeAccountIdentityEvidence(
                    1,
                    "dev",
                    assignment,
                    binding.canonicalAccountUuid(),
                    51L,
                    "ACCOUNT_DATABASE_INSERT"),
                request.mutationIntentDigest())
            .characterUuid();
    seedSnapshot(actor);
  }

  /**
   * Seeds a controlled pre-V1022 retained owner record at its historical schema frontier. This
   * fixture deliberately has no runtime projection; only fresh assignments at the current schema
   * use the production assignment path above.
   */
  private void seedHistoricalV1011AssignmentActorAndSnapshotWithoutProjection() {
    var target = binding.target();
    UUID assignment = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    var request = assignmentRequest(assignment);

    dsl.execute(
        "INSERT INTO entity_playable_state_namespace_scopes "
            + "(tenant_uuid, playable_state_namespace_id, playable_state_scope) "
            + "VALUES (?, ?, ?) ON CONFLICT (tenant_uuid, playable_state_namespace_id) DO NOTHING",
        target.tenantUuid(),
        target.playableStateNamespaceId(),
        target.playableStateScope().name());
    dsl.execute(
        "INSERT INTO entity_tenant_identities (canonical_tenant_uuid) VALUES (?) "
            + "ON CONFLICT (canonical_tenant_uuid) DO NOTHING",
        target.tenantUuid());
    long entityTenantRowId =
        dsl.fetchSingle(
                "SELECT entity_tenant_row_id FROM entity_tenant_identities "
                    + "WHERE canonical_tenant_uuid = ?",
                target.tenantUuid())
            .get("entity_tenant_row_id", Long.class);

    dsl.execute(
        "INSERT INTO entity_preseeded_actor_assignment_operations "
            + "(assignment_uuid, intent_digest, account_uuid, account_uuid_provenance, "
            + "account_identity_schema_version, account_identity_target_namespace, "
            + "source_account_row_id, eligibility, eligibility_evaluated_at, "
            + "membership_authority_generation, eligibility_evidence_digest, "
            + "account_authority_snapshot_digest, account_purpose, account_currentness, "
            + "published_owner_proof_digest, published_release_bundle_ref, "
            + "tenant_uuid, realm_uuid, world_slug, realm_slug, game_instance_id, "
            + "catalog_revision, published_version_id, published_version_number, "
            + "canonical_version_uuid, publication_evidence_format, "
            + "frozen_policy_digest, playable_state_namespace_id, playable_state_scope, "
            + "entry_policy, actor_kind, display_name, status) "
            + "VALUES (?, ?, ?, 'ACCOUNT_DATABASE_INSERT', 1, 'dev', 51, 'ELIGIBLE', "
            + "clock_timestamp(), 4, ?, ?, 'PRESEEDED_ACTOR_STAGING', "
            + "'CURRENT_AT_REVALIDATION', ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, "
            + "'CANONICAL_UUID', ?, ?, ?, ?, ?, 'PENDING')",
        assignment,
        request.mutationIntentDigest(),
        binding.canonicalAccountUuid(),
        "1".repeat(64),
        "2".repeat(64),
        target.publishedOwnerProofDigest(),
        target.publishedReleaseBundleRef(),
        target.tenantUuid(),
        target.realmUuid(),
        target.worldSlug(),
        target.realmSlug(),
        target.gameInstanceUuid().toString(),
        target.catalogRevision(),
        target.canonicalVersionUuid(),
        target.publishedPolicyDigest(),
        target.playableStateNamespaceId(),
        target.playableStateScope().name(),
        target.entryPolicy().name(),
        request.corePayload().actorKind().name(),
        request.corePayload().displayName());
    dsl.execute(
        "INSERT INTO characters "
            + "(account_id, name, tenant_id, playable_state_key, character_uuid, account_uuid, "
            + "tenant_uuid, playable_state_namespace_id, playable_state_scope, "
            + "actor_identity_status, actor_identity_quarantine_reason) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'OWNER_RESOLVED', NULL)",
        51L,
        request.corePayload().displayName(),
        entityTenantRowId,
        target.playableStateNamespaceId().toString(),
        actor,
        binding.canonicalAccountUuid(),
        target.tenantUuid(),
        target.playableStateNamespaceId(),
        target.playableStateScope().name());
    assertThat(
            dsl.execute(
                "UPDATE entity_preseeded_actor_assignment_operations "
                    + "SET status = 'ASSIGNED', character_uuid = ?, "
                    + "completed_at = clock_timestamp() WHERE assignment_uuid = ? "
                    + "AND status = 'PENDING'",
                actor,
                assignment))
        .isEqualTo(1);
    seedSnapshot(actor);
  }

  private PreseededActorAssignmentRequest assignmentRequest(UUID assignment) {
    var target = binding.target();
    return new PreseededActorAssignmentRequest(
        assignment,
        binding.canonicalAccountUuid(),
        new PreseededActorCorePayload(
            PreseededActorCorePayload.ActorKind.PLAYER, "Retained Actor"),
        new PreseededActorAssignmentExpectedTarget(
            target.tenantUuid(),
            target.realmUuid(),
            target.worldSlug(),
            target.realmSlug(),
            target.gameInstanceUuid().toString(),
            target.catalogRevision(),
            target.canonicalVersionUuid(),
            target.publishedPolicyDigest(),
            target.playableStateNamespaceId(),
            target.publishedReleaseBundleRef(),
            target.playableStateScope()));
  }

  private void seedSnapshot(UUID actor) {
    var target = binding.target();
    dsl.execute(
        "INSERT INTO entity_canonical_gameplay_roster_snapshots "
            + "(snapshot_uuid, snapshot_digest, canonical_account_uuid, tenant_uuid, realm_uuid, "
            + "world_slug, realm_slug, game_instance_uuid, catalog_revision, canonical_version_uuid, "
            + "pointer_version, active_world_epoch, published_policy_digest, published_release_bundle_ref, "
            + "admission_pointer_snapshot_digest, published_owner_proof_digest, "
            + "playable_state_namespace_uuid, playable_state_scope, entry_policy, roster_count) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
        binding.snapshot().snapshotUuid(),
        binding.snapshot().snapshotDigest(),
        binding.canonicalAccountUuid(),
        target.tenantUuid(),
        target.realmUuid(),
        target.worldSlug(),
        target.realmSlug(),
        target.gameInstanceUuid(),
        target.catalogRevision(),
        target.canonicalVersionUuid(),
        target.pointerVersion(),
        target.activeWorldEpoch(),
        target.publishedPolicyDigest(),
        target.publishedReleaseBundleRef(),
        target.admissionPointerSnapshotDigest(),
        target.publishedOwnerProofDigest(),
        target.playableStateNamespaceId(),
        target.playableStateScope().name(),
        target.entryPolicy().name());
    dsl.execute(
        "INSERT INTO entity_canonical_gameplay_roster_snapshot_actors "
            + "(snapshot_uuid, ordinal_position, character_uuid, display_name, actor_kind) "
            + "VALUES (?, 0, ?, 'Retained Actor', 'PLAYER')",
        binding.snapshot().snapshotUuid(),
        actor);
    dsl.execute(
        "UPDATE entity_canonical_gameplay_roster_snapshots SET construction_state = 'SEALED' "
            + "WHERE snapshot_uuid = ?",
        binding.snapshot().snapshotUuid());
  }

  private static String predecessorRowProjection(String table) {
    return switch (table) {
      case "characters" ->
          "to_jsonb(t) - ARRAY['creation_target_namespace', 'creation_operation_id', 'actor_entry_policy', 'authored_initial_core_json', 'creation_descriptor_json', 'creation_descriptor_digest']::TEXT[]";
      case "entity_canonical_gameplay_roster_snapshots" ->
          "to_jsonb(t) - 'canonical_player_created_source_identity'";
      default -> "to_jsonb(t)";
    };
  }

  private void assertInheritedNullableFieldsRemainNull() {
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM characters WHERE creation_target_namespace IS NOT NULL OR creation_operation_id IS NOT NULL "
                        + "OR actor_entry_policy IS NOT NULL OR authored_initial_core_json IS NOT NULL "
                        + "OR creation_descriptor_json IS NOT NULL OR creation_descriptor_digest IS NOT NULL")
                .get(0, Long.class))
        .isZero();
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM entity_canonical_gameplay_roster_snapshots WHERE canonical_player_created_source_identity IS NOT NULL")
                .get(0, Long.class))
        .isZero();
  }

  private List<String> retainedRows() {
    return List.of(
            "characters", "entity_preseeded_actor_assignment_operations",
            "entity_canonical_gameplay_roster_snapshots",
                "entity_canonical_gameplay_roster_snapshot_actors")
        .stream()
        .flatMap(
            table ->
                dsl
                    .fetch(
                        "SELECT ("
                            + predecessorRowProjection(table)
                            + ")::TEXT || ':' || t.xmin::TEXT FROM "
                            + table
                            + " t ORDER BY ("
                            + predecessorRowProjection(table)
                            + ")::TEXT")
                    .getValues(0, String.class)
                    .stream())
        .toList();
  }

  private static CanonicalGameplayRosterTarget target() {
    return new CanonicalGameplayRosterTarget(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "world",
        "realm",
        UUID.randomUUID(),
        7,
        13,
        19,
        UUID.randomUUID(),
        "3".repeat(64),
        "release/retention-test",
        "4".repeat(64),
        "5".repeat(64),
        UUID.randomUUID(),
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY);
  }

  private List<CanonicalGameplayRosterReadRequest> legacyRequests;
  private Map<UUID, UUID> legacyNonces;
  private GetCanonicalGameplayRosterReferenceResponse legacyProof;

  private List<String> rebuildLegacyCohort(
      boolean retired, int intents, boolean disposed, boolean purgeTombstones) {
    return rebuildLegacyCohort(retired, intents, disposed, purgeTombstones, false);
  }

  private List<String> rebuildLegacyCohort(
      boolean retired,
      int intents,
      boolean disposed,
      boolean purgeTombstones,
      boolean inconsistentRestore) {
    dropOwnedFixture();
    schema = "roster_retained_" + UUID.randomUUID().toString().replace("-", "");
    var source = dataSource(schema);
    migrate(source, "1011");
    dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.executeWithoutResult(
        ignored -> seedHistoricalV1011AssignmentActorAndSnapshotWithoutProjection());
    migrate(source, "1017");
    legacyRequests = new java.util.ArrayList<>();
    legacyNonces = new java.util.HashMap<>();
    var owners = new java.util.ArrayList<GetCanonicalGameplayRosterReferenceResponse>();
    UUID fence = UUID.randomUUID();
    var snapshot = retainedSnapshot();
    transaction.executeWithoutResult(
        ignored -> {
          dsl.execute(
              "INSERT INTO entity_canonical_roster_retention_lifecycle (snapshot_uuid, snapshot_digest) VALUES (?, ?)",
              snapshot.snapshotUuid(),
              snapshot.snapshotDigest());
          for (int index = 0; index < (disposed ? 1 : 2); index++) {
            var local = localRequest();
            UUID nonce = UUID.randomUUID();
            var owner =
                CanonicalGameplayRosterReferenceEvidence.response(
                    recovery(local).getOriginalSelection(), 1, fence, null);
            legacyRequests.add(local);
            legacyNonces.put(local.requestUuid(), nonce);
            owners.add(owner);
            dsl.execute(
                "INSERT INTO entity_canonical_roster_admissions (operation_uuid, admission_uuid, request_binding, snapshot_uuid) VALUES (?, ?, ?, ?)",
                local.requestUuid(),
                nonce,
                legacyAdmissionBinding(local),
                snapshot.snapshotUuid());
            dsl.execute(
                "INSERT INTO entity_canonical_roster_retention_references (reference_uuid, acquire_operation_uuid, snapshot_uuid, snapshot_digest, reference_kind) VALUES (?, ?, ?, ?, 'ORIGINAL_SELECTION')",
                local.requestUuid(),
                local.requestUuid(),
                snapshot.snapshotUuid(),
                snapshot.snapshotDigest());
            dsl.execute(
                "INSERT INTO entity_canonical_roster_original_selections (operation_uuid, owner_request, owner_fence, owner_target_fence_uuid, snapshot_uuid, snapshot_digest, canonical_roster_receipt) VALUES (?, ?, ?, ?, ?, ?, ?)",
                local.requestUuid(),
                owner.getRequest().toByteArray(),
                1,
                fence,
                snapshot.snapshotUuid(),
                snapshot.snapshotDigest(),
                snapshot.toWire().toByteArray());
          }
          UUID anchor =
              dsl.fetchSingle(
                      "SELECT operation_uuid FROM entity_canonical_roster_original_selections ORDER BY operation_uuid LIMIT 1")
                  .get(0, UUID.class);
          var owner =
              owners.stream()
                  .filter(value -> value.getRequest().getOperationUuid().equals(anchor.toString()))
                  .findFirst()
                  .orElseThrow();
          legacyProof = retirement(owner, snapshot);
          if (retired) {
            for (var local : legacyRequests) {
              UUID release =
                  UUID.nameUUIDFromBytes(
                      (legacyProof.getRetirementUuid() + ":" + local.requestUuid())
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
              dsl.execute(
                  "INSERT INTO entity_canonical_roster_retention_releases (release_operation_uuid, reference_uuid, snapshot_uuid, snapshot_digest) VALUES (?, ?, ?, ?)",
                  release,
                  local.requestUuid(),
                  snapshot.snapshotUuid(),
                  snapshot.snapshotDigest());
            }
            dsl.execute(
                "UPDATE entity_canonical_roster_retention_lifecycle SET lifecycle_state = 'RETIRED', local_epoch = local_epoch + 1, owner_target_fence_uuid = ?, owner_retirement_uuid = ?, owner_retirement_proof_digest = ?, owner_retirement_proof = ?, retirement_observed_at = clock_timestamp(), minimum_retention_millis = 1 WHERE snapshot_uuid = ?",
                fence,
                UUID.fromString(legacyProof.getRetirementUuid()),
                legacyProof.getRetirementProofDigest(),
                legacyProof.toByteArray(),
                snapshot.snapshotUuid());
            for (int index = 0; index < intents; index++) {
              var local = legacyRequests.get(index);
              var original = owners.get(index).getRequest();
              UUID observation = UUID.randomUUID();
              var request =
                  net.firedevops.firemud.gamesession.v1
                      .CanonicalGameplayRosterRetirementAcknowledgementRequest.newBuilder()
                      .setSchemaVersion(1)
                      .setOriginalSelection(original)
                      .setObservedRetirement(legacyProof)
                      .setObservationUuid(observation.toString())
                      .setAdmissionUuid(legacyNonces.get(local.requestUuid()).toString())
                      .setSnapshotUuid(snapshot.snapshotUuid().toString())
                      .setSnapshotDigest(snapshot.snapshotDigest())
                      .setMinimumRetentionMillis(1)
                      .build();
              CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(request);
              dsl.execute(
                  "INSERT INTO entity_canonical_roster_retirement_transfers (observation_uuid, operation_uuid, original_request, admission_uuid, snapshot_uuid, snapshot_digest, owner_fence, target_fence_uuid, minimum_retention_millis, transfer_state, observed_retirement, acknowledgement_request, request_digest) VALUES (?, ?, ?, ?, ?, ?, 1, ?, 1, 'PENDING_ACK', ?, ?, ?)",
                  observation,
                  local.requestUuid(),
                  original.toByteArray(),
                  legacyNonces.get(local.requestUuid()),
                  snapshot.snapshotUuid(),
                  snapshot.snapshotDigest(),
                  fence,
                  legacyProof.toByteArray(),
                  request.toByteArray(),
                  CanonicalGameplayRosterRetirementEvidence.requestDigest(request));
            }
          }
        });
    if (disposed) {
      dsl.fetch("SELECT pg_sleep(0.02)");
      transaction.executeWithoutResult(
          ignored -> {
            long epoch =
                dsl.fetchSingle(
                        "UPDATE entity_canonical_roster_retention_lifecycle SET lifecycle_state = 'DISPOSING', local_epoch = local_epoch + 1 WHERE snapshot_uuid = ? RETURNING local_epoch",
                        snapshot.snapshotUuid())
                    .get(0, Long.class);
            dsl.execute(
                "INSERT INTO entity_canonical_roster_disposal_receipts (snapshot_uuid, snapshot_digest, owner_target_fence_uuid, owner_retirement_uuid, owner_retirement_proof_digest, local_epoch, minimum_retention_millis) VALUES (?, ?, ?, ?, ?, ?, 1)",
                snapshot.snapshotUuid(),
                snapshot.snapshotDigest(),
                fence,
                UUID.fromString(legacyProof.getRetirementUuid()),
                legacyProof.getRetirementProofDigest(),
                epoch);
            dsl.execute(
                "UPDATE entity_canonical_roster_admissions SET admission_state = 'DISPOSED', disposed_at = clock_timestamp(), minimum_retention_millis = 1 WHERE snapshot_uuid = ?",
                snapshot.snapshotUuid());
            dsl.execute(
                "DELETE FROM entity_canonical_gameplay_roster_snapshot_actors WHERE snapshot_uuid = ?",
                snapshot.snapshotUuid());
            dsl.execute(
                "DELETE FROM entity_canonical_roster_original_selections WHERE snapshot_uuid = ?",
                snapshot.snapshotUuid());
            dsl.execute(
                "DELETE FROM entity_canonical_roster_retention_references WHERE snapshot_uuid = ?",
                snapshot.snapshotUuid());
            dsl.execute(
                "DELETE FROM entity_canonical_roster_retention_releases WHERE snapshot_uuid = ?",
                snapshot.snapshotUuid());
            dsl.execute(
                "DELETE FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?",
                snapshot.snapshotUuid());
            dsl.execute(
                "DELETE FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ?",
                snapshot.snapshotUuid());
          });
      if (purgeTombstones) {
        dsl.fetch("SELECT pg_sleep(0.02)");
        dsl.execute("DELETE FROM entity_canonical_roster_admissions");
        dsl.execute("DELETE FROM entity_canonical_roster_disposal_receipts");
      }
    }
    if (inconsistentRestore) {
      // Explicit inconsistent-restore fixture only: production migration never disables guards.
      transaction.executeWithoutResult(
          ignored -> {
            dsl.execute(
                "ALTER TABLE entity_canonical_roster_original_selections DISABLE TRIGGER USER");
            dsl.execute(
                "DELETE FROM entity_canonical_roster_original_selections WHERE operation_uuid = ?",
                UUID.fromString(legacyProof.getRequest().getOperationUuid()));
            dsl.execute(
                "ALTER TABLE entity_canonical_roster_original_selections ENABLE TRIGGER USER");
          });
    }
    var rows = immutableRetentionRows();
    var authority = retainedLifecycleAuthority();
    migrate(source, "latest");
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    assertThat(immutableRetentionRows()).containsExactlyElementsOf(rows);
    assertThat(retainedLifecycleAuthority()).containsExactlyElementsOf(authority);
    assertInheritedNullableFieldsRemainNull();
    return rows;
  }

  private static byte[] legacyAdmissionBinding(CanonicalGameplayRosterReadRequest request) {
    return net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRequest.newBuilder()
        .setRequestUuid(request.requestUuid().toString())
        .setCanonicalAccountUuid(request.canonicalAccountUuid().toString())
        .setExpectedTarget(request.expectedTarget().toWire())
        .setPlayerExecutionContext(
            net.firedevops.firemud.shared.v1.PlayerExecutionContext.newBuilder()
                .setSessionId(request.playerExecutionContext().sessionUuid().toString()))
        .build()
        .toByteArray();
  }

  private List<String> retainedLifecycleAuthority() {
    return dsl.fetch(
            "SELECT (to_jsonb(l) - ARRAY['local_epoch', 'original_selection_count', 'retirement_original_count', 'retirement_prepared_count', 'retirement_preparation_cursor']::text[])::text FROM entity_canonical_roster_retention_lifecycle l ORDER BY snapshot_uuid")
        .getValues(0, String.class);
  }

  private List<String> immutableRetentionRows() {
    return dsl.fetch(
            "SELECT 'a:' || row_to_json(a)::text || ':' || a.xmin::text AS retained FROM entity_canonical_roster_admissions a "
                + "UNION ALL SELECT 'o:' || row_to_json(o)::text || ':' || o.xmin::text FROM entity_canonical_roster_original_selections o "
                + "UNION ALL SELECT 't:' || row_to_json(t)::text || ':' || t.xmin::text FROM entity_canonical_roster_retirement_transfers t ORDER BY retained")
        .getValues(0, String.class);
  }

  private void classifyAllRetainedSources() {
    while (transaction.<Integer>execute(ignored -> repository.classifyRetainedDependencies(2))
        != 0) {
      // The fixture has a finite inventory; UNKNOWN rows are classified once and never starve it.
    }
  }

  private void migrate(DriverManagerDataSource dataSource, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(
                "filesystem:"
                    + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize(),
                "classpath:db/migration/saga")
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .target(target);
    configuration
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    configuration.load().migrate();
  }

  private DriverManagerDataSource dataSource(String selectedSchema) {
    var dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    String baseUrl = postgres.getJdbcUrl();
    dataSource.setUrl(
        selectedSchema == null
            ? baseUrl
            : baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + selectedSchema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("controlled concurrent fixture timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private void insertRawTransferForBudget(
      UUID operation, GetCanonicalGameplayRosterReferenceResponse proof, byte[] request) {
    dsl.execute(
        "INSERT INTO entity_canonical_roster_retirement_transfers "
            + "(observation_uuid, operation_uuid, original_request, admission_uuid, closed_admission_receipt, owner_fence, target_fence_uuid, minimum_retention_millis, transfer_state, observed_retirement, acknowledgement_request, request_digest, entry_policy) "
            + "SELECT ?, operation_uuid, recovery_owner_request, admission_uuid, recovery_response, recovery_owner_fence, recovery_target_fence_uuid, minimum_retention_millis, 'PENDING_ACK', ?, ?, ?, entry_policy FROM entity_canonical_roster_admissions WHERE operation_uuid = ?",
        UUID.randomUUID(),
        proof.toByteArray(),
        request,
        "a".repeat(64),
        operation);
  }

  private List<String> migrationHistory() {
    return dsl.fetch(
            "SELECT to_jsonb(t)::TEXT || ':' || xmin::TEXT FROM flyway_schema_history t ORDER BY installed_rank")
        .getValues(0, String.class);
  }

  private List<String> retainedBeforeCreatedExtension() {
    return List.of(
            "characters",
            "entity_preseeded_actor_assignment_operations",
            "entity_canonical_gameplay_roster_snapshots",
            "entity_canonical_gameplay_roster_snapshot_actors",
            "entity_canonical_roster_retention_lifecycle")
        .stream()
        .flatMap(
            table -> {
              String projection =
                  table.equals("entity_canonical_gameplay_roster_snapshots")
                      ? "to_jsonb(t) - 'canonical_player_created_source_identity'"
                      : table.equals("entity_canonical_roster_retention_lifecycle")
                          ? "to_jsonb(t) - 'entry_policy'"
                          : "to_jsonb(t)";
              return dsl
                  .fetch(
                      "SELECT ("
                          + projection
                          + ")::TEXT || ':' || xmin::TEXT FROM "
                          + table
                          + " t ORDER BY ("
                          + projection
                          + ")::TEXT")
                  .getValues(0, String.class)
                  .stream();
            })
        .toList();
  }

  private CanonicalPlayerCreatedRosterRequest createdRequest() {
    var target =
        binding.target().toWire().toBuilder()
            .setEntryPolicy(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy
                    .CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED)
            .build();
    var source =
        CanonicalPlayerCreatedRosterSourceIdentity.newBuilder()
            .setSchemaVersion(1)
            .setDescriptorUuid(UUID.randomUUID().toString())
            .setDescriptorRevisionUuid(UUID.randomUUID().toString())
            .setDescriptorDigest("sha256:" + "a".repeat(64))
            .setAuthoredBindingUuid(UUID.randomUUID().toString())
            .setAuthoredBindingDigest("sha256:" + "b".repeat(64))
            .build();
    UUID operation = UUID.randomUUID();
    return CanonicalPlayerCreatedRosterEvidence.requireRequest(
        CanonicalPlayerCreatedRosterRequest.newBuilder()
            .setRequestUuid(operation.toString())
            .setCanonicalAccountUuid(binding.canonicalAccountUuid().toString())
            .setExpectedTarget(target)
            .setExpectedSource(source)
            .setPlayerExecutionContext(
                net.firedevops.firemud.shared.v1.PlayerExecutionContext.newBuilder()
                    .setRequestId(operation.toString())
                    .setAccountId(binding.canonicalAccountUuid().toString())
                    .setSessionId(UUID.randomUUID().toString())
                    .setTenantId(target.getTenantUuid())
                    .setRealmId(target.getRealmUuid())
                    .setPlayableStateNamespaceId(target.getPlayableStateNamespaceUuid())
                    .setGameInstanceId(target.getGameInstanceUuid())
                    .setPlayableStateScope("SHARED"))
            .build());
  }

  private static net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest
      createdOriginal(CanonicalPlayerCreatedRosterRequest request) {
    return CanonicalGameplayRosterReferenceEvidence.playerCreatedRequest(
        UUID.fromString(request.getRequestUuid()),
        UUID.fromString(request.getCanonicalAccountUuid()),
        UUID.fromString(request.getPlayerExecutionContext().getSessionId()),
        "gameplay",
        request.getExpectedTarget(),
        request.getExpectedSource());
  }

  private static CanonicalPlayerCreatedRosterResponse createdReceipt(
      CanonicalPlayerCreatedRosterRequest request) {
    return CanonicalPlayerCreatedRosterEvidence.requireResponse(
        request,
        CanonicalPlayerCreatedRosterResponse.newBuilder()
            .setSourceIdentity(request.getExpectedSource())
            .setRoster(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse
                    .newBuilder()
                    .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
                    .setTarget(request.getExpectedTarget())
                    .setSnapshotUuid(UUID.randomUUID().toString())
                    .setSnapshotDigest(
                        CanonicalPlayerCreatedRosterEvidence.computeSnapshotDigest(
                            request.getCanonicalAccountUuid(),
                            request.getExpectedTarget(),
                            request.getExpectedSource(),
                            List.of())))
            .build());
  }

  private void seedCreatedSnapshot(CanonicalPlayerCreatedRosterResponse receipt, int count) {
    var roster = receipt.getRoster();
    var target = roster.getTarget();
    dsl.execute(
        "INSERT INTO entity_canonical_gameplay_roster_snapshots "
            + "(snapshot_uuid, snapshot_digest, canonical_account_uuid, tenant_uuid, realm_uuid, world_slug, realm_slug, game_instance_uuid, catalog_revision, canonical_version_uuid, pointer_version, active_world_epoch, published_policy_digest, published_release_bundle_ref, admission_pointer_snapshot_digest, published_owner_proof_digest, playable_state_namespace_uuid, playable_state_scope, entry_policy, canonical_player_created_source_identity, roster_count) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PLAYER_CREATED', ?, ?)",
        UUID.fromString(roster.getSnapshotUuid()),
        roster.getSnapshotDigest(),
        UUID.fromString(roster.getCanonicalAccountUuid()),
        UUID.fromString(target.getTenantUuid()),
        UUID.fromString(target.getRealmUuid()),
        target.getWorldSlug(),
        target.getRealmSlug(),
        UUID.fromString(target.getGameInstanceUuid()),
        target.getCatalogRevision(),
        UUID.fromString(target.getCanonicalVersionUuid()),
        target.getPointerVersion(),
        target.getActiveWorldEpoch(),
        target.getPublishedPolicyDigest(),
        target.getPublishedReleaseBundleRef(),
        target.getAdmissionPointerSnapshotDigest(),
        target.getPublishedOwnerProofDigest(),
        UUID.fromString(target.getPlayableStateNamespaceUuid()),
        target.getPlayableStateScope().name(),
        receipt.hasSourceIdentity() ? receipt.getSourceIdentity().toByteArray() : null,
        count);
    if (count == 0)
      dsl.execute(
          "UPDATE entity_canonical_gameplay_roster_snapshots SET construction_state = 'SEALED' WHERE snapshot_uuid = ?",
          UUID.fromString(roster.getSnapshotUuid()));
  }
}
