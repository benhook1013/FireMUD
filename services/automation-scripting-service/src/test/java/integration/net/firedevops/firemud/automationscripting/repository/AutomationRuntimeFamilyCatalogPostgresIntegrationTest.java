package net.firedevops.firemud.automationscripting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.automationscripting.runtimecatalog.AutomationRuntimeFamilyCatalogReadGrpcService;
import net.firedevops.firemud.automationscripting.runtimecatalog.AutomationRuntimeFamilyCatalogRepository;
import net.firedevops.firemud.automationscripting.sourceintake.AutomationEmptySelectedSourceIntakeRepository;
import net.firedevops.firemud.automationscripting.v1.ReadAutomationRuntimeFamilyCatalogResponse;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog.FactKind;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog.FreshDisposition;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog.GapId;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog.StateClass;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.common.automation.sourceintake.AutomationEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual combined Automation/Saga migration proof, using the existing Automation filesystem Flyway
 * and Testcontainers pattern. Legacy rows and RPC peer context are controlled. No SQL fixture is a
 * live private authority producer or mTLS/PKI proof. Positive cases are not disabled without
 * Docker.
 */
@Testcontainers(disabledWithoutDocker = false)
@SuppressWarnings("resource")
class AutomationRuntimeFamilyCatalogPostgresIntegrationTest {
  private static final String MIGRATIONS =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String HISTORY = "flyway_schema_history_automation_scripting_service";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void actualConfiguredHistoryNotHardcodedHistoryBindsInstallation() throws Exception {
    for (String history :
        List.of("flyway_schema_history_public", "flyway_schema_history_custom_auto")) {
      try (Fixture f = fixture(null, history)) {
        var evidence = f.owner().read(request());
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
  void completeLiveCensusIncludesEveryEmptyTableAndExplicitBlockingFamiliesAndNonSqlGaps()
      throws Exception {
    try (Fixture f = fixture(null)) {
      var catalog = f.owner().read(request()).catalog();
      assertThat(catalog.catalogEpoch()).isEqualTo(2);
      assertThat(
              catalog.schemaFacts().stream()
                  .filter(fact -> fact.kind() == FactKind.TABLE)
                  .map(AutomationRuntimeFamilyCatalog.SchemaFact::identity)
                  .toList())
          .containsExactlyInAnyOrderElementsOf(f.tables());
      assertThat(catalog.families().stream().filter(family -> family.component().isEmpty()).count())
          .isEqualTo(f.tables().size());
      for (String name :
          List.of(
              "npc_memory",
              "npc_formations",
              "npc_formation_member",
              "script_work_items",
              "plugin_runtime_states")) {
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
                assertThat(family.familyId()).isEqualTo("faction_standing");
                assertThat(family.stateClass()).isEqualTo(StateClass.UNRESOLVED);
                assertThat(family.freshDisposition())
                    .isEqualTo(FreshDisposition.OWNERSHIP_DRIFT_BLOCKING);
                assertThat(family.ownerConsequence())
                    .contains(
                        "Social", "retained-row disposition", "not Automation FRESH ownership");
              })
          .anySatisfy(
              family -> {
                assertThat(family.familyId()).isEqualTo("script_schedule_instances");
                assertThat(family.stateClass()).isEqualTo(StateClass.S2);
                assertThat(family.freshDisposition())
                    .isEqualTo(FreshDisposition.NAMESPACE_MAPPING_BLOCKING);
              });
      assertThat(catalog.families())
          .anyMatch(family -> family.familyId().equals("saga_instance"))
          .anyMatch(family -> family.familyId().equals("saga_step"))
          .anyMatch(family -> family.familyId().equals("automation_empty_selected_source_receipt"))
          .anyMatch(family -> family.familyId().equals("script_work_items.read_snapshot_token"))
          .allSatisfy(family -> assertThat(family.ownerConsequence()).isNotBlank());
      assertThat(catalog.nonSqlGaps())
          .extracting(AutomationRuntimeFamilyCatalog.NonSqlGap::id)
          .containsExactly(
              GapId.CAPACITY_LEASE_ACCOUNTING,
              GapId.CENTRAL_RESET_ACL_REGISTRATION,
              GapId.DURABLE_CHARGE_MARKERS,
              GapId.QUEUE_DISCOVERY_AND_ENCODING,
              GapId.QUEUE_POINTER_RETENTION);
      assertThat(catalog.families())
          .noneMatch(
              family ->
                  family.familyId().contains("charge_record")
                      || family.familyId().equals("redis_queue")
                      || family.familyId().equals("script_firing_claims"));
      assertThat(catalog.schemaFacts())
          .anyMatch(fact -> fact.kind() == FactKind.ROUTINE)
          .anyMatch(fact -> fact.kind() == FactKind.MIGRATION)
          .anyMatch(fact -> fact.kind() == FactKind.SEQUENCE);
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT catalog_bytes=automation_runtime_family_catalog_bytes(catalog_epoch)"
                          + " FROM automation_runtime_family_catalog_epoch ORDER BY catalog_epoch DESC LIMIT 1")
                  .get(0, Boolean.class))
          .isTrue();
      assertThat(f.count("script_work_items")).isZero();
      assertThat(f.count("script_schedule_instances")).isZero();
      assertThat(f.count("plugin_runtime_states")).isZero();
      assertThat(f.count("faction_standing")).isZero();
    }
  }

