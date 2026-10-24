package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRecoveryEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence;
import net.firedevops.firemud.common.world.CanonicalPlayerCreatedRosterEvidence;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterRecoveryClient;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterRecoveryClient.VerifiedRecovery;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementRequest;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementResponse;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementConfirmationResponse;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import net.firedevops.firemud.test.TestContainerImages;
import org.awaitility.Awaitility;
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
 * Actual owner SQL and migration proof; synthetic target bytes do not prove deployed publication.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayRosterReferencePostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private String schema;
  private DriverManagerDataSource dataSource;
  private DSLContext dsl;
  private TransactionTemplate transaction;
  private CanonicalGameplayRosterReferenceRepository repository;

  @BeforeEach
  void upgradeActualCanonicalHistory() {
    schema = "gs_roster_" + UUID.randomUUID().toString().replace("-", "");
    dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dataSource.setSchema(schema);
    migrate("1013");
    dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var before = history();
    migrate("1015");
    assertThat(history().subList(0, before.size())).containsExactlyElementsOf(before);
    assertThat(history()).hasSize(before.size() + 1);
    assertThat(history().get(history().size() - 1))
        .contains("1015", "V1015__canonical_roster_reference_owner.sql");
    var beforeObservation = history();
    migrate("1017");
    assertThat(history().subList(0, beforeObservation.size()))
        .containsExactlyElementsOf(beforeObservation);
    assertThat(history().get(history().size() - 1))
        .contains("1017", "V1017__canonical_roster_retirement_observation.sql");
    var beforeCreated = history();
    migrate("1020");
    assertThat(history().subList(0, beforeCreated.size())).containsExactlyElementsOf(beforeCreated);
    assertThat(history()).hasSize(beforeCreated.size() + 1);
    assertThat(history().getLast()).contains("1020", "V1020__player_created_roster_reference.sql");
    var beforeScheduling = history();
    migrate("1021");
    assertThat(history().subList(0, beforeScheduling.size()))
        .containsExactlyElementsOf(beforeScheduling);
    assertThat(history().getLast())
        .contains("1021", "V1021__canonical_roster_recovery_attempt_scheduling.sql");
    var beforeLatest = history();
    migrate("latest");
    assertThat(history().subList(0, beforeLatest.size())).containsExactlyElementsOf(beforeLatest);
    assertThat(history()).hasSize(beforeLatest.size() + 1);
    assertThat(history().get(history().size() - 1))
        .contains("1022", "V1022__canonical_roster_binary_safe_recovery_guard.sql");
    transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    repository =
        new CanonicalGameplayRosterReferenceRepository(
            dsl,
            new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferencePolicy(
                java.time.Duration.ofMillis(1),
                java.time.Duration.ofMillis(1),
                java.time.Duration.ofMillis(1),
                java.time.Duration.ofSeconds(1),
                8));
  }

  @Test
  void recoverySchedulingMigrationPreservesRetainedRowsAndRetriesPendingMarkers() throws Exception {
    String upgradeSchema = "gs_roster_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var upgradeDataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    upgradeDataSource.setSchema(upgradeSchema);
    migrate(upgradeDataSource, upgradeSchema, "1020");
    var upgradeDsl =
        DSL.using(new TransactionAwareDataSourceProxy(upgradeDataSource), SQLDialect.POSTGRES);
    var upgradeTransaction =
        new TransactionTemplate(new DataSourceTransactionManager(upgradeDataSource));
    var upgradeRepository =
        new CanonicalGameplayRosterReferenceRepository(upgradeDsl, policy(1L, 1L, 1L));

    try {
      var ordinaryTerminal = request();
      var recoveredTerminal = request();
      var pendingRecovery = request();
      var createdTerminalFixture = createdFixture(true);
      var createdPendingFixture = createdFixture(true);
      var createdTerminal = createdTerminalFixture.original();
      var createdPending = createdPendingFixture.original();
      upgradeTransaction.executeWithoutResult(
          ignored -> {
            upgradeRepository.begin(ordinaryTerminal);
            upgradeRepository.completeUnissuedDenied(ordinaryTerminal);
            for (var original : List.of(recoveredTerminal, pendingRecovery)) {
              upgradeRepository.begin(original);
              upgradeRepository.reserveSelectionInput(original, selectionInput());
              upgradeRepository.markRosterReadStarted(original);
            }
            for (var fixture : List.of(createdTerminalFixture, createdPendingFixture)) {
              var original = fixture.original();
              upgradeRepository.begin(original);
              upgradeRepository.reserveSelectionInput(original, selectionInput());
              upgradeRepository.markRosterReadStarted(original);
              upgradeRepository.recordPlayerCreated(original, fixture.roster());
              upgradeRepository.selectOriginal(
                  original, UUID.fromString(fixture.selected().getSelectedCharacterUuid()));
              upgradeRepository.recordSelectedPlayerCreatedCharacter(original, fixture.selected());
            }
          });
      Thread.sleep(10L);

      var terminalRecoveryUuid = UUID.randomUUID();
      var pendingRecoveryUuid = UUID.randomUUID();
      var createdTerminalRecoveryUuid = UUID.randomUUID();
      var createdPendingRecoveryUuid = UUID.randomUUID();
      upgradeTransaction.executeWithoutResult(
          ignored -> {
            for (var entry :
                List.of(
                    Map.entry(recoveredTerminal, terminalRecoveryUuid),
                    Map.entry(pendingRecovery, pendingRecoveryUuid),
                    Map.entry(createdTerminal, createdTerminalRecoveryUuid),
                    Map.entry(createdPending, createdPendingRecoveryUuid))) {
              upgradeDsl.execute(
                  "UPDATE game_session_canonical_roster_selection SET recovery_uuid = ?, recovery_started_at = clock_timestamp() WHERE operation_uuid = ?",
                  entry.getValue(),
                  UUID.fromString(entry.getKey().getOperationUuid()));
            }
          });
      var recoveryRequest =
          CanonicalGameplayRosterRecoveryEvidence.requireRequest(
              CanonicalGameplayRosterRecoveryRequest.newBuilder()
                  .setSchemaVersion(1)
                  .setOriginalSelection(recoveredTerminal)
                  .setRecoveryUuid(terminalRecoveryUuid.toString())
                  .build());
      var originalRecord = upgradeRepository.read(recoveredTerminal);
      var terminalRecoveryResponse =
          CanonicalGameplayRosterRecoveryEvidence.response(
              recoveryRequest,
              UUID.randomUUID(),
              originalRecord.getOwnerFence(),
              UUID.fromString(originalRecord.getTargetFenceUuid()),
              null);
      upgradeTransaction.executeWithoutResult(
          ignored ->
              upgradeRepository.finishRecovery(
                  verifiedRecovery(recoveryRequest, terminalRecoveryResponse)));

      var createdRecoveryRequest =
          CanonicalGameplayRosterRecoveryEvidence.requireRequest(
              CanonicalGameplayRosterRecoveryRequest.newBuilder()
                  .setSchemaVersion(1)
                  .setOriginalSelection(createdTerminal)
                  .setRecoveryUuid(createdTerminalRecoveryUuid.toString())
                  .build());
      var createdTerminalRecord = upgradeRepository.read(createdTerminal);
      var createdRecoveryResponse =
          CanonicalGameplayRosterRecoveryEvidence.playerCreatedResponse(
              createdRecoveryRequest,
              UUID.randomUUID(),
              createdTerminalRecord.getOwnerFence(),
              UUID.fromString(createdTerminalRecord.getTargetFenceUuid()),
              createdTerminalFixture.roster());
      upgradeTransaction.executeWithoutResult(
          ignored ->
              upgradeRepository.finishRecovery(
                  verifiedRecovery(createdRecoveryRequest, createdRecoveryResponse)));

      var retainedOrdinaryTerminal = upgradeRow(upgradeDsl, ordinaryTerminal);
      var retainedRecoveredTerminal = upgradeRow(upgradeDsl, recoveredTerminal);
      var retainedPendingRecovery = upgradeRow(upgradeDsl, pendingRecovery);
      var retainedCreatedTerminal = upgradePredecessorRow(upgradeDsl, createdTerminal);
      var retainedCreatedPending = upgradePredecessorRow(upgradeDsl, createdPending);
      assertThat(
              upgradeDsl
                  .fetchSingle(
                      "SELECT terminal_outcome FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
                      UUID.fromString(ordinaryTerminal.getOperationUuid()))
                  .get("terminal_outcome", String.class))
          .isEqualTo("DENIED");
      assertThat(
              upgradeDsl
                  .fetchSingle(
                      "SELECT terminal_outcome FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
                      UUID.fromString(recoveredTerminal.getOperationUuid()))
                  .get("terminal_outcome", String.class))
          .isEqualTo("DENIED");
      assertThat(upgradeRowValue(upgradeDsl, pendingRecovery, "recovery_uuid", String.class))
          .isEqualTo(pendingRecoveryUuid.toString());
      assertThat(upgradeRowValue(upgradeDsl, createdTerminal, "recovery_uuid", String.class))
          .isEqualTo(createdTerminalRecoveryUuid.toString());
      assertThat(upgradeRowValue(upgradeDsl, createdPending, "recovery_uuid", String.class))
          .isEqualTo(createdPendingRecoveryUuid.toString());
      assertThat(
              upgradeDsl
                  .fetchSingle(
                      "SELECT terminal_outcome FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
                      UUID.fromString(createdTerminal.getOperationUuid()))
                  .get("terminal_outcome", String.class))
          .isEqualTo("DENIED");
      assertThat(upgradeRepository.read(createdTerminal).getCanonicalPlayerCreatedRosterReceipt())
          .isEqualTo(createdTerminalFixture.roster().toByteString());
      assertThat(
              upgradeRepository
                  .read(createdTerminal)
                  .getCanonicalSelectedPlayerCreatedCharacterReceipt())
          .isEqualTo(createdTerminalFixture.selected().toByteString());
      var historyBeforeScheduling = history(upgradeDsl);

      migrate(upgradeDataSource, upgradeSchema, "1021");
      String retainedRowsSql =
          "SELECT row_to_json(s)::text || ':' || s.xmin::text FROM game_session_canonical_roster_selection s ORDER BY operation_uuid";
      var rowsBeforeBinaryGuard = upgradeDsl.fetch(retainedRowsSql).getValues(0, String.class);
      var historyBeforeBinaryGuard = history(upgradeDsl);
      migrate(upgradeDataSource, upgradeSchema, "latest");
      assertThat(history(upgradeDsl).subList(0, historyBeforeBinaryGuard.size()))
          .containsExactlyElementsOf(historyBeforeBinaryGuard);
      assertThat(upgradeDsl.fetch(retainedRowsSql).getValues(0, String.class))
          .containsExactlyElementsOf(rowsBeforeBinaryGuard);
      assertThat(history(upgradeDsl).subList(0, historyBeforeScheduling.size()))
          .containsExactlyElementsOf(historyBeforeScheduling);
      assertThat(history(upgradeDsl).get(history(upgradeDsl).size() - 1))
          .contains("1022", "V1022__canonical_roster_binary_safe_recovery_guard.sql");
      assertThat(upgradeRow(upgradeDsl, ordinaryTerminal)).isEqualTo(retainedOrdinaryTerminal);
      assertThat(upgradeRow(upgradeDsl, recoveredTerminal)).isEqualTo(retainedRecoveredTerminal);
      assertThat(upgradeRow(upgradeDsl, pendingRecovery)).isEqualTo(retainedPendingRecovery);
      assertThat(upgradePredecessorRow(upgradeDsl, createdTerminal))
          .isEqualTo(retainedCreatedTerminal);
      assertThat(upgradePredecessorRow(upgradeDsl, createdPending))
          .isEqualTo(retainedCreatedPending);
      assertThat(upgradeRepository.read(createdTerminal).getCanonicalPlayerCreatedRosterReceipt())
          .isEqualTo(createdTerminalFixture.roster().toByteString());
      assertThat(
              upgradeRepository
                  .read(createdTerminal)
                  .getCanonicalSelectedPlayerCreatedCharacterReceipt())
          .isEqualTo(createdTerminalFixture.selected().toByteString());
      assertThat(upgradeRepository.read(createdPending).getCanonicalPlayerCreatedRosterReceipt())
          .isEqualTo(createdPendingFixture.roster().toByteString());
      assertThat(
              upgradeRepository
                  .read(createdPending)
                  .getCanonicalSelectedPlayerCreatedCharacterReceipt())
          .isEqualTo(createdPendingFixture.selected().toByteString());
      assertThat(
              upgradeRowValue(
                  upgradeDsl, ordinaryTerminal, "recovery_last_attempt_at", String.class))
          .isNull();
      assertThat(
              upgradeRowValue(
                  upgradeDsl, recoveredTerminal, "recovery_last_attempt_at", String.class))
          .isNull();
      assertThat(
              upgradeRowValue(
                  upgradeDsl, pendingRecovery, "recovery_last_attempt_at", String.class))
          .isNull();
      assertThat(
              upgradeRowValue(
                  upgradeDsl, createdTerminal, "recovery_last_attempt_at", String.class))
          .isNull();
      assertThat(
              upgradeRowValue(upgradeDsl, createdPending, "recovery_last_attempt_at", String.class))
          .isNull();

      // A retained future scheduling value must advance strictly when its pending retry is
      // selected.
      upgradeTransaction.executeWithoutResult(
          ignored ->
              upgradeDsl.execute(
                  "UPDATE game_session_canonical_roster_selection SET recovery_last_attempt_at = clock_timestamp() + interval '1 day' WHERE operation_uuid = ?",
                  UUID.fromString(pendingRecovery.getOperationUuid())));
      String futureAttempt =
          upgradeRowValue(upgradeDsl, pendingRecovery, "recovery_last_attempt_at", String.class);
      var retryPage =
          upgradeTransaction.execute(
              ignored ->
                  upgradeRepository.beginRecoveries(pendingRecovery, current(pendingRecovery)));
      assertThat(retryPage).hasSize(1);
      assertThat(retryPage.get(0).getRecoveryUuid()).isEqualTo(pendingRecoveryUuid.toString());
      assertThat(upgradeRowValue(upgradeDsl, pendingRecovery, "recovery_uuid", String.class))
          .isEqualTo(pendingRecoveryUuid.toString());
      assertThat(
              upgradeDsl.fetchExists(
                  DSL.selectOne()
                      .from("game_session_canonical_roster_selection")
                      .where(
                          "operation_uuid = ? AND recovery_last_attempt_at > ?::timestamptz",
                          UUID.fromString(pendingRecovery.getOperationUuid()),
                          futureAttempt)))
          .isTrue();
      assertThat(upgradeRow(upgradeDsl, ordinaryTerminal)).isEqualTo(retainedOrdinaryTerminal);
      assertThat(upgradeRow(upgradeDsl, recoveredTerminal)).isEqualTo(retainedRecoveredTerminal);

      String createdPendingBeforeAttempt = upgradePredecessorColumns(upgradeDsl, createdPending);
      var createdRetryPage =
          upgradeTransaction.execute(
              ignored ->
                  upgradeRepository.beginRecoveries(createdPending, current(createdPending)));
      assertThat(createdRetryPage).hasSize(1);
      assertThat(createdRetryPage.getFirst().getRecoveryUuid())
          .isEqualTo(createdPendingRecoveryUuid.toString());
      assertThat(upgradePredecessorColumns(upgradeDsl, createdPending))
          .isEqualTo(createdPendingBeforeAttempt);
      String createdAttempt =
          upgradeRowValue(upgradeDsl, createdPending, "recovery_last_attempt_at", String.class);
      assertThat(createdAttempt).isNotNull();
      var createdPendingAfterAttempt = upgradePredecessorRow(upgradeDsl, createdPending);
      for (String change :
          List.of(
              "recovery_last_attempt_at = NULL",
              "recovery_last_attempt_at = recovery_last_attempt_at - interval '1 millisecond'",
              "recovery_last_attempt_at = recovery_last_attempt_at + interval '1 millisecond', original_request = decode('01', 'hex')")) {
        assertThatThrownBy(
                () ->
                    upgradeDsl.execute(
                        "UPDATE game_session_canonical_roster_selection SET "
                            + change
                            + " WHERE operation_uuid = ?",
                        UUID.fromString(createdPending.getOperationUuid())))
            .isInstanceOf(DataAccessException.class);
      }
      assertThat(upgradePredecessorRow(upgradeDsl, createdPending))
          .isEqualTo(createdPendingAfterAttempt);
      assertThat(
              upgradeRowValue(upgradeDsl, createdPending, "recovery_last_attempt_at", String.class))
          .isEqualTo(createdAttempt);
      var alteredSelectedReceipt =
          createdPendingFixture.selected().toBuilder()
              .setTerminal(
                  createdPendingFixture.selected().getTerminal().toBuilder()
                      .setTerminalId(UUID.randomUUID().toString()))
              .build();
      assertThatThrownBy(
              () ->
                  upgradeDsl.execute(
                      "UPDATE game_session_canonical_roster_selection SET canonical_selected_player_created_character_receipt = ? WHERE operation_uuid = ?",
                      alteredSelectedReceipt.toByteArray(),
                      UUID.fromString(createdPending.getOperationUuid())))
          .isInstanceOf(DataAccessException.class);
      assertThat(upgradePredecessorRow(upgradeDsl, createdPending))
          .isEqualTo(createdPendingAfterAttempt);
      assertThat(upgradeRowValue(upgradeDsl, createdPending, "recovery_uuid", String.class))
          .isEqualTo(createdPendingRecoveryUuid.toString());
      assertThat(
              upgradeRowValue(upgradeDsl, createdPending, "recovery_last_attempt_at", String.class))
          .isEqualTo(createdAttempt);
    } finally {
      try (var admin =
          DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
        admin.execute("DROP SCHEMA IF EXISTS " + upgradeSchema + " CASCADE");
      }
    }
  }

  @AfterEach
  void dropOwnedSchema() {
    try (var admin =
        DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void originalIntentAndReceiptReplayWithoutMutationAndRemainProtected() {
    var request = request();
    var original = transaction.execute(ignored -> repository.begin(request));
    assertThat(original.getCanonicalRosterReceipt()).isEmpty();
    var receipt = receipt(request);
    var recorded = transaction.execute(ignored -> repository.record(request, receipt));
    var retained = retainedRow(request);
    assertThat(
            transaction
                .<net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse>
                    execute(ignored -> repository.begin(request)))
        .isEqualTo(recorded);
    assertThat(
            transaction
                .<net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse>
                    execute(ignored -> repository.record(request, receipt)))
        .isEqualTo(recorded);
    assertThat(retainedRow(request)).isEqualTo(retained);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.begin(
                            request.toBuilder()
                                .setSessionUuid(UUID.randomUUID().toString())
                                .build())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CONFLICT");
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.record(
                            request,
                            receipt.toBuilder()
                                .setSnapshotUuid(UUID.randomUUID().toString())
                                .build())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CONFLICT");
    assertThatThrownBy(() -> dsl.execute("DELETE FROM game_session_canonical_roster_selection"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> dsl.execute("TRUNCATE game_session_canonical_roster_selection"))
        .isInstanceOf(DataAccessException.class);
    assertThat(retainedRow(request)).isEqualTo(retained);
  }

  @Test
  void terminalOriginalCannotBeMarkedAsRosterReadStarted() {
    repository = repository(policy(60_000L, 60_000L, 60_000L));
    var request = request();
    transaction.execute(ignored -> repository.begin(request));
    transaction.executeWithoutResult(ignored -> repository.completeUnissuedDenied(request));
    var retainedResponseBytes = repository.read(request).toByteArray();
    var retainedSelectionRow = retainedRow(request);
    var retainedLifecycle =
        dsl.fetchSingle(
            "SELECT roster_read_started, terminal_outcome, "
                + "encode(original_request, 'hex') AS original_request_hex, xmin::text "
                + "FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
            UUID.fromString(request.getOperationUuid()));
    assertThat(retainedLifecycle.get("roster_read_started", Boolean.class)).isFalse();
    assertThat(retainedLifecycle.get("terminal_outcome", String.class)).isEqualTo("DENIED");

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.markRosterReadStarted(request)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_ORIGINAL_DEREFERENCE_CLOSED");

    assertThat(repository.read(request).toByteArray()).containsExactly(retainedResponseBytes);
    assertThat(retainedRow(request)).isEqualTo(retainedSelectionRow);
    assertThat(
            dsl.fetchSingle(
                    "SELECT roster_read_started, terminal_outcome, "
                        + "encode(original_request, 'hex') AS original_request_hex, xmin::text "
                        + "FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
                    UUID.fromString(request.getOperationUuid()))
                .formatJSON())
        .isEqualTo(retainedLifecycle.formatJSON());
  }

  @Test
  void rosterReadCannotStartBeforeSelectionInputIsDurablyReserved() {
    var request = request();
    var original = transaction.execute(ignored -> repository.begin(request));
    var retainedSelectionRow = retainedRow(request);
    var retainedDispatchState =
        dsl.fetchSingle(
            "SELECT selection_input IS NOT NULL AS has_selection_input, roster_read_started, xmin::text "
                + "FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
            UUID.fromString(request.getOperationUuid()));
    assertThat(retainedDispatchState.get("has_selection_input", Boolean.class)).isFalse();
    assertThat(retainedDispatchState.get("roster_read_started", Boolean.class)).isFalse();

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.markRosterReadStarted(request)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_SELECTION_INPUT_UNAVAILABLE");

    assertThat(repository.read(request).toByteArray()).containsExactly(original.toByteArray());
    assertThat(retainedRow(request)).isEqualTo(retainedSelectionRow);
    assertThat(
            dsl.fetchSingle(
                    "SELECT selection_input IS NOT NULL AS has_selection_input, roster_read_started, xmin::text "
                        + "FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
                    UUID.fromString(request.getOperationUuid()))
                .formatJSON())
        .isEqualTo(retainedDispatchState.formatJSON());

    transaction.executeWithoutResult(
        ignored -> {
          repository.reserveSelectionInput(request, selectionInput());
          repository.markRosterReadStarted(request);
        });
    var dispatchedState =
        dsl.fetchSingle(
            "SELECT selection_input IS NOT NULL AS has_selection_input, roster_read_started "
                + "FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
            UUID.fromString(request.getOperationUuid()));
    assertThat(dispatchedState.get("has_selection_input", Boolean.class)).isTrue();
    assertThat(dispatchedState.get("roster_read_started", Boolean.class)).isTrue();
  }

  @Test
  void existingTargetFenceKeepsItsConfiguredHorizonsAfterPolicyChanges() throws Exception {
    var originalPolicy = policy(50L, 20L, 25L);
    repository = repository(originalPolicy);
    var request = request();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(request);
          repository.reserveSelectionInput(
              request,
              net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput
                  .newBuilder()
                  .setSchemaVersion(1)
                  .build());
          repository.completeSelection(
              request, CanonicalGameplayRosterReferenceRepository.SelectionOutcome.DENIED);
        });

    Thread.sleep(80L);
    var changedPolicy = policy(2_000L, 1_000L, 1_500L);
    repository = repository(changedPolicy);

    assertThatThrownBy(() -> transaction.execute(ignored -> repository.begin(request)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("REPLAY_WINDOW_CLOSED");

    var sameTargetNewOperation =
        request.toBuilder()
            .setOperationUuid(UUID.randomUUID().toString())
            .setSessionUuid(UUID.randomUUID().toString())
            .build();
    transaction.execute(ignored -> repository.begin(sameTargetNewOperation));
    assertThat(fencePolicy(request)).containsExactly(50L, 25L, "ACTIVE");

    var distinctTarget = request();
    transaction.execute(ignored -> repository.begin(distinctTarget));
    assertThat(fencePolicy(distinctTarget)).containsExactly(2_000L, 1_500L, "ACTIVE");
  }

  @Test
  void selectionInputChosenActorAndAssignmentResultCannotChangeWithinOriginalOperation() {
    var request = request();
    UUID menu = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    var input =
        net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput.newBuilder()
            .setSchemaVersion(1)
            .setSelector("1")
            .setMenuSnapshotUuid(menu.toString())
            .setMenuSnapshotDigest("d".repeat(64))
            .build();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(request);
          repository.reserveSelectionInput(request, input);
        });
    var roster =
        receipt(request).toBuilder()
            .setSnapshotUuid(menu.toString())
            .addActors(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor.newBuilder()
                    .setCharacterUuid(first.toString())
                    .setDisplayName("First")
                    .setActorKind(
                        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind
                            .CANONICAL_ROSTER_ACTOR_KIND_PLAYER))
            .addActors(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor.newBuilder()
                    .setCharacterUuid(second.toString())
                    .setDisplayName("Second")
                    .setActorKind(
                        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind
                            .CANONICAL_ROSTER_ACTOR_KIND_PLAYER))
            .build();
    transaction.executeWithoutResult(ignored -> repository.record(request, roster));
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.reserveSelectionInput(
                            request, input.toBuilder().setSelector("2").build())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CONFLICT");
    transaction.executeWithoutResult(ignored -> repository.selectOriginal(request, first));
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.selectOriginal(request, second)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CONFLICT");
    var assignment =
        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSelectedAssignmentResponse
            .newBuilder()
            .setRequestUuid(request.getOperationUuid())
            .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
            .setSelectedCharacterUuid(first.toString())
            .setTarget(roster.getTarget())
            .setSnapshot(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSnapshotReference
                    .newBuilder()
                    .setSnapshotUuid(roster.getSnapshotUuid())
                    .setSnapshotDigest(roster.getSnapshotDigest()))
            .setAssignmentUuid(UUID.randomUUID().toString())
            .setIntentDigest("e".repeat(64))
            .build();
    transaction.executeWithoutResult(
        ignored -> repository.recordSelectedAssignment(request, assignment));
    var retained = retainedRow(request);
    transaction.executeWithoutResult(
        ignored -> {
          repository.reserveSelectionInput(request, input);
          repository.selectOriginal(request, first);
          repository.recordSelectedAssignment(request, assignment);
        });
    assertThat(retainedRow(request)).isEqualTo(retained);
    assertThat(
            CanonicalGameplayRosterReferenceEvidence.selectedAssignmentReceipt(
                repository.read(request)))
        .isEqualTo(assignment);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.recordSelectedAssignment(
                            request,
                            assignment.toBuilder()
                                .setAssignmentUuid(UUID.randomUUID().toString())
                                .build())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CONFLICT");
  }

  @Test
  void nonsupersedingCommittedRouteReturnsBeforeAdmissionReadsOrOwnerWrites() {
    var request = request();
    var original = transaction.execute(ignored -> repository.begin(request));
    var originalResponseBytes = original.toByteArray();
    var originalTarget = CanonicalGameplayRosterReferenceEvidence.target(request);
    var samePointerRequest =
        request.toBuilder()
            .setCanonicalTarget(
                originalTarget.toBuilder()
                    .setPointerVersion(originalTarget.getPointerVersion() - 1L)
                    .build()
                    .toByteString())
            .build();
    var current = current(samePointerRequest);
    var routes =
        org.mockito.Mockito.mock(
            net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService.class);
    org.mockito.Mockito.when(
            routes.readCurrentTarget(UUID.fromString(originalTarget.getTenantUuid())))
        .thenReturn(current);
    var admissions = org.mockito.Mockito.mock(CanonicalInitialAdmissionRepository.class);
    var recovery = org.mockito.Mockito.mock(CanonicalGameplayRosterRecoveryClient.class);
    var owner =
        new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner(
            repository,
            transaction,
            org.mockito.Mockito.mock(
                net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                    .class),
            routes,
            admissions,
            recovery,
            new SimpleMeterRegistry());

    assertThat(owner.retireTarget(request)).isFalse();
    org.mockito.Mockito.verifyNoInteractions(admissions, recovery);
    assertThat(repository.read(request).toByteArray()).containsExactly(originalResponseBytes);
    assertThat(
            dsl.fetchSingle("SELECT count(*) FROM game_session_canonical_roster_target_fences")
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThat(
            dsl.fetchSingle("SELECT count(*) FROM game_session_canonical_roster_selection")
                .get(0, Long.class))
        .isEqualTo(1L);
  }

  @Test
  void actualOutcomeAndIndependentCurrentOwnerSupersessionSealEveryOriginalAtOneTargetFence()
      throws Exception {
    var request = request();
    var input =
        net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput.newBuilder()
            .setSchemaVersion(1)
            .build();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(request);
          repository.reserveSelectionInput(request, input);
          repository.markRosterReadStarted(request);
          repository.record(request, receipt(request));
        });
    var routeReader =
        org.mockito.Mockito.mock(
            net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService.class);
    var current = current(request);
    org.mockito.Mockito.when(
            routeReader.readCurrentTarget(
                UUID.fromString(
                    CanonicalGameplayRosterReferenceEvidence.target(request).getTenantUuid())))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              return current;
            });
    // Controlled admission source here; the existing admission-owner integration fixture separately
    // exercises the real committed reader and actual pointer/attempt/catalog row locks.
    var admissions = org.mockito.Mockito.mock(CanonicalInitialAdmissionRepository.class);
    var snapshot = currentSnapshot(request);
    org.mockito.Mockito.when(
            admissions.readCurrentOpenSnapshotForRealm(
                request.getTargetNamespace(), current.canonicalTenantId(), current.realmId()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              return snapshot;
            });
    org.mockito.Mockito.when(admissions.lockExactCurrentOpenSnapshot(snapshot))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isTrue();
              return snapshot;
            });
    var owner =
        new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner(
            repository,
            transaction,
            org.mockito.Mockito.mock(
                net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                    .class),
            routeReader,
            admissions,
            org.mockito.Mockito.mock(
                net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterRecoveryClient
                    .class),
            new SimpleMeterRegistry());
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(request, current)))
        .isFalse();
    org.mockito.Mockito.when(
            admissions.readCurrentOpenSnapshotForRealm(
                request.getTargetNamespace(), current.canonicalTenantId(), current.realmId()))
        .thenReturn(
            new CanonicalInitialAdmissionRepository.CurrentOpenSnapshot(
                snapshot.ownerProof(), "e".repeat(64)));
    assertThatThrownBy(() -> owner.retireTarget(request))
        .hasMessageContaining("CURRENT_SOURCE_CONFLICT");
    org.mockito.Mockito.verify(admissions, org.mockito.Mockito.never())
        .lockExactCurrentOpenSnapshot(snapshot);
    org.mockito.Mockito.when(
            admissions.readCurrentOpenSnapshotForRealm(
                request.getTargetNamespace(), current.canonicalTenantId(), current.realmId()))
        .thenReturn(snapshot);
    transaction.executeWithoutResult(
        ignored ->
            repository.completeSelection(
                request, CanonicalGameplayRosterReferenceRepository.SelectionOutcome.DENIED));
    Thread.sleep(5L);
    org.mockito.Mockito.doThrow(
            new CanonicalInitialAdmissionRepository
                .CanonicalInitialAdmissionReconciliationRequiredException(
                "controlled pointer changed after qualification"))
        .when(admissions)
        .lockExactCurrentOpenSnapshot(snapshot);
    assertThatThrownBy(() -> owner.retireTarget(request))
        .hasMessageContaining("changed after qualification");
    assertThat(repository.read(request).getRetirementUuid()).isEmpty();
    org.mockito.Mockito.doAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isTrue();
              return snapshot;
            })
        .when(admissions)
        .lockExactCurrentOpenSnapshot(snapshot);
    assertThat(owner.retireTarget(request)).isTrue();
    var retired = repository.read(request);
    assertThat(CanonicalGameplayRosterReferenceEvidence.requireRetirement(retired))
        .isEqualTo(retired);
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.begin(request)))
        .hasMessageContaining("RETIRED");
    var newOriginal = request.toBuilder().setOperationUuid(UUID.randomUUID().toString()).build();
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.begin(newOriginal)))
        .hasMessageContaining("RETIRED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.markRosterReadStarted(request)))
        .hasMessageContaining("RETIRED");
  }

  @Test
  void newAndRecoveredReferencesSerializeAgainstRetirementOnTheSameTargetFence() throws Exception {
    var request = request();
    var input =
        net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput.newBuilder()
            .setSchemaVersion(1)
            .build();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(request);
          repository.reserveSelectionInput(request, input);
          repository.record(request, receipt(request));
          repository.completeSelection(
              request, CanonicalGameplayRosterReferenceRepository.SelectionOutcome.DENIED);
        });
    Thread.sleep(5L);
    CountDownLatch sealed = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    CountDownLatch recoveryStarted = new CountDownLatch(1);
    AtomicInteger retirementPid = new AtomicInteger();
    AtomicInteger recoveryPid = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var retirement =
          executor.submit(
              () ->
                  transaction.execute(
                      ignored -> {
                        retirementPid.set(
                            java.util.Objects.requireNonNull(
                                dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class)));
                        boolean result = repository.sealTargetRetirement(request, current(request));
                        sealed.countDown();
                        try {
                          if (!commit.await(15L, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("fixture timeout");
                          }
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        return result;
                      }));
      assertThat(sealed.await(5L, TimeUnit.SECONDS)).isTrue();
      var recovery =
          executor.submit(
              () ->
                  transaction.execute(
                      ignored -> {
                        recoveryPid.set(
                            java.util.Objects.requireNonNull(
                                dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class)));
                        recoveryStarted.countDown();
                        return repository.begin(
                            request.toBuilder()
                                .setOperationUuid(UUID.randomUUID().toString())
                                .build());
                      }));
      assertThat(recoveryStarted.await(5L, TimeUnit.SECONDS)).isTrue();
      awaitBackendBlockedBy(dsl, recoveryPid.get(), retirementPid.get());
      commit.countDown();
      assertThat(retirement.get(5L, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> recovery.get(5L, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class);
    } finally {
      commit.countDown();
    }
  }

  private static void awaitBackendBlockedBy(DSLContext observer, int waitingPid, int holderPid)
      throws InterruptedException {
    assertThat(waitingPid).isPositive().isNotEqualTo(holderPid);
    assertThat(holderPid).isPositive();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    boolean blocked = false;
    while (System.nanoTime() < deadline) {
      blocked =
          Boolean.TRUE.equals(
              observer
                  .fetchSingle("SELECT ? = ANY(pg_blocking_pids(?))", holderPid, waitingPid)
                  .get(0, Boolean.class));
      if (blocked) break;
      TimeUnit.MILLISECONDS.sleep(10L);
    }
    assertThat(blocked)
        .as("PostgreSQL backend %s waits on exact holder %s", waitingPid, holderPid)
        .isTrue();
  }

  private static net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget
      currentCreatedTarget(GetCanonicalGameplayRosterReferenceRequest request) {
    var target = current(request);
    return new net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget(
        target.targetNamespace(),
        target.tenantSlug(),
        target.canonicalTenantId(),
        target.worldSlug(),
        target.worldDisplayName(),
        target.realmId(),
        target.realmSlug(),
        target.realmDisplayName(),
        target.gameSessionTenantId(),
        target.gameInstanceId(),
        target.playableStateNamespaceId(),
        target.playableStateScope(),
        target.canonicalGameInstanceId(),
        target.canonicalVersionId(),
        target.runtimeVersionId(),
        target.catalogRevision(),
        target.pointerVersion(),
        target.admissionPointerSnapshotDigest(),
        target.activeWorldEpoch(),
        target.initialAdmissionRequestId(),
        target.initialAdmissionRequestDigest(),
        target.initialAdmissionOriginKind(),
        target.expectedPriorPointerVersion(),
        target.holdId(),
        target.holdFence(),
        target.holdBindingDigest(),
        target.auditEventId(),
        target.ownerProofDigest(),
        target.ownerProofOutcome(),
        target.positiveDurableAbort(),
        target.ownerProofTerminalAt(),
        "PLAYER_CREATED");
  }

  private static CanonicalInitialAdmissionRepository.CurrentOpenSnapshot currentSnapshot(
      GetCanonicalGameplayRosterReferenceRequest request) {
    var target = CanonicalGameplayRosterReferenceEvidence.target(request);
    var proof =
        new net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof(
            net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof.Outcome
                .COMMITTED,
            "controlled-current-owner",
            "1".repeat(64),
            request.getTargetNamespace(),
            UUID.fromString(target.getTenantUuid()),
            target.getWorldSlug(),
            UUID.fromString(target.getRealmUuid()),
            UUID.fromString(target.getPlayableStateNamespaceUuid()),
            "SHARED",
            UUID.fromString(target.getGameInstanceUuid()),
            UUID.fromString(target.getCanonicalVersionUuid()),
            target.getActiveWorldEpoch() + 1L,
            target.getCatalogRevision() + 1L,
            net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind
                .EXPECT_CLOSED,
            target.getPointerVersion(),
            UUID.fromString(request.getOperationUuid()),
            UUID.fromString(request.getSessionUuid()),
            "sha256:" + "2".repeat(64),
            target.getPointerVersion() + 1L,
            1L,
            "sha256:" + "3".repeat(64),
            false,
            java.time.Instant.parse("2026-10-01T00:00:00Z"));
    return new CanonicalInitialAdmissionRepository.CurrentOpenSnapshot(proof, "f".repeat(64));
  }

  private static net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget current(
      GetCanonicalGameplayRosterReferenceRequest request) {
    var target = CanonicalGameplayRosterReferenceEvidence.target(request);
    var snapshot = currentSnapshot(request);
    var p = snapshot.ownerProof();
    return new net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget(
        p.targetNamespace(),
        "controlled-tenant",
        p.canonicalTenantId(),
        p.worldSlug(),
        "Controlled World",
        p.realmId(),
        target.getRealmSlug(),
        "Controlled Realm",
        1L,
        1L,
        p.playableStateNamespaceId(),
        p.playableStateScope(),
        p.canonicalGameInstanceId(),
        p.canonicalVersionId(),
        1L,
        p.expectedCatalogRevision(),
        p.committedPointerVersion(),
        snapshot.admissionPointerSnapshotDigest(),
        p.activeLifecycleEpoch(),
        p.initialAdmissionRequestId(),
        p.requestDigest(),
        p.originKind(),
        p.expectedPriorPointerVersion(),
        p.holdId(),
        p.holdFence(),
        p.holdBindingDigest(),
        p.auditEventId(),
        p.proofDigest(),
        p.outcome(),
        p.positiveDurableAbort(),
        p.terminalAt(),
        "PRESEEDED_ONLY");
  }

  @Test
  void missingOriginalAndRolledBackReservationNeverSupplyPositiveEvidence() {
    var request = request();
    assertThatThrownBy(() -> repository.read(request)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.begin(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TRANSACTION_REQUIRED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> {
                      repository.begin(request);
                      throw new IllegalStateException("controlled rollback");
                    }))
        .hasMessage("controlled rollback");
    assertThatThrownBy(() -> repository.read(request)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void concurrentSameOriginalReservesOneFenceAndCannotReplaceItsIntent() throws Exception {
    var request = request();
    CountDownLatch reserved = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    CountDownLatch replayStarted = new CountDownLatch(1);
    AtomicInteger firstPid = new AtomicInteger();
    AtomicInteger replayPid = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () ->
                  transaction.execute(
                      ignored -> {
                        firstPid.set(
                            java.util.Objects.requireNonNull(
                                dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class)));
                        var value = repository.begin(request);
                        reserved.countDown();
                        try {
                          if (!finish.await(15L, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("fixture timeout");
                          }
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        return value;
                      }));
      assertThat(reserved.await(5L, TimeUnit.SECONDS)).isTrue();
      var replay =
          executor.submit(
              () ->
                  transaction.execute(
                      ignored -> {
                        replayPid.set(
                            java.util.Objects.requireNonNull(
                                dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class)));
                        replayStarted.countDown();
                        return repository.begin(request);
                      }));
      assertThat(replayStarted.await(5L, TimeUnit.SECONDS)).isTrue();
      awaitBackendBlockedBy(dsl, replayPid.get(), firstPid.get());
      finish.countDown();
      assertThat(replay.get(5L, TimeUnit.SECONDS)).isEqualTo(first.get(5L, TimeUnit.SECONDS));
      assertThat(
              dsl.fetchSingle("SELECT count(*) FROM game_session_canonical_roster_selection")
                  .get(0, Long.class))
          .isEqualTo(1L);
    } finally {
      finish.countDown();
    }
  }

  @Test
  void interruptedRosterReadRecoversExactReceiptAndFencesLateWritersBeforeFiniteRetirement()
      throws Exception {
    var request = request();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(request);
          repository.reserveSelectionInput(request, selectionInput());
          repository.markRosterReadStarted(request);
        });
    Thread.sleep(5L);
    var recovery =
        transaction
            .execute(ignored -> repository.beginRecoveries(request, current(request)))
            .getFirst();
    var marked = repository.read(request);
    assertThat(marked.getRecoveryUuid()).isEqualTo(recovery.getRecoveryUuid());
    assertThat(marked.getTerminalOutcome()).isEmpty();
    assertThat(
            transaction.<List<CanonicalGameplayRosterRecoveryRequest>>execute(
                ignored -> repository.beginRecoveries(request, current(request))))
        .containsExactly(recovery);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(request, current(request))))
        .isFalse();
    var originalReceipt = receipt(request);
    assertThatThrownBy(
            () -> transaction.execute(ignored -> repository.record(request, originalReceipt)))
        .hasMessageContaining("RECOVERY_FENCED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.reserveSelectionInput(request, selectionInput())))
        .hasMessageContaining("RECOVERY_FENCED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.markRosterReadStarted(request)))
        .hasMessageContaining("RECOVERY_FENCED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.completeSelection(
                            request,
                            CanonicalGameplayRosterReferenceRepository.SelectionOutcome.DENIED)))
        .hasMessageContaining("RECOVERY_FENCED");
    transaction.executeWithoutResult(ignored -> repository.completeUnissuedDenied(request));
    assertThat(repository.read(request).getTerminalOutcome()).isEmpty();
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_session_canonical_roster_selection SET selection_input = ? WHERE operation_uuid = ?",
                    selectionInput().toBuilder().setSelector("1").build().toByteArray(),
                    UUID.fromString(request.getOperationUuid())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_session_canonical_roster_selection SET recovery_uuid = ? WHERE operation_uuid = ?",
                    UUID.randomUUID(),
                    UUID.fromString(request.getOperationUuid())))
        .isInstanceOf(DataAccessException.class);
    var response = recoveryResponse(recovery, originalReceipt);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.finishRecovery(
                            verifiedRecovery(
                                recovery,
                                response.toBuilder()
                                    .setTargetFenceUuid(UUID.randomUUID().toString())
                                    .build()))))
        .hasMessageContaining("FENCE_CONFLICT");
    var wrongMarker = recovery.toBuilder().setRecoveryUuid(UUID.randomUUID().toString()).build();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.finishRecovery(
                            verifiedRecovery(
                                wrongMarker,
                                response.toBuilder().setRequest(wrongMarker).build()))))
        .hasMessageContaining("recovery fence differs");
    transaction.executeWithoutResult(
        ignored -> repository.finishRecovery(verifiedRecovery(recovery, response)));
    assertThat(repository.read(request).getCanonicalRosterReceipt())
        .isEqualTo(originalReceipt.toByteString());
    assertThat(repository.read(request).getTerminalOutcome()).isEqualTo("DENIED");
    var retained = retainedRow(request);
    transaction.executeWithoutResult(
        ignored -> repository.finishRecovery(verifiedRecovery(recovery, response)));
    assertThat(retainedRow(request)).isEqualTo(retained);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.finishRecovery(
                            verifiedRecovery(
                                recovery,
                                response.toBuilder()
                                    .setAdmissionUuid(UUID.randomUUID().toString())
                                    .build()))))
        .hasMessageContaining("RECEIPT_CONFLICT");
    Thread.sleep(5L);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(request, current(request))))
        .isTrue();
    assertThat(CanonicalGameplayRosterReferenceEvidence.requireRetirement(repository.read(request)))
        .isEqualTo(repository.read(request));
  }

  @Test
  void frozenActorWithoutAssignmentReceiptClosesOnlyWithExactRecoveredRoster() throws Exception {
    var request = request();
    var actor = UUID.randomUUID();
    var roster =
        receipt(request).toBuilder()
            .addActors(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor.newBuilder()
                    .setCharacterUuid(actor.toString())
                    .setDisplayName("Chosen")
                    .setActorKind(
                        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind
                            .CANONICAL_ROSTER_ACTOR_KIND_PLAYER))
            .build();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(request);
          repository.reserveSelectionInput(request, selectionInput());
          repository.markRosterReadStarted(request);
          repository.record(request, roster);
          repository.selectOriginal(request, actor);
        });
    Thread.sleep(5L);
    var recovery =
        transaction
            .execute(ignored -> repository.beginRecoveries(request, current(request)))
            .getFirst();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.selectOriginal(request, actor)))
        .hasMessageContaining("RECOVERY_FENCED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.recordSelectedAssignment(
                            request,
                            net.firedevops.firemud.entitymanagement.v1
                                .CanonicalGameplayRosterSelectedAssignmentResponse
                                .getDefaultInstance())))
        .hasMessageContaining("RECOVERY_FENCED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.finishRecovery(
                            verifiedRecovery(recovery, recoveryResponse(recovery, null)))))
        .hasMessageContaining("ROSTER_CONFLICT");
    transaction.executeWithoutResult(
        ignored ->
            repository.finishRecovery(
                verifiedRecovery(recovery, recoveryResponse(recovery, roster))));
    var closed = repository.read(request);
    assertThat(closed.getSelectedCharacterUuid()).isEqualTo(actor.toString());
    assertThat(closed.getCanonicalSelectedAssignmentReceipt()).isEmpty();
    assertThat(closed.getTerminalOutcome()).isEqualTo("DENIED");
    Thread.sleep(5L);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(request, current(request))))
        .isTrue();
  }

  @Test
  void positiveNoCaptureRecoveryAllowsClosureWithoutInventingRosterOrSelection() throws Exception {
    var request = request();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(request);
          repository.reserveSelectionInput(request, selectionInput());
          repository.markRosterReadStarted(request);
        });
    Thread.sleep(5L);
    var recovery =
        transaction
            .execute(ignored -> repository.beginRecoveries(request, current(request)))
            .getFirst();
    transaction.executeWithoutResult(
        ignored ->
            repository.finishRecovery(
                verifiedRecovery(recovery, recoveryResponse(recovery, null))));
    var result = repository.read(request);
    assertThat(result.getCanonicalRosterReceipt()).isEmpty();
    assertThat(result.getSelectedCharacterUuid()).isEmpty();
    assertThat(result.getTerminalOutcome()).isEqualTo("DENIED");
    Thread.sleep(5L);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(request, current(request))))
        .isTrue();
  }

  @Test
  void storedReplayHorizonAndExactSupersessionAreRequiredBeforeRecovery() throws Exception {
    repository = repository(policy(6000L, 5000L, 5000L));
    var request = request();
    transaction.executeWithoutResult(ignored -> repository.begin(request));
    // Later lowered configuration must not shorten the already retained target's horizon.
    repository = repository(policy(1L, 1L, 1L));
    assertThat(
            transaction.<List<CanonicalGameplayRosterRecoveryRequest>>execute(
                ignored -> repository.beginRecoveries(request, current(request))))
        .isEmpty();
    Thread.sleep(5500L);
    var oldTarget = CanonicalGameplayRosterReferenceEvidence.target(request);
    var samePointer =
        request.toBuilder()
            .setCanonicalTarget(
                oldTarget.toBuilder()
                    .setPointerVersion(oldTarget.getPointerVersion() - 1L)
                    .build()
                    .toByteString())
            .build();
    assertThat(
            transaction.<List<CanonicalGameplayRosterRecoveryRequest>>execute(
                ignored -> repository.beginRecoveries(request, current(samePointer))))
        .isEmpty();
    var wrongNamespace = request.toBuilder().setTargetNamespace("different").build();
    assertThat(
            transaction.<List<CanonicalGameplayRosterRecoveryRequest>>execute(
                ignored -> repository.beginRecoveries(request, current(wrongNamespace))))
        .isEmpty();
    var regressedCatalog =
        request.toBuilder()
            .setCanonicalTarget(
                oldTarget.toBuilder()
                    .setCatalogRevision(oldTarget.getCatalogRevision() - 2L)
                    .build()
                    .toByteString())
            .build();
    assertThat(
            transaction.<List<CanonicalGameplayRosterRecoveryRequest>>execute(
                ignored -> repository.beginRecoveries(request, current(regressedCatalog))))
        .isEmpty();
    var regressedWorld =
        request.toBuilder()
            .setCanonicalTarget(
                oldTarget.toBuilder()
                    .setActiveWorldEpoch(oldTarget.getActiveWorldEpoch() - 2L)
                    .build()
                    .toByteString())
            .build();
    assertThat(
            transaction.<List<CanonicalGameplayRosterRecoveryRequest>>execute(
                ignored -> repository.beginRecoveries(request, current(regressedWorld))))
        .isEmpty();
    assertThat(repository.read(request).getRecoveryUuid()).isEmpty();
    assertThat(
            transaction.<List<CanonicalGameplayRosterRecoveryRequest>>execute(
                ignored -> repository.beginRecoveries(request, current(request))))
        .hasSize(1);
  }

  @Test
  void ownerRetriesUnknownEntityOutsideSqlAndRetainsPositiveClosureThroughLocalMinimum()
      throws Exception {
    repository = repository(policy(5000L, 1L, 1L));
    var request = request();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(request);
          repository.reserveSelectionInput(request, selectionInput());
          repository.markRosterReadStarted(request);
        });
    Thread.sleep(5L);
    var routes =
        org.mockito.Mockito.mock(
            net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService.class);
    var admissions = org.mockito.Mockito.mock(CanonicalInitialAdmissionRepository.class);
    var current = current(request);
    var snapshot = currentSnapshot(request);
    org.mockito.Mockito.when(routes.readCurrentTarget(current.canonicalTenantId()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              return current;
            });
    org.mockito.Mockito.when(
            admissions.readCurrentOpenSnapshotForRealm(
                request.getTargetNamespace(), current.canonicalTenantId(), current.realmId()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              return snapshot;
            });
    org.mockito.Mockito.when(admissions.lockExactCurrentOpenSnapshot(snapshot))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isTrue();
              return snapshot;
            });
    var client = org.mockito.Mockito.mock(CanonicalGameplayRosterRecoveryClient.class);
    var metrics = new SimpleMeterRegistry();
    org.mockito.Mockito.when(client.read(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isSynchronizationActive())
                  .isFalse();
              throw io.grpc.Status.UNAVAILABLE.asRuntimeException();
            });
    var owner =
        new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner(
            repository,
            transaction,
            org.mockito.Mockito.mock(
                net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                    .class),
            routes,
            admissions,
            client,
            metrics);
    assertThat(owner.retireTarget(request)).isFalse();
    var marker = repository.read(request).getRecoveryUuid();
    assertThat(marker).isNotEmpty();
    assertThat(
            metrics
                .get("firemud.canonical.roster.reference.recovery.failure")
                .tag("kind", "unavailable")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(repository.read(request).getTerminalOutcome()).isEmpty();
    assertThatThrownBy(
            () -> transaction.execute(ignored -> repository.record(request, receipt(request))))
        .hasMessageContaining("RECOVERY_FENCED");
    org.mockito.Mockito.doAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isSynchronizationActive())
                  .isFalse();
              CanonicalGameplayRosterRecoveryRequest retry = call.getArgument(0);
              assertThat(retry.getRecoveryUuid()).isEqualTo(marker);
              return verifiedRecovery(retry, recoveryResponse(retry, null));
            })
        .when(client)
        .read(org.mockito.ArgumentMatchers.any());
    assertThat(owner.retireTarget(request)).isFalse();
    assertThat(repository.read(request).getRecoveryUuid()).isEqualTo(marker);
    assertThat(
            metrics
                .get("firemud.canonical.roster.reference.recovery.failure")
                .tag("kind", "unavailable")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(repository.read(request).getTerminalOutcome()).isEqualTo("DENIED");
    assertThat(repository.read(request).getRetirementUuid()).isEmpty();
    Thread.sleep(5500L);
    assertThat(owner.retireTarget(request)).isTrue();
    org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2))
        .read(org.mockito.ArgumentMatchers.any());
    assertThatThrownBy(() -> transaction.execute(ignored -> owner.retireTarget(request)))
        .hasMessageContaining("NO_AMBIENT_TRANSACTION");
  }

  @Test
  void recoveryPageAdvancesPastFailedBatchWithoutReplacingRecoveryIdentity() throws Exception {
    repository = repository(policy(5000L, 1L, 1L));
    var request = request();
    var originals = new ArrayList<GetCanonicalGameplayRosterReferenceRequest>();
    transaction.executeWithoutResult(
        ignored -> {
          for (int i = 0; i < 9; i++) {
            var original =
                i == 0
                    ? request
                    : request.toBuilder().setOperationUuid(UUID.randomUUID().toString()).build();
            originals.add(original);
            repository.begin(original);
            repository.reserveSelectionInput(original, selectionInput());
            repository.markRosterReadStarted(original);
          }
        });
    Thread.sleep(5L);
    var routes =
        org.mockito.Mockito.mock(
            net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService.class);
    var admissions = org.mockito.Mockito.mock(CanonicalInitialAdmissionRepository.class);
    var current = current(request);
    var snapshot = currentSnapshot(request);
    org.mockito.Mockito.when(routes.readCurrentTarget(current.canonicalTenantId()))
        .thenReturn(current);
    org.mockito.Mockito.when(
            admissions.readCurrentOpenSnapshotForRealm(
                request.getTargetNamespace(), current.canonicalTenantId(), current.realmId()))
        .thenReturn(snapshot);
    org.mockito.Mockito.when(admissions.lockExactCurrentOpenSnapshot(snapshot))
        .thenReturn(snapshot);
    var client = org.mockito.Mockito.mock(CanonicalGameplayRosterRecoveryClient.class);
    var failedBatchCalls = new ArrayList<CanonicalGameplayRosterRecoveryRequest>();
    var retryCalls = new ArrayList<CanonicalGameplayRosterRecoveryRequest>();
    var failuresRemaining = new AtomicInteger(8);
    org.mockito.Mockito.when(client.read(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            call -> {
              CanonicalGameplayRosterRecoveryRequest recovery = call.getArgument(0);
              if (failuresRemaining.getAndDecrement() > 0) {
                failedBatchCalls.add(recovery);
                throw io.grpc.Status.UNAVAILABLE.asRuntimeException();
              }
              retryCalls.add(recovery);
              return verifiedRecovery(recovery, recoveryResponse(recovery, null));
            });
    var metrics = new SimpleMeterRegistry();
    var owner =
        new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner(
            repository,
            transaction,
            org.mockito.Mockito.mock(
                net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                    .class),
            routes,
            admissions,
            client,
            metrics);
    assertThat(owner.retireTarget(request)).isFalse();
    assertThat(failedBatchCalls).hasSize(8);
    assertThat(retryCalls).isEmpty();
    assertThat(
            metrics
                .get("firemud.canonical.roster.reference.recovery.failure")
                .tag("kind", "unavailable")
                .counter()
                .count())
        .isEqualTo(8.0);
    var failedOperationIds =
        failedBatchCalls.stream()
            .map(recovery -> recovery.getOriginalSelection().getOperationUuid())
            .collect(java.util.stream.Collectors.toSet());
    var laterOriginal =
        originals.stream()
            .filter(original -> !failedOperationIds.contains(original.getOperationUuid()))
            .findFirst()
            .orElseThrow();
    var firstAttemptState = new HashMap<String, List<String>>();
    for (var recovery : failedBatchCalls) {
      String operationUuid = recovery.getOriginalSelection().getOperationUuid();
      var row = recoveryScheduleRow(operationUuid);
      assertThat(row.get("recovery_uuid", String.class)).isEqualTo(recovery.getRecoveryUuid());
      firstAttemptState.put(
          operationUuid,
          List.of(
              row.get("recovery_uuid", String.class),
              row.get("recovery_started_at", String.class),
              row.get("recovery_last_attempt_at", String.class)));
      assertThat(repository.read(recovery.getOriginalSelection()).getTerminalOutcome()).isEmpty();
    }
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM game_session_canonical_roster_selection WHERE recovery_uuid IS NOT NULL")
                .get(0, Long.class))
        .isEqualTo(8L);

    // Reconstruct the repository between failed attempts; the ninth untouched operation must
    // enter the next bounded page while the original UUID and start time remain immutable.
    repository = repository(policy(5000L, 1L, 1L));
    owner =
        new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner(
            repository,
            transaction,
            org.mockito.Mockito.mock(
                net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                    .class),
            routes,
            admissions,
            client,
            metrics);
    assertThat(owner.retireTarget(request)).isFalse();
    assertThat(retryCalls).hasSize(8);
    assertThat(
            retryCalls.stream().map(recovery -> recovery.getOriginalSelection().getOperationUuid()))
        .contains(laterOriginal.getOperationUuid());
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM game_session_canonical_roster_selection WHERE recovery_uuid IS NOT NULL")
                .get(0, Long.class))
        .isEqualTo(9L);
    var retriedOperationIds =
        retryCalls.stream()
            .map(recovery -> recovery.getOriginalSelection().getOperationUuid())
            .collect(java.util.stream.Collectors.toSet());
    for (var entry : firstAttemptState.entrySet()) {
      var row = recoveryScheduleRow(entry.getKey());
      assertThat(row.get("recovery_uuid", String.class)).isEqualTo(entry.getValue().get(0));
      assertThat(row.get("recovery_started_at", String.class)).isEqualTo(entry.getValue().get(1));
      if (retriedOperationIds.contains(entry.getKey())) {
        assertThat(
                dsl.fetchExists(
                    DSL.selectOne()
                        .from("game_session_canonical_roster_selection")
                        .where(
                            "operation_uuid = ? AND recovery_last_attempt_at > ?::timestamptz",
                            UUID.fromString(entry.getKey()),
                            entry.getValue().get(2))))
            .isTrue();
      } else {
        assertThat(row.get("recovery_last_attempt_at", String.class))
            .isEqualTo(entry.getValue().get(2));
      }
    }

    // A recovery page keeps the existing target-fence exclusion: another page cannot select
    // rows until the first page transaction commits its scheduled attempt timestamps.
    CountDownLatch pageHeld = new CountDownLatch(1);
    CountDownLatch releasePage = new CountDownLatch(1);
    CountDownLatch waiterStarted = new CountDownLatch(1);
    AtomicInteger holderPid = new AtomicInteger();
    AtomicInteger waiterPid = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var holder =
          executor.submit(
              () ->
                  transaction.execute(
                      ignored -> {
                        holderPid.set(
                            dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                        var page = repository.beginRecoveries(request, current(request));
                        pageHeld.countDown();
                        try {
                          if (!releasePage.await(15L, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("fixture timeout");
                          }
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        return page;
                      }));
      assertThat(pageHeld.await(5L, TimeUnit.SECONDS)).isTrue();
      var waiter =
          executor.submit(
              () ->
                  transaction.execute(
                      ignored -> {
                        waiterPid.set(
                            dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                        waiterStarted.countDown();
                        return repository.beginRecoveries(request, current(request));
                      }));
      assertThat(waiterStarted.await(5L, TimeUnit.SECONDS)).isTrue();
      awaitBackendBlockedBy(dsl, waiterPid.get(), holderPid.get());
      releasePage.countDown();
      assertThat(holder.get(5L, TimeUnit.SECONDS)).hasSize(1);
      assertThat(waiter.get(5L, TimeUnit.SECONDS)).hasSize(1);
    } finally {
      releasePage.countDown();
    }
  }

  @Test
  void purgedOriginalCannotReturnReservedWhenFreshSourceOrLockedCurrentProofFails()
      throws Exception {
    var published =
        support.net.firedevops.firemud.gamesession.PublishedRealmPolicyEvidenceFixture
            .canonicalPublishedRoute();
    var request = publishedRequest(published);
    var candidate = retireOriginal(request);
    Thread.sleep(5L);
    assertThat(transaction.<Boolean>execute(ignored -> repository.purgeRetiredTarget(candidate)))
        .isTrue();
    var sources =
        org.mockito.Mockito.mock(
            net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                .class);
    var admissions = org.mockito.Mockito.mock(CanonicalInitialAdmissionRepository.class);
    var owner = referenceOwner(sources, admissions);
    org.mockito.Mockito.when(
            sources.readCurrent(
                org.mockito.ArgumentMatchers.any(
                    net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence
                        .Request.class)))
        .thenThrow(
            new net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                .StorageUnavailableException("source unavailable"));
    assertThatThrownBy(() -> owner.begin(request)).hasMessageContaining("source unavailable");
    assertNoOriginalOrFence();
    org.mockito.Mockito.doAnswer(
            call -> {
              var source = sourceEvidence(call.getArgument(0), published);
              return new net.firedevops.firemud.common.world
                  .CanonicalGameplayRosterOwnerReadEvidence(
                  source.request(),
                  "0".repeat(64),
                  source.gameSessionOwnerProof(),
                  source.publishedPolicySetEvidence());
            })
        .when(sources)
        .readCurrent(
            org.mockito.ArgumentMatchers.any(
                net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request
                    .class));
    assertThatThrownBy(() -> owner.begin(request)).hasMessageContaining("SOURCE_MISMATCH");
    assertNoOriginalOrFence();
    org.mockito.Mockito.doAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              return sourceEvidence(call.getArgument(0), published);
            })
        .when(sources)
        .readCurrent(
            org.mockito.ArgumentMatchers.any(
                net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request
                    .class));
    org.mockito.Mockito.when(
            admissions.lockExactCurrentOpenSnapshot(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isTrue();
              CanonicalInitialAdmissionRepository.CurrentOpenSnapshot qualified =
                  call.getArgument(0);
              assertThat(qualified.admissionPointerSnapshotDigest())
                  .isEqualTo(published.route().admissionPointerSnapshotDigest());
              assertThat(qualified.ownerProof().proofDigest())
                  .isEqualTo(published.route().ownerProofDigest());
              assertThat(qualified.ownerProof().holdId()).isEqualTo(published.route().holdId());
              throw new CanonicalInitialAdmissionRepository
                  .CanonicalInitialAdmissionReconciliationRequiredException(
                  "current changed before reservation commit");
            });
    assertThatThrownBy(() -> owner.begin(request)).hasMessageContaining("current changed");
    assertNoOriginalOrFence();
  }

  @Test
  void freshReservationQualifiesOutsideSqlAndLocksThatExactSourceBeforeCommit() {
    var published =
        support.net.firedevops.firemud.gamesession.PublishedRealmPolicyEvidenceFixture
            .canonicalPublishedRoute();
    var request = publishedRequest(published);
    var sources =
        org.mockito.Mockito.mock(
            net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                .class);
    var admissions = org.mockito.Mockito.mock(CanonicalInitialAdmissionRepository.class);
    var observed =
        new java.util.concurrent.atomic.AtomicReference<
            net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence>();
    org.mockito.Mockito.when(
            sources.readCurrent(
                org.mockito.ArgumentMatchers.any(
                    net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence
                        .Request.class)))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              assertNoOriginalOrFence();
              var source = sourceEvidence(call.getArgument(0), published);
              observed.set(source);
              return source;
            });
    org.mockito.Mockito.when(
            admissions.lockExactCurrentOpenSnapshot(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isTrue();
              CanonicalInitialAdmissionRepository.CurrentOpenSnapshot qualified =
                  call.getArgument(0);
              assertThat(repository.find(request)).isPresent();
              assertThat(qualified.ownerProof().holdBindingDigest())
                  .isEqualTo(
                      observed.get().gameSessionOwnerProof().holdIdentity().holdBindingDigest());
              assertThat(qualified.ownerProof().initialAdmissionRequestId())
                  .isEqualTo(
                      observed
                          .get()
                          .gameSessionOwnerProof()
                          .holdIdentity()
                          .request()
                          .initialAdmissionRequestId());
              assertThat(qualified.admissionPointerSnapshotDigest())
                  .isEqualTo(observed.get().admissionPointerSnapshotDigest());
              return qualified;
            });
    var owner = referenceOwner(sources, admissions);
    assertThat(owner.begin(request).getCanonicalRosterReceipt()).isEmpty();
    assertThat(repository.find(request)).isPresent();
    org.mockito.Mockito.verify(admissions)
        .lockExactCurrentOpenSnapshot(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void purgeBetweenExactLookupAndLockedReplayNeverReservesReplacementRows() throws Exception {
    var request = request();
    var candidate = retireOriginal(request);
    assertThat(repository.find(request)).isPresent();
    Thread.sleep(5L);
    assertThat(transaction.<Boolean>execute(ignored -> repository.purgeRetiredTarget(candidate)))
        .isTrue();
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.replay(request)))
        .hasMessageContaining("UNAVAILABLE_OR_RETIRED");
    assertThat(repository.find(request)).isEmpty();
    assertNoOriginalOrFence();
  }

  @Test
  void historicalReceiptReplaySkipsFreshSourceButUnissuedOwnerReadsRequireIt() throws Exception {
    var sources =
        org.mockito.Mockito.mock(
            net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                .class);
    var owner =
        referenceOwner(
            sources, org.mockito.Mockito.mock(CanonicalInitialAdmissionRepository.class));
    var request = request();
    transaction.executeWithoutResult(ignored -> repository.begin(request));
    org.mockito.Mockito.when(
            sources.readCurrent(
                org.mockito.ArgumentMatchers.any(
                    net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence
                        .Request.class)))
        .thenThrow(
            new net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                .StaleAuthorityException("target superseded"));
    assertThatThrownBy(() -> owner.read(request)).hasMessageContaining("target superseded");
    var roster = receipt(request);
    transaction.executeWithoutResult(ignored -> repository.record(request, roster));
    assertThat(owner.begin(request).getCanonicalRosterReceipt()).isEqualTo(roster.toByteString());
    assertThat(owner.read(request).getCanonicalRosterReceipt()).isEqualTo(roster.toByteString());
    org.mockito.Mockito.verify(sources, org.mockito.Mockito.times(1))
        .readCurrent(
            org.mockito.ArgumentMatchers.any(
                net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request
                    .class));
    var unissued = request.toBuilder().setOperationUuid(UUID.randomUUID().toString()).build();
    transaction.executeWithoutResult(ignored -> repository.begin(unissued));
    Thread.sleep(5L);
    transaction.execute(ignored -> repository.beginRecoveries(request, current(request)));
    assertThat(owner.read(request).getRecoveryUuid()).isNotEmpty();
    assertThat(owner.read(unissued).getRecoveryUuid()).isNotEmpty();
    assertThat(owner.read(unissued).getCanonicalRosterReceipt()).isEmpty();
    org.mockito.Mockito.verify(sources, org.mockito.Mockito.times(1))
        .readCurrent(
            org.mockito.ArgumentMatchers.any(
                net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request
                    .class));
  }

  @Test
  void deniedDispatchedReadsKeepTheirOriginalDenialAndActualRepositoryRecoveryProtection()
      throws Exception {
    for (boolean selectedRead : List.of(false, true)) {
      var published =
          support.net.firedevops.firemud.gamesession.PublishedRealmPolicyEvidenceFixture
              .canonicalPublishedRoute();
      var request = publishedRequest(published);
      var actor = UUID.randomUUID();
      var roster =
          receipt(request).toBuilder()
              .addActors(
                  net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor
                      .newBuilder()
                      .setCharacterUuid(actor.toString())
                      .setDisplayName("Selected")
                      .setActorKind(
                          net.firedevops.firemud.entitymanagement.v1
                              .CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER))
              .build();
      transaction.executeWithoutResult(
          ignored -> {
            repository.begin(request);
            repository.reserveSelectionInput(request, selectionInput());
            repository.markRosterReadStarted(request);
            if (selectedRead) {
              repository.record(request, roster);
              repository.selectOriginal(request, actor);
            }
          });
      var routes =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService.class);
      var publications =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                  .class);
      var client =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient.class);
      org.mockito.Mockito.when(routes.readCurrentTarget(published.route().canonicalTenantId()))
          .thenReturn(published.route());
      org.mockito.Mockito.when(publications.readCurrent(published.route())).thenReturn(published);
      if (selectedRead) {
        org.mockito.Mockito.when(
                client.listPreseededRoster(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(
                new net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient
                    .PreseededRosterSnapshot(
                    UUID.fromString(request.getCanonicalAccountUuid()),
                    UUID.fromString(roster.getSnapshotUuid()),
                    roster.getSnapshotDigest(),
                    roster.getTarget(),
                    List.of(
                        new net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient
                            .RosterActor(actor, "Selected"))));
        org.mockito.Mockito.when(
                client.readSelectedPreseededAssignment(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(io.grpc.Status.FAILED_PRECONDITION.asRuntimeException());
      } else {
        org.mockito.Mockito.when(
                client.listPreseededRoster(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(io.grpc.Status.FAILED_PRECONDITION.asRuntimeException());
      }
      // The terminal write below executes the real owner SQL and its missing-receipt gate.
      org.mockito.Mockito.doAnswer(
              call -> {
                assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive())
                    .isFalse();
                transaction.executeWithoutResult(
                    ignored -> repository.completeSelection(request, call.getArgument(2)));
                return null;
              })
          .when(client)
          .completeSelection(
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any());
      var context =
          net.firedevops.firemud.shared.v1.PlayerExecutionContext.newBuilder()
              .setAccountId(request.getCanonicalAccountUuid())
              .setSessionId(request.getSessionUuid())
              .setRequestId(request.getOperationUuid())
              .setTenantId(published.route().canonicalTenantId().toString())
              .setRealmId(published.route().realmId().toString())
              .setPlayableStateNamespaceId(published.route().playableStateNamespaceId().toString())
              .setPlayableStateScope("SHARED")
              .setGameInstanceId(published.route().canonicalGameInstanceId().toString())
              .build();
      var service =
          new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterSelectionService(
              routes, publications, client);
      assertThat(service.select(context, null, null))
          .isEqualTo(
              new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterSelectionService
                  .Denied(
                  net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterSelectionService
                      .Denial.ROUTE_CHANGED));
      org.mockito.Mockito.verify(client)
          .completeSelection(
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.eq(
                  CanonicalGameplayRosterReferenceRepository.SelectionOutcome.DENIED));
      assertThat(repository.read(request).getTerminalOutcome()).isEmpty();
      assertThat(
              transaction.<Boolean>execute(
                  ignored -> repository.sealTargetRetirement(request, current(request))))
          .isFalse();
    }
  }

  @Test
  void allRequiredObservationsAndStoredHorizonProtectOriginalsAndLostReplyReplaysAfterPurge()
      throws Exception {
    var first = request();
    var second =
        first.toBuilder()
            .setOperationUuid(UUID.randomUUID().toString())
            .setSessionUuid(UUID.randomUUID().toString())
            .build();
    var roster = receipt(first);
    for (var original : List.of(first, second)) {
      transaction.executeWithoutResult(
          ignored -> {
            repository.begin(original);
            repository.reserveSelectionInput(original, selectionInput());
            repository.markRosterReadStarted(original);
            repository.record(original, roster);
            repository.completeSelection(
                original, CanonicalGameplayRosterReferenceRepository.SelectionOutcome.MENU);
          });
    }
    var originalFenceUuid =
        dsl.fetchSingle(
                "SELECT target_fence_uuid FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
                UUID.fromString(first.getOperationUuid()))
            .get("target_fence_uuid", UUID.class);
    assertThat(
            dsl.fetchSingle(
                    "SELECT target_fence_uuid FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
                    UUID.fromString(second.getOperationUuid()))
                .get("target_fence_uuid", UUID.class))
        .isEqualTo(originalFenceUuid);
    for (var original : List.of(first, second)) {
      assertThat(
              dsl.fetchSingle(
                      "SELECT owner_fence FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
                      UUID.fromString(original.getOperationUuid()))
                  .get("owner_fence", Long.class))
          .isEqualTo(1L);
    }
    Thread.sleep(5L);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(first, current(first))))
        .isTrue();
    for (var original : List.of(first, second)) {
      assertThat(
              dsl.fetchSingle(
                      "SELECT target_fence_uuid FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
                      UUID.fromString(original.getOperationUuid()))
                  .get("target_fence_uuid", UUID.class))
          .isEqualTo(originalFenceUuid);
    }
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_session_canonical_roster_selection SET owner_fence = 2 WHERE operation_uuid = ?",
                    UUID.fromString(first.getOperationUuid())))
        .isInstanceOf(DataAccessException.class);
    for (var original : List.of(first, second)) {
      assertThat(
              dsl.fetchSingle(
                      "SELECT owner_fence FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
                      UUID.fromString(original.getOperationUuid()))
                  .get("owner_fence", Long.class))
          .isEqualTo(1L);
    }
    var retiredFence =
        dsl.fetchSingle(
            "SELECT owner_fence, fence_state FROM game_session_canonical_roster_target_fences WHERE target_fence_uuid = ?",
            originalFenceUuid);
    assertThat(retiredFence.get("owner_fence", Long.class)).isEqualTo(1L);
    assertThat(retiredFence.get("fence_state", String.class)).isEqualTo("RETIRED");
    var candidate =
        new CanonicalGameplayRosterReferenceRepository.TargetCandidate(
            UUID.fromString(repository.read(first).getTargetFenceUuid()), first, "RETIRED");
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM game_session_canonical_roster_retirement_transfers")
                .get(0, Long.class))
        .isEqualTo(2L);
    Thread.sleep(5L);
    // Entity may be unavailable indefinitely: a passed target horizon alone releases nothing.
    assertThat(transaction.<Boolean>execute(ignored -> repository.purgeRetiredTarget(candidate)))
        .isFalse();
    assertThatThrownBy(() -> dsl.execute("DELETE FROM game_session_canonical_roster_selection"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> dsl.execute("DELETE FROM game_session_canonical_roster_target_fences"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> dsl.execute("DELETE FROM game_session_canonical_roster_retirement_transfers"))
        .isInstanceOf(DataAccessException.class);
    var firstRequest = acknowledgement(first, repository.read(first), UUID.randomUUID(), 250L);
    var secondRequest = acknowledgement(second, repository.read(first), UUID.randomUUID(), 250L);
    var unrecordedConfirmation =
        CanonicalGameplayRosterRetirementEvidence.confirmation(
            firstRequest,
            CanonicalGameplayRosterRetirementEvidence.response(
                firstRequest, UUID.randomUUID(), 250L));
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.confirmRetirementAcknowledgement(
                            unrecordedConfirmation, entityPrincipal())))
        .hasMessageContaining("ACKNOWLEDGEMENT_CONFLICT");
    var firstAck =
        transaction.execute(
            ignored -> repository.acknowledgeRetirement(firstRequest, entityPrincipal()));
    assertThat(transaction.<Boolean>execute(ignored -> repository.purgeRetiredTarget(candidate)))
        .isFalse();
    var beforeReplay = transferRow(first);
    assertThat(
            transaction.<CanonicalGameplayRosterRetirementAcknowledgementResponse>execute(
                ignored -> repository.acknowledgeRetirement(firstRequest, entityPrincipal())))
        .isEqualTo(firstAck);
    assertThat(transferRow(first)).isEqualTo(beforeReplay);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.acknowledgeRetirement(
                            firstRequest.toBuilder()
                                .setAdmissionUuid(UUID.randomUUID().toString())
                                .build(),
                            entityPrincipal())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.acknowledgeRetirement(
                            firstRequest,
                            "spiffe://firemud/ns/other/sa/entity-management-service")))
        .isInstanceOf(IllegalArgumentException.class);
    var secondAck =
        transaction.execute(
            ignored -> repository.acknowledgeRetirement(secondRequest, entityPrincipal()));
    assertThat(transaction.<Boolean>execute(ignored -> repository.purgeRetiredTarget(candidate)))
        .isTrue();
    assertNoOriginalOrFence();
    // Crash/lost reply: the original rows are gone, but the actual first response is unchanged.
    assertThat(
            transaction.<CanonicalGameplayRosterRetirementAcknowledgementResponse>execute(
                ignored -> repository.acknowledgeRetirement(firstRequest, entityPrincipal())))
        .isEqualTo(firstAck);
    Thread.sleep(275L);
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementAcknowledgements(8)))
        .isZero();
    assertThatThrownBy(
            () -> dsl.execute("DELETE FROM game_session_canonical_roster_retirement_transfers"))
        .isInstanceOf(DataAccessException.class);
    var confirmation =
        CanonicalGameplayRosterRetirementEvidence.confirmation(firstRequest, firstAck);
    assertThat(
            transaction.<CanonicalGameplayRosterRetirementConfirmationResponse>execute(
                ignored ->
                    repository.confirmRetirementAcknowledgement(confirmation, entityPrincipal())))
        .isEqualTo(CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmation));
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementAcknowledgements(8)))
        .isZero();
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.confirmRetirementAcknowledgement(
                            confirmation.toBuilder()
                                .setAcknowledgement(
                                    firstAck.toBuilder()
                                        .setAcknowledgementUuid(UUID.randomUUID().toString()))
                                .build(),
                            entityPrincipal())))
        .isInstanceOf(IllegalArgumentException.class);
    Thread.sleep(275L);
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementAcknowledgements(1)))
        .isEqualTo(1);
    assertThat(
            transaction.<CanonicalGameplayRosterRetirementConfirmationResponse>execute(
                ignored ->
                    repository.confirmRetirementAcknowledgement(confirmation, entityPrincipal())))
        .isEqualTo(CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmation));
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored -> repository.acknowledgeRetirement(firstRequest, entityPrincipal())))
        .isInstanceOf(
            CanonicalGameplayRosterReferenceRepository.OriginalUnavailableException.class);
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM game_session_canonical_roster_retirement_transfers")
                .get(0, Long.class))
        .isEqualTo(1L);
    transaction.executeWithoutResult(
        ignored ->
            repository.confirmRetirementAcknowledgement(
                CanonicalGameplayRosterRetirementEvidence.confirmation(secondRequest, secondAck),
                entityPrincipal()));
  }

  @Test
  void confirmationWithoutInventoryIsOnlyANoopAndCannotCreateFirstAckOrReleaseTarget()
      throws Exception {
    var original = request();
    var candidate = retireOriginal(original); // Proven never issued; no Entity transfer required.
    var request = acknowledgement(original, repository.read(original), UUID.randomUUID(), 1L);
    var unrecorded =
        CanonicalGameplayRosterRetirementEvidence.response(request, UUID.randomUUID(), 1L);
    var confirmation = CanonicalGameplayRosterRetirementEvidence.confirmation(request, unrecorded);
    transaction.executeWithoutResult(
        ignored -> repository.confirmRetirementAcknowledgement(confirmation, entityPrincipal()));
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM game_session_canonical_roster_retirement_transfers")
                .get(0, Long.class))
        .isZero();
    assertThat(repository.read(original).getTargetFenceUuid())
        .isEqualTo(candidate.fenceUuid().toString());
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored -> repository.acknowledgeRetirement(request, entityPrincipal())))
        .isInstanceOf(
            CanonicalGameplayRosterReferenceRepository.OriginalUnavailableException.class);
  }

  @Test
  void storedMinimumStillGatesPurgeAndConfirmationBeforePurgeCannotShortenReceiptHorizon()
      throws Exception {
    repository = repository(policy(5_000L, 1L, 1L));
    var original = request();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(original);
          repository.reserveSelectionInput(original, selectionInput());
          repository.record(original, receipt(original));
          repository.completeSelection(
              original, CanonicalGameplayRosterReferenceRepository.SelectionOutcome.MENU);
        });
    Awaitility.await()
        .alias("database clock reaches the stored roster minimum before retirement")
        .pollInterval(50L, TimeUnit.MILLISECONDS)
        .atMost(15L, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(
                        dsl.fetchSingle(
                                "SELECT s.terminal_at + f.minimum_retention_millis * interval '1 millisecond' <= clock_timestamp() "
                                    + "FROM game_session_canonical_roster_selection s "
                                    + "JOIN game_session_canonical_roster_target_fences f USING (target_fence_uuid) "
                                    + "WHERE s.operation_uuid = ?",
                                UUID.fromString(original.getOperationUuid()))
                            .get(0, Boolean.class))
                    .isTrue());
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(original, current(original))))
        .isTrue();
    repository = repository(policy(1L, 1L, 1L));
    var request = acknowledgement(original, repository.read(original), UUID.randomUUID(), 1L);
    var ack =
        transaction.execute(
            ignored -> repository.acknowledgeRetirement(request, entityPrincipal()));
    assertThat(ack.getMinimumRetentionMillis()).isEqualTo(5_000L);
    var confirmation = CanonicalGameplayRosterRetirementEvidence.confirmation(request, ack);
    transaction.executeWithoutResult(
        ignored -> repository.confirmRetirementAcknowledgement(confirmation, entityPrincipal()));
    var candidate =
        new CanonicalGameplayRosterReferenceRepository.TargetCandidate(
            UUID.fromString(repository.read(original).getTargetFenceUuid()), original, "RETIRED");
    assertThat(
            dsl.fetchSingle(
                    "SELECT retired_at + minimum_retention_millis * interval '1 millisecond' > clock_timestamp() "
                        + "FROM game_session_canonical_roster_target_fences WHERE target_fence_uuid = ?",
                    candidate.fenceUuid())
                .get(0, Boolean.class))
        .isTrue();
    assertThat(transaction.<Boolean>execute(ignored -> repository.purgeRetiredTarget(candidate)))
        .isFalse();
    assertThatThrownBy(() -> dsl.execute("DELETE FROM game_session_canonical_roster_selection"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_session_canonical_roster_retirement_transfers SET target_purged_at = clock_timestamp()"))
        .isInstanceOf(DataAccessException.class);
    Awaitility.await()
        .alias("database clock reaches the stored target retirement horizon")
        .pollInterval(50L, TimeUnit.MILLISECONDS)
        .atMost(15L, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(
                        dsl.fetchSingle(
                                "SELECT retired_at + minimum_retention_millis * interval '1 millisecond' <= clock_timestamp() "
                                    + "FROM game_session_canonical_roster_target_fences WHERE target_fence_uuid = ?",
                                candidate.fenceUuid())
                            .get(0, Boolean.class))
                    .isTrue());
    assertThat(transaction.<Boolean>execute(ignored -> repository.purgeRetiredTarget(candidate)))
        .isTrue();
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementAcknowledgements(8)))
        .isZero();
    assertThatThrownBy(
            () -> dsl.execute("DELETE FROM game_session_canonical_roster_retirement_transfers"))
        .isInstanceOf(DataAccessException.class);
    Awaitility.await()
        .alias("database clock reaches the stored confirmed receipt cleanup horizon")
        .pollInterval(50L, TimeUnit.MILLISECONDS)
        .atMost(15L, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(
                        dsl.fetchSingle(
                                "SELECT GREATEST(confirmed_at, target_purged_at) + effective_minimum_millis * interval '1 millisecond' <= clock_timestamp() "
                                    + "FROM game_session_canonical_roster_retirement_transfers "
                                    + "WHERE target_fence_uuid = ? AND confirmed_at IS NOT NULL AND target_purged_at IS NOT NULL",
                                candidate.fenceUuid())
                            .get(0, Boolean.class))
                    .isTrue());
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementAcknowledgements(8)))
        .isEqualTo(1);
  }

  @Test
  void recoveryObservationChecksKnownNonceAndExactRetirementBeforeFirstAck() throws Exception {
    var original = request();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(original);
          repository.reserveSelectionInput(original, selectionInput());
          repository.markRosterReadStarted(original);
        });
    Thread.sleep(5L);
    var recovery =
        transaction
            .execute(ignored -> repository.beginRecoveries(original, current(original)))
            .getFirst();
    var closed = recoveryResponse(recovery, null);
    transaction.executeWithoutResult(
        ignored -> repository.finishRecovery(verifiedRecovery(recovery, closed)));
    Thread.sleep(5L);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(original, current(original))))
        .isTrue();
    var proof = repository.read(original);
    var wrongNonce = acknowledgement(original, proof, UUID.randomUUID(), 1L);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored -> repository.acknowledgeRetirement(wrongNonce, entityPrincipal())))
        .hasMessageContaining("ADMISSION_CONFLICT");
    var exact = wrongNonce.toBuilder().setAdmissionUuid(closed.getAdmissionUuid()).build();
    var wrongFenceProof =
        CanonicalGameplayRosterReferenceEvidence.sealRetirement(
            proof.toBuilder().setTargetFenceUuid(UUID.randomUUID().toString()).build());
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.acknowledgeRetirement(
                            exact.toBuilder().setObservedRetirement(wrongFenceProof).build(),
                            entityPrincipal())))
        .hasMessageContaining("OBSERVATION_CONFLICT");
    var absent = original.toBuilder().setOperationUuid(UUID.randomUUID().toString()).build();
    var absentProof =
        CanonicalGameplayRosterReferenceEvidence.sealRetirement(
            proof.toBuilder().setRequest(absent).build());
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.acknowledgeRetirement(
                            acknowledgement(absent, absentProof, UUID.randomUUID(), 1L),
                            entityPrincipal())))
        .isInstanceOf(
            CanonicalGameplayRosterReferenceRepository.OriginalUnavailableException.class);
    var ack =
        transaction.execute(ignored -> repository.acknowledgeRetirement(exact, entityPrincipal()));
    assertThat(ack.getMinimumRetentionMillis()).isEqualTo(1L);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_session_canonical_roster_retirement_transfers SET admission_uuid = ?",
                    UUID.randomUUID()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void firstAckSerializesWithPurgeOnTheRetiredFence() throws Exception {
    var original = request();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(original);
          repository.reserveSelectionInput(original, selectionInput());
          repository.record(original, receipt(original));
          repository.completeSelection(
              original, CanonicalGameplayRosterReferenceRepository.SelectionOutcome.MENU);
        });
    Thread.sleep(5L);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(original, current(original))))
        .isTrue();
    Thread.sleep(5L);
    var request = acknowledgement(original, repository.read(original), UUID.randomUUID(), 1L);
    var candidate =
        new CanonicalGameplayRosterReferenceRepository.TargetCandidate(
            UUID.fromString(repository.read(original).getTargetFenceUuid()), original, "RETIRED");
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var purgeStarted = new CountDownLatch(1);
    var acknowledgementPid = new AtomicInteger();
    var purgePid = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var firstAck =
          executor.submit(
              () ->
                  transaction.execute(
                      ignored -> {
                        acknowledgementPid.set(
                            java.util.Objects.requireNonNull(
                                dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class)));
                        var ack = repository.acknowledgeRetirement(request, entityPrincipal());
                        locked.countDown();
                        try {
                          assertThat(release.await(15L, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        return ack;
                      }));
      try {
        assertThat(locked.await(5L, TimeUnit.SECONDS)).isTrue();
        var purged =
            executor.submit(
                () ->
                    transaction.execute(
                        ignored -> {
                          purgePid.set(
                              java.util.Objects.requireNonNull(
                                  dsl.fetchSingle("SELECT pg_backend_pid()")
                                      .get(0, Integer.class)));
                          purgeStarted.countDown();
                          return repository.purgeRetiredTarget(candidate);
                        }));
        assertThat(purgeStarted.await(5L, TimeUnit.SECONDS)).isTrue();
        awaitBackendBlockedBy(dsl, purgePid.get(), acknowledgementPid.get());
        release.countDown();
        var ack = firstAck.get(5L, TimeUnit.SECONDS);
        assertThat(purged.get(5L, TimeUnit.SECONDS)).isTrue();
        assertThat(
                transaction.<CanonicalGameplayRosterRetirementAcknowledgementResponse>execute(
                    ignored -> repository.acknowledgeRetirement(request, entityPrincipal())))
            .isEqualTo(ack);
      } finally {
        release.countDown();
      }
    }
  }

  private String transferRow(GetCanonicalGameplayRosterReferenceRequest original) {
    return dsl.fetchSingle(
            "SELECT row_to_json(t)::text AS retained, xmin::text FROM game_session_canonical_roster_retirement_transfers t WHERE operation_uuid = ?",
            UUID.fromString(original.getOperationUuid()))
        .intoMap()
        .toString();
  }

  private static String entityPrincipal() {
    return "spiffe://firemud/ns/gameplay/sa/entity-management-service";
  }

  private static CanonicalGameplayRosterRetirementAcknowledgementRequest acknowledgement(
      GetCanonicalGameplayRosterReferenceRequest original,
      net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse observed,
      UUID admission,
      long minimum) {
    var request =
        CanonicalGameplayRosterRetirementAcknowledgementRequest.newBuilder()
            .setSchemaVersion(1)
            .setOriginalSelection(original)
            .setObservedRetirement(observed)
            .setObservationUuid(UUID.randomUUID().toString())
            .setAdmissionUuid(admission.toString())
            .setMinimumRetentionMillis(minimum);
    if (!observed.getCanonicalRosterReceipt().isEmpty()) {
      var roster = CanonicalGameplayRosterReferenceEvidence.receipt(observed);
      request
          .setSnapshotUuid(roster.getSnapshotUuid())
          .setSnapshotDigest(roster.getSnapshotDigest());
    }
    return CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(request.build());
  }

  @org.junit.jupiter.api.io.TempDir Path ownerReadDirectory;

  @Test
  void createdOriginalSurvivesSqlAndProductionMtlsOwnerReadAcknowledgementAndConfirmation()
      throws Exception {
    // Controlled historical creation and superseding source values; real GS SQL and handlers.
    // This does not prove genuine source issuance, a current supersession writer or production PKI.
    repository = repository(policy(1L, 1L, 1L));
    boolean maximum = Boolean.getBoolean("firemud.test.created-retirement.maximum-payload");
    boolean highEntropy =
        Boolean.parseBoolean(
            System.getProperty("firemud.test.created-retirement.high-entropy", "true"));
    var fixture =
        maximum
            ? net.firedevops.firemud.common.testing.ControlledPlayerCreatedRosterTerminalFixtures
                .withMaximumOpaqueProtections("gameplay", highEntropy)
            : net.firedevops.firemud.common.testing.ControlledPlayerCreatedRosterTerminalFixtures
                .withFiveMebibyteAccountProtection("gameplay");
    var original = fixture.originalRequest();
    var roster = CanonicalGameplayRosterReferenceEvidence.playerCreatedReceipt(fixture.original());
    var selected = fixture.selected();
    assertThat(selected.getTerminal().getOriginalAccountProtection().size())
        .isEqualTo((maximum ? 40 : 5) * 1024 * 1024);
    if (maximum) {
      assertThat(selected.getTerminal().getAccountOriginal().getOriginalSourceReadback().size())
          .isEqualTo(32 * 1024 * 1024);
      assertThat(selected.getTerminal().getOriginalGameSessionProtection().size())
          .isEqualTo(48 * 1024 * 1024);
      assertThat(selected.getTerminal().getOriginalWorldProtection().size())
          .isEqualTo(48 * 1024 * 1024);
      System.out.println(
          "created-sql-purpose-bytes terminal="
              + selected.getTerminal().getSerializedSize()
              + " selected="
              + selected.getSerializedSize()
              + " original="
              + fixture.original().getSerializedSize()
              + " highEntropy="
              + highEntropy);
    }
    var input =
        net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput.newBuilder()
            .setSchemaVersion(1)
            .setSelector("1")
            .setMenuSnapshotUuid(roster.getRoster().getSnapshotUuid())
            .setMenuSnapshotDigest(roster.getRoster().getSnapshotDigest())
            .build();

    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(original);
          repository.reserveSelectionInput(original, input);
          repository.markRosterReadStarted(original);
          repository.recordPlayerCreated(original, roster);
          repository.selectOriginal(original, UUID.fromString(selected.getSelectedCharacterUuid()));
          repository.recordSelectedPlayerCreatedCharacter(original, selected);
          repository.completeSelection(
              original, CanonicalGameplayRosterReferenceRepository.SelectionOutcome.SELECTED);
        });
    assertThat(
            dsl.fetchSingle(
                    "SELECT canonical_player_created_roster_receipt FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
                    UUID.fromString(original.getOperationUuid()))
                .get("canonical_player_created_roster_receipt", byte[].class))
        .containsExactly(roster.toByteArray());
    assertThat(
            dsl.fetchSingle(
                    "SELECT canonical_selected_player_created_character_receipt FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
                    UUID.fromString(original.getOperationUuid()))
                .get("canonical_selected_player_created_character_receipt", byte[].class))
        .containsExactly(selected.toByteArray());

    Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(
                        dsl.fetchSingle(
                                "SELECT s.terminal_at + f.minimum_retention_millis * interval '1 millisecond' <= clock_timestamp() FROM game_session_canonical_roster_selection s JOIN game_session_canonical_roster_target_fences f USING (target_fence_uuid) WHERE s.operation_uuid = ?",
                                UUID.fromString(original.getOperationUuid()))
                            .get(0, Boolean.class))
                    .isTrue());

    var routes =
        org.mockito.Mockito.mock(
            net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService.class);
    var current = currentCreatedTarget(original);
    org.mockito.Mockito.when(
            routes.readCurrentTarget(
                UUID.fromString(
                    CanonicalGameplayRosterReferenceEvidence.target(original).getTenantUuid())))
        .thenReturn(current);
    var admissions = org.mockito.Mockito.mock(CanonicalInitialAdmissionRepository.class);
    var snapshot = currentSnapshot(original);
    org.mockito.Mockito.when(
            admissions.readCurrentOpenSnapshotForRealm(
                original.getTargetNamespace(), current.canonicalTenantId(), current.realmId()))
        .thenReturn(snapshot);
    org.mockito.Mockito.when(admissions.lockExactCurrentOpenSnapshot(snapshot))
        .thenReturn(snapshot);
    var owner =
        new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner(
            repository,
            transaction,
            org.mockito.Mockito.mock(
                net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                    .class),
            routes,
            admissions,
            org.mockito.Mockito.mock(CanonicalGameplayRosterRecoveryClient.class),
            new SimpleMeterRegistry());

    var pkiDirectory = java.nio.file.Files.createDirectories(ownerReadDirectory.resolve("pki"));
    var pki =
        net.firedevops.firemud.test.LoopbackMtlsTestPki.create(
            pkiDirectory,
            "Maximum created roster owner-read test CA",
            new net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec(
                "gs-server", "spiffe://firemud/ns/gameplay/sa/game-session-service", true),
            new net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec(
                "entity-client",
                "spiffe://firemud/ns/gameplay/sa/entity-management-service",
                false));
    var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    beans.addBean(
        "source",
        org.mockito.Mockito.mock(
            net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                .class));
    beans.addBean("reference", owner);
    var adapter =
        new net.firedevops.firemud.gamesession.service.impl
            .CanonicalGameplayRosterOwnerReadGrpcAdapter(
            beans.getBeanProvider(
                net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
                    .class),
            "gameplay",
            beans.getBeanProvider(
                net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner
                    .class));
    var serverIdentity = pki.identity("gs-server");
    var bytePolicy =
        new net.firedevops.firemud.gamesession.config.GameSessionGrpcRequestByteConfiguration()
            .gameSessionGrpcRequestBytePolicy();
    var serverFactory =
        new net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory(
            "127.0.0.1:0",
            List.of(),
            pki.keyManagerFactory(serverIdentity),
            pki.trustManagerFactory(),
            io.grpc.TlsServerCredentials.ClientAuth.REQUIRE,
            bytePolicy,
            bytePolicy.transportMaximumBytes());
    serverFactory.addService(
        io.grpc.ServerInterceptors.intercept(
            adapter, new net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor()));
    var server = serverFactory.createServer().start();
    var endpoints = new net.firedevops.firemud.common.config.ServiceEndpointsProperties();
    endpoints.setGameSessionService("127.0.0.1:" + server.getPort());
    var client =
        new net.firedevops.firemud.entitymanagement.client
            .GameSessionCanonicalGameplayRosterOwnerReadClient(
            endpoints,
            pki.clientProperties(pkiDirectory, pki.identity("entity-client")),
            new net.firedevops.firemud.common.grpc.GrpcChannelFactory(),
            "gameplay");
    try {
      client.init();
      var retained = repository.read(original);
      long started = System.nanoTime();
      var transported = client.getCanonicalGameplayRosterReference(original);
      if (maximum) {
        System.out.println(
            "created-sql-purpose-call purpose=initial-read elapsedNanos="
                + (System.nanoTime() - started)
                + " requestBytes="
                + original.getSerializedSize()
                + " responseBytes="
                + transported.getSerializedSize()
                + " highEntropy="
                + highEntropy);
      }
      assertThat(transported).isEqualTo(retained);
      assertThat(CanonicalGameplayRosterReferenceEvidence.playerCreatedReceipt(transported))
          .isEqualTo(roster);
      assertThat(
              CanonicalGameplayRosterReferenceEvidence.selectedPlayerCreatedCharacterReceipt(
                  transported))
          .isEqualTo(selected);
      // Let the initial full-size decoded responses go before the retirement exchange.
      transported = null;
      retained = null;

      assertThat(owner.retireTarget(original)).isTrue();
      var retired = repository.read(original);
      started = System.nanoTime();
      var retiredTransport = client.getCanonicalGameplayRosterReference(original);
      if (maximum) {
        System.out.println(
            "created-sql-purpose-call purpose=retired-read elapsedNanos="
                + (System.nanoTime() - started)
                + " requestBytes="
                + original.getSerializedSize()
                + " responseBytes="
                + retiredTransport.getSerializedSize()
                + " highEntropy="
                + highEntropy);
      }
      assertThat(retiredTransport).isEqualTo(retired);
      retiredTransport = null;
      assertThat(
              dsl.fetchSingle(
                      "SELECT canonical_player_created_roster_receipt FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
                      UUID.fromString(original.getOperationUuid()))
                  .get("canonical_player_created_roster_receipt", byte[].class))
          .containsExactly(roster.toByteArray());
      assertThat(
              dsl.fetchSingle(
                      "SELECT canonical_selected_player_created_character_receipt FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
                      UUID.fromString(original.getOperationUuid()))
                  .get("canonical_selected_player_created_character_receipt", byte[].class))
          .containsExactly(selected.toByteArray());

      var acknowledgementRequest =
          createdAcknowledgement(original, retired, UUID.randomUUID().toString());
      started = System.nanoTime();
      var acknowledgement =
          client.acknowledgeCanonicalGameplayRosterRetirement(acknowledgementRequest);
      if (maximum) {
        System.out.println(
            "created-sql-purpose-call purpose=acknowledgement elapsedNanos="
                + (System.nanoTime() - started)
                + " requestBytes="
                + acknowledgementRequest.getSerializedSize()
                + " responseBytes="
                + acknowledgement.getSerializedSize()
                + " highEntropy="
                + highEntropy);
      }
      assertThat(acknowledgement)
          .isEqualTo(
              CanonicalGameplayRosterRetirementEvidence.response(
                  acknowledgementRequest,
                  UUID.fromString(acknowledgement.getAcknowledgementUuid()),
                  acknowledgement.getMinimumRetentionMillis()));
      var confirmationRequest =
          CanonicalGameplayRosterRetirementEvidence.confirmation(
              acknowledgementRequest, acknowledgement);
      started = System.nanoTime();
      var confirmation =
          client.confirmCanonicalGameplayRosterRetirementAcknowledgement(confirmationRequest);
      if (maximum) {
        System.out.println(
            "created-sql-purpose-call purpose=confirmation elapsedNanos="
                + (System.nanoTime() - started)
                + " requestBytes="
                + confirmationRequest.getSerializedSize()
                + " responseBytes="
                + confirmation.getSerializedSize()
                + " highEntropy="
                + highEntropy);
      }
      assertThat(confirmation)
          .isEqualTo(
              CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmationRequest));
      assertThat(repository.read(original)).isEqualTo(retired);
    } finally {
      client.close();
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void createdEmptyMenuReplaysExactTypedReceiptWithoutInventingASelection() throws Exception {
    repository = repository(policy(60000L, 60000L, 60000L));
    var fixture = createdFixture(false);
    var original = fixture.original();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(original);
          repository.reserveSelectionInput(original, selectionInput());
          repository.markRosterReadStarted(original);
          repository.recordPlayerCreated(original, fixture.roster());
          repository.completeSelection(
              original, CanonicalGameplayRosterReferenceRepository.SelectionOutcome.MENU);
        });
    var recorded = repository.read(original);
    var retained = createdRow(original);
    assertThat(
            CanonicalGameplayRosterReferenceEvidence.playerCreatedReceipt(recorded)
                .getRoster()
                .getActorsList())
        .isEmpty();
    assertThat(recorded.getSelectedCharacterUuid()).isEmpty();
    assertThat(recorded.getCanonicalRosterReceipt()).isEmpty();
    assertThat(
            transaction.<GetCanonicalGameplayRosterReferenceResponse>execute(
                ignored -> repository.replay(original)))
        .isEqualTo(recorded);
    assertThat(
            transaction.<GetCanonicalGameplayRosterReferenceResponse>execute(
                ignored -> repository.recordPlayerCreated(original, fixture.roster())))
        .isEqualTo(recorded);
    assertThat(createdRow(original)).isEqualTo(retained);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored -> repository.selectOriginal(original, UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void createdOneActorAndCompleteSelectedTerminalReplayWithExactBytesAndPolicyIsolation()
      throws Exception {
    repository = repository(policy(60000L, 60000L, 60000L));
    var fixture = createdFixture(true);
    var original = fixture.original();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(original);
          repository.reserveSelectionInput(original, selectionInput());
          repository.markRosterReadStarted(original);
          repository.recordPlayerCreated(original, fixture.roster());
          repository.selectOriginal(
              original, UUID.fromString(fixture.selected().getSelectedCharacterUuid()));
          repository.recordSelectedPlayerCreatedCharacter(original, fixture.selected());
          repository.completeSelection(
              original, CanonicalGameplayRosterReferenceRepository.SelectionOutcome.SELECTED);
        });
    var selected = repository.read(original);
    assertThat(
            CanonicalGameplayRosterReferenceEvidence.selectedPlayerCreatedCharacterReceipt(
                selected))
        .isEqualTo(fixture.selected());
    assertThat(selected.getCanonicalSelectedPlayerCreatedCharacterReceipt())
        .isEqualTo(fixture.selected().toByteString());
    assertThat(selected.getCanonicalSelectedAssignmentReceipt()).isEmpty();
    var retained = createdRow(original);
    transaction.executeWithoutResult(
        ignored -> repository.recordSelectedPlayerCreatedCharacter(original, fixture.selected()));
    assertThat(
            transaction.<GetCanonicalGameplayRosterReferenceResponse>execute(
                ignored -> repository.replay(original)))
        .isEqualTo(selected);
    assertThat(createdRow(original)).isEqualTo(retained);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored -> repository.record(original, fixture.roster().getRoster())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.recordSelectedAssignment(
                            original,
                            net.firedevops.firemud.entitymanagement.v1
                                .CanonicalGameplayRosterSelectedAssignmentResponse
                                .getDefaultInstance())))
        .isInstanceOf(IllegalArgumentException.class);
    var changed =
        fixture.selected().toBuilder()
            .setTerminal(
                fixture.selected().getTerminal().toBuilder()
                    .setTerminalId(UUID.randomUUID().toString()))
            .build();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.recordSelectedPlayerCreatedCharacter(original, changed)))
        .hasMessageContaining("CREATED_RECEIPT_CONFLICT");
    assertThat(createdRow(original)).isEqualTo(retained);
  }

  @Test
  void createdSourceIdentityOwnsItsFenceAndSqlRefusesMixedMutableAndIncompleteReceipts()
      throws Exception {
    repository = repository(policy(60000L, 60000L, 60000L));
    var fixture = createdFixture(true);
    var original = fixture.original();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(original);
          repository.reserveSelectionInput(original, selectionInput());
          repository.recordPlayerCreated(original, fixture.roster());
        });
    var initial = repository.read(original);
    var alteredRoster =
        fixture.roster().toBuilder()
            .setRoster(
                fixture.roster().getRoster().toBuilder()
                    .setSnapshotUuid(UUID.randomUUID().toString()))
            .build();
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored -> repository.recordPlayerCreated(original, alteredRoster)))
        .hasMessageContaining("RECEIPT_CONFLICT");
    var source = CanonicalGameplayRosterReferenceEvidence.playerCreatedSourceIdentity(original);
    var changedSource =
        original.toBuilder()
            .setCanonicalPlayerCreatedSourceIdentity(
                source.toBuilder()
                    .setDescriptorRevisionUuid(UUID.randomUUID().toString())
                    .build()
                    .toByteString())
            .build();
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.begin(changedSource)))
        .hasMessageContaining("REQUEST_CONFLICT");
    assertThat(
            dsl.fetchSingle("SELECT count(*) FROM game_session_canonical_roster_target_fences")
                .get(0, Long.class))
        .isEqualTo(1L);
    var otherOperation =
        changedSource.toBuilder().setOperationUuid(UUID.randomUUID().toString()).build();
    var other = transaction.execute(ignored -> repository.begin(otherOperation));
    assertThat(other.getTargetFenceUuid()).isNotEqualTo(initial.getTargetFenceUuid());
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_session_canonical_roster_selection SET entry_policy='PRESEEDED_ONLY' WHERE operation_uuid=?",
                    UUID.fromString(original.getOperationUuid())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_session_canonical_roster_selection SET canonical_roster_receipt=? WHERE operation_uuid=?",
                    new byte[] {1},
                    UUID.fromString(original.getOperationUuid())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_session_canonical_roster_selection SET canonical_player_created_roster_receipt=? WHERE operation_uuid=?",
                    alteredRoster.toByteArray(),
                    UUID.fromString(original.getOperationUuid())))
        .isInstanceOf(DataAccessException.class);
    transaction.execute(
        ignored ->
            repository.selectOriginal(
                original, UUID.fromString(fixture.selected().getSelectedCharacterUuid())));
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.completeSelection(
                            original,
                            CanonicalGameplayRosterReferenceRepository.SelectionOutcome.SELECTED)))
        .hasMessageContaining("RECOVERY_REQUIRED");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_session_canonical_roster_selection SET terminal_outcome='SELECTED', terminal_at=clock_timestamp() WHERE operation_uuid=?",
                    UUID.fromString(original.getOperationUuid())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> dsl.execute("TRUNCATE game_session_canonical_roster_selection CASCADE"))
        .isInstanceOf(DataAccessException.class);
    var definitions =
        dsl.fetch(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='game_session_canonical_roster_selection'::regclass")
            .getValues(0, String.class);
    assertThat(definitions)
        .anyMatch(value -> value.contains("202375168"))
        .anyMatch(value -> value.contains("67584"))
        .anyMatch(value -> value.contains("65536"))
        .anyMatch(value -> value.contains("8192"));
    assertThat(repository.read(original).getTerminalOutcome()).isEmpty();
  }

  @Test
  void createdRecoveryClosesMissingSelectedReceiptAndRequiresEveryExactEntityAck()
      throws Exception {
    var fixture = createdFixture(true);
    var original = fixture.original();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(original);
          repository.reserveSelectionInput(original, selectionInput());
          repository.markRosterReadStarted(original);
          repository.recordPlayerCreated(original, fixture.roster());
          repository.selectOriginal(
              original, UUID.fromString(fixture.selected().getSelectedCharacterUuid()));
        });
    Thread.sleep(5L);
    var recovery =
        transaction
            .execute(ignored -> repository.beginRecoveries(original, current(original)))
            .getFirst();
    var owner = repository.read(original);
    var response =
        CanonicalGameplayRosterRecoveryEvidence.playerCreatedResponse(
            recovery,
            UUID.randomUUID(),
            owner.getOwnerFence(),
            UUID.fromString(owner.getTargetFenceUuid()),
            fixture.roster());
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.recordSelectedPlayerCreatedCharacter(
                            original, fixture.selected())))
        .hasMessageContaining("RECOVERY_FENCED");
    transaction.executeWithoutResult(
        ignored -> repository.finishRecovery(verifiedRecovery(recovery, response)));
    var closed = repository.read(original);
    assertThat(closed.getTerminalOutcome()).isEqualTo("DENIED");
    assertThat(closed.getCanonicalSelectedPlayerCreatedCharacterReceipt()).isEmpty();
    assertThat(closed.getCanonicalPlayerCreatedRosterReceipt())
        .isEqualTo(fixture.roster().toByteString());
    assertThat(closed.getSelectedCharacterUuid())
        .isEqualTo(fixture.selected().getSelectedCharacterUuid());
    Thread.sleep(5L);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(original, current(original))))
        .isTrue();
    var proof = repository.read(original);
    var candidate =
        new CanonicalGameplayRosterReferenceRepository.TargetCandidate(
            UUID.fromString(proof.getTargetFenceUuid()), original, "RETIRED");
    Thread.sleep(5L);
    assertThat(transaction.<Boolean>execute(ignored -> repository.purgeRetiredTarget(candidate)))
        .isFalse();
    var acknowledgement = createdAcknowledgement(original, proof, response.getAdmissionUuid());
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.acknowledgeRetirement(
                            acknowledgement.toBuilder()
                                .setAdmissionUuid(UUID.randomUUID().toString())
                                .build(),
                            createdPrincipal(original))))
        .hasMessageContaining("ADMISSION_CONFLICT");
    var ack =
        transaction.execute(
            ignored ->
                repository.acknowledgeRetirement(acknowledgement, createdPrincipal(original)));
    assertThat(transaction.<Boolean>execute(ignored -> repository.purgeRetiredTarget(candidate)))
        .isTrue();
    assertThat(
            transaction.<CanonicalGameplayRosterRetirementAcknowledgementResponse>execute(
                ignored ->
                    repository.acknowledgeRetirement(acknowledgement, createdPrincipal(original))))
        .isEqualTo(ack);
  }

  @Test
  void createdNoCaptureRecoveryRetainsPositiveClosureWithoutInventingEitherRosterReceipt()
      throws Exception {
    repository = repository(policy(500L, 500L, 500L));
    var fixture = createdFixture(false);
    var original = fixture.original();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(original);
          repository.reserveSelectionInput(original, selectionInput());
          repository.markRosterReadStarted(original);
        });
    Thread.sleep(550L);
    var recovery =
        transaction
            .execute(ignored -> repository.beginRecoveries(original, current(original)))
            .getFirst();
    var owner = repository.read(original);
    var response =
        CanonicalGameplayRosterRecoveryEvidence.playerCreatedResponse(
            recovery,
            UUID.randomUUID(),
            owner.getOwnerFence(),
            UUID.fromString(owner.getTargetFenceUuid()),
            null);
    transaction.executeWithoutResult(
        ignored -> repository.finishRecovery(verifiedRecovery(recovery, response)));
    var closed = repository.read(original);
    assertThat(closed.getTerminalOutcome()).isEqualTo("DENIED");
    assertThat(closed.getRecoveryUuid()).isEqualTo(recovery.getRecoveryUuid());
    assertThat(closed.getCanonicalRosterReceipt()).isEmpty();
    assertThat(closed.getCanonicalPlayerCreatedRosterReceipt()).isEmpty();
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored -> repository.recordPlayerCreated(original, fixture.roster())))
        .hasMessageContaining("RECOVERY_FENCED");
    Thread.sleep(550L);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(original, current(original))))
        .isTrue();
    var proof = repository.read(original);
    var acknowledgement = createdAcknowledgement(original, proof, response.getAdmissionUuid());
    assertThat(acknowledgement.getSnapshotUuid()).isEmpty();
    transaction.execute(
        ignored -> repository.acknowledgeRetirement(acknowledgement, createdPrincipal(original)));
    Thread.sleep(550L);
    var candidate =
        new CanonicalGameplayRosterReferenceRepository.TargetCandidate(
            UUID.fromString(proof.getTargetFenceUuid()), original, "RETIRED");
    assertThat(transaction.<Boolean>execute(ignored -> repository.purgeRetiredTarget(candidate)))
        .isTrue();
  }

  @Test
  void completeCreatedSelectedRetirementFreezesInventoryEvenWithoutDispatchFlag() throws Exception {
    var fixture = createdFixture(true);
    var original = fixture.original();
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(original);
          repository.reserveSelectionInput(original, selectionInput());
          repository.recordPlayerCreated(original, fixture.roster());
          repository.selectOriginal(
              original, UUID.fromString(fixture.selected().getSelectedCharacterUuid()));
          repository.recordSelectedPlayerCreatedCharacter(original, fixture.selected());
          repository.completeSelection(
              original, CanonicalGameplayRosterReferenceRepository.SelectionOutcome.SELECTED);
        });
    Thread.sleep(5L);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(original, current(original))))
        .isTrue();
    var proof = repository.read(original);
    assertThat(proof.getCanonicalSelectedPlayerCreatedCharacterReceipt())
        .isEqualTo(fixture.selected().toByteString());
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM game_session_canonical_roster_retirement_transfers")
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThatThrownBy(() -> dsl.execute("DELETE FROM game_session_canonical_roster_selection"))
        .isInstanceOf(DataAccessException.class);
    var request = createdAcknowledgement(original, proof, UUID.randomUUID().toString());
    var ack =
        transaction.execute(
            ignored -> repository.acknowledgeRetirement(request, createdPrincipal(original)));
    Thread.sleep(5L);
    var candidate =
        new CanonicalGameplayRosterReferenceRepository.TargetCandidate(
            UUID.fromString(proof.getTargetFenceUuid()), original, "RETIRED");
    assertThat(transaction.<Boolean>execute(ignored -> repository.purgeRetiredTarget(candidate)))
        .isTrue();
    assertThat(
            transaction.<CanonicalGameplayRosterRetirementAcknowledgementResponse>execute(
                ignored -> repository.acknowledgeRetirement(request, createdPrincipal(original))))
        .isEqualTo(ack);
  }

  @Test
  void actual1017To1020UpgradePreservesPriorHistoryOriginalBytesAndXmin() throws Exception {
    String previousSchema = "gs_created_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var previousSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    previousSource.setSchema(previousSchema);
    var previous = DSL.using(previousSource, SQLDialect.POSTGRES);
    try {
      migrate(previousSource, previousSchema, "1017");
      var original = request();
      var roster = receipt(original);
      var fence = UUID.randomUUID();
      var binding =
          original.toBuilder().clearOperationUuid().clearSessionUuid().build().toByteArray();
      String digest =
          java.util.HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(binding));
      // Controlled predecessor writer through all installed guards; no current owner RPC or Human
      // admission.
      previous.execute(
          "INSERT INTO game_session_canonical_roster_target_fences(target_fence_uuid,target_binding_digest,target_binding,minimum_retention_millis,replay_horizon_millis) VALUES(?,?,?,60000,60000)",
          fence,
          digest,
          binding);
      previous.execute(
          "INSERT INTO game_session_canonical_roster_selection(operation_uuid,original_request,target_fence_uuid) VALUES(?,?,?)",
          UUID.fromString(original.getOperationUuid()),
          original.toByteArray(),
          fence);
      previous.execute(
          "UPDATE game_session_canonical_roster_selection SET canonical_roster_receipt=?,receipt_recorded_at=clock_timestamp() WHERE operation_uuid=?",
          roster.toByteArray(),
          UUID.fromString(original.getOperationUuid()));
      var before =
          previous.fetch("SELECT * FROM flyway_schema_history ORDER BY installed_rank").intoMaps();
      String retained =
          previous
              .fetchSingle(
                  "SELECT encode(original_request,'hex') AS original_request_hex,encode(canonical_roster_receipt,'hex') AS roster_receipt_hex,recorded_at,receipt_recorded_at,xmin::text AS row_version FROM game_session_canonical_roster_selection")
              .intoMap()
              .toString();
      var upgraded = migrate(previousSource, previousSchema, "1020");
      assertThat(upgraded.getConfiguration().isOutOfOrder()).isFalse();
      assertThat(upgraded.info().applied())
          .allSatisfy(
              info ->
                  assertThat(info.getState())
                      .isNotEqualTo(org.flywaydb.core.api.MigrationState.OUT_OF_ORDER));
      var after =
          previous.fetch("SELECT * FROM flyway_schema_history ORDER BY installed_rank").intoMaps();
      assertThat(after.subList(0, before.size())).containsExactlyElementsOf(before);
      assertThat(after).hasSize(before.size() + 1);
      assertThat(after.getLast().get("version")).isEqualTo("1020");
      assertThat(
              previous
                  .fetchSingle(
                      "SELECT encode(original_request,'hex') AS original_request_hex,encode(canonical_roster_receipt,'hex') AS roster_receipt_hex,recorded_at,receipt_recorded_at,xmin::text AS row_version FROM game_session_canonical_roster_selection")
                  .intoMap()
                  .toString())
          .isEqualTo(retained);
      var upgradedRow =
          previous.fetchSingle(
              "SELECT entry_policy,canonical_player_created_roster_receipt,canonical_selected_player_created_character_receipt FROM game_session_canonical_roster_selection");
      assertThat(upgradedRow.get("entry_policy", String.class)).isEqualTo("PRESEEDED_ONLY");
      assertThat(upgradedRow.get("canonical_player_created_roster_receipt", byte[].class)).isNull();
      assertThat(
              upgradedRow.get("canonical_selected_player_created_character_receipt", byte[].class))
          .isNull();
      var upgradedRepository =
          new CanonicalGameplayRosterReferenceRepository(previous, policy(60000L, 60000L, 60000L));
      assertThat(upgradedRepository.read(original).getCanonicalRosterReceipt())
          .isEqualTo(roster.toByteString());
    } finally {
      previous.execute("DROP SCHEMA IF EXISTS " + previousSchema + " CASCADE");
    }
  }

  private String createdRow(GetCanonicalGameplayRosterReferenceRequest original) {
    return dsl.fetchSingle(
            "SELECT row_to_json(s)::text, xmin::text FROM game_session_canonical_roster_selection s WHERE operation_uuid=?",
            UUID.fromString(original.getOperationUuid()))
        .intoMap()
        .toString();
  }

  private static String createdPrincipal(GetCanonicalGameplayRosterReferenceRequest original) {
    return "spiffe://firemud/ns/" + original.getTargetNamespace() + "/sa/entity-management-service";
  }

  private static CanonicalGameplayRosterRetirementAcknowledgementRequest createdAcknowledgement(
      GetCanonicalGameplayRosterReferenceRequest original,
      GetCanonicalGameplayRosterReferenceResponse proof,
      String admission) {
    var request =
        CanonicalGameplayRosterRetirementAcknowledgementRequest.newBuilder()
            .setSchemaVersion(1)
            .setOriginalSelection(original)
            .setObservedRetirement(proof)
            .setObservationUuid(UUID.randomUUID().toString())
            .setAdmissionUuid(admission)
            .setMinimumRetentionMillis(1L);
    if (!proof.getCanonicalPlayerCreatedRosterReceipt().isEmpty()) {
      var roster = CanonicalGameplayRosterReferenceEvidence.snapshotReceipt(proof);
      request
          .setSnapshotUuid(roster.getSnapshotUuid())
          .setSnapshotDigest(roster.getSnapshotDigest());
    }
    return request.build();
  }

  private record CreatedFixture(
      GetCanonicalGameplayRosterReferenceRequest original,
      CanonicalPlayerCreatedRosterResponse roster,
      CanonicalSelectedPlayerCreatedCharacterResponse selected) {}

  private static CreatedFixture createdFixture(boolean oneActor) throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !java.nio.file.Files.exists(root.resolve("settings.gradle.kts")))
      root = root.getParent();
    if (root == null)
      throw new IllegalStateException("Controlled CHARACTER_CREATE fixture unavailable");
    byte[] bytes =
        java.nio.file.Files.readString(
                root.resolve(
                    "services/account-service/src/testFixtures/resources/charactercreate/public-action.json"))
            .strip()
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var action =
        net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec.decode(bytes);
    var authored = action.mutation().authoredSource();
    var descriptor =
        net.firedevops.firemud.common.publication.PlayerCreationDescriptor.parse(
            authored.descriptor().canonicalJson());
    var source =
        net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterSourceIdentity
            .newBuilder()
            .setSchemaVersion(1)
            .setDescriptorUuid(descriptor.descriptorId())
            .setDescriptorRevisionUuid(descriptor.descriptorRevisionId())
            .setDescriptorDigest(descriptor.digest())
            .setAuthoredBindingUuid(authored.bindingId())
            .setAuthoredBindingDigest(authored.bindingDigest())
            .build();
    var target =
        CanonicalGameplayRosterTarget.newBuilder()
            .setTenantUuid(action.scope().tenantId())
            .setRealmUuid(action.target().realmId())
            .setWorldSlug(action.target().worldSlug())
            .setRealmSlug(action.target().realmSlug())
            .setGameInstanceUuid(action.target().gameInstanceId())
            .setCanonicalVersionUuid(action.target().versionId())
            .setPlayableStateNamespaceUuid(action.scope().playableStateNamespaceId())
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setEntryPolicy(
                CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED)
            .setCatalogRevision(Long.parseLong(action.expectedVersion().catalogRevision()))
            .setPointerVersion(Long.parseLong(action.expectedVersion().pointerVersion()))
            .setActiveWorldEpoch(Long.parseLong(action.expectedVersion().activeLifecycleEpoch()))
            .setPublishedPolicyDigest(authored.policy().contentDigest().substring(7))
            .setPublishedReleaseBundleRef("controlled-created-release")
            .setAdmissionPointerSnapshotDigest("b".repeat(64))
            .setPublishedOwnerProofDigest("c".repeat(64))
            .build();
    var original =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedRequest(
            UUID.randomUUID(),
            UUID.fromString(action.target().accountId()),
            UUID.randomUUID(),
            action.scope().targetNamespace(),
            target,
            source);
    String character = UUID.randomUUID().toString();
    var actors =
        oneActor
            ? List.of(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor.newBuilder()
                    .setCharacterUuid(character)
                    .setDisplayName("Persisted fixture actor")
                    .setActorKind(
                        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind
                            .CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                    .build())
            : List.<net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor>of();
    String snapshot = UUID.randomUUID().toString();
    String snapshotDigest =
        CanonicalPlayerCreatedRosterEvidence.computeSnapshotDigest(
            original.getCanonicalAccountUuid(), target, source, actors);
    var roster =
        CanonicalPlayerCreatedRosterResponse.newBuilder()
            .setSourceIdentity(source)
            .setRoster(
                CanonicalGameplayRosterResponse.newBuilder()
                    .setCanonicalAccountUuid(original.getCanonicalAccountUuid())
                    .setTarget(target)
                    .setSnapshotUuid(snapshot)
                    .setSnapshotDigest(snapshotDigest)
                    .addAllActors(actors))
            .build();
    String creationOperation = UUID.randomUUID().toString();
    String mutationDigest =
        net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec.mutationDigest(
            action);
    var creationRequest =
        net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateRequest.newBuilder()
            .setTargetNamespace(action.scope().targetNamespace())
            .setOperationId(creationOperation)
            .setMutationDigest(mutationDigest)
            .build();
    var account =
        net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateResponse.newBuilder()
            .setTargetNamespace(action.scope().targetNamespace())
            .setOperationId(creationOperation)
            .setMutationDigest(mutationDigest)
            .setCreateCharacterRequestId(UUID.randomUUID().toString())
            .setConnectScopeId("controlled-create-scope")
            .setAuthenticatedAccountId(action.target().accountId())
            .setPurpose("CHARACTER_CREATE")
            .setOriginalAction(com.google.protobuf.ByteString.copyFrom(bytes))
            .setOriginalSourceReadback(
                com.google.protobuf.ByteString.copyFromUtf8("controlled-source-readback"))
            .build();
    var protection =
        net.firedevops.firemud.entitymanagement.v1.CharacterCreateProtectionReference.newBuilder()
            .setProtectionId(UUID.randomUUID().toString())
            .setProtectionFence(1L)
            .build();
    var terminal =
        net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal.newBuilder()
            .setOriginalRequest(
                net.firedevops.firemud.entitymanagement.v1.CommitProtectedCharacterCreateRequest
                    .newBuilder()
                    .setOriginal(creationRequest)
                    .setAccountProtection(protection)
                    .setGameSessionProtection(
                        protection.toBuilder().setProtectionId(UUID.randomUUID().toString()))
                    .setWorldProtection(
                        protection.toBuilder().setProtectionId(UUID.randomUUID().toString())))
            .setAccountOriginal(account)
            .setOriginalAccountProtection(
                com.google.protobuf.ByteString.copyFromUtf8("controlled-account-protection"))
            .setOriginalGameSessionProtection(
                com.google.protobuf.ByteString.copyFromUtf8("controlled-game-session-protection"))
            .setOriginalWorldProtection(
                com.google.protobuf.ByteString.copyFromUtf8("controlled-world-protection"))
            .setEntityIntakeRequestId(UUID.randomUUID().toString())
            .setOutcome(
                net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateOutcome
                    .ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED)
            .setCharacterId(character)
            .setTerminalId(UUID.randomUUID().toString())
            .setTerminalFence(1L)
            .setCompletedAt("2026-10-23T00:00:00Z")
            .setEntitySourceReceiptDigest("sha256:" + "d".repeat(64))
            .build();
    var selected =
        CanonicalSelectedPlayerCreatedCharacterResponse.newBuilder()
            .setRequestUuid(original.getOperationUuid())
            .setCanonicalAccountUuid(original.getCanonicalAccountUuid())
            .setSelectedCharacterUuid(character)
            .setTarget(target)
            .setSourceIdentity(source)
            .setSnapshot(
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSnapshotReference
                    .newBuilder()
                    .setSnapshotUuid(snapshot)
                    .setSnapshotDigest(snapshotDigest))
            .setCreationOperationUuid(creationOperation)
            .setMutationDigest(mutationDigest)
            .setTerminal(terminal)
            .build();
    return new CreatedFixture(original, roster, selected);
  }

  private CanonicalGameplayRosterReferenceRepository.TargetCandidate retireOriginal(
      GetCanonicalGameplayRosterReferenceRequest request) throws Exception {
    transaction.executeWithoutResult(
        ignored -> {
          repository.begin(request);
          repository.completeUnissuedDenied(request);
        });
    Thread.sleep(5L);
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.sealTargetRetirement(request, current(request))))
        .isTrue();
    return new CanonicalGameplayRosterReferenceRepository.TargetCandidate(
        UUID.fromString(repository.read(request).getTargetFenceUuid()), request, "RETIRED");
  }

  private void assertNoOriginalOrFence() {
    assertThat(
            dsl.fetchSingle("SELECT count(*) FROM game_session_canonical_roster_selection")
                .get(0, Long.class))
        .isZero();
    assertThat(
            dsl.fetchSingle("SELECT count(*) FROM game_session_canonical_roster_target_fences")
                .get(0, Long.class))
        .isZero();
  }

  private net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner
      referenceOwner(
          net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService
              sources,
          CanonicalInitialAdmissionRepository admissions) {
    return new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner(
        repository,
        transaction,
        sources,
        org.mockito.Mockito.mock(
            net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService.class),
        admissions,
        org.mockito.Mockito.mock(CanonicalGameplayRosterRecoveryClient.class),
        new SimpleMeterRegistry());
  }

  private static GetCanonicalGameplayRosterReferenceRequest publishedRequest(
      net.firedevops.firemud.gamesession.dto.CanonicalPublishedPlayerRoute published) {
    return CanonicalGameplayRosterReferenceEvidence.request(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        published.route().targetNamespace(),
        net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient.expectedTarget(
            published));
  }

  private static net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence
      sourceEvidence(
          net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence.Request
              selectors,
          net.firedevops.firemud.gamesession.dto.CanonicalPublishedPlayerRoute published) {
    var route = published.route();
    var request =
        new net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request(
            route.targetNamespace(),
            route.canonicalTenantId(),
            route.worldSlug(),
            route.realmId(),
            route.playableStateNamespaceId(),
            route.playableStateScope(),
            route.canonicalGameInstanceId(),
            route.canonicalVersionId(),
            route.activeWorldEpoch(),
            route.initialAdmissionRequestId(),
            route.initialAdmissionRequestDigest(),
            net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold
                .InitialAdmissionOrigin.valueOf(route.initialAdmissionOriginKind().name()),
            route.catalogRevision(),
            route.expectedPriorPointerVersion());
    var hold =
        new net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity(
            request, route.holdId(), route.holdFence());
    var proof =
        new net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof(
            hold,
            net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof
                .Outcome.COMMITTED,
            route.pointerVersion(),
            route.auditEventId(),
            route.ownerProofDigest(),
            false,
            route.ownerProofTerminalAt());
    return new net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence(
        selectors, route.admissionPointerSnapshotDigest(), proof, published.policySetEvidence());
  }

  private static net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput
      selectionInput() {
    return net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput.newBuilder()
        .setSchemaVersion(1)
        .build();
  }

  private CanonicalGameplayRosterRecoveryResponse recoveryResponse(
      CanonicalGameplayRosterRecoveryRequest recovery, CanonicalGameplayRosterResponse roster) {
    var original = repository.read(recovery.getOriginalSelection());
    return CanonicalGameplayRosterRecoveryEvidence.response(
        recovery,
        UUID.randomUUID(),
        original.getOwnerFence(),
        UUID.fromString(original.getTargetFenceUuid()),
        roster);
  }

  /** Storage proof only: the real authenticated client capability is independently qualified. */
  private static VerifiedRecovery verifiedRecovery(
      CanonicalGameplayRosterRecoveryRequest request,
      CanonicalGameplayRosterRecoveryResponse response) {
    var verified = org.mockito.Mockito.mock(VerifiedRecovery.class);
    org.mockito.Mockito.when(verified.request()).thenReturn(request);
    org.mockito.Mockito.when(verified.response()).thenReturn(response);
    return verified;
  }

  private List<String> history() {
    return history(dsl);
  }

  private List<String> history(DSLContext context) {
    return context
        .fetch(
            "SELECT version, script, checksum, installed_rank, success FROM flyway_schema_history ORDER BY installed_rank")
        .map(row -> row.intoMap().toString());
  }

  private String upgradePredecessorRow(
      DSLContext context, GetCanonicalGameplayRosterReferenceRequest request) {
    return context
        .fetchSingle(
            "SELECT (to_jsonb(s) - 'recovery_last_attempt_at')::text AS predecessor_columns, xmin::text AS row_version FROM game_session_canonical_roster_selection s WHERE operation_uuid = ?",
            UUID.fromString(request.getOperationUuid()))
        .intoMap()
        .toString();
  }

  private String upgradePredecessorColumns(
      DSLContext context, GetCanonicalGameplayRosterReferenceRequest request) {
    return context
        .fetchSingle(
            "SELECT (to_jsonb(s) - 'recovery_last_attempt_at')::text AS predecessor_columns FROM game_session_canonical_roster_selection s WHERE operation_uuid = ?",
            UUID.fromString(request.getOperationUuid()))
        .get("predecessor_columns", String.class);
  }

  private String upgradeRow(
      DSLContext context, GetCanonicalGameplayRosterReferenceRequest request) {
    return context
        .fetchSingle(
            "SELECT encode(original_request, 'hex') AS original_request_hex, entry_policy, "
                + "encode(selection_input, 'hex') AS selection_input_hex, selected_character_uuid::text, "
                + "encode(canonical_roster_receipt, 'hex') AS roster_receipt_hex, "
                + "encode(canonical_player_created_roster_receipt, 'hex') AS player_created_roster_receipt_hex, "
                + "encode(canonical_selected_assignment_receipt, 'hex') AS assignment_receipt_hex, "
                + "encode(canonical_selected_player_created_character_receipt, 'hex') AS player_created_character_receipt_hex, "
                + "receipt_recorded_at::text, recovery_uuid::text, recovery_started_at::text, "
                + "encode(recovery_receipt, 'hex') AS recovery_receipt_hex, terminal_outcome, terminal_at::text, xmin::text "
                + "FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
            UUID.fromString(request.getOperationUuid()))
        .intoMap()
        .toString();
  }

  private <T> T upgradeRowValue(
      DSLContext context,
      GetCanonicalGameplayRosterReferenceRequest request,
      String column,
      Class<T> type) {
    String projection =
        switch (column) {
          case "recovery_uuid" -> "recovery_uuid::text";
          case "recovery_last_attempt_at" -> "recovery_last_attempt_at::text";
          default -> throw new IllegalArgumentException("unsupported fixture column");
        };
    return context
        .fetchSingle(
            "SELECT "
                + projection
                + " AS value FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
            UUID.fromString(request.getOperationUuid()))
        .get("value", type);
  }

  private String retainedRow(GetCanonicalGameplayRosterReferenceRequest request) {
    return dsl.fetchSingle(
            "SELECT encode(original_request, 'hex') AS original_request_hex, owner_fence, encode(canonical_roster_receipt, 'hex') AS roster_receipt_hex, "
                + "recorded_at, receipt_recorded_at, encode(selection_input, 'hex') AS selection_input_hex, selected_character_uuid, encode(canonical_selected_assignment_receipt, 'hex') AS assignment_receipt_hex, xmin::text FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
            UUID.fromString(request.getOperationUuid()))
        .intoMap()
        .toString();
  }

  private org.jooq.Record recoveryScheduleRow(String operationUuid) {
    return dsl.fetchSingle(
        "SELECT recovery_uuid::text AS recovery_uuid, recovery_started_at::text AS recovery_started_at, "
            + "recovery_last_attempt_at::text AS recovery_last_attempt_at "
            + "FROM game_session_canonical_roster_selection WHERE operation_uuid = ?",
        UUID.fromString(operationUuid));
  }

  private List<Object> fencePolicy(GetCanonicalGameplayRosterReferenceRequest request) {
    byte[] binding =
        request.toBuilder().clearOperationUuid().clearSessionUuid().build().toByteArray();
    return dsl.fetchSingle(
            "SELECT minimum_retention_millis, replay_horizon_millis, fence_state FROM game_session_canonical_roster_target_fences WHERE target_binding = ?",
            binding)
        .intoList();
  }

  private CanonicalGameplayRosterReferenceRepository repository(
      net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferencePolicy policy) {
    return new CanonicalGameplayRosterReferenceRepository(dsl, policy);
  }

  private static net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferencePolicy
      policy(long minimumRetentionMillis, long retryHorizonMillis, long restoreHorizonMillis) {
    return new net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferencePolicy(
        java.time.Duration.ofMillis(minimumRetentionMillis),
        java.time.Duration.ofMillis(retryHorizonMillis),
        java.time.Duration.ofMillis(restoreHorizonMillis),
        java.time.Duration.ofSeconds(1),
        8);
  }

  private void migrate(String target) {
    migrate(dataSource, schema, target);
  }

  private static Flyway migrate(
      DriverManagerDataSource selectedSource, String selectedSchema, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(selectedSource)
            .schemas(selectedSchema)
            .defaultSchema(selectedSchema)
            .locations(
                "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath(),
                "filesystem:"
                    + Path.of("../common-saga/src/main/resources/db/migration/saga")
                        .toAbsolutePath())
            .placeholders(java.util.Map.of("serviceSchema", selectedSchema))
            .target(target);
    configuration
        .getPluginRegister()
        .getPlugin(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    var flyway = configuration.load();
    flyway.migrate();
    return flyway;
  }

  private static CanonicalGameplayRosterResponse receipt(
      GetCanonicalGameplayRosterReferenceRequest request) {
    return CanonicalGameplayRosterResponse.newBuilder()
        .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
        .setSnapshotUuid(UUID.randomUUID().toString())
        .setSnapshotDigest("d".repeat(64))
        .setTarget(CanonicalGameplayRosterReferenceEvidence.target(request))
        .build();
  }

  private static GetCanonicalGameplayRosterReferenceRequest request() {
    var target =
        CanonicalGameplayRosterTarget.newBuilder()
            .setTenantUuid(UUID.randomUUID().toString())
            .setRealmUuid(UUID.randomUUID().toString())
            .setGameInstanceUuid(UUID.randomUUID().toString())
            .setCanonicalVersionUuid(UUID.randomUUID().toString())
            .setPlayableStateNamespaceUuid(UUID.randomUUID().toString())
            .setWorldSlug("earth")
            .setRealmSlug("main")
            .setCatalogRevision(7L)
            .setPointerVersion(11L)
            .setActiveWorldEpoch(13L)
            .setPublishedPolicyDigest("a".repeat(64))
            .setPublishedOwnerProofDigest("b".repeat(64))
            .setAdmissionPointerSnapshotDigest("c".repeat(64))
            .setPublishedReleaseBundleRef("release:exact")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setEntryPolicy(
                CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
            .build();
    return CanonicalGameplayRosterReferenceEvidence.request(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "gameplay", target);
  }
}
