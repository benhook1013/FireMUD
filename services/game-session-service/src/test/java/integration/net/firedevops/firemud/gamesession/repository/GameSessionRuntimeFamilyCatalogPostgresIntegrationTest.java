package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.gamesession.repository.CanonicalHumanPublicationOwnerTestFixtures;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionTestFixture;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog.FactKind;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog.FreshDisposition;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog.GapId;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog.RetentionDisposition;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog.Role;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog.StateClass;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real combined Game Session/Saga filesystem/PostgreSQL source tests, never an H2 pg_catalog model.
 * Populated predecessor identities/bytes are controlled retention fixtures. Private catalog rows
 * use real Game Session owners with stipulated upstream publication evidence, not private
 * credential issuance, Account/World authority, Redis custody, process-death recovery or
 * whole-FRESH proof. Positive tests deliberately do not skip when Docker is absent. Execution is
 * delegated to Main.
 */
@Testcontainers(disabledWithoutDocker = false)
@SuppressWarnings("resource")
class GameSessionRuntimeFamilyCatalogPostgresIntegrationTest {
  private static final String MIGRATIONS =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String SAGA_MIGRATIONS =
      "filesystem:"
          + Path.of("../common-saga/src/main/resources/db/migration/saga")
              .toAbsolutePath()
              .normalize();
  private static final String HISTORY = "flyway_schema_history_game_session_service";
  private static final String SNAPSHOTS = "game_session_runtime_family_catalog_snapshot";
  private static final String EPOCHS = "game_session_runtime_family_catalog_epoch";
  private static final String CLASSIFICATIONS = "game_session_runtime_family_classification";
  private static final String GAPS = "game_session_runtime_catalog_non_sql_gap";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @BeforeAll
  static void awaitReady() {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(postgres);
  }

