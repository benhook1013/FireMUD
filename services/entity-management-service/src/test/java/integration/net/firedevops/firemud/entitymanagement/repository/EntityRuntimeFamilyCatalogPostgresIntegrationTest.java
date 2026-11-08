package net.firedevops.firemud.entitymanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.FactKind;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.FreshDisposition;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.Role;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.StateClass;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.entitymanagement.runtimecatalog.EntityRuntimeFamilyCatalogReadGrpcService;
import net.firedevops.firemud.entitymanagement.v1.ReadEntityRuntimeFamilyCatalogResponse;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Entity/Saga migrations and PostgreSQL census. Controlled SQL identities and RPC context are
 * not genuine upstream custody/PKI, FRESH materialization or cross-owner completeness proof.
 * Repository reconstruction proves durable readback, not operating-system process-death recovery.
 */
@Testcontainers(disabledWithoutDocker = false)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
@SuppressWarnings("resource")
class EntityRuntimeFamilyCatalogPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private static final String HISTORY = "flyway_schema_history_entity_management_service";

  @Test
  void actualCombinedMigrationsYieldExactCompleteCensusIncludingOwnInfrastructure() {
    try (var f = fixture(null, HISTORY)) {
      var catalog = f.owner().read(request()).catalog();
      assertThat(catalog.catalogEpoch()).isEqualTo(2);
      assertThat(
              catalog.schemaFacts().stream()
                  .filter(fact -> fact.kind() == FactKind.TABLE)
                  .map(EntityRuntimeFamilyCatalog.SchemaFact::identity)
                  .toList())
          .hasSize(62)
          .containsExactlyInAnyOrderElementsOf(
              f.dsl
                  .fetch(
                      "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relkind IN ('r','p','f')",
                      f.schema)
                  .getValues(0, String.class));
      assertThat(catalog.families().stream().filter(family -> family.component().isEmpty()).count())
          .isEqualTo(62);
      assertFamily(
          catalog,
          "saga_instance",
          Role.CONTROL,
          StateClass.NOT_PAYLOAD,
          FreshDisposition.CONTROL_ONLY);
      assertFamily(
          catalog,
          "saga_step",
          Role.CONTROL,
          StateClass.NOT_PAYLOAD,
          FreshDisposition.CONTROL_ONLY);
      assertFamily(
          catalog, HISTORY, Role.HISTORY, StateClass.NOT_PAYLOAD, FreshDisposition.HISTORY_ONLY);
      assertFamily(
          catalog,
          "entity_runtime_family_catalog_snapshot",
          Role.CATALOG,
          StateClass.NOT_PAYLOAD,
          FreshDisposition.CATALOG_ONLY);
      assertThat(catalog.schemaFacts())
          .anyMatch(fact -> fact.kind() == FactKind.ROUTINE)
          .anyMatch(fact -> fact.kind() == FactKind.COLUMN)
          .anyMatch(
              fact ->
                  fact.kind() == FactKind.SEQUENCE
                      && fact.identity().equals("entity_runtime_entity_id_sequence"))
          .anyMatch(fact -> fact.kind() == FactKind.MIGRATION && fact.identity().contains("1004"))
          .anyMatch(
              fact ->
                  fact.kind() == FactKind.MIGRATION
                      && fact.identity().equals("catalog-installation:1025"));
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT catalog_bytes=entity_runtime_family_catalog_bytes(catalog_epoch) FROM entity_runtime_family_catalog_epoch ORDER BY catalog_epoch DESC LIMIT 1")
                  .get(0, Boolean.class))
          .isTrue();
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT version FROM "
                          + HISTORY
                          + " WHERE version IS NOT NULL ORDER BY installed_rank")
                  .getValues(0, String.class))
          .containsExactly(
              "1", "1000", "1001", "1002", "1003", "1004", "1005", "1006", "1007", "1008", "1009",
              "1010", "1011", "1012", "1014", "1015", "1016", "1017", "1018", "1019", "1020",
              "1021", "1022", "1023", "1024", "1025", "1026");
    }
  }

  @Test
  void conditionalAndMixedFamiliesAreExplicitBlockingFactsEvenWithZeroRows() {
    try (var f = fixture(null, HISTORY)) {
      var catalog = f.owner().read(request()).catalog();
      for (String name : List.of("actor_resource_states", "actor_active_conditions")) {
        assertFamily(
            catalog,
            name,
            Role.RUNTIME_PAYLOAD,
            StateClass.CONDITIONAL_S1_S2,
            FreshDisposition.BLOCKING_NAMESPACE_REMAP);
        assertThat(f.count(name)).isZero();
      }
      for (String name : List.of("item_instances", "container_instances", "item_stacks"))
        assertFamily(
            catalog,
            name,
            Role.RUNTIME_PAYLOAD,
            StateClass.CONDITIONAL_HOLDER,
            FreshDisposition.BLOCKING_HOLDER_CLOSURE);
      assertFamily(
          catalog, "npcs", Role.MIXED, StateClass.UNRESOLVED, FreshDisposition.BLOCKING_MIXED_NPC);
      assertFamily(
          catalog,
          "npcs.last_defeated_at",
          Role.MIXED,
          StateClass.UNRESOLVED,
          FreshDisposition.BLOCKING_MIXED_NPC);
      assertFamily(
          catalog,
          "npcs.behavior",
          Role.SOURCE,
          StateClass.NOT_PAYLOAD,
          FreshDisposition.AUTHORED_SOURCE_ONLY);
      assertFamily(
          catalog,
          "item_visible_ref_counters",
          Role.ALLOCATOR,
          StateClass.UNRESOLVED,
          FreshDisposition.BLOCKING_ALLOCATOR_CLASS);
      assertFamily(
          catalog,
          "inventory",
          Role.RUNTIME_PAYLOAD,
          StateClass.S2,
          FreshDisposition.TEMPLATE_MAPPING_REQUIRED);
      assertFamily(
          catalog,
          "character_equipment",
          Role.RUNTIME_PAYLOAD,
          StateClass.S2,
          FreshDisposition.TEMPLATE_MAPPING_REQUIRED);
      assertFamily(
          catalog,
          "characters",
          Role.RUNTIME_PAYLOAD,
          StateClass.S1,
          FreshDisposition.PROVISIONAL_NAMESPACE);
      assertFamily(
          catalog,
          "characters.body_layout_key",
          Role.RUNTIME_PAYLOAD,
          StateClass.S2,
          FreshDisposition.TEMPLATE_MAPPING_REQUIRED);
      assertFamily(
          catalog,
          "character_friend",
          Role.RUNTIME_PAYLOAD,
          StateClass.S1,
          FreshDisposition.PROVISIONAL_NAMESPACE);
      assertFamily(
          catalog,
          "room_ground_inventory",
          Role.RUNTIME_PAYLOAD,
          StateClass.S3,
          FreshDisposition.GROUND_ONLY_S3);
      for (String name :
          List.of(
              "entity_preseeded_actor_runtime_projections",
              "entity_player_created_runtime_projections"))
        assertFamily(
            catalog,
            name,
            Role.PROJECTION,
            StateClass.S3,
            FreshDisposition.PROJECTION_S3_RETAIN_LINEAGE);
      assertFamily(
          catalog,
          "entity_mutation_effects",
          Role.REPLAY,
          StateClass.NOT_PAYLOAD,
          FreshDisposition.REPLAY_ONLY);
      assertFamily(
          catalog,
          "entity_character_create_operations",
          Role.CREATION,
          StateClass.NOT_PAYLOAD,
          FreshDisposition.CREATE_ONLY);
      assertFamily(
          catalog,
          "entity_preseeded_actor_assignment_operations",
          Role.ASSIGNMENT,
          StateClass.NOT_PAYLOAD,
          FreshDisposition.ASSIGNMENT_ONLY);
      assertFamily(
          catalog,
          "entity_empty_source_archetype_roots",
          Role.SOURCE,
          StateClass.NOT_PAYLOAD,
          FreshDisposition.SELECTED_EMPTY_SOURCE_ONLY);
      assertThat(catalog.families())
          .noneMatch(
              family ->
                  family.familyId().equals("entity_tick_state")
                      || family.familyId().equals("character"));
    }
  }

  @Test
  void configuredHistoryNamesBindActualHistoryWithoutHardcodedFallback() {
    for (String history :
        List.of("flyway_schema_history", "flyway_schema_history_public", "custom_entity_history")) {
      try (var f = fixture(null, history)) {
        var original = f.owner().read(request());
        assertFamily(
            original.catalog(),
            history,
            Role.HISTORY,
            StateClass.NOT_PAYLOAD,
            FreshDisposition.HISTORY_ONLY);
        assertThat(f.owner().readExact(original.request())).isEqualTo(original);
        assertThat(
                f.dsl
                    .fetchSingle("SELECT to_regclass(?) IS NULL", f.schema + "." + HISTORY)
                    .get(0, Boolean.class))
            .isTrue();
      }
    }
  }

  @Test
  void exactReplayRestartIndependentReadbackAndChangedRequestConflict() {
    try (var f = fixture(null, HISTORY)) {
      var read = request();
      var original = f.owner().read(read);
      assertThat(f.owner().read(read)).isEqualTo(original);
      assertThat(f.owner().readExact(read)).isEqualTo(original);
      assertThat(f.count("entity_runtime_family_catalog_snapshot")).isEqualTo(1);
      var other = f.owner().read(request());
      assertThat(other.snapshotId()).isNotEqualTo(original.snapshotId());
      assertThat(other.catalog().exactBytes()).isEqualTo(original.catalog().exactBytes());
      var otherOwner =
          new EntityRuntimeFamilyCatalogRepository(f.dsl, f.manager, f.schema, "other");
      assertThatThrownBy(() -> otherOwner.read(new Request(read.readRequestId(), "other")))
          .isInstanceOf(EntityRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThat(f.count("entity_runtime_family_catalog_snapshot")).isEqualTo(2);
      assertThatThrownBy(() -> f.owner().readExact(request()))
          .isInstanceOf(EntityRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThatThrownBy(
              () -> new TransactionTemplate(f.manager).execute(status -> f.owner().read(request())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("independent");
    }
  }

  @Test
  void actualConcurrentIdenticalRequestsConvergeOnOneCommittedSnapshot() throws Exception {
    try (var f = fixture(null, HISTORY);
        var workers = Executors.newFixedThreadPool(2)) {
      var read = request();
      var first = workers.submit(() -> f.owner().read(read));
      var second = workers.submit(() -> f.owner().read(read));
      assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(second.get(30, TimeUnit.SECONDS));
      assertThat(f.count("entity_runtime_family_catalog_snapshot")).isEqualTo(1);
    }
  }

  @Test
  void additiveUpgradePreservesPopulatedActorHolderAuthoredReplayAuditSagaAndHistoryRows() {
    try (var f = fixture("1024", HISTORY)) {
      seedRetained(f.dsl);
      List<String> tables =
          List.of(
              "characters",
              "npcs",
              "items",
              "inventory",
              "character_friend",
              "character_equipment",
              "actor_resource_states",
              "actor_active_conditions",
              "container_instances",
              "item_instances",
              "item_stacks",
              "item_visible_ref_counters",
              "room_ground_inventory",
              "crafting_recipes",
              "crafting_ingredients",
              "equipment_slot_definitions",
              "body_layout_slot_definitions",
              "item_transfer_audits",
              "entity_mutation_effects",
              "saga_instance",
              "saga_step");
      var before = new LinkedHashMap<String, List<String>>();
      tables.forEach(table -> before.put(table, f.rows(table)));
      var history = f.rows(HISTORY);
      String sequence =
          f.dsl
              .fetchSingle(
                  "SELECT last_value::text||':'||is_called::text FROM entity_runtime_entity_id_sequence")
              .get(0, String.class);
      migration(f.source, f.schema, HISTORY, null).load().migrate();
      tables.forEach(table -> assertThat(f.rows(table)).as(table).isEqualTo(before.get(table)));
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(t)::text||':'||xmin::text FROM "
                          + HISTORY
                          + " t WHERE version IS DISTINCT FROM '1025' AND version IS DISTINCT FROM '1026' ORDER BY installed_rank")
                  .getValues(0, String.class))
          .containsExactlyInAnyOrderElementsOf(history);
      f.owner().read(request());
      tables.forEach(table -> assertThat(f.rows(table)).as(table).isEqualTo(before.get(table)));
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT last_value::text||':'||is_called::text FROM entity_runtime_entity_id_sequence")
                  .get(0, String.class))
          .isEqualTo(sequence);
      assertThat(f.count("entity_preseeded_actor_runtime_projections")).isZero();
      assertThat(f.count("entity_player_created_runtime_projections")).isZero();
      migration(f.source, f.schema, HISTORY, null).load().validate();
    }
  }

  @Test
  void unknownEmptyTableRefusesInitialInstallAtomicallyAndInvalidatesExistingSeal() {
    try (var f = fixture("1024", HISTORY)) {
      var history = f.rows(HISTORY);
      f.dsl.execute("CREATE TABLE unknown_runtime_family(id BIGINT PRIMARY KEY)");
      assertThatThrownBy(() -> migration(f.source, f.schema, HISTORY, null).load().migrate())
          .hasMessageContaining("Unclassified or changed");
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT to_regclass(?) IS NULL",
                      f.schema + ".entity_runtime_family_catalog_epoch")
                  .get(0, Boolean.class))
          .isTrue();
      assertThat(f.rows(HISTORY)).isEqualTo(history);
    }
    try (var f = fixture(null, HISTORY)) {
      var read = request();
      f.owner().read(read);
      f.dsl.execute("CREATE TABLE unknown_runtime_family(id BIGINT PRIMARY KEY)");
      assertThatThrownBy(() -> f.owner().readExact(read))
          .hasMessageContaining("Unclassified or changed");
      assertThatThrownBy(() -> f.owner().read(request()))
          .hasMessageContaining("Unclassified or changed");
      assertThat(f.count("unknown_runtime_family")).isZero();
      assertThat(f.count("entity_runtime_family_catalog_snapshot")).isEqualTo(1);
    }
  }

  @Test
  void columnRoutineConstraintAndSequenceDefinitionDriftCannotResample() {
    for (String change :
        List.of(
            "ALTER TABLE npcs ADD COLUMN future_state TEXT",
            "ALTER TABLE npcs RENAME COLUMN last_defeated_at TO defeated_later",
            "ALTER TABLE npcs ALTER COLUMN behavior TYPE VARCHAR(120)",
            "ALTER TABLE npcs ADD CONSTRAINT catalog_probe CHECK(id>0)",
            "CREATE FUNCTION future_writer() RETURNS INTEGER LANGUAGE SQL AS 'SELECT 1'",
            "ALTER SEQUENCE entity_runtime_entity_id_sequence INCREMENT BY 2")) {
      try (var f = fixture(null, HISTORY)) {
        var read = request();
        var original = f.owner().read(read);
        var retained =
            f.dsl
                .fetchSingle(
                    "SELECT evidence_bytes FROM entity_runtime_family_catalog_snapshot WHERE read_request_id=?",
                    read.readRequestId())
                .get(0, byte[].class);
        f.dsl.execute(change);
        boolean classifiedColumnRemoved =
            change.startsWith("ALTER TABLE npcs RENAME COLUMN last_defeated_at");
        Class<? extends RuntimeException> expectedType =
            classifiedColumnRemoved
                ? DataAccessException.class
                : EntityRuntimeFamilyCatalogRepository.CatalogConflictException.class;
        String expectedMessage =
            classifiedColumnRemoved
                ? "Unclassified or changed"
                : "Entity schema/classification drift invalidates its epoch";
        assertThatThrownBy(() -> f.owner().readExact(read))
            .isInstanceOf(expectedType)
            .hasMessageContaining(expectedMessage);
        assertThatThrownBy(() -> f.owner().read(request()))
            .isInstanceOf(expectedType)
            .hasMessageContaining(expectedMessage);
        assertThat(f.count("entity_runtime_family_catalog_snapshot")).isEqualTo(1);
        assertThat(
                f.dsl
                    .fetchSingle(
                        "SELECT evidence_bytes FROM entity_runtime_family_catalog_snapshot WHERE read_request_id=?",
                        read.readRequestId())
                    .get(0, byte[].class))
            .isEqualTo(retained)
            .isEqualTo(EntityRuntimeFamilyCatalogCodec.evidenceBytes(original));
      }
    }
  }

  @Test
  void missingTableClassificationAndMigrationChangesInvalidateWithoutRewritingEvidence() {
    for (String change :
        List.of(
            "DROP TABLE npcs",
            "ALTER TABLE npcs RENAME TO renamed_npcs",
            "INSERT INTO entity_runtime_family_classification VALUES ('npcs.version_id_extra','npcs','version_id_extra','SOURCE','NOT_PAYLOAD','AUTHORED_SOURCE_ONLY')")) {
      try (var f = fixture(null, HISTORY)) {
        var read = request();
        var original = f.owner().read(read);
        f.dsl.execute(change);
        assertThatThrownBy(() -> f.owner().readExact(read))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("Unclassified or changed");
        assertThatThrownBy(() -> f.owner().read(request()))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("Unclassified or changed");
        assertThat(f.count("entity_runtime_family_catalog_snapshot")).isEqualTo(1);
        assertThat(
                f.dsl
                    .fetchSingle(
                        "SELECT evidence_bytes FROM entity_runtime_family_catalog_snapshot WHERE read_request_id=?",
                        read.readRequestId())
                    .get(0, byte[].class))
            .isEqualTo(EntityRuntimeFamilyCatalogCodec.evidenceBytes(original));
      }
    }
    for (String change :
        List.of(
            "UPDATE " + HISTORY + " SET checksum=checksum+1 WHERE version='1'",
            "UPDATE " + HISTORY + " SET success=false WHERE version='1025'",
            "UPDATE " + HISTORY + " SET success=false WHERE version='1026'",
            "UPDATE " + HISTORY + " SET script='other.sql' WHERE version='1026'",
            "UPDATE " + HISTORY + " SET script='other.sql' WHERE version='1025'")) {
      try (var f = fixture(null, HISTORY)) {
        var read = request();
        var original = f.owner().read(read);
        f.dsl.execute(change);
        String expectedMessage =
            change.contains("version='1025'") || change.contains("version='1026'")
                ? "No durable Entity catalog epoch"
                : "Entity schema/classification drift invalidates its epoch";
        assertThatThrownBy(() -> f.owner().readExact(read))
            .isInstanceOf(EntityRuntimeFamilyCatalogRepository.CatalogConflictException.class)
            .hasMessage(expectedMessage);
        assertThatThrownBy(() -> f.owner().read(request()))
            .isInstanceOf(EntityRuntimeFamilyCatalogRepository.CatalogConflictException.class)
            .hasMessage(expectedMessage);
        assertThat(f.count("entity_runtime_family_catalog_snapshot")).isEqualTo(1);
        assertThat(
                f.dsl
                    .fetchSingle(
                        "SELECT evidence_bytes FROM entity_runtime_family_catalog_snapshot WHERE read_request_id=?",
                        read.readRequestId())
                    .get(0, byte[].class))
            .isEqualTo(EntityRuntimeFamilyCatalogCodec.evidenceBytes(original));
      }
    }
  }

  @Test
  void validNewClassificationRequiresExplicitNewOwnerEpochAndInvalidatesOldRequest() {
    try (var f = fixture(null, HISTORY)) {
      var read = request();
      var original = f.owner().read(read);
      f.dsl.execute(
          "INSERT INTO entity_runtime_family_classification VALUES ('items.name','items','name','SOURCE','NOT_PAYLOAD','AUTHORED_SOURCE_ONLY')");
      assertThatThrownBy(() -> f.owner().readExact(read))
          .isInstanceOf(EntityRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      f.dsl.execute(
          "INSERT INTO entity_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)"
              + " SELECT 3,bytes,'sha256:'||encode(sha256(bytes),'hex') FROM (SELECT entity_runtime_family_catalog_bytes(3) AS bytes) revised");
      assertThatThrownBy(() -> f.owner().read(read))
          .isInstanceOf(EntityRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      var revised = f.owner().read(request());
      assertThat(revised.catalog().catalogEpoch()).isEqualTo(3);
      assertThat(revised.catalog().digest()).isNotEqualTo(original.catalog().digest());
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT evidence_bytes FROM entity_runtime_family_catalog_snapshot WHERE read_request_id=?",
                      read.readRequestId())
                  .get(0, byte[].class))
          .isEqualTo(EntityRuntimeFamilyCatalogCodec.evidenceBytes(original));
    }
  }

  @Test
  void viewAuthorizationAndMaterializedOptionsInvalidateTheSealWithoutResampling() {
    for (String alteration :
        List.of(
            "ALTER VIEW catalog_view SET (security_invoker=true)",
            "ALTER VIEW catalog_view SET (security_barrier=true)",
            "ALTER MATERIALIZED VIEW catalog_materialized SET (fillfactor=70)")) {
      try (var f = fixture("1024", HISTORY)) {
        f.dsl.execute("CREATE VIEW catalog_view AS SELECT id FROM characters");
        f.dsl.execute(
            "CREATE MATERIALIZED VIEW catalog_materialized AS SELECT id FROM characters WITH NO DATA");
        migration(f.source, f.schema, HISTORY, null).load().migrate();
        var read = request();
        var original = f.owner().read(read);
        var retained = f.rows("entity_runtime_family_catalog_snapshot");
        f.dsl.execute(alteration);
        assertThatThrownBy(() -> f.owner().readExact(read))
            .isInstanceOf(EntityRuntimeFamilyCatalogRepository.CatalogConflictException.class);
        assertThatThrownBy(() -> f.owner().read(read))
            .isInstanceOf(EntityRuntimeFamilyCatalogRepository.CatalogConflictException.class);
        assertThatThrownBy(() -> f.owner().read(request()))
            .isInstanceOf(EntityRuntimeFamilyCatalogRepository.CatalogConflictException.class);
        assertThat(f.rows("entity_runtime_family_catalog_snapshot")).isEqualTo(retained);
        assertThat(
                f.dsl
                    .fetchSingle(
                        "SELECT evidence_bytes FROM entity_runtime_family_catalog_snapshot WHERE read_request_id=?",
                        read.readRequestId())
                    .get(0, byte[].class))
            .isEqualTo(EntityRuntimeFamilyCatalogCodec.evidenceBytes(original));
      }
    }
  }

  @Test
  void forwardFrameGuardUpgradeRetainsOldEpochSnapshotAndHistoryWithoutResampling() {
    try (var f = fixture("1025", HISTORY)) {
      var read = request();
      var original = f.owner().read(read);
      assertThat(original.catalog().catalogEpoch()).isEqualTo(1);
      var epochs = f.rows("entity_runtime_family_catalog_epoch");
      var snapshots = f.rows("entity_runtime_family_catalog_snapshot");
      var history = f.rows(HISTORY);
      migration(f.source, f.schema, HISTORY, null).load().migrate();
      assertThat(f.rows("entity_runtime_family_catalog_epoch")).containsAll(epochs).hasSize(2);
      assertThat(f.rows("entity_runtime_family_catalog_snapshot")).isEqualTo(snapshots);
      assertThat(f.rows(HISTORY)).containsAll(history).hasSize(history.size() + 1);
      assertThatThrownBy(() -> f.owner().readExact(read))
          .isInstanceOf(EntityRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThatThrownBy(() -> f.owner().read(read))
          .isInstanceOf(EntityRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      var current = f.owner().read(request());
      assertThat(current.catalog().catalogEpoch()).isEqualTo(2);
      assertThat(current.catalog().digest()).isNotEqualTo(original.catalog().digest());
      assertThat(current.catalog().schemaFacts())
          .anyMatch(
              fact ->
                  fact.kind() == FactKind.MIGRATION
                      && fact.identity().equals("catalog-installation:1026"));
      migration(f.source, f.schema, HISTORY, null).load().validate();
    }
  }

  @Test
  void directSqlPoisonFramesRefuseWithoutConsumingRequestIdentity() {
    try (var f = fixture(null, HISTORY)) {
      var original = f.owner().read(request());
      for (String vector :
          List.of(
              "request-arbitrary",
              "evidence-arbitrary",
              "request-id",
              "namespace",
              "row-namespace",
              "request-magic",
              "request-trailing",
              "request-truncated",
              "evidence-request",
              "snapshot",
              "catalog-digest",
              "catalog-bytes",
              "evidence-magic",
              "evidence-trailing",
              "evidence-truncated")) {
        Request read = request();
        UUID snapshot = UUID.randomUUID();
        var proposal = new Evidence(read, snapshot, original.catalog());
        byte[] requestBytes = EntityRuntimeFamilyCatalogCodec.requestBytes(read);
        byte[] evidenceBytes = EntityRuntimeFamilyCatalogCodec.evidenceBytes(proposal);
        String namespace = read.targetNamespace();
        switch (vector) {
          case "request-id" ->
              requestBytes = EntityRuntimeFamilyCatalogCodec.requestBytes(request());
          case "namespace" ->
              requestBytes =
                  EntityRuntimeFamilyCatalogCodec.requestBytes(
                      new Request(read.readRequestId(), "other"));
          case "row-namespace" -> namespace = "other";
          case "request-arbitrary" ->
              requestBytes = "arbitrary".getBytes(StandardCharsets.US_ASCII);
          case "evidence-arbitrary" ->
              evidenceBytes = "arbitrary".getBytes(StandardCharsets.US_ASCII);
          case "request-magic" -> requestBytes[0] ^= 1;
          case "request-trailing" ->
              requestBytes = java.util.Arrays.copyOf(requestBytes, requestBytes.length + 1);
          case "request-truncated" ->
              requestBytes = java.util.Arrays.copyOf(requestBytes, requestBytes.length - 1);
          case "evidence-request" ->
              evidenceBytes =
                  EntityRuntimeFamilyCatalogCodec.evidenceBytes(
                      new Evidence(request(), snapshot, original.catalog()));
          case "snapshot" ->
              evidenceBytes =
                  EntityRuntimeFamilyCatalogCodec.evidenceBytes(
                      new Evidence(read, UUID.randomUUID(), original.catalog()));
          case "catalog-digest" ->
              evidenceBytes[Integer.BYTES * 3 + requestBytes.length + Long.BYTES * 2] ^= 1;
          case "catalog-bytes" -> evidenceBytes[evidenceBytes.length - 1] ^= 1;
          case "evidence-magic" -> evidenceBytes[0] ^= 1;
          case "evidence-trailing" ->
              evidenceBytes = java.util.Arrays.copyOf(evidenceBytes, evidenceBytes.length + 1);
          case "evidence-truncated" ->
              evidenceBytes = java.util.Arrays.copyOf(evidenceBytes, evidenceBytes.length - 1);
          default -> throw new AssertionError("Unknown poison vector: " + vector);
        }
        byte[] insertedRequest = requestBytes;
        byte[] insertedEvidence = evidenceBytes;
        String insertedNamespace = namespace;
        long before = f.count("entity_runtime_family_catalog_snapshot");
        assertThatThrownBy(
                () ->
                    f.dsl.execute(
                        "INSERT INTO entity_runtime_family_catalog_snapshot(read_request_id,target_namespace,request_bytes,request_digest,snapshot_id,catalog_epoch,catalog_digest,evidence_bytes,evidence_digest) VALUES (?,?,?,?,?,?,?,?,?)",
                        read.readRequestId(),
                        insertedNamespace,
                        insertedRequest,
                        EntityRuntimeFamilyCatalog.sha256(insertedRequest),
                        snapshot,
                        original.catalog().catalogEpoch(),
                        original.catalog().digest(),
                        insertedEvidence,
                        EntityRuntimeFamilyCatalog.sha256(insertedEvidence)))
            .as(vector)
            .hasMessageContaining("complete exact request and evidence");
        assertThat(f.count("entity_runtime_family_catalog_snapshot")).isEqualTo(before);
        var committed = f.owner().read(read);
        assertThat(f.count("entity_runtime_family_catalog_snapshot")).isEqualTo(before + 1);
        assertThat(f.owner().readExact(read)).isEqualTo(committed);
        assertThat(f.owner().read(read)).isEqualTo(committed);
        assertThat(f.owner().readExact(original.request())).isEqualTo(original);
      }
    }
  }

  @Test
  void metadataEpochAndSnapshotsRemainImmutableAndWrongSealCannotBeInserted() {
    try (var f = fixture(null, HISTORY)) {
      f.owner().read(request());
      for (String table :
          List.of(
              "entity_runtime_family_classification",
              "entity_runtime_family_catalog_epoch",
              "entity_runtime_family_catalog_snapshot")) {
        assertThatThrownBy(() -> f.dsl.execute("DELETE FROM " + table))
            .hasMessageContaining("immutable");
        assertThatThrownBy(() -> f.dsl.execute("TRUNCATE " + table + " CASCADE"))
            .hasMessageContaining("immutable");
      }
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "UPDATE entity_runtime_family_classification SET state_class='S3' WHERE family_id='item_instances'"))
          .hasMessageContaining("immutable");
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "INSERT INTO entity_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)"
                          + " SELECT 3,catalog_bytes,catalog_digest FROM entity_runtime_family_catalog_epoch WHERE catalog_epoch=2"))
          .hasMessageContaining("exact classified installed schema");
    }
  }

  @Test
  void actualHandlerReturnsCommittedExactEvidenceWithoutCreatingActorsOrRuntime() {
    try (var f = fixture(null, HISTORY)) {
      var read = request();
      var collector = new Collector();
      var service = new EntityRuntimeFamilyCatalogReadGrpcService(f.owner(), "test");
      var peer =
          GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-session-service")
              .orElseThrow();
      Context.current()
          .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
          .run(
              () ->
                  service.readEntityRuntimeFamilyCatalog(
                      EntityRuntimeFamilyCatalogCodec.toRequest(read), collector));
      assertThat(collector.error).isNull();
      assertThat(collector.completed).isTrue();
      assertThat(EntityRuntimeFamilyCatalogCodec.fromResponse(read, collector.value))
          .isEqualTo(f.owner().readExact(read));
      for (String table :
          List.of(
              "characters",
              "actor_resource_states",
              "actor_active_conditions",
              "entity_character_create_operations",
              "entity_preseeded_actor_runtime_projections",
              "entity_player_created_runtime_projections")) assertThat(f.count(table)).isZero();
    }
  }

  private static void seedRetained(DSLContext dsl) {
    dsl.execute(
        "INSERT INTO characters(id,account_id,name,tenant_id) VALUES (101,81,'retained',71),(102,82,'friend',71)");
    dsl.execute(
        "INSERT INTO npcs(name,behavior,last_defeated_at,tenant_id) VALUES ('mixed','guard','2020-01-01',71)");
    dsl.execute(
        "INSERT INTO items(id,name,tenant_id,is_container) VALUES (201,'bag',71,true),(202,'gem',71,false)");
    dsl.execute("INSERT INTO inventory(character_id,item_id,quantity) VALUES (101,202,6)");
    dsl.execute(
        "INSERT INTO character_friend(character_id,friend_id,tenant_id) VALUES (101,102,71)");
    dsl.execute(
        "INSERT INTO character_equipment(character_id,slot,item_id) VALUES (101,'hand',202)");
    dsl.execute(
        "INSERT INTO actor_resource_states(tenant_id,character_id,stat_key,current_value,playable_state_key) VALUES (71,101,'health',31,'shared-live')");
    dsl.execute(
        "INSERT INTO actor_active_conditions(tenant_id,character_id,condition_key,source_type,playable_state_key,effect_payload_json) VALUES (71,101,'stunned','retained','shared-live','{}')");
    dsl.execute(
        "INSERT INTO container_instances(id,tenant_id,character_id,game_instance_id,item_id) VALUES (401,71,101,'44',201)");
    dsl.execute(
        "INSERT INTO item_instances(id,tenant_id,character_id,game_instance_id,item_id,visible_ref_token,visible_ref_sequence,visible_ref) VALUES (501,71,101,'44',201,'retained',1,'retained-bag')");
    dsl.execute("UPDATE container_instances SET item_instance_id=501 WHERE id=401");
    dsl.execute(
        "INSERT INTO item_instances(id,tenant_id,game_instance_id,container_instance_id,item_id,visible_ref_token,visible_ref_sequence,visible_ref) VALUES (502,71,'44',401,202,'retained',2,'retained-gem')");
    dsl.execute(
        "INSERT INTO item_stacks(tenant_id,character_id,game_instance_id,item_id,compatibility_fingerprint,quantity) VALUES (71,101,'44',202,'retained',13)");
    dsl.execute(
        "INSERT INTO item_visible_ref_counters(tenant_id,visible_ref_token,next_sequence) VALUES (71,'retained',3)");
    dsl.execute(
        "INSERT INTO room_ground_inventory(tenant_id,game_instance_id,room_instance_id,item_id,quantity) VALUES (71,'44','room-9',202,15)");
    dsl.execute(
        "INSERT INTO crafting_recipes(id,tenant_id,name,result_item_id,result_quantity) VALUES (601,71,'retained',202,1)");
    dsl.execute("INSERT INTO crafting_ingredients(recipe_id,item_id,quantity) VALUES (601,202,2)");
    dsl.execute(
        "INSERT INTO equipment_slot_definitions(tenant_id,slot_key,display_name) VALUES (71,'hand','Hand')");
    dsl.execute(
        "INSERT INTO body_layout_slot_definitions(tenant_id,body_layout_key,slot_key) VALUES (71,'DEFAULT','hand')");
    dsl.execute(
        "INSERT INTO item_transfer_audits(tenant_id,item_id,quantity,verb,correlation_key,source_holder_kind,destination_holder_kind) VALUES (71,202,1,'DROP','retained','CHARACTER','ROOM')");
    dsl.execute(
        "INSERT INTO entity_mutation_effects(tenant_id,effect_id,operation_name,response_payload,status) VALUES (71,'retained','DROP',decode('cafe','hex'),'APPLIED')");
    dsl.execute("INSERT INTO saga_instance(saga_name,state) VALUES ('retained','PENDING')");
    dsl.execute(
        "INSERT INTO saga_step(instance_id,name,status) SELECT id,'retained','PENDING' FROM saga_instance WHERE saga_name='retained'");
  }

  private static void assertFamily(
      EntityRuntimeFamilyCatalog catalog,
      String name,
      Role role,
      StateClass state,
      FreshDisposition disposition) {
    assertThat(catalog.families())
        .anySatisfy(
            family -> {
              assertThat(family.familyId()).isEqualTo(name);
              assertThat(family.role()).isEqualTo(role);
              assertThat(family.stateClass()).isEqualTo(state);
              assertThat(family.freshDisposition()).isEqualTo(disposition);
            });
  }

  private static Request request() {
    return new Request(UUID.randomUUID(), "test");
  }

  private static FluentConfiguration migration(
      DriverManagerDataSource source, String schema, String history, String target) {
    var config =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .table(history)
            .locations(
                "filesystem:"
                    + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize(),
                "classpath:db/migration/saga")
            .placeholders(Map.of("serviceSchema", schema));
    config
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    if (target != null) config.target(target);
    return config;
  }

  private static Fixture fixture(String target, String history) {
    String schema = "entity_catalog_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    try {
      migration(source, schema, history, target).load().migrate();
      return new Fixture(schema, source, dsl, new DataSourceTransactionManager(source));
    } catch (RuntimeException failed) {
      dsl.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      throw failed;
    }
  }

  private record Fixture(
      String schema,
      DriverManagerDataSource source,
      DSLContext dsl,
      DataSourceTransactionManager manager)
      implements AutoCloseable {
    EntityRuntimeFamilyCatalogRepository owner() {
      return new EntityRuntimeFamilyCatalogRepository(dsl, manager, schema, "test");
    }

    long count(String table) {
      return dsl.fetchSingle("SELECT count(*) FROM " + table).get(0, Long.class);
    }

    List<String> rows(String table) {
      return dsl.fetch(
              "SELECT to_jsonb(t)::text||':'||xmin::text AS value FROM "
                  + table
                  + " t ORDER BY value")
          .getValues(0, String.class);
    }

    @Override
    public void close() {
      dsl.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static final class Collector
      implements StreamObserver<ReadEntityRuntimeFamilyCatalogResponse> {
    private ReadEntityRuntimeFamilyCatalogResponse value;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(ReadEntityRuntimeFamilyCatalogResponse next) {
      value = next;
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