  @Test
  void exactRetryRestartAndIndependentReadbackRetainBytesDigestsAndSnapshotIdentity()
      throws Exception {
    try (Fixture f = fixture(null)) {
      Request read = request();
      var original = f.owner().read(read);
      assertThat(f.owner().read(read)).isEqualTo(original);
      assertThat(f.owner().readExact(read)).isEqualTo(original);
      assertThat(f.count("automation_runtime_family_catalog_snapshot")).isEqualTo(1);
      var other = f.owner().read(request());
      assertThat(other.snapshotId()).isNotEqualTo(original.snapshotId());
      assertThat(other.catalog().exactBytes()).isEqualTo(original.catalog().exactBytes());
      assertThat(other.catalog().digest()).isEqualTo(original.catalog().digest());
      var foreignOwner =
          new AutomationRuntimeFamilyCatalogRepository(f.dsl, f.manager, f.schema, "other");
      assertThatThrownBy(() -> foreignOwner.read(new Request(read.readRequestId(), "other")))
          .isInstanceOf(AutomationRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThatThrownBy(() -> f.owner().readExact(request()))
          .isInstanceOf(AutomationRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThat(f.count("automation_runtime_family_catalog_snapshot")).isEqualTo(2);
    }
  }

  @Test
  void concurrentExactRetriesConvergeOnOneImmutableSnapshot() throws Exception {
    try (Fixture f = fixture(null);
        var workers = Executors.newFixedThreadPool(2)) {
      Request read = request();
      var first = workers.submit(() -> f.owner().read(read));
      var second = workers.submit(() -> f.owner().read(read));
      assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(second.get(30, TimeUnit.SECONDS));
      assertThat(f.count("automation_runtime_family_catalog_snapshot")).isEqualTo(1);
    }
  }

  @Test
  void forwardUpgradePreservesPopulatedPredecessorRowsHistorySequencesAndSourceGuards()
      throws Exception {
    try (Fixture f = fixture("1003")) {
      f.populatePredecessor();
      Map<String, String> rows = f.rows();
      assertThat(rows.values()).allSatisfy(value -> assertThat(value).isNotEqualTo("[]"));
      List<String> history = f.historyRows(false);
      List<String> sequences = f.sequencePositions();
      List<String> guards = f.guards();
      migration(f.source, f.schema, f.history, null).migrate();
      for (var retained : rows.entrySet())
        assertThat(f.jsonRows(retained.getKey())).isEqualTo(retained.getValue());
      assertThat(f.historyRows(true)).isEqualTo(history);
      assertThat(f.sequencePositions()).isEqualTo(sequences);
      assertThat(f.guards()).containsAll(guards);
      var evidence = f.owner().read(request());
      assertThat(f.owner().readExact(evidence.request())).isEqualTo(evidence);
      for (var retained : rows.entrySet())
        assertThat(f.jsonRows(retained.getKey())).isEqualTo(retained.getValue());
      assertThat(f.sequencePositions()).isEqualTo(sequences);
      // Existing source custody trigger must still reject its protected immutable reservation.
      assertThatThrownBy(
              () -> f.dsl.execute("DELETE FROM automation_empty_source_numeric_key_reservation"))
          .hasMessageContaining("immutable");
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "INSERT INTO scripts(tenant_id,name,version,definition) VALUES(1,'forbidden','patch','{}')"))
          .hasMessageContaining("reserved by an immutable empty-source receipt");
      migration(f.source, f.schema, f.history, null).validate();
    }
  }

  @Test
  void emptyUnknownTableInvalidatesOldReadAndRefusesNewCapture() throws Exception {
    try (Fixture f = fixture(null)) {
      Request read = request();
      f.owner().read(read);
      f.dsl.execute("CREATE TABLE unknown_runtime_family (id BIGINT PRIMARY KEY)");
      assertThat(f.count("unknown_runtime_family")).isZero();
      assertThatThrownBy(() -> f.owner().readExact(read)).hasMessageContaining("Unclassified");
      assertThatThrownBy(() -> f.owner().read(request())).hasMessageContaining("Unclassified");
      assertThat(f.count("automation_runtime_family_catalog_snapshot")).isEqualTo(1);
    }
  }