  @Test
  void currentCombinedChainSealsEveryEmptyTableActualRenamesComponentsAndExplicitBlockingGaps() {
    try (Fixture f = fixture(null)) {
      Map<String, String> before = f.rows(f.tables());
      var evidence = f.owner().capture(request());
      var catalog = evidence.catalog();
      assertThat(catalog.catalogEpoch()).isEqualTo(22);
      assertThat(
              catalog.schemaFacts().stream()
                  .filter(fact -> fact.kind() == FactKind.TABLE)
                  .map(GameSessionRuntimeFamilyCatalog.SchemaFact::identity)
                  .toList())
          .containsExactlyInAnyOrderElementsOf(f.tables());
      assertThat(catalog.families().stream().filter(family -> family.component().isEmpty()).count())
          .isEqualTo(f.tables().size());
      for (String name : List.of("gameplay_command", "remote_followup")) {
        assertThat(catalog.families())
            .anySatisfy(
                family -> {
                  assertThat(family.familyId()).isEqualTo(name);
                  assertThat(family.stateClass()).isEqualTo(StateClass.UNKNOWN);
                  assertThat(family.freshDisposition())
                      .isEqualTo(FreshDisposition.STATE_CLASS_UNKNOWN_BLOCKING);
                });
      }
      assertThat(catalog.families())
          .anySatisfy(
              family -> {
                assertThat(family.familyId()).isEqualTo("game_manifest");
                assertThat(family.stateClass()).isEqualTo(StateClass.UNRESOLVED);
                assertThat(family.freshDisposition())
                    .isEqualTo(FreshDisposition.UNSUPPORTED_SOURCE_BLOCKING);
                assertThat(family.ownerConsequence())
                    .contains("legacy", "tenant", "Not build or FRESH authority");
              });
      for (String name :
          List.of(
              "tick_batch",
              "tick_effect",
              "remote_followup_result",
              "script_pin_operation",
              "game_session_region_runtime_status",
              "game_session_region_timeline_preparation")) {
        assertThat(catalog.families())
            .anySatisfy(
                family -> {
                  assertThat(family.familyId()).isEqualTo(name);
                  assertThat(family.freshDisposition())
                      .isEqualTo(FreshDisposition.RUNTIME_EVIDENCE_BLOCKING);
                });
      }
      assertThat(catalog.families())
          .anySatisfy(
              family -> {
                assertThat(family.familyId()).isEqualTo("player_command_history");
                assertThat(family.role()).isEqualTo(Role.AUDIT);
              });
      for (String name :
          List.of(
              "saga_instance",
              "saga_step",
              HISTORY,
              "gs_canonical_account_coverage_snapshot_issuer_obligation",
              "gs_canonical_account_coverage_snapshot_region_obligation",
              "game_session_canonical_account_admission_fence_intent_cohort_ro",
              "game_session_canonical_account_admission_fence_intent_barrier_t",
              "game_session_account_fence_final_observation",
              "game_instances.script_patch_version",
              "gameplay_command.declared_effects_json",
              "game_session_canonical_binding_transition.admission_request_id",
              "game_session_canonical_realm_catalog.private_preparation_request_id",
              "game_session_private_catalog_revision_history",
              "game_session_current_private_catalog_revisions",
              "game_session_original_new_admitted_logout_intent")) {
        assertThat(catalog.families()).anyMatch(family -> family.familyId().equals(name));
      }
      assertPrivateRevisionClassifications(catalog);
      assertPrivateRuntimeClassification(catalog);
      assertThat(catalog.families())
          .noneMatch(
              family ->
                  family.familyId().equals("redis_session")
                      || family.familyId().equals("tick_queue")
                      || family.familyId().equals("issuer_redis_index"));
      assertThat(catalog.families())
          .allSatisfy(family -> assertThat(family.ownerConsequence()).isNotBlank());
      assertThat(catalog.nonSqlGaps())
          .extracting(GameSessionRuntimeFamilyCatalog.NonSqlGap::id)
          .containsExactly(GapId.values());
      assertThat(catalog.schemaFacts())
          .anyMatch(fact -> fact.kind() == FactKind.ROUTINE)
          .anyMatch(fact -> fact.kind() == FactKind.MIGRATION)
          .anyMatch(
              fact ->
                  fact.kind() == FactKind.TRIGGER
                      && fact.identity()
                          .equals(
                              "game_session_region_owner_transfer.gs_region_owner_transfer_frozen_insert"))
          .anyMatch(
              fact ->
                  fact.kind() == FactKind.TRIGGER
                      && fact.identity()
                          .equals(
                              "game_session_original_new_capacity_execution.gs_capacity_execution_initial_dispatch"))
          .anyMatch(fact -> fact.kind() == FactKind.SEQUENCE);
      assertThat(
              catalog.schemaFacts().stream()
                  .filter(fact -> fact.kind() == FactKind.MIGRATION)
                  .map(GameSessionRuntimeFamilyCatalog.SchemaFact::identity)
                  .toList())
          .contains(
              "catalog-installation:1057",
              "catalog-installation:1058",
              "catalog-installation:1059",
              "catalog-installation:1060",
              "catalog-installation:1061",
              "catalog-installation:1062",
              "catalog-installation:1063",
              "catalog-installation:1064",
              "catalog-installation:1065",
              "catalog-installation:1065.0.1",
              "catalog-installation:1065.0.2",
              "catalog-installation:1065.0.3",
              "catalog-installation:1065.0.4",
              "catalog-installation:1065.0.5",
              "catalog-installation:1065.0.6",
              "catalog-installation:1065.0.7",
              "catalog-installation:1065.0.8",
              "catalog-installation:1065.0.9",
              "catalog-installation:1065.0.10",
              "catalog-installation:1065.1",
              "catalog-installation:1065.2",
              "catalog-installation:1066")
          .noneMatch(
              identity ->
                  identity.contains(":1057:")
                      || identity.contains(":1058:")
                      || identity.contains(":1059:")
                      || identity.contains(":1060:")
                      || identity.contains(":1061:")
                      || identity.contains(":1062:")
                      || identity.contains(":1063:")
                      || identity.contains(":1064:")
                      || identity.contains(":1065:")
                      || identity.contains(":1065.0.1:")
                      || identity.contains(":1065.0.2:")
                      || identity.contains(":1065.0.3:")
                      || identity.contains(":1065.0.4:")
                      || identity.contains(":1065.0.5:")
                      || identity.contains(":1065.0.6:")
                      || identity.contains(":1065.0.7:")
                      || identity.contains(":1065.0.8:")
                      || identity.contains(":1065.0.9:")
                      || identity.contains(":1065.0.10:")
                      || identity.contains(":1065.1:")
                      || identity.contains(":1065.2:")
                      || identity.contains(":1066:"));
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT catalog_bytes=gs_runtime_family_catalog_bytes(catalog_epoch) FROM "
                          + EPOCHS
                          + " ORDER BY catalog_epoch DESC LIMIT 1")
                  .get(0, Boolean.class))
          .isTrue();
      before.remove(SNAPSHOTS);
      assertThat(f.rows(before.keySet())).isEqualTo(before);
      for (String table :
          List.of(
              "game_instances",
              "gameplay_admission_pointer",
              "game_session_canonical_realm_catalog",
              "game_session_initial_playtest_preparations",
              "game_session_private_playtest_selections",
              "game_session_current_private_playtests",
              "gameplay_command",
              "tick_batch",
              "tick_effect")) {
        assertThat(f.count(table)).as(table).isZero();
      }
    }
  }

  @Test
  void actualConfiguredHistoryIsBoundWithoutHardcodedHistoryAlias() {
    for (String history :
        List.of("flyway_schema_history_public", "flyway_schema_history_custom_gs")) {
      try (Fixture f = fixture(null, history)) {
        var evidence = f.owner().capture(request());
        assertThat(evidence.catalog().families())
            .anySatisfy(
                family -> {
                  assertThat(family.familyId()).isEqualTo(history);
                  assertThat(family.freshDisposition()).isEqualTo(FreshDisposition.CONTROL_ONLY);
                });
        assertThat(f.owner().readExact(evidence.request())).isEqualTo(evidence);
        assertThat(
                f.dsl
                    .fetchSingle("SELECT to_regclass(?) IS NULL", f.schema + "." + HISTORY)
                    .get(0, Boolean.class))
            .isTrue();
      }
    }
  }

  @Test
  void exactReplayReconstructionAndIndependentReadbackRetainSnapshotAndRejectChangedNamespace() {
    try (Fixture f = fixture(null)) {
      Request read = request();
      var original = f.owner().capture(read);
      assertThat(f.owner().capture(read)).isEqualTo(original);
      assertThat(f.owner().readExact(read)).isEqualTo(original);
      assertThat(f.count(SNAPSHOTS)).isEqualTo(1);
      var another = f.owner().capture(request());
      assertThat(another.snapshotId()).isNotEqualTo(original.snapshotId());
      assertThat(another.catalog().exactBytes()).isEqualTo(original.catalog().exactBytes());
      assertThat(f.evidence(read))
          .isEqualTo(GameSessionRuntimeFamilyCatalogCodec.evidenceBytes(original));
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT evidence_digest FROM " + SNAPSHOTS + " WHERE read_request_id=?",
                      read.readRequestId())
                  .get(0, String.class))
          .isEqualTo(original.digest());
      var foreignConfig =
          new GameSessionRuntimeFamilyCatalogRepository(f.dsl, f.manager, f.schema, "other");
      assertThatThrownBy(() -> foreignConfig.capture(new Request(read.readRequestId(), "other")))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThatThrownBy(() -> f.owner().readExact(request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThat(f.count(SNAPSHOTS)).isEqualTo(2);
    }
  }

  @Test
  void concurrentExactRetriesConvergeOnOneCommittedSnapshot() throws Exception {
    try (Fixture f = fixture(null);
        var workers = Executors.newFixedThreadPool(2)) {
      Request read = request();
      var start = new CountDownLatch(1);
      var first =
          workers.submit(
              () -> {
                start.await();
                return f.owner().capture(read);
              });
      var second =
          workers.submit(
              () -> {
                start.await();
                return f.owner().capture(read);
              });
      start.countDown();
      var evidence = first.get(30, TimeUnit.SECONDS);
      assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(evidence);
      assertThat(f.owner().readExact(read)).isEqualTo(evidence);
      assertThat(f.count(SNAPSHOTS)).isEqualTo(1);
    }
  }

  @Test
  void populatedV1056UpgradePreservesAllOriginalRowsHistorySequencesAndGuards() {
    try (Fixture f = fixture("1056")) {
      f.populatePredecessor();
      f.populatePrivateCatalogAllocation();
      Map<String, String> before = f.rows(f.tables());
      before.remove(f.history);
      for (String table :
          List.of(
              "game_instances",
              "game_manifest",
              "gameplay_command",
              "player_command_history",
              "resume_transcript_entry",
              "script_pin_operation",
              "tick_batch",
              "tick_effect",
              "remote_followup",
              "remote_followup_result",
              "game_session_start_session_operator_attempt",
              "game_session_initial_gameplay_login_journal",
              "saga_instance",
              "saga_step")) {
        assertThat(before.get(table)).as(table).isNotEqualTo("[]");
      }
      List<String> history = f.historyRows(false);
      List<String> sequences = f.sequencePositions();
      Map<String, String> guards = f.guards();
      Map<String, String> routines = f.routines();
      String sourceGuard = "game_session_initial_playtest_source_guard()";
      String originalSourceGuard = routines.remove(sourceGuard);
      assertThat(originalSourceGuard).isNotNull();
      Map<String, String> constraints = f.constraints();
      Map<String, String> indexes = f.indexes();
      migration(f.source, f.schema, f.history, "1059").migrate();
      assertThat(f.rows(before.keySet())).isEqualTo(before);
      assertThat(f.historyRows(true)).isEqualTo(history);
      assertThat(f.sequencePositions()).isEqualTo(sequences);
      assertThat(f.guards()).containsAllEntriesOf(guards);
      Map<String, String> installedRoutines = f.routines();
      assertThat(installedRoutines).containsAllEntriesOf(routines);
      assertThat(installedRoutines.get(sourceGuard))
          .isNotEqualTo(originalSourceGuard)
          .contains("target.public_production");
      assertThat(f.constraints()).containsAllEntriesOf(constraints);
      assertThat(f.indexes()).containsAllEntriesOf(indexes);
      var original = f.owner().capture(request());
      assertThat(original.catalog().catalogEpoch()).isEqualTo(3);
      assertThat(f.owner().readExact(original.request())).isEqualTo(original);
      migration(f.source, f.schema, f.history, "1059").validate();

      String sourceGuardAtV1059 = f.routines().get(sourceGuard);
      Map<String, String> rowsBeforeV1060 = f.rows(f.tables());
      rowsBeforeV1060.remove(f.history);
      rowsBeforeV1060.remove(EPOCHS);
      rowsBeforeV1060.remove(CLASSIFICATIONS);
      Map<String, String> classificationsBeforeV1060 = f.classificationRows();
      List<String> epochsBeforeV1060 =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                      + EPOCHS
                      + " e WHERE catalog_epoch<=3 ORDER BY catalog_epoch")
              .getValues(0, String.class);
      List<String> snapshotsBeforeV1060 =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(s)::text||':'||xmin::text FROM "
                      + SNAPSHOTS
                      + " s ORDER BY read_request_id")
              .getValues(0, String.class);
      List<String> historyBeforeV1060 = f.historyRows(false);
      byte[] evidenceBeforeV1060 = f.evidence(original.request());

      migration(f.source, f.schema, f.history, "1060").migrate();
      assertThat(f.rows(rowsBeforeV1060.keySet())).isEqualTo(rowsBeforeV1060);
      assertV1060Classifications(f, classificationsBeforeV1060);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                          + EPOCHS
                          + " e WHERE catalog_epoch<=3 ORDER BY catalog_epoch")
                  .getValues(0, String.class))
          .isEqualTo(epochsBeforeV1060);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(s)::text||':'||xmin::text FROM "
                          + SNAPSHOTS
                          + " s ORDER BY read_request_id")
                  .getValues(0, String.class))
          .isEqualTo(snapshotsBeforeV1060);
      List<String> historyAfterV1060 = f.historyRows(false);
      assertThat(historyAfterV1060).hasSize(historyBeforeV1060.size() + 1);
      assertThat(historyAfterV1060.subList(0, historyBeforeV1060.size()))
          .isEqualTo(historyBeforeV1060);
      assertThat(
              f.dsl
                  .fetchSingle("SELECT count(*) FROM " + f.history + " WHERE version='1060'")
                  .get(0, Long.class))
          .isEqualTo(1L);
      var installedV1060 =
          f.dsl.fetchSingle(
              "SELECT type,script,success FROM " + f.history + " WHERE version='1060'");
      assertThat(installedV1060.get("type", String.class)).isEqualTo("SQL");
      assertThat(installedV1060.get("script", String.class))
          .isEqualTo("V1060__private_catalog_lifecycle_revisions.sql");
      assertThat(installedV1060.get("success", Boolean.class)).isTrue();
      assertThat(f.routines()).containsAllEntriesOf(routines);
      assertThat(f.routines().get(sourceGuard)).isEqualTo(sourceGuardAtV1059);
      assertThat(f.guards()).containsAllEntriesOf(guards);
      assertThat(f.constraints()).containsAllEntriesOf(constraints);
      assertThat(f.indexes()).containsAllEntriesOf(indexes);
      assertThat(f.sequencePositions()).containsAll(sequences);
      assertThat(f.tables())
          .contains(
              "game_session_private_catalog_revision_history",
              "game_session_current_private_catalog_revisions");
      assertInitialPrivateCatalogRevisionHeads(f);
      assertThat(
              f.dsl
                  .fetch("SELECT catalog_epoch FROM " + EPOCHS + " ORDER BY catalog_epoch")
                  .getValues(0, Long.class))
          .containsExactly(1L, 2L, 3L, 4L);
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT catalog_bytes=gs_runtime_family_catalog_bytes(catalog_epoch) FROM "
                          + EPOCHS
                          + " WHERE catalog_epoch=4")
                  .get(0, Boolean.class))
          .isTrue();
      assertThat(f.count(EPOCHS)).isEqualTo(4);
      assertThat(f.count(SNAPSHOTS)).isEqualTo(1);
      assertThatThrownBy(() -> f.owner().readExact(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThatThrownBy(() -> f.owner().capture(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThat(f.evidence(original.request())).isEqualTo(evidenceBeforeV1060);
      var current = f.owner().capture(request());
      assertThat(current.catalog().catalogEpoch()).isEqualTo(4);
      assertPrivateRevisionClassifications(current.catalog());
      assertThat(f.owner().readExact(current.request())).isEqualTo(current);
      assertThat(f.count(SNAPSHOTS)).isEqualTo(2);
      migration(f.source, f.schema, f.history, "1060").validate();
      assertThat(f.rows(before.keySet())).isEqualTo(before);
      assertThat(f.sequencePositions()).containsAll(sequences);
      // Original authority/replay/hold guards remain effective, not merely present by name.
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "UPDATE game_session_start_session_operator_attempt SET owner_fence=owner_fence+1"))
          .hasMessageContaining("immutable");
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "UPDATE game_session_start_session_operator_attempt SET lease_expires_at=lease_expires_at+interval '1 hour'"))
          .hasMessageContaining("immutable");
      assertThatThrownBy(
              () -> f.dsl.execute("DELETE FROM game_session_start_session_operator_attempt"))
          .hasMessageContaining("immutable");
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "UPDATE game_session_initial_gameplay_login_journal SET expected_attempt_fence=2"))
          .hasMessageContaining("cannot be replaced or reopened");
      assertThatThrownBy(
              () -> f.dsl.execute("DELETE FROM game_session_initial_gameplay_login_journal"))
          .hasMessageContaining("retention is held");
      assertThatThrownBy(
              () -> f.dsl.execute("TRUNCATE game_session_initial_gameplay_login_journal CASCADE"))
          .hasMessageContaining("retention is held");
      assertThat(f.rows(before.keySet())).isEqualTo(before);
    }
  }

  @Test
  void genuineV1057ThroughV1060UpgradesRetainOldEvidenceBeforeSealingEpochFour() {
    try (Fixture f = fixture("1057")) {
      f.populatePredecessor();
      f.populatePrivateCatalogAllocation();
      var original = f.owner().capture(request());
      assertThat(original.catalog().catalogEpoch()).isEqualTo(1);
      Map<String, String> before = f.rows(f.tables());
      before.remove(f.history);
      before.remove(EPOCHS);
      String originalEpoch =
          f.dsl
              .fetchSingle(
                  "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                      + EPOCHS
                      + " e WHERE catalog_epoch=1")
              .get(0, String.class);
      String originalSnapshot =
          f.dsl
              .fetchSingle("SELECT to_jsonb(s)::text||':'||xmin::text FROM " + SNAPSHOTS + " s")
              .get(0, String.class);
      List<String> history = f.historyRows(false);
      List<String> sequences = f.sequencePositions();
      Map<String, String> guards = f.guards();
      Map<String, String> constraints = f.constraints();
      Map<String, String> indexes = f.indexes();

      migration(f.source, f.schema, f.history, "1058").migrate();
      assertThat(f.rows(before.keySet())).isEqualTo(before);
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                          + EPOCHS
                          + " e WHERE catalog_epoch=1")
                  .get(0, String.class))
          .isEqualTo(originalEpoch);
      assertThat(
              f.dsl
                  .fetchSingle("SELECT to_jsonb(s)::text||':'||xmin::text FROM " + SNAPSHOTS + " s")
                  .get(0, String.class))
          .isEqualTo(originalSnapshot);
      assertThat(f.historyRows(false)).hasSize(history.size() + 1);
      assertThat(f.historyRows(false).subList(0, history.size())).isEqualTo(history);
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT script FROM " + f.history + " WHERE version='1058' AND success")
                  .get(0, String.class))
          .isEqualTo("V1058__game_session_catalog_view_options.sql");
      assertThat(f.sequencePositions()).isEqualTo(sequences);
      assertThat(f.guards()).isEqualTo(guards);
      assertThat(f.constraints()).isEqualTo(constraints);
      assertThat(f.indexes()).isEqualTo(indexes);
      assertThatThrownBy(() -> f.owner().readExact(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThatThrownBy(() -> f.owner().capture(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThat(f.evidence(original.request()))
          .isEqualTo(GameSessionRuntimeFamilyCatalogCodec.evidenceBytes(original));
      assertThat(f.count(SNAPSHOTS)).isEqualTo(1);
      var current = f.owner().capture(request());
      assertThat(current.catalog().catalogEpoch()).isEqualTo(2);
      assertThat(current.catalog().digest()).isNotEqualTo(original.catalog().digest());
      assertThat(f.owner().readExact(current.request())).isEqualTo(current);
      assertThat(f.count(EPOCHS)).isEqualTo(2);
      assertThat(f.count(SNAPSHOTS)).isEqualTo(2);
      migration(f.source, f.schema, f.history, "1058").validate();

      Map<String, String> rowsBeforeV1059 = f.rows(f.tables());
      rowsBeforeV1059.remove(f.history);
      rowsBeforeV1059.remove(EPOCHS);
      List<String> epochsBeforeV1059 =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                      + EPOCHS
                      + " e ORDER BY catalog_epoch")
              .getValues(0, String.class);
      List<String> snapshotsBeforeV1059 =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(s)::text||':'||xmin::text FROM "
                      + SNAPSHOTS
                      + " s ORDER BY read_request_id")
              .getValues(0, String.class);
      List<String> historyBeforeV1059 = f.historyRows(false);
      byte[] originalEvidence = f.evidence(original.request());
      byte[] currentEvidence = f.evidence(current.request());

      migration(f.source, f.schema, f.history, "1059").migrate();
      assertThat(f.rows(rowsBeforeV1059.keySet())).isEqualTo(rowsBeforeV1059);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                          + EPOCHS
                          + " e WHERE catalog_epoch IN (1,2) ORDER BY catalog_epoch")
                  .getValues(0, String.class))
          .isEqualTo(epochsBeforeV1059);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(s)::text||':'||xmin::text FROM "
                          + SNAPSHOTS
                          + " s ORDER BY read_request_id")
                  .getValues(0, String.class))
          .isEqualTo(snapshotsBeforeV1059);
      List<String> historyAfterV1059 = f.historyRows(false);
      assertThat(historyAfterV1059).hasSize(historyBeforeV1059.size() + 1);
      assertThat(historyAfterV1059.subList(0, historyBeforeV1059.size()))
          .isEqualTo(historyBeforeV1059);
      assertThat(
              f.dsl
                  .fetchSingle("SELECT count(*) FROM " + f.history + " WHERE version='1059'")
                  .get(0, Long.class))
          .isEqualTo(1L);
      var installedV1059 =
          f.dsl.fetchSingle(
              "SELECT type,script,success FROM " + f.history + " WHERE version='1059'");
      assertThat(installedV1059.get("type", String.class)).isEqualTo("SQL");
      assertThat(installedV1059.get("script", String.class))
          .isEqualTo("V1059__private_lifecycle_preparation_after_catalog_allocation.sql");
      assertThat(installedV1059.get("success", Boolean.class)).isTrue();
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT catalog_bytes=gs_runtime_family_catalog_bytes(catalog_epoch) FROM "
                          + EPOCHS
                          + " WHERE catalog_epoch=3")
                  .get(0, Boolean.class))
          .isTrue();
      assertThat(f.count(EPOCHS)).isEqualTo(3);
      assertThat(f.count(SNAPSHOTS)).isEqualTo(2);
      for (var old : List.of(original, current)) {
        assertThatThrownBy(() -> f.owner().readExact(old.request()))
            .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
        assertThatThrownBy(() -> f.owner().capture(old.request()))
            .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      }
      assertThat(f.evidence(original.request())).isEqualTo(originalEvidence);
      assertThat(f.evidence(current.request())).isEqualTo(currentEvidence);

      var latest = f.owner().capture(request());
      assertThat(latest.catalog().catalogEpoch()).isEqualTo(3);
      assertThat(latest.catalog().digest()).isNotEqualTo(current.catalog().digest());
      assertThat(f.owner().readExact(latest.request())).isEqualTo(latest);
      assertThat(f.count(SNAPSHOTS)).isEqualTo(3);
      migration(f.source, f.schema, f.history, "1059").validate();

      Map<String, String> rowsBeforeV1060 = f.rows(f.tables());
      rowsBeforeV1060.remove(f.history);
      rowsBeforeV1060.remove(EPOCHS);
      rowsBeforeV1060.remove(CLASSIFICATIONS);
      Map<String, String> classificationsBeforeV1060 = f.classificationRows();
      List<String> epochsBeforeV1060 =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                      + EPOCHS
                      + " e WHERE catalog_epoch<=3 ORDER BY catalog_epoch")
              .getValues(0, String.class);
      List<String> snapshotsBeforeV1060 =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(s)::text||':'||xmin::text FROM "
                      + SNAPSHOTS
                      + " s ORDER BY read_request_id")
              .getValues(0, String.class);
      List<String> historyBeforeV1060 = f.historyRows(false);
      byte[] latestEvidence = f.evidence(latest.request());

      migration(f.source, f.schema, f.history, "1060").migrate();
      assertThat(f.rows(rowsBeforeV1060.keySet())).isEqualTo(rowsBeforeV1060);
      assertV1060Classifications(f, classificationsBeforeV1060);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                          + EPOCHS
                          + " e WHERE catalog_epoch<=3 ORDER BY catalog_epoch")
                  .getValues(0, String.class))
          .isEqualTo(epochsBeforeV1060);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(s)::text||':'||xmin::text FROM "
                          + SNAPSHOTS
                          + " s ORDER BY read_request_id")
                  .getValues(0, String.class))
          .isEqualTo(snapshotsBeforeV1060);
      List<String> historyAfterV1060 = f.historyRows(false);
      assertThat(historyAfterV1060).hasSize(historyBeforeV1060.size() + 1);
      assertThat(historyAfterV1060.subList(0, historyBeforeV1060.size()))
          .isEqualTo(historyBeforeV1060);
      assertThat(
              f.dsl
                  .fetchSingle("SELECT count(*) FROM " + f.history + " WHERE version='1060'")
                  .get(0, Long.class))
          .isEqualTo(1L);
      var installedV1060 =
          f.dsl.fetchSingle(
              "SELECT type,script,success FROM " + f.history + " WHERE version='1060'");
      assertThat(installedV1060.get("type", String.class)).isEqualTo("SQL");
      assertThat(installedV1060.get("script", String.class))
          .isEqualTo("V1060__private_catalog_lifecycle_revisions.sql");
      assertThat(installedV1060.get("success", Boolean.class)).isTrue();
      assertThat(
              f.dsl
                  .fetch("SELECT catalog_epoch FROM " + EPOCHS + " ORDER BY catalog_epoch")
                  .getValues(0, Long.class))
          .containsExactly(1L, 2L, 3L, 4L);
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT catalog_bytes=gs_runtime_family_catalog_bytes(catalog_epoch) FROM "
                          + EPOCHS
                          + " WHERE catalog_epoch=4")
                  .get(0, Boolean.class))
          .isTrue();
      assertThat(f.count(EPOCHS)).isEqualTo(4);
      assertThat(f.count(SNAPSHOTS)).isEqualTo(3);
      assertThat(f.tables())
          .contains(
              "game_session_private_catalog_revision_history",
              "game_session_current_private_catalog_revisions");
      assertInitialPrivateCatalogRevisionHeads(f);
      for (var old : List.of(original, current, latest)) {
        assertThatThrownBy(() -> f.owner().readExact(old.request()))
            .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
        assertThatThrownBy(() -> f.owner().capture(old.request()))
            .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      }
      assertThat(f.evidence(latest.request())).isEqualTo(latestEvidence);
      assertThat(f.evidence(original.request()))
          .isEqualTo(GameSessionRuntimeFamilyCatalogCodec.evidenceBytes(original));
      assertThat(f.evidence(current.request()))
          .isEqualTo(GameSessionRuntimeFamilyCatalogCodec.evidenceBytes(current));
      var currentV1060 = f.owner().capture(request());
      assertThat(currentV1060.catalog().catalogEpoch()).isEqualTo(4);
      assertPrivateRevisionClassifications(currentV1060.catalog());
      assertThat(f.owner().readExact(currentV1060.request())).isEqualTo(currentV1060);
      assertThat(f.count(SNAPSHOTS)).isEqualTo(4);
      migration(f.source, f.schema, f.history, "1060").validate();
    }
  }

  @Test
  void viewOptionsDriftRefusesReadsRetriesAndNewRequestsWithoutChangingRetainedRows() {
    for (String change :
        List.of(
            "ALTER VIEW catalog_probe_view SET (security_invoker=true)",
            "ALTER VIEW catalog_probe_view SET (security_barrier=true)",
            "ALTER MATERIALIZED VIEW catalog_probe_materialized SET (fillfactor=70)")) {
      try (Fixture f = fixture("1056")) {
        // Create actual PostgreSQL views before the real catalog migrations install their seals.
        f.dsl.execute("CREATE VIEW catalog_probe_view AS SELECT id,description FROM game_manifest");
        f.dsl.execute(
            "CREATE MATERIALIZED VIEW catalog_probe_materialized AS SELECT id,description FROM game_manifest WITH NO DATA");
        migration(f.source, f.schema, f.history, null).migrate();
        var original = f.owner().capture(request());
        assertThat(original.catalog().catalogEpoch()).isEqualTo(22);
        assertThat(original.catalog().schemaFacts())
            .anyMatch(
                fact ->
                    fact.kind() == FactKind.VIEW && fact.identity().equals("catalog_probe_view"))
            .anyMatch(
                fact ->
                    fact.kind() == FactKind.VIEW
                        && fact.identity().equals("catalog_probe_materialized"));
        Map<String, String> before = f.rows(f.tables());
        var definitions =
            f.dsl.fetch(
                "SELECT relname,pg_get_viewdef(c.oid,true),reloptions::text FROM pg_class c"
                    + " JOIN pg_namespace n ON n.oid=c.relnamespace"
                    + " WHERE n.nspname=? AND c.relkind IN ('v','m') ORDER BY relname",
                f.schema);
        f.dsl.execute(change);
        var changed =
            f.dsl.fetch(
                "SELECT relname,pg_get_viewdef(c.oid,true),reloptions::text FROM pg_class c"
                    + " JOIN pg_namespace n ON n.oid=c.relnamespace"
                    + " WHERE n.nspname=? AND c.relkind IN ('v','m') ORDER BY relname",
                f.schema);
        assertThat(changed.getValues(0, String.class))
            .isEqualTo(definitions.getValues(0, String.class));
        assertThat(changed.getValues(1, String.class))
            .isEqualTo(definitions.getValues(1, String.class));
        assertThat(changed.getValues(2, String.class))
            .isNotEqualTo(definitions.getValues(2, String.class));
        assertThatThrownBy(() -> f.owner().readExact(original.request()))
            .as(change)
            .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
        assertThatThrownBy(() -> f.owner().capture(original.request()))
            .as(change)
            .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
        assertThatThrownBy(() -> f.owner().capture(request()))
            .as(change)
            .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
        // Includes every epoch, exact snapshot bytes and original installed-history row.
        assertThat(f.rows(before.keySet())).isEqualTo(before);
        assertThat(f.evidence(original.request()))
            .isEqualTo(GameSessionRuntimeFamilyCatalogCodec.evidenceBytes(original));
        assertThat(f.count(SNAPSHOTS)).isEqualTo(1);
        assertThat(f.count(EPOCHS)).isEqualTo(original.catalog().catalogEpoch());
      }
    }
  }

  @Test
  void unknownEmptyPredecessorRefusesMigrationAtomicallyWithoutHistoryRewrite() {
    try (Fixture f = fixture("1056")) {
      f.dsl.execute("CREATE TABLE unknown_runtime_family(id BIGINT PRIMARY KEY)");
      List<String> history = f.historyRows(false);
      assertThatThrownBy(() -> migration(f.source, f.schema, f.history, null).migrate())
          .hasMessageContaining("Unclassified or changed Game Session schema family");
      assertThat(
              f.dsl
                  .fetchSingle("SELECT to_regclass(?) IS NULL", f.schema + "." + EPOCHS)
                  .get(0, Boolean.class))
          .isTrue();
      assertThat(f.historyRows(false)).isEqualTo(history);
      assertThat(f.count("unknown_runtime_family")).isZero();
    }
  }

  @Test
  void evenAnEmptyUnknownTableInvalidatesReadbackAndRefusesNewCapture() {
    try (Fixture f = fixture(null)) {
      var original = f.owner().capture(request());
      f.dsl.execute("CREATE TABLE unknown_runtime_family(id BIGINT PRIMARY KEY)");
      assertThat(f.count("unknown_runtime_family")).isZero();
      assertThatThrownBy(() -> f.owner().readExact(original.request()))
          .hasMessageContaining("Unclassified or changed");
      assertThatThrownBy(() -> f.owner().capture(request()))
          .hasMessageContaining("Unclassified or changed");
      assertThat(f.evidence(original.request()))
          .isEqualTo(GameSessionRuntimeFamilyCatalogCodec.evidenceBytes(original));
      assertThat(f.count(SNAPSHOTS)).isEqualTo(1);
    }
  }

  @Test
  void addDropRenameChangeTableColumnConstraintIndexSequenceOrRoutineCannotReuseOldSeal() {
    for (String change :
        List.of(
            "ALTER TABLE game_manifest ADD COLUMN future_state TEXT",
            "ALTER TABLE game_manifest DROP COLUMN description",
            "ALTER TABLE game_manifest RENAME COLUMN description TO changed_description",
            "ALTER TABLE game_manifest ADD CONSTRAINT catalog_probe CHECK(id>0)",
            "ALTER TABLE game_manifest RENAME TO renamed_manifest",
            "DROP TABLE game_manifest",
            "CREATE INDEX catalog_probe ON game_manifest(version_id)",
            "ALTER SEQUENCE game_manifest_id_seq INCREMENT BY 2",
            "CREATE FUNCTION future_runtime_writer() RETURNS INTEGER LANGUAGE SQL AS 'SELECT 1'",
            "ALTER FUNCTION guard_initial_gameplay_login_journal() RENAME TO renamed_login_guard",
            "CREATE OR REPLACE FUNCTION guard_initial_gameplay_login_journal() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END; $$")) {
      try (Fixture f = fixture(null)) {
        var original = f.owner().capture(request());
        f.dsl.execute(change);
        assertThatThrownBy(() -> f.owner().readExact(original.request()))
            .as(change)
            .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> f.owner().capture(request()))
            .as(change)
            .isInstanceOf(RuntimeException.class);
        assertThat(f.evidence(original.request()))
            .isEqualTo(GameSessionRuntimeFamilyCatalogCodec.evidenceBytes(original));
        assertThat(f.count(SNAPSHOTS)).isEqualTo(1);
      }
    }
  }

  @Test
  void changedClassificationInvalidatesWithoutChangingRetainedEvidence() {
    try (Fixture f = fixture(null)) {
      var original = f.owner().capture(request());
      f.addComponent();
      assertThatThrownBy(() -> f.owner().readExact(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThatThrownBy(() -> f.owner().capture(request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThat(f.evidence(original.request()))
          .isEqualTo(GameSessionRuntimeFamilyCatalogCodec.evidenceBytes(original));
    }
  }

  @Test
  void priorAndLaterHistoryAndOwnSuccessfulScriptRemainSeparateInstallationChecks() {
    try (Fixture f = fixture(null)) {
      var original = f.owner().capture(request());
      f.dsl.execute("UPDATE " + f.history + " SET checksum=checksum+1 WHERE version='1'");
      assertThatThrownBy(() -> f.owner().readExact(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
    }
    for (String version :
        List.of(
            "1057",
            "1058",
            "1059",
            "1060",
            "1061",
            "1062",
            "1063",
            "1064",
            "1065",
            "1065.0.1",
            "1065.0.2",
            "1065.0.3",
            "1065.0.4",
            "1065.0.5",
            "1065.0.6",
            "1065.0.7",
            "1065.0.8",
            "1065.0.9",
            "1065.0.10",
            "1065.1",
            "1065.2",
            "1066")) {
      for (String change :
          List.of("success=false", "script='wrong_installation.sql'", "type='JDBC'")) {
        try (Fixture f = fixture(null)) {
          f.dsl.execute("UPDATE " + f.history + " SET " + change + " WHERE version=?", version);
          assertThatThrownBy(() -> f.owner().capture(request()))
              .hasMessageContaining("No durable Game Session catalog epoch");
          assertThat(f.count(SNAPSHOTS)).isZero();
        }
      }
      try (Fixture f = fixture(null)) {
        f.dsl.execute("DELETE FROM " + f.history + " WHERE version=?", version);
        assertThatThrownBy(() -> f.owner().capture(request()))
            .hasMessageContaining("No durable Game Session catalog epoch");
        assertThat(f.count(SNAPSHOTS)).isZero();
      }
    }
    try (Fixture f = fixture(null)) {
      var original = f.owner().capture(request());
      f.dsl.execute(
          "INSERT INTO "
              + f.history
              + "(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)"
              + " VALUES(10000,'1067','controlled later migration','SQL','V1067__probe.sql',1,current_user,0,true)");
      assertThatThrownBy(() -> f.owner().readExact(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
    }
    for (String version :
        List.of(
            "1057",
            "1058",
            "1059",
            "1060",
            "1061",
            "1062",
            "1063",
            "1064",
            "1065",
            "1065.0.1",
            "1065.0.2",
            "1065.0.3",
            "1065.0.4",
            "1065.0.5",
            "1065.0.6",
            "1065.0.7",
            "1065.0.8",
            "1065.0.9",
            "1065.0.10",
            "1065.1",
            "1065.2",
            "1066")) {
      try (Fixture f = fixture(null)) {
        f.dsl.execute(
            "INSERT INTO "
                + f.history
                + "(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)"
                + " SELECT 10000,version,description,type,script,checksum,installed_by,execution_time,success FROM "
                + f.history
                + " WHERE version=?",
            version);
        assertThatThrownBy(() -> f.owner().capture(request()))
            .hasMessageContaining("No durable Game Session catalog epoch");
      }
    }
  }

  @Test
  void explicitNewOwnerEpochRetainsButInvalidatesOldRequestsAndRejectsReusedOrSkippedEpochs() {
    try (Fixture f = fixture(null)) {
      var original = f.owner().capture(request());
      long currentEpoch = original.catalog().catalogEpoch();
      long nextEpoch = currentEpoch + 1;
      long previousEpochCount = f.count(EPOCHS);
      f.addComponent();
      for (long invalid : new long[] {1, currentEpoch, nextEpoch + 1}) {
        assertThatThrownBy(() -> f.installEpoch(invalid))
            .hasMessageContaining("exact classified installed schema");
      }
      // Controlled explicit owner revision, not an exposed registry/runtime mutation API.
      f.installEpoch(nextEpoch);
      assertThatThrownBy(() -> f.owner().capture(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThatThrownBy(() -> f.owner().readExact(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThat(f.evidence(original.request()))
          .isEqualTo(GameSessionRuntimeFamilyCatalogCodec.evidenceBytes(original));
      var revised = f.owner().capture(request());
      assertThat(revised.catalog().catalogEpoch()).isEqualTo(nextEpoch);
      assertThat(revised.catalog().digest()).isNotEqualTo(original.catalog().digest());
      assertThat(f.count(EPOCHS)).isEqualTo(previousEpochCount + 1);
    }
  }

  @Test
  void metadataEpochAndSnapshotGuardsRefuseMutationStaleBytesAndChangedExactSqlFrames() {
    try (Fixture f = fixture(null)) {
      var original = f.owner().capture(request());
      for (String table : List.of(CLASSIFICATIONS, GAPS, EPOCHS, SNAPSHOTS)) {
        assertThatThrownBy(() -> f.dsl.execute("DELETE FROM " + table))
            .hasMessageContaining("immutable");
        assertThatThrownBy(() -> f.dsl.execute("TRUNCATE " + table + " CASCADE"))
            .hasMessageContaining("immutable");
        String column =
            table.equals(CLASSIFICATIONS)
                ? "family_id"
                : table.equals(GAPS) ? "gap_id" : "catalog_epoch";
        assertThatThrownBy(() -> f.dsl.execute("UPDATE " + table + " SET " + column + "=" + column))
            .hasMessageContaining("immutable");
      }
      for (String consequence : List.of(" ", "\t")) {
        assertThatThrownBy(
                () ->
                    f.dsl.execute(
                        "INSERT INTO "
                            + CLASSIFICATIONS
                            + " VALUES('game_manifest.description','game_manifest','description','SOURCE','UNRESOLVED',"
                            + "'UNSUPPORTED_SOURCE_BLOCKING','UNRESOLVED_RETAINED',?)",
                        consequence))
            .hasMessageContaining("ck_gs_runtime_family_consequence");
      }
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "INSERT INTO "
                          + EPOCHS
                          + "(catalog_epoch,catalog_bytes,catalog_digest)"
                          + " SELECT catalog_epoch+1,catalog_bytes,catalog_digest FROM "
                          + EPOCHS
                          + " ORDER BY catalog_epoch DESC LIMIT 1"))
          .hasMessageContaining("exact classified installed schema");
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "INSERT INTO "
                          + SNAPSHOTS
                          + "(read_request_id,target_namespace,request_bytes,request_digest,snapshot_id,catalog_epoch,catalog_digest,evidence_bytes,evidence_digest)"
                          + " SELECT ?,target_namespace,request_bytes,request_digest,?,catalog_epoch,catalog_digest,evidence_bytes,evidence_digest"
                          + " FROM "
                          + SNAPSHOTS
                          + " WHERE read_request_id=?",
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      original.request().readRequestId()))
          .hasMessageContaining("complete exact request and evidence");
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "INSERT INTO "
                          + SNAPSHOTS
                          + "(read_request_id,target_namespace,request_bytes,request_digest,snapshot_id,catalog_epoch,catalog_digest,evidence_bytes,evidence_digest)"
                          + " SELECT ?,target_namespace,request_bytes,request_digest,?,catalog_epoch,'sha256:'||repeat('0',64),evidence_bytes,evidence_digest"
                          + " FROM "
                          + SNAPSHOTS
                          + " WHERE read_request_id=?",
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      original.request().readRequestId()))
          .hasMessageContaining("new owner epoch required");
      assertThat(f.count(SNAPSHOTS)).isEqualTo(1);
    }
  }

  @Test
  void postCommitDriftFailsReadbackButLeavesTheOriginalSnapshotRetained() {
    try (Fixture f = fixture(null)) {
      // Controlled inter-transaction race, using real commits; no production callback is added.
      var changeAfterFirstCommit =
          new PlatformTransactionManager() {
            private boolean captured;

            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
              return f.manager.getTransaction(definition);
            }

            @Override
            public void commit(TransactionStatus status) {
              f.manager.commit(status);
              if (!captured) {
                captured = true;
                f.addComponent();
              }
            }

            @Override
            public void rollback(TransactionStatus status) {
              f.manager.rollback(status);
            }
          };
      var owner =
          new GameSessionRuntimeFamilyCatalogRepository(
              f.dsl, changeAfterFirstCommit, f.schema, "test");
      Request read = request();
      assertThatThrownBy(() -> owner.capture(read))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThat(f.count(SNAPSHOTS)).isEqualTo(1);
      assertThat(
              GameSessionRuntimeFamilyCatalogCodec.evidenceFromStored(f.evidence(read)).request())
          .isEqualTo(read);
      assertThatThrownBy(() -> f.owner().readExact(read))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
    }
  }

  @Test
  void captureUsesIndependentCommittedTransactionsAndRejectsRealAmbientSql() {
    try (Fixture f = fixture(null)) {
      var evidence = f.owner().capture(request());
      // A new data source/manager/repository has no capture transaction or Java snapshot to reuse.
      var independent = source(f.schema);
      var independentDsl =
          DSL.using(new TransactionAwareDataSourceProxy(independent), SQLDialect.POSTGRES);
      var restarted =
          new GameSessionRuntimeFamilyCatalogRepository(
              independentDsl, new DataSourceTransactionManager(independent), f.schema, "test");
      assertThat(restarted.readExact(evidence.request())).isEqualTo(evidence);
      var transaction = new TransactionTemplate(f.manager);
      assertThatThrownBy(() -> transaction.execute(status -> f.owner().capture(request())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("independent");
      assertThatThrownBy(
              () -> transaction.execute(status -> f.owner().readExact(evidence.request())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("independent");
      assertThat(f.count(SNAPSHOTS)).isEqualTo(1);
    }
  }

  @Test
  void genuineV1060ToV1061UpgradeRetainsCensusRowsEpochsAndSnapshotsBeforeSealingEpochFive() {
    try (Fixture f = fixture("1060")) {
      f.populatePredecessor();
      var original = f.owner().capture(request());
      Map<String, String> before = f.rows(f.tables());
      before.remove(f.history);
      before.remove(EPOCHS);
      before.remove(CLASSIFICATIONS);
      var classifications = f.classificationRows();
      var epochs =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                      + EPOCHS
                      + " e ORDER BY catalog_epoch")
              .getValues(0, String.class);
      var snapshots =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(s)::text||':'||xmin::text FROM "
                      + SNAPSHOTS
                      + " s ORDER BY read_request_id")
              .getValues(0, String.class);
      var history = f.historyRows(false);
      var sequences = f.sequencePositions();
      var guards = f.guards();
      var constraints = f.constraints();
      var indexes = f.indexes();
      var routines = f.routines();
      routines.remove("gs_runtime_family_catalog_installed()");
      routines.remove("gs_runtime_family_catalog_bytes(p_epoch bigint)");
      byte[] evidence = f.evidence(original.request());
      migration(f.source, f.schema, f.history, "1061").migrate();
      assertThat(f.rows(before.keySet())).isEqualTo(before);
      assertThat(f.classificationRows())
          .hasSize(classifications.size() + 1)
          .containsAllEntriesOf(classifications);
      assertThat(
              f.classificationRows().keySet().stream()
                  .filter(id -> !classifications.containsKey(id))
                  .toList())
          .containsExactly("game_session_private_fresh_runtime_allocations");
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                          + EPOCHS
                          + " e WHERE catalog_epoch<=4 ORDER BY catalog_epoch")
                  .getValues(0, String.class))
          .isEqualTo(epochs);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(s)::text||':'||xmin::text FROM "
                          + SNAPSHOTS
                          + " s ORDER BY read_request_id")
                  .getValues(0, String.class))
          .isEqualTo(snapshots);
      assertThat(f.historyRows(false)).hasSize(history.size() + 1);
      assertThat(f.historyRows(false).subList(0, history.size())).isEqualTo(history);
      assertThat(f.sequencePositions()).isEqualTo(sequences);
      assertThat(f.guards()).containsAllEntriesOf(guards);
      assertThat(f.constraints()).containsAllEntriesOf(constraints);
      assertThat(f.indexes()).containsAllEntriesOf(indexes);
      assertThat(f.routines()).containsAllEntriesOf(routines);
      assertThat(f.count("game_session_private_fresh_runtime_allocations")).isZero();
      assertThat(f.count(EPOCHS)).isEqualTo(5);
      assertThat(f.evidence(original.request())).isEqualTo(evidence);
      assertThatThrownBy(() -> f.owner().readExact(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      var current = f.owner().capture(request());
      assertThat(current.catalog().catalogEpoch()).isEqualTo(5);
      assertPrivateRuntimeClassification(current.catalog());
      assertThat(f.owner().readExact(current.request())).isEqualTo(current);
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT catalog_bytes=gs_runtime_family_catalog_bytes(5) FROM "
                          + EPOCHS
                          + " WHERE catalog_epoch=5")
                  .get(0, Boolean.class))
          .isTrue();
      var installed =
          f.dsl.fetchSingle(
              "SELECT type,script,success FROM " + f.history + " WHERE version='1061'");
      assertThat(installed.get("type", String.class)).isEqualTo("SQL");
      assertThat(installed.get("script", String.class))
          .isEqualTo("V1061__private_fresh_runtime_identity_allocation.sql");
      assertThat(installed.get("success", Boolean.class)).isTrue();
    }
  }

  @Test
  void genuineV1061ToV1062UpgradeRetainsCensusRowsEpochsAndSnapshotsBeforeSealingEpochSix() {
    try (Fixture f = fixture("1061")) {
      f.populatePredecessor();
      var original = f.owner().capture(request());
      Map<String, String> before = f.rows(f.tables());
      before.remove(f.history);
      before.remove(EPOCHS);
      before.remove(CLASSIFICATIONS);
      var classifications = f.classificationRows();
      var epochs =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                      + EPOCHS
                      + " e ORDER BY catalog_epoch")
              .getValues(0, String.class);
      var snapshots =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(s)::text||':'||xmin::text FROM "
                      + SNAPSHOTS
                      + " s ORDER BY read_request_id")
              .getValues(0, String.class);
      var history = f.historyRows(false);
      var sequences = f.sequencePositions();
      var guards = f.guards();
      var constraints = f.constraints();
      var indexes = f.indexes();
      var routines = f.routines();
      routines.remove("gs_runtime_family_catalog_installed()");
      routines.remove("gs_runtime_family_catalog_bytes(p_epoch bigint)");
      byte[] evidence = f.evidence(original.request());
      migration(f.source, f.schema, f.history, "1062").migrate();
      assertThat(f.rows(before.keySet())).isEqualTo(before);
      assertThat(f.classificationRows())
          .hasSize(classifications.size() + 1)
          .containsAllEntriesOf(classifications);
      assertThat(
              f.classificationRows().keySet().stream()
                  .filter(id -> !classifications.containsKey(id))
                  .toList())
          .containsExactly("game_session_private_preparation_protection");
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                          + EPOCHS
                          + " e WHERE catalog_epoch<=5 ORDER BY catalog_epoch")
                  .getValues(0, String.class))
          .isEqualTo(epochs);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(s)::text||':'||xmin::text FROM "
                          + SNAPSHOTS
                          + " s ORDER BY read_request_id")
                  .getValues(0, String.class))
          .isEqualTo(snapshots);
      assertThat(f.historyRows(false)).hasSize(history.size() + 1);
      assertThat(f.historyRows(false).subList(0, history.size())).isEqualTo(history);
      assertThat(f.sequencePositions()).hasSize(sequences.size() + 1).containsAll(sequences);
      assertThat(f.guards()).containsAllEntriesOf(guards);
      assertThat(f.constraints()).containsAllEntriesOf(constraints);
      assertThat(f.indexes()).containsAllEntriesOf(indexes);
      assertThat(f.routines()).containsAllEntriesOf(routines);
      assertThat(f.count("game_session_private_preparation_protection")).isZero();
      assertThat(f.count(EPOCHS)).isEqualTo(6);
      assertThat(f.evidence(original.request())).isEqualTo(evidence);
      assertThatThrownBy(() -> f.owner().readExact(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      var current = f.owner().capture(request());
      assertThat(current.catalog().catalogEpoch()).isEqualTo(6);
      assertThat(current.catalog().families())
          .anyMatch(
              family -> family.familyId().equals("game_session_private_preparation_protection"));
      assertThat(f.owner().readExact(current.request())).isEqualTo(current);
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT catalog_bytes=gs_runtime_family_catalog_bytes(6) FROM "
                          + EPOCHS
                          + " WHERE catalog_epoch=6")
                  .get(0, Boolean.class))
          .isTrue();
      var installed =
          f.dsl.fetchSingle(
              "SELECT type,script,success FROM " + f.history + " WHERE version='1062'");
      assertThat(installed.get("type", String.class)).isEqualTo("SQL");
      assertThat(installed.get("script", String.class))
          .isEqualTo("V1062__private_preparation_source_protection.sql");
      assertThat(installed.get("success", Boolean.class)).isTrue();
    }
  }

  @Test
  void
      genuineV1062ToV1063CapacityUpgradeRetainsCensusRowsEpochsAndSnapshotsBeforeSealingEpochSeven() {
    try (Fixture f = fixture("1062")) {
      f.populatePredecessor();
      var original = f.owner().capture(request());
      assertThat(original.catalog().catalogEpoch()).isEqualTo(6);
      Map<String, String> before = f.rows(f.tables());
      before.remove(f.history);
      before.remove(EPOCHS);
      before.remove(CLASSIFICATIONS);
      var classifications = f.classificationRows();
      var epochs =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                      + EPOCHS
                      + " e ORDER BY catalog_epoch")
              .getValues(0, String.class);
      var snapshots =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(s)::text||':'||xmin::text FROM "
                      + SNAPSHOTS
                      + " s ORDER BY read_request_id")
              .getValues(0, String.class);
      var history = f.historyRows(false);
      var sequences = f.sequencePositions();
      var guards = f.guards();
      var constraints = f.constraints();
      var indexes = f.indexes();
      var routines = f.routines();
      routines.remove("gs_runtime_family_catalog_installed()");
      routines.remove("gs_runtime_family_catalog_bytes(p_epoch bigint)");
      // Only the existing final-receipt allowlists gain the capacity execution digest.
      routines.remove("require_running_runtime_for_lease_bound_binding_decision()");
      routines.remove("reject_unowned_lease_bound_binding_release()");
      byte[] evidence = f.evidence(original.request());
      migration(f.source, f.schema, f.history, "1063").migrate();
      assertThat(f.rows(before.keySet())).isEqualTo(before);
      assertThat(f.classificationRows())
          .hasSize(classifications.size() + 2)
          .containsAllEntriesOf(classifications);
      assertThat(
              f.classificationRows().keySet().stream()
                  .filter(id -> !classifications.containsKey(id))
                  .toList())
          .containsExactlyInAnyOrder(
              "game_session_original_new_capacity_execution",
              "game_session_canonical_binding_transition.original_new_capacity_execution_digest");
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                          + EPOCHS
                          + " e WHERE catalog_epoch<=6 ORDER BY catalog_epoch")
                  .getValues(0, String.class))
          .isEqualTo(epochs);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(s)::text||':'||xmin::text FROM "
                          + SNAPSHOTS
                          + " s ORDER BY read_request_id")
                  .getValues(0, String.class))
          .isEqualTo(snapshots);
      assertThat(f.historyRows(false)).hasSize(history.size() + 1);
      assertThat(f.historyRows(false).subList(0, history.size())).isEqualTo(history);
      assertThat(f.sequencePositions()).isEqualTo(sequences);
      assertThat(f.guards()).containsAllEntriesOf(guards);
      assertThat(f.constraints()).containsAllEntriesOf(constraints);
      assertThat(f.indexes()).containsAllEntriesOf(indexes);
      assertThat(f.routines()).containsAllEntriesOf(routines);
      assertThat(f.count("game_session_original_new_capacity_execution")).isZero();
      assertThat(f.count(EPOCHS)).isEqualTo(7);
      assertThat(f.evidence(original.request())).isEqualTo(evidence);
      assertThatThrownBy(() -> f.owner().readExact(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThatThrownBy(() -> f.owner().capture(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      var current = f.owner().capture(request());
      assertThat(current.catalog().catalogEpoch()).isEqualTo(7);
      assertThat(current.catalog().families())
          .anyMatch(
              family -> family.familyId().equals("game_session_original_new_capacity_execution"))
          .anyMatch(
              family ->
                  family
                      .familyId()
                      .equals(
                          "game_session_canonical_binding_transition.original_new_capacity_execution_digest"));
      assertThat(f.owner().readExact(current.request())).isEqualTo(current);
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT catalog_bytes=gs_runtime_family_catalog_bytes(7) FROM "
                          + EPOCHS
                          + " WHERE catalog_epoch=7")
                  .get(0, Boolean.class))
          .isTrue();
      var installed =
          f.dsl.fetchSingle(
              "SELECT type,script,success FROM " + f.history + " WHERE version='1063'");
      assertThat(installed.get("type", String.class)).isEqualTo("SQL");
      assertThat(installed.get("script", String.class))
          .isEqualTo("V1063__original_new_tenant_capacity_execution.sql");
      assertThat(installed.get("success", Boolean.class)).isTrue();
      migration(f.source, f.schema, f.history, "1063").validate();
    }
  }

  @Test
  void genuineV1063ToV1064CompletionRepairRetainsEpochSevenAndSnapshotsBeforeSealingEpochEight() {
    try (Fixture f = fixture("1063")) {
      f.populatePredecessor();
      assertThat(f.count("game_instances")).isGreaterThan(0L);
      assertThat(f.count("gameplay_command")).isGreaterThan(0L);
      var original = f.owner().capture(request());
      assertThat(original.catalog().catalogEpoch()).isEqualTo(7);
      Map<String, String> before = f.rows(f.tables());
      before.remove(f.history);
      before.remove(EPOCHS);
      before.remove(SNAPSHOTS);
      var classifications = f.classificationRows();
      var epochs =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                      + EPOCHS
                      + " e ORDER BY catalog_epoch")
              .getValues(0, String.class);
      var snapshots =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(s)::text||':'||xmin::text FROM "
                      + SNAPSHOTS
                      + " s ORDER BY read_request_id")
              .getValues(0, String.class);
      var history = f.historyRows(false);
      String decisionGuardBefore =
          f.dsl
              .fetchSingle(
                  "SELECT pg_get_functiondef((quote_ident(?)||"
                      + "'.require_running_runtime_for_lease_bound_binding_decision()')::regprocedure)",
                  f.schema)
              .get(0, String.class);
      String oldComparison = "completion.completed_running_row_version = runtime.row_version";
      String newComparison = "completion.completed_running_row_version <= runtime.row_version";
      assertThat(
              (decisionGuardBefore.length()
                      - decisionGuardBefore.replace(oldComparison, "").length())
                  / oldComparison.length())
          .isEqualTo(1);
      var routines = f.routines();
      routines.remove("gs_runtime_family_catalog_installed()");
      routines.remove("gs_runtime_family_catalog_bytes(p_epoch bigint)");
      routines.remove("require_running_runtime_for_lease_bound_binding_decision()");

      migration(f.source, f.schema, f.history, "1064").migrate();

      assertThat(f.rows(before.keySet())).isEqualTo(before);
      assertThat(f.classificationRows()).isEqualTo(classifications);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                          + EPOCHS
                          + " e WHERE catalog_epoch<=7 ORDER BY catalog_epoch")
                  .getValues(0, String.class))
          .isEqualTo(epochs);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(s)::text||':'||xmin::text FROM "
                          + SNAPSHOTS
                          + " s ORDER BY read_request_id")
                  .getValues(0, String.class))
          .isEqualTo(snapshots);
      assertThat(f.historyRows(false)).hasSize(history.size() + 1);
      assertThat(f.historyRows(false).subList(0, history.size())).isEqualTo(history);
      assertThat(f.routines()).containsAllEntriesOf(routines);
      assertThat(f.count(EPOCHS)).isEqualTo(8);
      assertThat(f.evidence(original.request()))
          .isEqualTo(GameSessionRuntimeFamilyCatalogCodec.evidenceBytes(original));
      assertThatThrownBy(() -> f.owner().readExact(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThatThrownBy(() -> f.owner().capture(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);

      var current = f.owner().capture(request());
      assertThat(current.catalog().catalogEpoch()).isEqualTo(8);
      assertThat(f.owner().readExact(current.request())).isEqualTo(current);
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT catalog_bytes=gs_runtime_family_catalog_bytes(8) FROM "
                          + EPOCHS
                          + " WHERE catalog_epoch=8")
                  .get(0, Boolean.class))
          .isTrue();
      String decisionGuardAfter =
          f.dsl
              .fetchSingle(
                  "SELECT pg_get_functiondef((quote_ident(?)||"
                      + "'.require_running_runtime_for_lease_bound_binding_decision()')::regprocedure)",
                  f.schema)
              .get(0, String.class);
      assertThat(decisionGuardAfter)
          .isEqualTo(decisionGuardBefore.replace(oldComparison, newComparison));
      var installed =
          f.dsl.fetchSingle(
              "SELECT type,script,success FROM " + f.history + " WHERE version='1064'");
      assertThat(installed.get("type", String.class)).isEqualTo("SQL");
      assertThat(installed.get("script", String.class))
          .isEqualTo("V1064__restore_final_human_completion_lower_bound.sql");
      assertThat(installed.get("success", Boolean.class)).isTrue();
      migration(f.source, f.schema, f.history, "1064").validate();
    }
  }

  @Test
  void genuineV1064ToV1065TransferFenceUpgradeRetainsOriginalRowsSnapshotsAndEpochs() {
    try (Fixture f = fixture("1064")) {
      f.populatePredecessor();
      var original = f.owner().capture(request());
      assertThat(original.catalog().catalogEpoch()).isEqualTo(8);
      Map<String, String> before = f.rows(f.tables());
      before.remove(f.history);
      before.remove(EPOCHS);
      before.remove(CLASSIFICATIONS);
      var epochs =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                      + EPOCHS
                      + " e ORDER BY catalog_epoch")
              .getValues(0, String.class);
      var history = f.historyRows(false);
      var classifications = f.classificationRows();
      migration(f.source, f.schema, f.history, "1065").migrate();
      assertThat(f.rows(before.keySet())).isEqualTo(before);
      assertThat(f.classificationRows())
          .containsAllEntriesOf(classifications)
          .hasSize(classifications.size() + 2);
      assertThat(f.classificationRows().keySet())
          .containsExactlyInAnyOrderElementsOf(
              java.util.stream.Stream.concat(
                      classifications.keySet().stream(),
                      java.util.stream.Stream.of(
                          "game_session_region_owner_transfer",
                          "game_session_region_owner_transfer_item"))
                  .toList());
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                          + EPOCHS
                          + " e WHERE catalog_epoch<=8 ORDER BY catalog_epoch")
                  .getValues(0, String.class))
          .isEqualTo(epochs);
      assertThat(f.historyRows(false)).hasSize(history.size() + 1);
      assertThat(f.historyRows(false).subList(0, history.size())).isEqualTo(history);
      assertThat(f.evidence(original.request()))
          .isEqualTo(GameSessionRuntimeFamilyCatalogCodec.evidenceBytes(original));
      assertThatThrownBy(() -> f.owner().readExact(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThatThrownBy(() -> f.owner().capture(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      var current = f.owner().capture(request());
      assertThat(current.catalog().catalogEpoch()).isEqualTo(9);
      assertThat(f.owner().readExact(current.request())).isEqualTo(current);
      assertThat(current.catalog().families())
          .anyMatch(family -> family.familyId().equals("game_session_region_owner_transfer"))
          .anyMatch(family -> family.familyId().equals("game_session_region_owner_transfer_item"));
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT catalog_bytes=gs_runtime_family_catalog_bytes(9) FROM "
                          + EPOCHS
                          + " WHERE catalog_epoch=9")
                  .get(0, Boolean.class))
          .isTrue();
      for (String table :
          List.of(
              "game_session_region_owner_transfer", "game_session_region_owner_transfer_item")) {
        assertThat(f.count(table)).isZero();
        assertThatThrownBy(() -> f.dsl.execute("TRUNCATE " + table + " CASCADE"))
            .isInstanceOf(org.jooq.exception.DataAccessException.class)
            .hasMessageContaining("Region owner transfer custody is retained");
      }
      migration(f.source, f.schema, f.history, "1065").validate();
    }
  }

  @Test
  void genuineV1065To1065Point0Point1UpgradeRetainsPopulatedCatalogAndAddsCurrentOwnerGuard() {
    try (Fixture f = fixture("1065")) {
      assertCapacityCatalogGuardUpgrade(
          f,
          "1065.0.1",
          9,
          "V1065.0.1__require_current_capacity_dispatch_owner.sql",
          "fence_gs_capacity_dispatch_during_transfer()",
          null,
          List.of(
              "FOR UPDATE",
              "current_owner.owner_instance_id IS DISTINCT FROM NEW.selected_owner_id",
              "current_owner.executor_fence IS DISTINCT FROM NEW.selected_owner_fence",
              "Region dispatch is frozen for normal owner transfer",
              "Completed region predecessor cannot acquire dispatch"));
    }
  }

  @Test
  void originCustodyUpgradeSealsNewFamiliesWithoutInventingPriorOriginEvidence() {
    try (Fixture f = fixture("1065.0.4")) {
      f.populatePredecessor();
      var attemptedOrigin = f.populateCapacityDispatchPredecessor();
      String executionTable = "game_session_original_new_capacity_execution";
      assertThat(f.count(executionTable)).isOne();
      var original = f.owner().capture(request());
      assertThat(original.catalog().catalogEpoch()).isEqualTo(13);
      var history = f.historyRows(false);
      var classifications = f.classificationRows();
      var oldEpochs =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                      + EPOCHS
                      + " e ORDER BY catalog_epoch")
              .getValues(0, String.class);
      var predecessorExecution =
          f.rows(
              List.of(
                  executionTable,
                  "game_session_canonical_gameplay_binding_inventory",
                  "game_session_canonical_binding_transition",
                  "game_session_canonical_binding_region_bridge_obligation",
                  "game_session_canonical_issuer_partition_reservation",
                  "game_session_canonical_tenant_gameplay_tag",
                  "game_session_region_runtime_status"));
      byte[] executionBytes =
          f.dsl
              .fetchSingle("SELECT canonical_execution FROM " + executionTable)
              .get(0, byte[].class);

      migration(f.source, f.schema, f.history, "1065.0.10").migrate();
      var beforeOriginCustody = f.owner().capture(request());
      assertThat(beforeOriginCustody.catalog().catalogEpoch()).isEqualTo(19);
      assertThat(f.owner().readExact(beforeOriginCustody.request())).isEqualTo(beforeOriginCustody);
      byte[] beforeOriginCustodyEvidence = f.evidence(beforeOriginCustody.request());

      // Apply the immutable child only after the parent's .0.8 through .0.10 seals.
      migration(f.source, f.schema, f.history, "1065.1").migrate();

      assertThat(f.evidence(beforeOriginCustody.request())).isEqualTo(beforeOriginCustodyEvidence);
      assertThat(f.rows(predecessorExecution.keySet())).isEqualTo(predecessorExecution);
      assertThat(
              f.dsl
                  .fetchSingle("SELECT canonical_execution FROM " + executionTable)
                  .get(0, byte[].class))
          .isEqualTo(executionBytes);
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "UPDATE " + executionTable + " SET selected_owner_id='replacement-owner'"))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("Original NEW capacity execution identity/outcome is immutable");
      assertThatThrownBy(() -> f.tryBackfillCapacityOrigin(attemptedOrigin))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining(
              "Historical execution cannot acquire fabricated pre-Account origin");
      assertThat(f.rows(predecessorExecution.keySet())).isEqualTo(predecessorExecution);
      assertThat(f.historyRows(false)).hasSize(history.size() + 7);
      assertThat(f.historyRows(false).subList(0, history.size())).isEqualTo(history);
      assertThat(f.classificationRows())
          .containsAllEntriesOf(classifications)
          .hasSize(classifications.size() + 5);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                          + EPOCHS
                          + " e WHERE catalog_epoch<=13 ORDER BY catalog_epoch")
                  .getValues(0, String.class))
          .isEqualTo(oldEpochs);
      assertThat(f.evidence(original.request()))
          .isEqualTo(GameSessionRuntimeFamilyCatalogCodec.evidenceBytes(original));
      assertThatThrownBy(() -> f.owner().readExact(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);

      var current = f.owner().capture(request());
      assertThat(current.catalog().catalogEpoch()).isEqualTo(20);
      assertThat(f.owner().readExact(current.request())).isEqualTo(current);
      for (String family :
          List.of(
              "game_session_original_new_capacity_origin",
              "game_session_capacity_origin_transfer_item")) {
        assertThat(current.catalog().families()).anyMatch(item -> item.familyId().equals(family));
        assertThat(f.count(family)).isZero();
        assertThatThrownBy(() -> f.dsl.execute("TRUNCATE " + family + " CASCADE"))
            .hasMessageContaining("Region owner transfer custody is retained");
      }
      migration(f.source, f.schema, f.history, "1065.1").validate();
    }
  }

  @Test
  void admittedLogoutUpgradeRetainsPriorCatalogAndSealsTheNewIntentFamily() {
    try (Fixture f = fixture("1065.2")) {
      f.populatePredecessor();
      var original = f.owner().capture(request());
      assertThat(original.catalog().catalogEpoch()).isEqualTo(21);
      var before = f.rows(f.tables());
      before.remove(f.history);
      before.remove(CLASSIFICATIONS);
      before.remove(EPOCHS);
      var history = f.historyRows(false);
      var classifications = f.classificationRows();
      String priorEpochs =
          "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
              + EPOCHS
              + " e WHERE catalog_epoch<=21 ORDER BY catalog_epoch";
      var epochs = f.dsl.fetch(priorEpochs).getValues(0, String.class);

      migration(f.source, f.schema, f.history, "1066").migrate();

      assertThat(f.rows(before.keySet())).isEqualTo(before);
      assertThat(f.historyRows(false)).hasSize(history.size() + 1);
      assertThat(f.historyRows(false).subList(0, history.size())).isEqualTo(history);
      assertThat(f.classificationRows())
          .containsAllEntriesOf(classifications)
          .hasSize(classifications.size() + 1);
      assertThat(f.dsl.fetch(priorEpochs).getValues(0, String.class)).isEqualTo(epochs);
      assertThat(f.evidence(original.request()))
          .isEqualTo(GameSessionRuntimeFamilyCatalogCodec.evidenceBytes(original));
      assertThatThrownBy(() -> f.owner().readExact(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);

      var current = f.owner().capture(request());
      assertThat(current.catalog().catalogEpoch()).isEqualTo(22);
      assertThat(current.catalog().families())
          .anyMatch(
              item -> item.familyId().equals("game_session_original_new_admitted_logout_intent"));
      assertThat(f.count("game_session_original_new_admitted_logout_intent")).isZero();
      assertThat(f.owner().readExact(current.request())).isEqualTo(current);
      migration(f.source, f.schema, f.history, "1066").validate();
    }
  }

  @Test
  void historicalFinalObservationUpgradePreservesPopulatedV1065Point1AndSealsEpoch18() {
    try (Fixture f = fixture("1065.1")) {
      f.populatePredecessor();
      assertThat(f.count("gameplay_command")).isPositive();
      assertThat(f.count("game_session_canonical_account_coverage_control")).isPositive();
      assertThat(f.count("saga_instance")).isPositive();
      String observationFamily = "game_session_account_fence_final_observation";
      assertThat(f.tables()).doesNotContain(observationFamily);
      var original = f.owner().capture(request());
      assertThat(original.catalog().catalogEpoch()).isEqualTo(20);
      assertThat(f.owner().readExact(original.request())).isEqualTo(original);

      Map<String, String> predecessorRows = f.rows(f.tables());
      predecessorRows.remove(f.history);
      predecessorRows.remove(EPOCHS);
      predecessorRows.remove(CLASSIFICATIONS);
      var classifications = f.classificationRows();
      var history = f.historyRows(false);
      byte[] snapshot = f.evidence(original.request());
      var predecessorEpochs =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                      + EPOCHS
                      + " e ORDER BY catalog_epoch")
              .getValues(0, String.class);

      migration(f.source, f.schema, f.history, "1065.2").migrate();

      assertThat(f.rows(predecessorRows.keySet())).isEqualTo(predecessorRows);
      assertThat(f.classificationRows())
          .containsAllEntriesOf(classifications)
          .containsKey(observationFamily)
          .hasSize(classifications.size() + 1);
      var installedHistory = f.historyRows(false);
      assertThat(installedHistory).hasSize(history.size() + 1);
      assertThat(installedHistory.subList(0, history.size())).isEqualTo(history);
      var installed =
          f.dsl.fetchSingle(
              "SELECT type,script,success FROM " + f.history + " WHERE version='1065.2'");
      assertThat(installed.get("type", String.class)).isEqualTo("SQL");
      assertThat(installed.get("script", String.class))
          .isEqualTo("V1065.2__account_fence_historical_final_observation.sql");
      assertThat(installed.get("success", Boolean.class)).isTrue();
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                          + EPOCHS
                          + " e WHERE catalog_epoch<=20 ORDER BY catalog_epoch")
                  .getValues(0, String.class))
          .isEqualTo(predecessorEpochs);
      assertThat(f.evidence(original.request())).isEqualTo(snapshot);
      assertThatThrownBy(() -> f.owner().readExact(original.request()))
          .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThat(f.count(observationFamily)).isZero();

      assertThat(f.count(EPOCHS)).isEqualTo(predecessorEpochs.size() + 1L);
      var current = f.owner().capture(request());
      assertThat(current.catalog().catalogEpoch()).isEqualTo(21);
      assertThat(current.catalog().families())
          .anySatisfy(
              family -> {
                assertThat(family.familyId()).isEqualTo(observationFamily);
                assertThat(family.role()).isEqualTo(Role.CONTROL);
                assertThat(family.retentionDisposition())
                    .isEqualTo(RetentionDisposition.OWNER_POLICY_RETAINED);
              });
      assertThat(f.owner().readExact(current.request())).isEqualTo(current);
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT catalog_bytes=gs_runtime_family_catalog_bytes(catalog_epoch) FROM "
                          + EPOCHS
                          + " WHERE catalog_epoch=21")
                  .get(0, Boolean.class))
          .isTrue();
      migration(f.source, f.schema, f.history, "1065.2").validate();
    }
  }

  @Test
  void
      genuineV1065Point0Point1ToPoint0Point2UpgradeRetainsPopulatedCatalogAndAddsFrozenInsertGuard() {
    try (Fixture f = fixture("1065.0.1")) {
      assertCapacityCatalogGuardUpgrade(
          f,
          "1065.0.2",
          10,
          "V1065.0.2__require_frozen_region_transfer_insert.sql",
          "require_gs_frozen_region_owner_transfer_insert()",
          "game_session_region_owner_transfer.gs_region_owner_transfer_frozen_insert",
          List.of(
              "NEW.state IS DISTINCT FROM 'FROZEN'",
              "NEW.incarnation_digest IS NOT NULL",
              "NEW.verification_round IS NOT NULL",
              "NEW.verification_cursor IS DISTINCT FROM -1",
              "NEW.verification_complete IS DISTINCT FROM false",
              "NEW.new_owner_id IS NOT NULL",
              "NEW.new_owner_fence IS NOT NULL",
              "NEW.new_lease_correlation IS NOT NULL",
              "Region transfer must start as a clean frozen intent"));
    }
  }

  @Test
  void
      genuineV1065Point0Point2ToPoint0Point3UpgradeRetainsPopulatedCatalogAndAddsInitialDispatchGuard() {
    try (Fixture f = fixture("1065.0.2")) {
      assertCapacityCatalogGuardUpgrade(
          f,
          "1065.0.3",
          11,
          "V1065.0.3__require_initial_capacity_execution_dispatch.sql",
          "require_gs_initial_capacity_execution_dispatch()",
          "game_session_original_new_capacity_execution.gs_capacity_execution_initial_dispatch",
          List.of(
              "NEW.status IN ('AUTHORIZED','COMMITTED','CLOSED')",
              "NEW.terminal_readback IS NOT NULL",
              "Original NEW capacity execution cannot start with an authorization or terminal proof"));
    }
  }

  @Test
  void
      genuineV1065Point0Point3ToPoint0Point4UpgradeRetainsCatalogAndSerializesCapacityTransferGuards() {
    try (Fixture f = fixture("1065.0.3")) {
      assertCapacityCatalogGuardUpgrade(
          f,
          "1065.0.4",
          12,
          "V1065.0.4__serialize_original_capacity_transfer_guards.sql",
          Map.of(
              "reject_gs_capacity_close_after_final_receipt()",
              List.of(
                  "OLD.status='AUTHORIZED' AND NEW.status='CLOSED'",
                  "t.original_new_capacity_execution_digest=OLD.execution_digest",
                  "Original NEW execution cannot close after retained final receipt"),
              "guard_gs_original_new_capacity_receipt()",
              List.of(
                  "SET status=e.status",
                  "e.request_id=NEW.admission_request_id",
                  "e.admission_id=NEW.transition_id",
                  "exact authorized capacity execution"),
              "fence_gs_capacity_dispatch_during_transfer()",
              List.of(
                  "transaction_isolation",
                  "Region dispatch requires READ COMMITTED owner fencing",
                  "FOR UPDATE",
                  "Region dispatch is frozen for normal owner transfer"),
              "require_gs_frozen_region_owner_transfer_insert()",
              List.of(
                  "Region transfer freeze requires READ COMMITTED owner fencing",
                  "FOR UPDATE",
                  "current_owner.owner_instance_id IS DISTINCT FROM NEW.old_owner_id",
                  "Region transfer freeze requires the exact current owner"),
              "guard_gs_region_owner_transfer_item()",
              List.of(
                  "FOR UPDATE",
                  "UPDATE game_session_region_owner_transfer SET state=state",
                  "FOR SHARE OF e",
                  "e.status IN ('INSTALL_DISPATCHED','AUTHORIZED')",
                  "Transfer item differs from unresolved original region execution"),
              "guard_gs_region_owner_transfer()",
              List.of(
                  "NEW.state='COMPLETED' AND OLD.state='FROZEN'",
                  "NEW.verification_complete IS DISTINCT FROM true",
                  "i.before_payload_digest IS NULL",
                  "i.after_payload_digest IS NULL",
                  "i.verified_round IS DISTINCT FROM NEW.verification_round",
                  "Captured region transfer item lacks its full exact current receipt")),
          "game_session_original_new_capacity_execution.gs_capacity_close_after_final_receipt",
          "reject_gs_capacity_close_after_final_receipt()");
    }
  }

  @Test
  void capacityEvidenceBudgetUpgradeRetainsPredecessorAndSealsNewOwnerTables() {
    try (Fixture f = fixture("1065.0.4")) {
      f.populatePredecessor();
      var original = f.owner().capture(request());
      assertThat(original.catalog().catalogEpoch()).isEqualTo(13);
      var before = f.rows(f.tables());
      before.remove(f.history);
      before.remove(EPOCHS);
      before.remove(CLASSIFICATIONS);
      var priorClassifications = f.classificationRows();
      byte[] retained = f.evidence(original.request());

      migration(f.source, f.schema, f.history, "1065.0.5").migrate();

      assertThat(f.rows(before.keySet())).isEqualTo(before);
      assertThat(f.classificationRows()).containsAllEntriesOf(priorClassifications);
      assertThat(f.count("game_session_capacity_evidence_budget")).isZero();
      assertThat(f.count("game_session_capacity_evidence_charge")).isZero();
      assertThat(f.evidence(original.request())).isEqualTo(retained);
      var current = f.owner().capture(request());
      assertThat(current.catalog().catalogEpoch()).isEqualTo(14);
      assertThat(current.catalog().families())
          .extracting(GameSessionRuntimeFamilyCatalog.Family::familyId)
          .contains(
              "game_session_capacity_evidence_budget", "game_session_capacity_evidence_charge");
      assertThat(f.owner().readExact(current.request())).isEqualTo(current);
      migration(f.source, f.schema, f.history, "1065.0.5").validate();
    }
  }

  @Test
  void transferHistoryBudgetUpgradeSealsItsOwnerWithoutChangingRetainedEvidence() {
    try (Fixture f = fixture("1065.0.6")) {
      f.populatePredecessor();
      var original = f.owner().capture(request());
      assertThat(original.catalog().catalogEpoch()).isEqualTo(15);
      var before = f.rows(f.tables());
      before.remove(f.history);
      before.remove(EPOCHS);
      before.remove(CLASSIFICATIONS);
      byte[] retained = f.evidence(original.request());

      migration(f.source, f.schema, f.history, "1065.0.7").migrate();

      assertThat(f.rows(before.keySet())).isEqualTo(before);
      assertThat(f.count("game_session_region_transfer_history_budget")).isZero();
      assertThat(f.evidence(original.request())).isEqualTo(retained);
      var current = f.owner().capture(request());
      assertThat(current.catalog().catalogEpoch()).isEqualTo(16);
      assertThat(current.catalog().families())
          .extracting(GameSessionRuntimeFamilyCatalog.Family::familyId)
          .contains("game_session_region_transfer_history_budget");
      assertThat(f.owner().readExact(current.request())).isEqualTo(current);
      migration(f.source, f.schema, f.history, "1065.0.7").validate();
    }
  }

  @Test
  void ownerReplacementIsolationUpgradeSealsGuardAndPreservesRetainedEvidence() {
    try (Fixture f = fixture("1065.0.7")) {
      f.populatePredecessor();
      var original = f.owner().capture(request());
      assertThat(original.catalog().catalogEpoch()).isEqualTo(16);
      var before = f.rows(f.tables());
      before.remove(f.history);
      before.remove(EPOCHS);
      byte[] retained = f.evidence(original.request());

      migration(f.source, f.schema, f.history, "1065.0.8").migrate();

      assertThat(f.rows(before.keySet())).isEqualTo(before);
      assertThat(f.evidence(original.request())).isEqualTo(retained);
      var current = f.owner().capture(request());
      assertThat(current.catalog().catalogEpoch()).isEqualTo(17);
      assertThat(f.owner().readExact(current.request())).isEqualTo(current);
      migration(f.source, f.schema, f.history, "1065.0.8").validate();
    }
  }

  private static void assertCapacityCatalogGuardUpgrade(
      Fixture f,
      String target,
      long priorEpoch,
      String script,
      String guardRoutine,
      String addedTrigger,
      List<String> guardFragments) {
    assertCapacityCatalogGuardUpgrade(
        f,
        target,
        priorEpoch,
        script,
        Map.of(guardRoutine, guardFragments),
        addedTrigger,
        guardRoutine);
  }

  private static void assertCapacityCatalogGuardUpgrade(
      Fixture f,
      String target,
      long priorEpoch,
      String script,
      Map<String, List<String>> guardFragmentsByRoutine,
      String addedTrigger,
      String addedTriggerRoutine) {
    f.populatePredecessor();
    assertThat(f.count("game_instances")).isGreaterThan(0L);
    assertThat(f.count("gameplay_command")).isGreaterThan(0L);
    assertThat(f.count("saga_instance")).isGreaterThan(0L);
    var original = f.owner().capture(request());
    assertThat(original.catalog().catalogEpoch()).isEqualTo(priorEpoch);
    Map<String, String> before = f.rows(f.tables());
    before.remove(f.history);
    before.remove(EPOCHS);
    var classifications = f.classificationRows();
    var epochs =
        f.dsl
            .fetch(
                "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                    + EPOCHS
                    + " e ORDER BY catalog_epoch")
            .getValues(0, String.class);
    var snapshots =
        f.dsl
            .fetch(
                "SELECT to_jsonb(s)::text||':'||xmin::text FROM "
                    + SNAPSHOTS
                    + " s ORDER BY read_request_id")
            .getValues(0, String.class);
    var history = f.historyRows(false);
    var sequences = f.sequencePositions();
    var guards = f.guards();
    var constraints = f.constraints();
    var indexes = f.indexes();
    var routines = f.routines();
    var routineIdentities = List.copyOf(routines.keySet());
    routines.remove("gs_runtime_family_catalog_installed()");
    routines.remove("gs_runtime_family_catalog_bytes(p_epoch bigint)");
    guardFragmentsByRoutine.keySet().forEach(routines::remove);
    long addedRoutineCount =
        guardFragmentsByRoutine.keySet().stream()
            .filter(name -> !routineIdentities.contains(name))
            .count();
    byte[] evidence = f.evidence(original.request());

    migration(f.source, f.schema, f.history, target).migrate();

    assertThat(f.rows(before.keySet())).isEqualTo(before);
    assertThat(f.classificationRows()).isEqualTo(classifications);
    assertThat(
            f.dsl
                .fetch(
                    "SELECT to_jsonb(e)::text||':'||xmin::text FROM "
                        + EPOCHS
                        + " e WHERE catalog_epoch<=? ORDER BY catalog_epoch",
                    priorEpoch)
                .getValues(0, String.class))
        .isEqualTo(epochs);
    assertThat(
            f.dsl
                .fetch(
                    "SELECT to_jsonb(s)::text||':'||xmin::text FROM "
                        + SNAPSHOTS
                        + " s ORDER BY read_request_id")
                .getValues(0, String.class))
        .isEqualTo(snapshots);
    assertThat(f.historyRows(false)).hasSize(history.size() + 1);
    assertThat(f.historyRows(false).subList(0, history.size())).isEqualTo(history);
    var installed =
        f.dsl.fetchSingle(
            "SELECT type,script,success FROM " + f.history + " WHERE version=?", target);
    assertThat(installed.get("type", String.class)).isEqualTo("SQL");
    assertThat(installed.get("script", String.class)).isEqualTo(script);
    assertThat(installed.get("success", Boolean.class)).isTrue();
    assertThat(f.sequencePositions()).isEqualTo(sequences);
    var installedGuards = f.guards();
    assertThat(installedGuards).containsAllEntriesOf(guards);
    if (addedTrigger == null) {
      assertThat(installedGuards).isEqualTo(guards);
    } else {
      assertThat(installedGuards.keySet()).hasSize(guards.size() + 1).contains(addedTrigger);
    }
    assertThat(f.constraints()).isEqualTo(constraints);
    assertThat(f.indexes()).isEqualTo(indexes);
    var installedRoutines = f.routines();
    assertThat(installedRoutines).containsAllEntriesOf(routines);
    assertThat(installedRoutines.keySet())
        .hasSize((int) (routineIdentities.size() + addedRoutineCount))
        .containsAll(routineIdentities)
        .containsAll(guardFragmentsByRoutine.keySet());
    assertThat(f.count(EPOCHS)).isEqualTo(priorEpoch + 1);
    assertThat(f.evidence(original.request())).isEqualTo(evidence);
    assertThatThrownBy(() -> f.owner().readExact(original.request()))
        .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);
    assertThatThrownBy(() -> f.owner().capture(original.request()))
        .isInstanceOf(GameSessionRuntimeFamilyCatalogRepository.CatalogConflictException.class);

    guardFragmentsByRoutine.forEach(
        (routine, fragments) -> {
          String installedGuard = f.routines().get(routine);
          assertThat(installedGuard).isNotNull();
          fragments.forEach(fragment -> assertThat(installedGuard).contains(fragment));
        });
    if (addedTrigger != null) {
      assertThat(f.guards()).containsKey(addedTrigger);
      assertThat(f.guards().get(addedTrigger))
          .contains(
              "reject_gs_capacity_close_after_final_receipt()".equals(addedTriggerRoutine)
                  ? "BEFORE UPDATE"
                  : "BEFORE INSERT")
          .contains("EXECUTE FUNCTION")
          .contains(addedTriggerRoutine.substring(0, addedTriggerRoutine.indexOf('(')));
    }
    var current = f.owner().capture(request());
    assertThat(current.catalog().catalogEpoch()).isEqualTo(priorEpoch + 1);
    assertThat(f.owner().readExact(current.request())).isEqualTo(current);
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT catalog_bytes=gs_runtime_family_catalog_bytes(?) FROM "
                        + EPOCHS
                        + " WHERE catalog_epoch=?",
                    priorEpoch + 1,
                    priorEpoch + 1)
                .get(0, Boolean.class))
        .isTrue();
    String installedRoutine = f.routines().get("gs_runtime_family_catalog_installed()");
    String bytesRoutine = f.routines().get("gs_runtime_family_catalog_bytes(p_epoch bigint)");
    assertThat(installedRoutine).contains("version='" + target + "'").contains(script);
    assertThat(bytesRoutine)
        .contains("version IS DISTINCT FROM '" + target + "'")
        .contains("catalog-installation:" + target)
        .contains(script);
    migration(f.source, f.schema, f.history, target).validate();
  }

  private static Request request() {
    return new Request(UUID.randomUUID(), "test");
  }

  private static void assertPrivateRuntimeClassification(GameSessionRuntimeFamilyCatalog catalog) {
    assertThat(catalog.families())
        .anySatisfy(
            family -> {
              assertThat(family.familyId())
                  .isEqualTo("game_session_private_fresh_runtime_allocations");
              assertThat(family.role()).isEqualTo(Role.CONTROL);
              assertThat(family.stateClass()).isEqualTo(StateClass.NOT_PAYLOAD);
              assertThat(family.freshDisposition()).isEqualTo(FreshDisposition.CONTROL_ONLY);
              assertThat(family.retentionDisposition())
                  .isEqualTo(RetentionDisposition.IMMUTABLE_RETAINED);
              assertThat(family.ownerConsequence()).contains("provenance", "no running instance");
            });
  }

  private static void assertPrivateRevisionClassifications(
      GameSessionRuntimeFamilyCatalog catalog) {
    for (var expected :
        List.of(
            Map.entry(
                "game_session_private_catalog_revision_history",
                RetentionDisposition.IMMUTABLE_RETAINED),
            Map.entry(
                "game_session_current_private_catalog_revisions",
                RetentionDisposition.OWNER_POLICY_RETAINED))) {
      assertThat(catalog.families())
          .anySatisfy(
              family -> {
                assertThat(family.familyId()).isEqualTo(expected.getKey());
                assertThat(family.role()).isEqualTo(Role.CONTROL);
                assertThat(family.stateClass()).isEqualTo(StateClass.NOT_PAYLOAD);
                assertThat(family.freshDisposition()).isEqualTo(FreshDisposition.CONTROL_ONLY);
                assertThat(family.retentionDisposition()).isEqualTo(expected.getValue());
                assertThat(family.ownerConsequence()).isNotBlank();
              });
    }
  }

  private static void assertV1060Classifications(
      Fixture fixture, Map<String, String> classificationsBeforeV1060) {
    Map<String, String> classificationsAfterV1060 = fixture.classificationRows();
    assertThat(classificationsAfterV1060)
        .hasSize(classificationsBeforeV1060.size() + 2)
        .containsAllEntriesOf(classificationsBeforeV1060);
    assertThat(
            classificationsAfterV1060.keySet().stream()
                .filter(familyId -> !classificationsBeforeV1060.containsKey(familyId))
                .toList())
        .containsExactlyInAnyOrder(
            "game_session_private_catalog_revision_history",
            "game_session_current_private_catalog_revisions");
  }

  private static void assertInitialPrivateCatalogRevisionHeads(Fixture fixture) {
    long originalPrivateAllocations =
        fixture
            .dsl
            .fetchSingle(
                "SELECT count(*) FROM game_session_canonical_realm_catalog "
                    + "WHERE private_preparation_request_id IS NOT NULL")
            .get(0, Long.class);
    assertThat(originalPrivateAllocations).isPositive();
    assertThat(fixture.count("game_session_private_catalog_revision_history")).isZero();
    assertThat(fixture.count("game_session_current_private_catalog_revisions"))
        .isEqualTo(originalPrivateAllocations);
    assertThat(
            fixture
                .dsl
                .fetchSingle(
                    "SELECT count(*) FROM game_session_current_private_catalog_revisions head "
                        + "LEFT JOIN game_session_canonical_realm_catalog original USING (realm_id) "
                        + "WHERE original.realm_id IS NULL "
                        + "OR original.private_preparation_request_id IS NULL "
                        + "OR head.catalog_revision IS DISTINCT FROM 1 "
                        + "OR head.revision_request_id IS DISTINCT FROM original.creation_request_id "
                        + "OR head.preparation_request_id IS DISTINCT FROM original.private_preparation_request_id "
                        + "OR head.playtest_lifecycle_id IS DISTINCT FROM original.private_playtest_lifecycle_id")
                .get(0, Long.class))
        .isZero();
  }

  private static Fixture fixture(String target) {
    return fixture(target, HISTORY);
  }

  private static Flyway migration(
      DriverManagerDataSource source, String schema, String history, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .table(history)
            .locations(MIGRATIONS, SAGA_MIGRATIONS)
            .placeholders(Map.of("serviceSchema", schema));
    if (target != null) configuration.target(target);
    return configuration.load();
  }

  private static DriverManagerDataSource source(String schema) {
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    return source;
  }

  private static Fixture fixture(String target, String history) {
    String schema = "gs_catalog_" + UUID.randomUUID().toString().replace("-", "");
    var source = source(schema);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    try {
      migration(source, schema, history, target).migrate();
      return new Fixture(schema, history, source, dsl, new DataSourceTransactionManager(source));
    } catch (RuntimeException failed) {
      dsl.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      throw failed;
    }
  }

  private record Fixture(
      String schema,
      String history,
      DriverManagerDataSource source,
      DSLContext dsl,
      DataSourceTransactionManager manager)
      implements AutoCloseable {
    GameSessionRuntimeFamilyCatalogRepository owner() {
      return new GameSessionRuntimeFamilyCatalogRepository(dsl, manager, schema, "test");
    }

    long count(String table) {
      return dsl.fetchSingle("SELECT count(*) FROM " + table).get(0, Long.class);
    }

    byte[] evidence(Request request) {
      return dsl.fetchSingle(
              "SELECT evidence_bytes FROM " + SNAPSHOTS + " WHERE read_request_id=?",
              request.readRequestId())
          .get(0, byte[].class);
    }

    List<String> tables() {
      return dsl.fetch(
              "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace"
                  + " WHERE n.nspname=? AND c.relkind IN ('r','p','f') ORDER BY c.relname",
              schema)
          .getValues(0, String.class);
    }

    Map<String, String> rows(Collection<String> tables) {
      Map<String, String> result = new LinkedHashMap<>();
      for (String table : tables) {
        result.put(
            table,
            dsl.fetchSingle(
                    "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "
                        + table
                        + " t")
                .get(0, String.class));
      }
      return result;
    }

    Map<String, String> classificationRows() {
      return dsl.fetch(
              "SELECT family_id,to_jsonb(classification)::text||':'||xmin::text FROM "
                  + CLASSIFICATIONS
                  + " classification ORDER BY family_id")
          .intoMap(row -> row.get(0, String.class), row -> row.get(1, String.class));
    }

    List<String> historyRows(boolean excludeCatalogInstalls) {
      return dsl.fetch(
              "SELECT to_jsonb(h)::text FROM "
                  + history
                  + " h"
                  + (excludeCatalogInstalls
                      ? " WHERE version IS DISTINCT FROM '1057' AND version IS DISTINCT FROM '1058' AND version IS DISTINCT FROM '1059' AND version IS DISTINCT FROM '1060' AND version IS DISTINCT FROM '1061' AND version IS DISTINCT FROM '1062' AND version IS DISTINCT FROM '1063'"
                      : "")
                  + " ORDER BY installed_rank")
          .getValues(0, String.class);
    }

    List<String> sequencePositions() {
      return dsl.fetch(
              "SELECT sequencename||':'||coalesce(last_value::text,'unused') FROM pg_sequences"
                  + " WHERE schemaname=? ORDER BY sequencename",
              schema)
          .getValues(0, String.class);
    }

    Map<String, String> guards() {
      return dsl.fetch(
              "SELECT c.relname||'.'||t.tgname,pg_get_triggerdef(t.oid,true)||':'||t.tgenabled::TEXT"
                  + " FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace"
                  + " WHERE n.nspname=? AND NOT t.tgisinternal ORDER BY c.relname,t.tgname",
              schema)
          .intoMap(row -> row.get(0, String.class), row -> row.get(1, String.class));
    }

    Map<String, String> constraints() {
      return dsl.fetch(
              "SELECT c.relname||'.'||k.conname,pg_get_constraintdef(k.oid,true)||':'||k.convalidated"
                  + " FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace"
                  + " WHERE n.nspname=? ORDER BY c.relname,k.conname",
              schema)
          .intoMap(row -> row.get(0, String.class), row -> row.get(1, String.class));
    }

    Map<String, String> routines() {
      return dsl.fetch(
              "SELECT p.proname||'('||pg_get_function_identity_arguments(p.oid)||')',pg_get_functiondef(p.oid)"
                  + " FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace"
                  + " WHERE n.nspname=? AND p.prokind IN ('f','p') ORDER BY 1",
              schema)
          .intoMap(row -> row.get(0, String.class), row -> row.get(1, String.class));
    }

    Map<String, String> indexes() {
      return dsl.fetch(
              "SELECT indexname,indexdef FROM pg_indexes WHERE schemaname=? ORDER BY indexname",
              schema)
          .intoMap(row -> row.get(0, String.class), row -> row.get(1, String.class));
    }

    void addComponent() {
      dsl.execute(
          "INSERT INTO "
              + CLASSIFICATIONS
              + " VALUES('game_manifest.description','game_manifest','description','SOURCE','UNRESOLVED',"
              + "'UNSUPPORTED_SOURCE_BLOCKING','UNRESOLVED_RETAINED','Controlled component revision; legacy source still blocks FRESH.')");
    }

    void installEpoch(long epoch) {
      dsl.execute(
          "INSERT INTO "
              + EPOCHS
              + "(catalog_epoch,catalog_bytes,catalog_digest)"
              + " SELECT ?,bytes,'sha256:'||encode(sha256(bytes),'hex') FROM (SELECT gs_runtime_family_catalog_bytes(?) AS bytes) revision",
          epoch,
          epoch);
    }

    void populatePrivateCatalogAllocation() {
      // Match the existing initial-playtest proof: upstream producers are stipulated by its
      // publication fixture, while every retained Game Session row uses its real guarded owner.
      var transaction = new TransactionTemplate(manager);
      UUID tenant = UUID.randomUUID();
      UUID registration = UUID.randomUUID();
      UUID sourceOperation = UUID.randomUUID();
      String requestDigest =
          AuthoredWorldSourceDigest.requestDigest(
              "test", registration, tenant, "source-tenant", "source-world", "Source World");
      var evidence =
          new AuthoredWorldSourceEvidence(
              1,
              "test",
              registration,
              sourceOperation,
              requestDigest,
              tenant,
              "source-tenant",
              "source-world",
              "Source World",
              731L,
              "controlled-source-game",
              "NEW_GAME_ROW",
              AuthoredWorldSourceDigest.evidenceDigest(
                  "test",
                  registration,
                  sourceOperation,
                  requestDigest,
                  tenant,
                  "source-tenant",
                  "source-world",
                  "Source World",
                  731L,
                  "controlled-source-game",
                  "NEW_GAME_ROW"));
      var sourceOwner = new GameSessionAuthoredWorldSourceRepository(dsl);
      var intake =
          Objects.requireNonNull(
              transaction.execute(status -> sourceOwner.register(UUID.randomUUID(), evidence)));
      transaction.executeWithoutResult(
          status ->
              new GameSessionFreshTenantAssociationRepository(dsl)
                  .associateFresh(
                      UUID.randomUUID(),
                      "test",
                      intake,
                      CanonicalHumanPublicationOwnerTestFixtures.creationEvidence(evidence)));
      var catalogRequest =
          new CreateCanonicalRealmCatalogRequest(
              UUID.randomUUID(),
              "test",
              tenant,
              intake.operationId(),
              "source-public",
              "Source Public",
              true,
              true,
              "SHARED",
              "PRESEEDED_ONLY",
              null,
              null);
      var privatePolicy =
          RealmEntryPolicy.parse(
              """
              {"schemaVersion":1,"worldSlug":"source-world","worldDisplayName":"Source World",
              "realmSlug":"private-playtest","realmDisplayName":"Private Playtest","visible":true,
              "publicProduction":false,"stateScope":"ISOLATED","entryPolicy":"PRESEEDED_ONLY"}
              """,
              JsonMapper.builder().build());
      UUID version = UUID.randomUUID();
      var published =
          CanonicalHumanPublicationOwnerTestFixtures.publishedSet(
              catalogRequest,
              evidence,
              version,
              72L,
              "controlled-publish-" + UUID.randomUUID(),
              "release-bundle:" + version,
              "generation-1",
              3L,
              List.of(privatePolicy));
      var catalogs = new GameSessionCanonicalRealmCatalogRepository(dsl);
      var preparedCatalog = catalogs.prepareInitialPublicProduction(catalogRequest, published);
      var catalog =
          Objects.requireNonNull(
              transaction.execute(
                  status -> catalogs.createInitialPublicProduction(preparedCatalog)));
      var preparationRequest =
          new GameSessionInitialPlaytestLifecycleRepository.Request(
              UUID.randomUUID(),
              "test",
              tenant,
              catalog.worldSlug(),
              privatePolicy.realmSlug(),
              catalog.realmId(),
              catalog.creationRequestId(),
              catalog.catalogRevision(),
              intake.operationId(),
              version,
              published.publishedReleaseBundleRef(),
              published.publishedReleaseBundleDigest(),
              GameSessionInitialPlaytestLifecycleRepository.InitializationMode.FRESH,
              dsl.fetchSingle("SELECT clock_timestamp()")
                  .get(0, OffsetDateTime.class)
                  .toInstant()
                  .plusSeconds(3600));
      var preparation =
          Objects.requireNonNull(
                  transaction.execute(
                      status ->
                          new GameSessionInitialPlaytestLifecycleRepository(dsl)
                              .prepare(preparationRequest)))
              .preparation();
      var privateTargets = new GameSessionPrivateFreshTargetRepository(dsl);
      var serializable = new TransactionTemplate(manager);
      serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
      var allocation =
          Objects.requireNonNull(
              serializable.execute(
                  status ->
                      privateTargets.allocate(
                          new GameSessionPrivateFreshTargetRepository.Request(
                              UUID.randomUUID(),
                              preparationRequest.requestId(),
                              preparation.playtestLifecycleId()))));
      assertThat(allocation.replayed()).isFalse();
      assertThat(
              privateTargets.readByPreparation(
                  preparationRequest.requestId(), preparation.playtestLifecycleId()))
          .contains(allocation.snapshot());
      assertThat(allocation.snapshot().catalogRevision()).isEqualTo(1);
      assertThat(allocation.snapshot().admissionAllowed()).isFalse();
    }

    CanonicalGameplayCapacityOrigin populateCapacityDispatchPredecessor() {
      // Controlled source rows for the real PostgreSQL insert guards. No Redis ACK, Account
      // commitment, or owner admission is claimed by this storage fixture.
      var target =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
                  source, UUID.randomUUID(), "test")
              .playableTarget();
      var binding =
          new CanonicalGameplayBindingIdentity(
              UUID.randomUUID(),
              target.canonicalTenantId(),
              target.playableStateNamespaceId(),
              UUID.randomUUID(),
              target.playableStateScope(),
              target.canonicalGameInstanceId(),
              target.gameInstanceId(),
              UUID.randomUUID().toString(),
              UUID.randomUUID(),
              BigInteger.ONE,
              GameSessionAccountDelegationProfile.ISSUER,
              BigInteger.ONE,
              BigInteger.ONE,
              BigInteger.ONE,
              BigInteger.ONE);
      UUID transitionId = UUID.randomUUID();
      UUID indexFence = UUID.randomUUID();
      UUID issuerReservation = UUID.randomUUID();
      UUID issuerReservationFence = UUID.randomUUID();
      UUID requestId = UUID.randomUUID();
      var ref = binding.bindingRef();
      var candidate =
          new CanonicalGameplayOriginalNewCandidate(
              transitionId,
              CanonicalGameplayIssuerTestFixtures.controlledLeaseEvidence(
                  binding,
                  BigInteger.ONE,
                  target,
                  new CanonicalGameplayIssuerTestFixtures.ControlledLeaseEvidenceIdentity(
                      binding.issuerId(),
                      requestId,
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      UUID.randomUUID())),
              binding,
              target,
              new CanonicalGameplayOriginalNewCandidate.TransitionSource(
                  transitionId,
                  ref,
                  BigInteger.ONE,
                  issuerReservation,
                  issuerReservationFence,
                  indexFence),
              new CanonicalGameplayOriginalNewCandidate.ReservationSource(
                  issuerReservation,
                  ref,
                  BigInteger.ONE,
                  transitionId,
                  BigInteger.ZERO,
                  issuerReservationFence,
                  UUID.randomUUID(),
                  BigInteger.ONE,
                  BigInteger.ONE,
                  BigInteger.ONE));
      byte[] candidateBytes = candidate.canonicalBytes();
      assertThat(CanonicalGameplayOriginalNewCandidate.parseCanonical(candidateBytes))
          .isEqualTo(candidate);
      String candidateDigest = "sha256:" + OriginalNewCapacityExecution.sha256(candidateBytes);
      dsl.execute(
          """
          INSERT INTO game_session_canonical_gameplay_binding_inventory
              (binding_ref,account_id,tenant_id,playable_state_namespace_id,character_id,
               playable_state_scope,game_instance_id,runtime_game_instance_id,session_id,
               binding_generation,region_id,region_epoch,issuer_id,issuer_auth_generation,
               issuer_index_layout_version,issuer_index_partition_count,issuer_index_partition_capacity,
               issuer_partition_id,account_index_fence,issuer_reservation_id,transition_id,lifecycle,
               account_index_state,issuer_index_state,inventory_revision)
          VALUES (?,?,?,?,?,'SHARED',?,?,?,1,?,1,?,1,1,1,1,0,?,?,?,
                  'CANDIDATE_PREPARED','REPAIR_REQUIRED','REPAIR_REQUIRED',1)
          """,
          ref.bytes(),
          binding.accountId(),
          binding.tenantId(),
          binding.playableStateNamespaceId(),
          binding.characterId(),
          binding.gameInstanceId(),
          binding.runtimeGameInstanceId(),
          binding.sessionId(),
          binding.regionId(),
          binding.issuerId(),
          indexFence,
          issuerReservation,
          transitionId);
      dsl.execute(
          """
          INSERT INTO game_session_canonical_binding_transition
              (transition_id,tenant_id,playable_state_namespace_id,character_id,candidate_binding_ref,
               candidate_binding_generation,candidate_account_index_fence,issuer_reservation_id,
               status,inventory_revision)
          VALUES (?,?,?,?,?,1,?,?,'PREPARED',1)
          """,
          transitionId,
          binding.tenantId(),
          binding.playableStateNamespaceId(),
          binding.characterId(),
          ref.bytes(),
          indexFence,
          issuerReservation);
      dsl.execute(
          """
          INSERT INTO game_session_canonical_issuer_partition_reservation
              (reservation_id,binding_ref,account_id,tenant_id,game_instance_id,
               runtime_game_instance_id,session_id,binding_generation,transition_id,issuer_id,
               issuer_auth_generation,playable_state_namespace_id,character_id,playable_state_scope,
               region_id,region_epoch,partition_id,issuer_index_layout_version,
               issuer_index_partition_count,issuer_index_partition_capacity,lifecycle,reservation_fence,
               issuer_coverage_operation_id,issuer_coverage_operation_fence,coverage_fence,
               inventory_snapshot_revision,inventory_revision)
          SELECT issuer_reservation_id,binding_ref,account_id,tenant_id,game_instance_id,
                 runtime_game_instance_id,session_id,binding_generation,transition_id,issuer_id,
                 issuer_auth_generation,playable_state_namespace_id,character_id,playable_state_scope,
                 region_id,region_epoch,issuer_partition_id,issuer_index_layout_version,
                 issuer_index_partition_count,issuer_index_partition_capacity,'RESERVED',?,?,1,1,1,1
          FROM game_session_canonical_gameplay_binding_inventory WHERE binding_ref=?
          """,
          issuerReservationFence,
          candidate.reservation().issuerCoverageOperationId(),
          ref.bytes());
      dsl.execute(
          """
          UPDATE game_session_canonical_binding_transition
          SET original_new_candidate_bytes=?,original_new_candidate_digest=?,
              original_new_candidate_state='DISPATCH_POSSIBLE'
          WHERE transition_id=?
          """,
          candidateBytes,
          candidateDigest,
          transitionId);
      dsl.execute(
          """
          INSERT INTO game_session_canonical_binding_region_bridge_obligation
              (transition_id,binding_ref,binding_generation,account_id,tenant_id,game_instance_id,
               runtime_game_instance_id,session_id,region_id,region_epoch,status,inventory_revision)
          SELECT transition_id,binding_ref,binding_generation,account_id,tenant_id,game_instance_id,
                 runtime_game_instance_id,session_id,region_id,region_epoch,'REQUIRED',inventory_revision
          FROM game_session_canonical_gameplay_binding_inventory WHERE binding_ref=?
          """,
          ref.bytes());
      String owner = "retained-capacity-owner";
      String hash = "a".repeat(64);
      long ownerFence =
          dsl.fetchSingle(
                  """
                  INSERT INTO game_session_region_runtime_status
                      (tenant_id,game_instance_id,canonical_region_id,region_id,tenant_region_tag,
                       lifecycle_epoch,lifecycle_row_version,lifecycle_request_bytes,capture_id,
                       graph_sha256,preparation_input_digest,region_epoch,executor_fence,
                       lease_token_correlation,owner_service,owner_instance_id,paused,
                       last_committed_tick_id,updated_at)
                  VALUES (?,?,?,?,?,1,0,?,?,?,?,1,nextval('game_session_region_executor_fence_seq'),
                          ?,'game-session-service',?,false,-1,CURRENT_TIMESTAMP)
                  RETURNING executor_fence
                  """,
                  binding.tenantId(),
                  binding.gameInstanceId(),
                  UUID.randomUUID(),
                  binding.regionId(),
                  CanonicalGameplayRegionRedisKeys.tenantRegionTag(
                      binding.tenantId(), binding.gameInstanceId(), binding.regionId()),
                  "controlled-region-source".getBytes(StandardCharsets.UTF_8),
                  UUID.randomUUID(),
                  hash,
                  "sha256:" + hash,
                  hash,
                  owner)
              .get(0, Long.class);
      var tag =
          new CanonicalGameplayTenantGameplayTagRepository(dsl, target.targetNamespace(), 1)
              .assignmentForTransition(transitionId)
              .tenantGameplayTag();
      var player =
          new TenantCapacityAdmission.PlayerContext(
              binding.accountId(),
              binding.tenantId(),
              binding.playableStateNamespaceId(),
              binding.gameInstanceId(),
              binding.characterId(),
              UUID.fromString(binding.sessionId()),
              target.realmId(),
              requestId,
              binding.playableStateScope());
      var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
      var capacityRequest =
          CapacityAdmissionTestFixture.playerRequestFor(
              player,
              transitionId,
              UUID.randomUUID(),
              target.targetNamespace(),
              Map.ofEntries(
                  Map.entry("targetClass", "PUBLIC_PRODUCTION"),
                  Map.entry("characterCreationPolicy", target.characterCreationPolicy()),
                  Map.entry("targetNamespace", target.targetNamespace()),
                  Map.entry("tenantId", binding.tenantId().toString()),
                  Map.entry("tenantSlug", target.tenantSlug()),
                  Map.entry("worldSlug", target.worldSlug()),
                  Map.entry("realmId", target.realmId().toString()),
                  Map.entry("realmSlug", target.realmSlug()),
                  Map.entry(
                      "playableStateNamespaceId", binding.playableStateNamespaceId().toString()),
                  Map.entry("playableStateScope", binding.playableStateScope()),
                  Map.entry("gameInstanceId", binding.gameInstanceId().toString()),
                  Map.entry("canonicalVersionId", target.canonicalVersionId().toString()),
                  Map.entry("catalogRevision", Long.toString(target.catalogRevision())),
                  Map.entry("pointerVersion", Long.toString(target.pointerVersion())),
                  Map.entry("activeWorldEpoch", Long.toString(target.activeWorldEpoch())),
                  Map.entry("evaluatedAt", now.minusSeconds(1).toString()),
                  Map.entry("connectScopeExpiresAt", now.plusSeconds(120).toString())),
              now);
      var reservation =
          new TenantCapacityAdmission.Result(
              capacityRequest.identity(),
              capacityRequest.admissionAttemptId(),
              capacityRequest.capacityRearmFence(),
              capacityRequest.mutationDigest(),
              UUID.randomUUID(),
              1,
              capacityRequest.evidence().expiresAt(),
              TenantCapacityAdmission.State.RESERVED,
              capacityRequest.evidence().snapshotIdentity(),
              capacityRequest.evidence().evaluationIdentity());
      var execution =
          new OriginalNewCapacityExecution(
              capacityRequest, reservation, transitionId, candidateDigest, owner, ownerFence);
      assertThat(
              dsl.execute(
                  """
                  INSERT INTO game_session_original_new_capacity_execution
                      (tenant_id,request_id,admission_id,admission_attempt_id,transition_id,
                       capacity_rearm_fence,reservation_id,reservation_fence,mutation_digest,
                       execution_digest,canonical_execution,selected_owner_id,selected_owner_fence,
                       tenant_gameplay_tag,status)
                  VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'INSTALL_DISPATCHED')
                  """,
                  binding.tenantId(),
                  requestId,
                  transitionId,
                  capacityRequest.admissionAttemptId(),
                  transitionId,
                  capacityRequest.capacityRearmFence(),
                  reservation.reservationId(),
                  reservation.reservationFence(),
                  capacityRequest.mutationDigest(),
                  execution.digest(),
                  execution.canonicalBytes(),
                  owner,
                  ownerFence,
                  tag))
          .isOne();
      return new CanonicalGameplayCapacityOrigin(
          capacityRequest, candidate, tag, owner, ownerFence);
    }

    void tryBackfillCapacityOrigin(CanonicalGameplayCapacityOrigin origin) {
      dsl.execute(
          """
          INSERT INTO game_session_original_new_capacity_origin
              (tenant_id,request_id,admission_id,admission_attempt_id,capacity_rearm_fence,
               transition_id,game_instance_id,region_id,mutation_digest,canonical_request,
               canonical_candidate,canonical_origin,origin_digest,candidate_digest,tenant_gameplay_tag,
               selected_owner_id,selected_owner_fence,custody_owner_id,custody_owner_fence,
               recovery_deadline,recovery_max_observations,state)
          SELECT e.tenant_id,e.request_id,e.admission_id,e.admission_attempt_id,e.capacity_rearm_fence,
                 e.transition_id,b.game_instance_id,b.region_id,e.mutation_digest,?,
                 t.original_new_candidate_bytes,?,?,t.original_new_candidate_digest,e.tenant_gameplay_tag,
                 e.selected_owner_id,e.selected_owner_fence,e.selected_owner_id,e.selected_owner_fence,
                 CURRENT_TIMESTAMP + interval '1 minute',1,'OPEN'
          FROM game_session_original_new_capacity_execution e
          JOIN game_session_canonical_binding_transition t ON t.transition_id=e.transition_id
          JOIN game_session_canonical_binding_region_bridge_obligation b ON b.transition_id=e.transition_id
          WHERE e.admission_attempt_id=?
          """,
          CapacityAdmissionDigest.preimage(origin.request()),
          origin.canonicalBytes(),
          origin.digest(),
          origin.request().admissionAttemptId());
    }

    void populatePredecessor() {
      // SQL fixture values preserve existing bytes; none is passed off as a live authority
      // producer.
      dsl.execute(
          "INSERT INTO game_instances(tenant_id,runtime_version,owner_account_id,status) VALUES(7001,'legacy',9,'STOPPED')");
      dsl.execute(
          "INSERT INTO game_manifest(version_id,description) VALUES('unqualified-legacy','retain unresolved lineage')");
      dsl.execute(
          "INSERT INTO feature_flag(tenant_id,name,enabled) VALUES(7001,'retained-override',false)");
      dsl.execute(
          "INSERT INTO gameplay_command(command_id,tenant_id,game_instance_id,session_id,account_id,character_id,command_name,"
              + "sanitized_command_text,requires_solo_tick,execution_outcome,gameplay_result,accepted_at,command_text,declared_effects_json)"
              + " VALUES('retained-command',7001,1,3,9,7,'LOOK','LOOK',false,'ACCEPTED','PENDING',CURRENT_TIMESTAMP,'LOOK','[]')");
      dsl.execute(
          "INSERT INTO player_command_history(tenant_id,game_instance_id,character_id,command_text,accepted_at)"
              + " VALUES(7001,1,7,'LOOK',CURRENT_TIMESTAMP)");
      dsl.execute(
          "INSERT INTO resume_transcript_entry(tenant_id,game_instance_id,character_id,protocol_text,line_count,byte_size,appended_at)"
              + " VALUES(7001,1,7,'retained',1,8,CURRENT_TIMESTAMP)");
      dsl.execute(
          "INSERT INTO script_pin_operation(tenant_id,game_instance_id,control_plane_request_id,operation_kind,"
              + "target_script_patch_version,expected_pin_kind,actor_principal,reason,mutation_digest,outcome,resulting_script_patch_version,resulting_script_pin_epoch)"
              + " VALUES(7001,1,'retained-pin','SET','patch','EXPECT_UNPINNED','controlled','retained',?,'COMMITTED','patch',1)",
          "a".repeat(64));
      dsl.execute(
          "INSERT INTO tick_batch(tick_batch_id,tenant_id,game_instance_id,region_epoch,executor_fence,batch_source,status,"
              + "requires_solo_tick,command_count,staged_at,selected_work_manifest_json)"
              + " VALUES('retained-batch',7001,1,1,'retained-fence','PLAYER','STAGED',false,1,CURRENT_TIMESTAMP,'{}')");
      dsl.execute(
          "INSERT INTO tick_effect(effect_id,tick_batch_id,command_id,effect_type,target_aggregate,status,staged_at,effect_key)"
              + " VALUES('retained-effect','retained-batch','retained-command','MOVE','actor','STAGED',CURRENT_TIMESTAMP,'retained-effect-key')");
      dsl.execute(
          "INSERT INTO remote_followup(followup_id,tenant_id,origin_game_instance_id,origin_region_id,origin_region_epoch,"
              + "target_game_instance_id,target_region_id,target_region_epoch,due_tick_id,effect_key,status,created_at,updated_at,claim_target_aggregate)"
              + " VALUES('retained-followup',7001,1,'origin',1,2,'target',1,4,'retained-remote-effect','PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'actor')");
      dsl.execute(
          "INSERT INTO remote_followup_result(result_id,tenant_id,coordinator_id,followup_id,origin_region_id,origin_region_epoch,"
              + "target_region_id,target_region_epoch,outcome,observed_at,origin_game_instance_id,target_game_instance_id)"
              + " VALUES('retained-result',7001,'retained-coordinator','retained-followup','origin',1,'target',1,'APPLIED',CURRENT_TIMESTAMP,1,2)");
      dsl.execute(
          "INSERT INTO game_session_start_session_operator_attempt(target_namespace,control_plane_request_id,canonical_tenant_id,"
              + "owner_attempt_id,owner_mutation_id,claim_owner_id,owner_fence,post_authorization_execution_tuple,mutation_digest,"
              + "authorization_reference_fingerprint,lease_expires_at)"
              + " VALUES('test','retained-start',?,?,?,?,nextval('game_session_start_session_operator_owner_fence_seq'),?,?,?,clock_timestamp()+interval '1 hour')",
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          "controlled-retention-only".getBytes(StandardCharsets.UTF_8),
          "b".repeat(64),
          "arfp/v1/controlled/" + "c".repeat(64));
      dsl.execute(
          "INSERT INTO game_session_initial_gameplay_login_journal(request_id,caller_context_id,expected_attempt_fence) VALUES(?,?,1)",
          UUID.randomUUID(),
          UUID.randomUUID());
      dsl.execute(
          "INSERT INTO game_session_canonical_account_coverage_control(account_id,state,last_operation_fence)"
              + " VALUES(?,'NO_ACTIVE_ACCOUNT_WIDE_FLOW',0)",
          UUID.randomUUID());
      dsl.execute("INSERT INTO saga_instance(saga_name,state) VALUES('retained','PENDING')");
      dsl.execute(
          "INSERT INTO saga_step(instance_id,name,status) VALUES(1,'retained-step','PENDING')");
    }

    @Override
    public void close() {
      dsl.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }
}