  @Test
  void installationOnUnknownPredecessorFamilyFailsAtomicallyWithoutHistoryRewrite()
      throws Exception {
    try (Fixture f = fixture("1003")) {
      f.dsl.execute("CREATE TABLE unknown_runtime_family (id BIGINT PRIMARY KEY)");
      List<String> history = f.historyRows(false);
      assertThatThrownBy(() -> migration(f.source, f.schema, f.history, null).migrate())
          .hasMessageContaining("Unclassified or changed");
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT to_regclass(?) IS NULL",
                      f.schema + ".automation_runtime_family_catalog_epoch")
                  .get(0, Boolean.class))
          .isTrue();
      assertThat(f.historyRows(false)).isEqualTo(history);
      assertThat(f.count("unknown_runtime_family")).isZero();
    }
  }

  @Test
  void columnAddRemoveRenameConstraintsAndRoutineDriftCannotResampleSameEpoch() throws Exception {
    for (String change :
        List.of(
            "ALTER TABLE npc_memory ADD COLUMN future_state TEXT",
            "ALTER TABLE npc_memory RENAME COLUMN value TO later_value",
            "ALTER TABLE npc_memory DROP COLUMN value",
            "ALTER TABLE npc_memory ADD CONSTRAINT catalog_probe CHECK (id>0)",
            "ALTER TABLE npc_memory RENAME TO renamed_memory",
            "CREATE FUNCTION future_runtime_writer() RETURNS INTEGER LANGUAGE SQL AS 'SELECT 1'",
            "ALTER FUNCTION automation_source_positive_numeric_key(TEXT) RENAME TO renamed_numeric_parser")) {
      try (Fixture f = fixture(null)) {
        Request read = request();
        f.owner().read(read);
        f.dsl.execute(change);
        assertThatThrownBy(() -> f.owner().readExact(read)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> f.owner().read(request())).isInstanceOf(RuntimeException.class);
        assertThat(f.count("automation_runtime_family_catalog_snapshot")).isEqualTo(1);
      }
    }
  }

  @Test
  void removalClassificationAndNonSqlGapChangesInvalidateWithoutOverwritingEvidence()
      throws Exception {
    try (Fixture f = fixture(null)) {
      Request read = request();
      f.owner().read(read);
      f.dsl.execute("DROP TABLE npc_memory");
      assertThatThrownBy(() -> f.owner().readExact(read))
          .hasMessageContaining("Unclassified or changed");
    }
    try (Fixture f = fixture(null)) {
      Request read = request();
      var original = f.owner().read(read);
      f.addComponent();
      assertThatThrownBy(() -> f.owner().readExact(read))
          .isInstanceOf(AutomationRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThatThrownBy(() -> f.owner().read(request()))
          .isInstanceOf(AutomationRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT evidence_bytes FROM automation_runtime_family_catalog_snapshot WHERE read_request_id=?",
                      read.readRequestId())
                  .get(0, byte[].class))
          .isEqualTo(AutomationRuntimeFamilyCatalogCodec.evidenceBytes(original));
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "UPDATE automation_runtime_catalog_non_sql_gap SET disposition='UNRESOLVED_BLOCKING'"))
          .hasMessageContaining("immutable");
    }
  }

  @Test
  void priorMigrationFactsAndOwnSuccessfulScriptAreRequiredNotFabricatedOwnChecksum()
      throws Exception {
    try (Fixture f = fixture(null)) {
      Request read = request();
      f.owner().read(read);
      f.dsl.execute("UPDATE " + f.history + " SET checksum=checksum+1 WHERE version='1'");
      assertThatThrownBy(() -> f.owner().readExact(read))
          .isInstanceOf(AutomationRuntimeFamilyCatalogRepository.CatalogConflictException.class);
    }
    for (String change : List.of("success=false", "script='wrong_catalog_installation.sql'")) {
      try (Fixture f = fixture(null)) {
        f.dsl.execute("UPDATE " + f.history + " SET " + change + " WHERE version='1004'");
        assertThatThrownBy(() -> f.owner().read(request()))
            .hasMessageContaining("No durable Automation catalog epoch");
        assertThat(f.count("automation_runtime_family_catalog_snapshot")).isZero();
      }
    }
    try (Fixture f = fixture(null)) {
      f.owner().read(request());
      f.dsl.execute(
          "INSERT INTO "
              + f.history
              + " (installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)"
              + " VALUES (10000,'1006','controlled later migration','SQL','V1006__probe.sql',1,current_user,0,true)");
      assertThatThrownBy(() -> f.owner().read(request()))
          .isInstanceOf(AutomationRuntimeFamilyCatalogRepository.CatalogConflictException.class);
    }
  }

  @Test
  void explicitOwnerEpochAdvanceInvalidatesOldRequestButRetainsOriginalSnapshot() throws Exception {
    try (Fixture f = fixture(null)) {
      Request read = request();
      var original = f.owner().read(read);
      f.addComponent();
      // Controlled owner revision installation only; no runtime mutation/registration API exists.
      f.dsl.execute(
          "INSERT INTO automation_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)"
              + " SELECT 3,bytes,'sha256:'||encode(sha256(bytes),'hex')"
              + " FROM (SELECT automation_runtime_family_catalog_bytes(3) AS bytes) revised");
      assertThatThrownBy(() -> f.owner().readExact(read))
          .isInstanceOf(AutomationRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThatThrownBy(() -> f.owner().read(read))
          .isInstanceOf(AutomationRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT evidence_bytes FROM automation_runtime_family_catalog_snapshot WHERE read_request_id=?",
                      read.readRequestId())
                  .get(0, byte[].class))
          .isEqualTo(AutomationRuntimeFamilyCatalogCodec.evidenceBytes(original));
      var revised = f.owner().read(request());
      assertThat(revised.catalog().catalogEpoch()).isEqualTo(3);
      assertThat(revised.catalog().digest()).isNotEqualTo(original.catalog().digest());
      assertThat(f.count("automation_runtime_family_catalog_epoch")).isEqualTo(3);
    }
  }

  @Test
  void metadataEpochSnapshotRetentionAndCompleteSqlFrameBindingCannotBeBypassed() throws Exception {
    try (Fixture f = fixture(null)) {
      var original = f.owner().read(request());
      for (String consequence : List.of(" ", "\t")) {
        assertThatThrownBy(
                () ->
                    f.dsl.execute(
                        "INSERT INTO automation_runtime_family_classification VALUES"
                            + " ('npc_memory.value','npc_memory','value','RUNTIME_PAYLOAD','UNKNOWN',"
                            + "'STATE_CLASS_UNKNOWN_BLOCKING','UNRESOLVED_RETAINED',?)",
                        consequence))
            .hasMessageContaining("ck_automation_runtime_family_consequence");
      }
      for (String table :
          List.of(
              "automation_runtime_family_classification",
              "automation_runtime_catalog_non_sql_gap",
              "automation_runtime_family_catalog_epoch",
              "automation_runtime_family_catalog_snapshot")) {
        assertThatThrownBy(() -> f.dsl.execute("DELETE FROM " + table))
            .hasMessageContaining("immutable");
        assertThatThrownBy(() -> f.dsl.execute("TRUNCATE " + table + " CASCADE"))
            .hasMessageContaining("immutable");
        assertThatThrownBy(
                () ->
                    f.dsl.execute(
                        "UPDATE "
                            + table
                            + " SET "
                            + (table.equals("automation_runtime_family_classification")
                                ? "family_id=family_id"
                                : table.equals("automation_runtime_catalog_non_sql_gap")
                                    ? "gap_id=gap_id"
                                    : "catalog_epoch=catalog_epoch")))
            .hasMessageContaining("immutable");
      }
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "INSERT INTO automation_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)"
                          + " SELECT 3,catalog_bytes,catalog_digest FROM automation_runtime_family_catalog_epoch WHERE catalog_epoch=2"))
          .hasMessageContaining("exact classified installed schema");
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "INSERT INTO automation_runtime_family_catalog_snapshot"
                          + " (read_request_id,target_namespace,request_bytes,request_digest,snapshot_id,catalog_epoch,catalog_digest,evidence_bytes,evidence_digest)"
                          + " SELECT ?,target_namespace,request_bytes,request_digest,?,catalog_epoch,catalog_digest,evidence_bytes,evidence_digest"
                          + " FROM automation_runtime_family_catalog_snapshot WHERE read_request_id=?",
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      original.request().readRequestId()))
          .hasMessageContaining("complete exact request and evidence");
      assertThat(f.count("automation_runtime_family_catalog_snapshot")).isEqualTo(1);
    }
  }

  @Test
  void realProducerReturnsCompleteCorrelatedEvidenceWithoutRuntimeWritesAndRejectsAmbientSql()
      throws Exception {
    try (Fixture f = fixture(null)) {
      Request read = request();
      var service = new AutomationRuntimeFamilyCatalogReadGrpcService(f.owner(), "test");
      var collector = new Collector();
      var peer =
          GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-session-service")
              .orElseThrow();
      Context.current()
          .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
          .run(
              () ->
                  service.readAutomationRuntimeFamilyCatalog(
                      AutomationRuntimeFamilyCatalogCodec.toRequest(read), collector));
      assertThat(collector.error).isNull();
      assertThat(collector.completed).isTrue();
      var evidence = AutomationRuntimeFamilyCatalogCodec.fromResponse(read, collector.value);
      assertThat(f.owner().readExact(read)).isEqualTo(evidence);
      for (String table :
          List.of(
              "script_work_items",
              "script_schedule_instances",
              "plugin_runtime_states",
              "npc_memory",
              "faction_standing")) {
        assertThat(f.count(table)).isZero();
      }
      var transaction = new TransactionTemplate(f.manager);
      assertThatThrownBy(() -> transaction.execute(status -> f.owner().read(request())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("independent");
    }
  }

  @Test
  void viewSecurityOptionsInvalidateReadRetryAndFreshCaptureWithoutChangingRetainedEvidence()
      throws Exception {
    for (String option : List.of("security_invoker", "security_barrier")) {
      try (Fixture f = fixture("1003")) {
        // Install the real view before either catalog migration so its initial options are sealed.
        f.dsl.execute("CREATE VIEW catalog_security_probe AS SELECT 1 AS id");
        migration(f.source, f.schema, f.history, null).migrate();
        Request read = request();
        var original = f.owner().read(read);
        assertThat(original.catalog().catalogEpoch()).isEqualTo(2);
        assertThat(original.catalog().schemaFacts())
            .anySatisfy(
                fact -> {
                  assertThat(fact.kind()).isEqualTo(FactKind.VIEW);
                  assertThat(fact.identity()).isEqualTo("catalog_security_probe");
                  assertThat(fact.definitionDigest())
                      .isEqualTo(
                          f.dsl
                              .fetchSingle(
                                  "SELECT 'sha256:'||encode(sha256(convert_to("
                                      + "jsonb_build_array(pg_get_viewdef(c.oid,true),c.reloptions)::text,'UTF8')),'hex')"
                                      + " FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace"
                                      + " WHERE n.nspname=? AND c.relname='catalog_security_probe'",
                                  f.schema)
                              .get(0, String.class));
                });
        assertThat(f.owner().read(read)).isEqualTo(original);
        assertThat(f.owner().readExact(read)).isEqualTo(original);
        String definition =
            f.dsl
                .fetchSingle("SELECT pg_get_viewdef('catalog_security_probe'::regclass,true)")
                .get(0, String.class);
        String epochs = f.catalogRows("automation_runtime_family_catalog_epoch");
        String snapshots = f.catalogRows("automation_runtime_family_catalog_snapshot");
        f.dsl.execute("ALTER VIEW catalog_security_probe SET (" + option + "=true)");
        assertThat(
                f.dsl
                    .fetchSingle("SELECT pg_get_viewdef('catalog_security_probe'::regclass,true)")
                    .get(0, String.class))
            .isEqualTo(definition);
        assertThatThrownBy(() -> f.owner().readExact(read))
            .isInstanceOf(AutomationRuntimeFamilyCatalogRepository.CatalogConflictException.class);
        assertThatThrownBy(() -> f.owner().read(read))
            .isInstanceOf(AutomationRuntimeFamilyCatalogRepository.CatalogConflictException.class);
        assertThatThrownBy(() -> f.owner().read(request()))
            .isInstanceOf(AutomationRuntimeFamilyCatalogRepository.CatalogConflictException.class);
        assertThat(f.catalogRows("automation_runtime_family_catalog_epoch")).isEqualTo(epochs);
        assertThat(f.catalogRows("automation_runtime_family_catalog_snapshot"))
            .isEqualTo(snapshots);
      }
    }
  }

  @Test
  void catalogViewOptionsUpgradeRetainsV1004DataHistoryEpochAndSnapshot() throws Exception {
    try (Fixture f = fixture("1004")) {
      f.populatePredecessor();
      Map<String, String> rows = f.rows();
      rows.remove("automation_runtime_family_catalog_epoch");
      rows.remove("automation_runtime_family_catalog_snapshot");
      Request read = request();
      var original = f.owner().read(read);
      assertThat(original.catalog().catalogEpoch()).isEqualTo(1);
      List<String> history = f.historyRows(false);
      String epoch = f.catalogRows("automation_runtime_family_catalog_epoch");
      String snapshots = f.catalogRows("automation_runtime_family_catalog_snapshot");
      migration(f.source, f.schema, f.history, null).migrate();
      for (var retained : rows.entrySet())
        assertThat(f.jsonRows(retained.getKey())).isEqualTo(retained.getValue());
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(h)::text FROM "
                          + f.history
                          + " h WHERE version IS DISTINCT FROM '1005' ORDER BY installed_rank")
                  .getValues(0, String.class))
          .isEqualTo(history);
      assertThat(f.catalogRows("automation_runtime_family_catalog_epoch WHERE catalog_epoch=1"))
          .isEqualTo(epoch);
      assertThat(f.catalogRows("automation_runtime_family_catalog_snapshot")).isEqualTo(snapshots);
      assertThat(f.count("automation_runtime_family_catalog_epoch")).isEqualTo(2);
      assertThatThrownBy(() -> f.owner().readExact(read))
          .isInstanceOf(AutomationRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThatThrownBy(() -> f.owner().read(read))
          .isInstanceOf(AutomationRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThat(f.catalogRows("automation_runtime_family_catalog_snapshot")).isEqualTo(snapshots);
      var current = f.owner().read(request());
      assertThat(current.catalog().catalogEpoch()).isEqualTo(2);
      assertThat(current.catalog().digest()).isNotEqualTo(original.catalog().digest());
      assertThat(f.owner().readExact(current.request())).isEqualTo(current);
      assertThat(
              current.catalog().schemaFacts().stream()
                  .filter(fact -> fact.kind() == FactKind.MIGRATION)
                  .map(AutomationRuntimeFamilyCatalog.SchemaFact::identity)
                  .toList())
          .contains("catalog-installation:1004", "catalog-installation:1005")
          .noneMatch(identity -> identity.contains(":1004:") || identity.contains(":1005:"));
      for (String version : List.of("1004", "1005")) {
        String script =
            version.equals("1004")
                ? "V1004__automation_owner_runtime_family_catalog.sql"
                : "V1005__automation_catalog_view_options.sql";
        assertThat(current.catalog().schemaFacts())
            .anySatisfy(
                fact -> {
                  assertThat(fact.kind()).isEqualTo(FactKind.MIGRATION);
                  assertThat(fact.identity()).isEqualTo("catalog-installation:" + version);
                  assertThat(fact.definitionDigest())
                      .isEqualTo(
                          AutomationRuntimeFamilyCatalog.sha256(
                              script.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                });
      }
      migration(f.source, f.schema, f.history, null).validate();
    }
  }

  @Test
  void bothExactSuccessfulCatalogInstallationsAreRequired() throws Exception {
    for (String version : List.of("1004", "1005")) {
      for (String change : List.of("success=false", "script='wrong_catalog_installation.sql'")) {
        try (Fixture f = fixture(null)) {
          Request read = request();
          f.owner().read(read);
          String snapshots = f.catalogRows("automation_runtime_family_catalog_snapshot");
          f.dsl.execute("UPDATE " + f.history + " SET " + change + " WHERE version=?", version);
          assertThatThrownBy(() -> f.owner().readExact(read))
              .hasMessageContaining("No durable Automation catalog epoch");
          assertThatThrownBy(() -> f.owner().read(read))
              .hasMessageContaining("No durable Automation catalog epoch");
          assertThatThrownBy(() -> f.owner().read(request()))
              .hasMessageContaining("No durable Automation catalog epoch");
          assertThat(f.catalogRows("automation_runtime_family_catalog_snapshot"))
              .isEqualTo(snapshots);
        }
      }
    }
  }

  private static Request request() {
    return new Request(UUID.randomUUID(), "test");
  }

  private static Fixture fixture(String target) {
    return fixture(target, HISTORY);
  }

  private static Flyway migration(
      DriverManagerDataSource source, String schema, String history, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(source)
            .locations(MIGRATIONS, "classpath:db/migration/saga")
            .schemas(schema)
            .defaultSchema(schema)
            .table(history)
            .placeholders(Map.of("serviceSchema", schema));
    if (target != null) configuration.target(target);
    return configuration.load();
  }

  private static Fixture fixture(String target, String history) {
    String schema = "auto_catalog_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
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
    AutomationRuntimeFamilyCatalogRepository owner() {
      return new AutomationRuntimeFamilyCatalogRepository(dsl, manager, schema, "test");
    }

    String catalogRows(String source) {
      return dsl.fetchSingle(
              "SELECT coalesce(jsonb_agg(row_json ORDER BY row_json::text),'[]'::jsonb)::text"
                  + " FROM (SELECT to_jsonb(t) AS row_json FROM (SELECT * FROM "
                  + source
                  + ") t) retained_rows")
          .get(0, String.class);
    }

    long count(String table) {
      return dsl.fetchSingle("SELECT count(*) FROM " + table).get(0, Long.class);
    }

    List<String> tables() {
      return dsl.fetch(
              "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace"
                  + " WHERE n.nspname=? AND c.relkind IN ('r','p','f') ORDER BY c.relname",
              schema)
          .getValues(0, String.class);
    }

    String jsonRows(String table) {
      return dsl.fetchSingle(
              "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "
                  + table
                  + " t")
          .get(0, String.class);
    }

    Map<String, String> rows() {
      Map<String, String> result = new LinkedHashMap<>();
      for (String table : tables()) if (!table.equals(history)) result.put(table, jsonRows(table));
      return result;
    }

    List<String> historyRows(boolean excludeNew) {
      return dsl.fetch(
              "SELECT to_jsonb(h)::text FROM "
                  + history
                  + " h"
                  + (excludeNew
                      ? " WHERE version IS DISTINCT FROM '1004' AND version IS DISTINCT FROM '1005'"
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

    List<String> guards() {
      return dsl.fetch(
              "SELECT pg_get_triggerdef(t.oid,true) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid"
                  + " JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND NOT t.tgisinternal ORDER BY t.tgname",
              schema)
          .getValues(0, String.class);
    }

    void addComponent() {
      dsl.execute(
          "INSERT INTO automation_runtime_family_classification VALUES"
              + " ('npc_memory.key','npc_memory','key','RUNTIME_PAYLOAD','UNKNOWN','STATE_CLASS_UNKNOWN_BLOCKING',"
              + "'UNRESOLVED_RETAINED','Controlled added component; still blocking, not S3.')");
    }

    void populatePredecessor() {
      // Reuse the existing selected-source fixture and owner to retain a real predecessor receipt.
      // Its Account/World inputs are controlled; this is preservation, not genuine intake proof.
      var selected = AutomationEmptySelectedSourceIntakePostgresFixture.create("1");
      new AutomationEmptySelectedSourceIntakeRepository(dsl)
          .retainFresh(
              selected.inputs(),
              AutomationEmptySelectedSourceIntakeReceipt.requestDigest(
                  "test", selected.authorization(), selected.freezeEvidence()));
      dsl.execute(
          "INSERT INTO scripts(tenant_id,name,version,definition,base_version_id) VALUES(7001,'retained','patch','{}',1)");
      dsl.execute(
          "INSERT INTO script_event_bindings(tenant_id,script_patch_version,event_type,event_schema_version,script_id,target_scope_type,target_scope_id)"
              + " VALUES(7001,'patch','onCommand','1','script','ENTITY','7')");
      dsl.execute(
          "INSERT INTO script_patch_base_bindings(tenant_id,script_patch_version,base_version_id) VALUES('7001','patch',1)");
      dsl.execute("INSERT INTO factions(tenant_id,name) VALUES(7001,'retained')");
      dsl.execute(
          "INSERT INTO faction_standing(tenant_id,character_id,faction_id,reputation,playable_state_key)"
              + " VALUES(7001,9,1,42,'instance:legacy')");
      dsl.execute(
          "INSERT INTO npc_memory(tenant_id,npc_id,key,value) VALUES(7001,7,'memory','retain')");
      dsl.execute(
          "INSERT INTO npc_formations(tenant_id,name,leader_npc_id,formation_type) VALUES(7001,'retained',7,'LINE')");
      dsl.execute("INSERT INTO npc_formation_member(formation_id,npc_id) VALUES(1,8)");
      dsl.execute(
          "INSERT INTO plugin_runtime_states(tenant_id,game_instance_id,plugin_id,active_plugin_version_id,plugin_state,status_reason,"
              + "plugin_activation_epoch,lifecycle_revision) VALUES('7001','legacy','plugin','v1','PLUGIN_STATE_ENABLED','retained',1,1)");
      dsl.execute(
          "INSERT INTO script_work_items(tenant_id,game_instance_id,region_id,region_epoch,entity_id,script_id,event_type,"
              + "event_schema_version,script_patch_version,script_pin_epoch,script_pin_control_plane_request_id,script_event_id,source_service,trigger_mode,status,failure_generation)"
              + " VALUES('7001','legacy','region',1,'7','script','onCommand','1','patch',1,'pin','event','game-session-service','NORMAL','DEAD_LETTERED',1)");
      dsl.execute(
          "INSERT INTO script_event_audit(tenant_id,game_instance_id,region_id,region_epoch,entity_id,script_id,event_type,"
              + "event_schema_version,script_patch_version,script_event_id,source_service,trigger_mode,work_item_id,final_stage,final_outcome,final_reason)"
              + " VALUES('7001','legacy','region',1,'7','script','onCommand','1','patch','event','game-session-service','NORMAL',1,'DSL_EVAL','failed','retain')");
      dsl.execute(
          "INSERT INTO script_event_ingress_audit(tenant_id,event_type,event_schema_version,script_patch_version,script_event_id,"
              + "source_service,trigger_mode,admitted,admission_outcome,admission_reason)"
              + " VALUES('7001','onLoad','1','patch','readiness','automation-scripting-service','NORMAL',false,'failed','retain')");
      dsl.execute(
          "INSERT INTO script_handoff_events(event_id,tenant_id,game_instance_id,script_patch_version,script_id,work_item_id,command_ordinal,"
              + "automation_dispatch_id,target_entity_id,handoff_outcome,handoff_reason)"
              + " VALUES('handoff','7001','legacy','patch','script',1,0,'dispatch','7','failed','retain')");
      dsl.execute(
          "INSERT INTO script_dead_letter_replay_requests(tenant_id,control_plane_request_id,request_fingerprint,status)"
              + " VALUES('7001','replay',?,'COMPLETED')",
          "a".repeat(64));
      dsl.execute(
          "INSERT INTO script_dead_letter_replay_results(tenant_id,replay_request_id,requested_work_item_id,work_item_id,outcome)"
              + " VALUES('7001',1,1,1,'rejected')");
      dsl.execute(
          "INSERT INTO script_schedule_instances(tenant_id,game_instance_id,script_patch_version,script_id,event_type,schedule_definition_id,"
              + "schedule_kind,cadence_value,cadence_unit,materialization_status,schedule_metadata_json,schedule_semantics_hash)"
              + " VALUES('7001','legacy','patch','script','onInterval','schedule','INTERVAL',10,'TICK','PENDING_RUNTIME_PROGRESS','{}',?)",
          "b".repeat(64));
      dsl.execute(
          "INSERT INTO plugin_runtime_request_history(tenant_id,game_instance_id,plugin_id,operation,control_plane_request_id,request_fingerprint,"
              + "plugin_state,plugin_activation_epoch,lifecycle_revision) VALUES('7001','legacy','plugin','ENABLE','request',?,'PLUGIN_STATE_ENABLED',1,1)",
          "c".repeat(64));
      dsl.execute(
          "INSERT INTO plugin_runtime_events(event_id,tenant_id,game_instance_id,plugin_id,plugin_state,status_reason)"
              + " VALUES('plugin-event','7001','legacy','plugin','PLUGIN_STATE_DISABLED','retain')");
      dsl.execute(
          "INSERT INTO automation_admission_request_history(tenant_id,game_instance_id,region_id,mode,control_plane_request_id,request_fingerprint,"
              + "admission_epoch,outcome,actor_principal,reason) VALUES('7001','legacy','region','NORMAL','barrier',?,1,'APPLIED','controlled','retain')",
          "d".repeat(64));
      dsl.execute(
          "INSERT INTO automation_admission_states(tenant_id,game_instance_id,region_id) VALUES('7001','legacy','region')");
      dsl.execute(
          "INSERT INTO script_patch_pin_projections(tenant_id,game_instance_id,observed_pinned_script_patch_version,script_pin_epoch,last_observed_control_plane_request_id)"
              + " VALUES('7001','legacy','patch',1,'pin')");
      dsl.execute(
          "INSERT INTO script_patch_instance_rollout_projections(tenant_id,game_instance_id,script_patch_version,rollout_status,status_reason)"
              + " VALUES('7001','legacy','patch','OBSERVED','retain')");
      dsl.execute(
          "INSERT INTO script_patch_instance_rollout_events(event_id,tenant_id,game_instance_id,script_patch_version,rollout_status,status_reason)"
              + " VALUES('rollout','7001','legacy','patch','OBSERVED','retain')");
      dsl.execute(
          "INSERT INTO script_schedule_definitions(tenant_id,script_patch_version,script_id,event_type,schedule_definition_id,schedule_kind,cadence_value,cadence_unit,"
              + "schedule_metadata_json,schedule_semantics_hash) VALUES(7001,'patch','script','onInterval','schedule','INTERVAL',10,'TICK','{}',?)",
          "b".repeat(64));
      dsl.execute(
          "INSERT INTO script_patch_readiness_projections(tenant_id,script_patch_version,readiness_status,status_reason)"
              + " VALUES('7001','patch','FAILED','retain')");
      dsl.execute(
          "INSERT INTO automation_empty_source_numeric_key_reservation VALUES('TENANT',7001,'LEGACY_NUMERIC')");
      dsl.execute("INSERT INTO saga_instance(saga_name,state) VALUES('retained','PENDING')");
      dsl.execute(
          "INSERT INTO saga_step(instance_id,name,status) VALUES(1,'retained-step','PENDING')");
    }

    @Override
    public void close() {
      dsl.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static final class Collector
      implements StreamObserver<ReadAutomationRuntimeFamilyCatalogResponse> {
    private ReadAutomationRuntimeFamilyCatalogResponse value;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(ReadAutomationRuntimeFamilyCatalogResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
