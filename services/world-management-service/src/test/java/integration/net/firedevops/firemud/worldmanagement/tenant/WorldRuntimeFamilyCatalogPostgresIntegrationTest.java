package net.firedevops.firemud.worldmanagement.tenant;

import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.migration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog.FactKind;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog.FreshDisposition;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldRuntimeFamilyCatalogResponse;
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
 * Actual installed World/Saga PostgreSQL source proof. SQL fixtures control legacy identity and the
 * RPC identity context; these cases do not prove live PKI, FRESH authorization or materialization.
 * No positive case is disabled when Docker is unavailable: Gameplay must obtain physical results.
 */
@Testcontainers
@SuppressWarnings("resource")
class WorldRuntimeFamilyCatalogPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void configuredFlywayHistoryNameBindsTheActualInstallationAndReadback() throws Exception {
    for (String history :
        List.of("flyway_schema_history_public", "flyway_schema_history_custom_world")) {
      try (Fixture f = fixture(null, history)) {
        var original = f.owner().read(request());
        assertThat(original.catalog().families())
            .anySatisfy(
                family -> {
                  assertThat(family.familyId()).isEqualTo(history);
                  assertThat(family.freshDisposition()).isEqualTo(FreshDisposition.CONTROL_ONLY);
                });
        assertThat(f.owner().readExact(original.request())).isEqualTo(original);
        assertThat(
                f.dsl
                    .fetchSingle(
                        "SELECT to_regclass(?) IS NULL",
                        f.schema + ".flyway_schema_history_world_management_service")
                    .get(0, Boolean.class))
            .isTrue();
      }
    }
  }

  @Test
  void actualCompleteCurrentCensusIncludesEmptyRuntimeAndNonPayloadFamilies() throws Exception {
    try (Fixture f = fixture(null)) {
      var evidence = f.owner().read(request());
      var catalog = evidence.catalog();
      assertThat(catalog.catalogEpoch()).isEqualTo(1);
      assertThat(
              catalog.schemaFacts().stream()
                  .filter(fact -> fact.kind() == FactKind.TABLE)
                  .map(WorldRuntimeFamilyCatalog.SchemaFact::identity)
                  .toList())
          .containsExactlyInAnyOrderElementsOf(
              f.dsl
                  .fetch(
                      "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace"
                          + " WHERE n.nspname=? AND c.relkind IN ('r','p','f')",
                      f.schema)
                  .getValues(0, String.class));
      assertThat(catalog.families().stream().filter(family -> family.component().isEmpty()).count())
          .isEqualTo(
              catalog.schemaFacts().stream().filter(fact -> fact.kind() == FactKind.TABLE).count());
      assertThat(catalog.families())
          .anySatisfy(
              family -> {
                assertThat(family.familyId()).isEqualTo("region_instance.weather");
                assertThat(family.freshDisposition())
                    .isEqualTo(FreshDisposition.UNSUPPORTED_WEATHER);
              })
          .anySatisfy(
              family -> {
                assertThat(family.familyId()).isEqualTo("instance");
                assertThat(family.freshDisposition()).isEqualTo(FreshDisposition.LEGACY_EXCLUDED);
              })
          .anySatisfy(
              family -> {
                assertThat(family.familyId()).isEqualTo("character_location");
                assertThat(family.freshDisposition())
                    .isEqualTo(FreshDisposition.NOT_INITIALIZED_ACTOR_LOCATION);
              })
          .anySatisfy(
              family -> {
                assertThat(family.familyId())
                    .isEqualTo("world_canonical_initial_player_location_operation");
                assertThat(family.freshDisposition()).isEqualTo(FreshDisposition.PROVENANCE_ONLY);
              });
      assertThat(f.count("region_instance")).isZero();
      assertThat(f.count("character_location")).isZero();
      assertThat(f.count("world_event")).isZero();
      assertThat(catalog.families())
          .noneMatch(
              family ->
                  family.familyId().equals("npc_location")
                      || family.familyId().equals("population_schedule_instance")
                      || family.familyId().equals("generation_run"));
      assertThat(catalog.schemaFacts())
          .anyMatch(fact -> fact.kind() == FactKind.MIGRATION)
          .anyMatch(fact -> fact.kind() == FactKind.ROUTINE)
          .anyMatch(fact -> fact.kind() == FactKind.COLUMN);
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT catalog_bytes = world_runtime_family_catalog_bytes(catalog_epoch)"
                          + " FROM world_runtime_family_catalog_epoch")
                  .get(0, Boolean.class))
          .isTrue();
    }
  }

  @Test
  void exactReplayRestartAndIndependentReadbackRetainBytesDigestsAndSnapshotIdentity()
      throws Exception {
    try (Fixture f = fixture(null)) {
      Request read = request();
      var original = f.owner().read(read);
      assertThat(f.owner().read(read)).isEqualTo(original);
      assertThat(f.owner().readExact(read)).isEqualTo(original);
      assertThat(f.count("world_runtime_family_catalog_snapshot")).isEqualTo(1);
      var freshRead = f.owner().read(request());
      assertThat(freshRead.snapshotId()).isNotEqualTo(original.snapshotId());
      assertThat(freshRead.catalog().exactBytes()).isEqualTo(original.catalog().exactBytes());
      assertThat(freshRead.catalog().digest()).isEqualTo(original.catalog().digest());
      var otherNamespaceOwner =
          new WorldRuntimeFamilyCatalogRepository(f.dsl, f.manager, f.schema, "other");
      assertThatThrownBy(() -> otherNamespaceOwner.read(new Request(read.readRequestId(), "other")))
          .isInstanceOf(WorldRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThat(f.count("world_runtime_family_catalog_snapshot")).isEqualTo(2);
      assertThatThrownBy(() -> f.owner().readExact(request()))
          .isInstanceOf(WorldRuntimeFamilyCatalogRepository.CatalogConflictException.class);
    }
  }

  @Test
  void concurrentExactRetriesConvergeOnOneRetainedSnapshot() throws Exception {
    try (Fixture f = fixture(null);
        var workers = Executors.newFixedThreadPool(2)) {
      Request read = request();
      var first = workers.submit(() -> f.owner().read(read));
      var second = workers.submit(() -> f.owner().read(read));
      assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(second.get(30, TimeUnit.SECONDS));
      assertThat(f.count("world_runtime_family_catalog_snapshot")).isEqualTo(1);
    }
  }

  @Test
  void additiveForwardMigrationRetainsLegacyPayloadAndPriorMigrationHistoryExactly()
      throws Exception {
    try (Fixture f = fixture("1045")) {
      f.dsl.execute(
          "INSERT INTO world_authored_source_tenant_key_reservation(tenant_key,claim_kind) VALUES(7001,'LEGACY_NUMERIC')");
      long region =
          f.dsl
              .fetchSingle(
                  "INSERT INTO region(name,tenant_id,version_id) VALUES ('retained',7001,1) RETURNING id")
              .get(0, Long.class);
      long zone =
          f.dsl
              .fetchSingle(
                  "INSERT INTO zone(region_id,name,tenant_id,version_id) VALUES (?,'retained',7001,1) RETURNING id",
                  region)
              .get(0, Long.class);
      f.dsl.execute(
          "INSERT INTO instance(zone_id,tenant_id,owner_account_id) VALUES (?,7001,89)", zone);
      String regionBefore = f.jsonRows("region");
      String zoneBefore = f.jsonRows("zone");
      String instanceBefore = f.jsonRows("instance");
      List<String> historyBefore =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(h)::text FROM flyway_schema_history_world_management_service h ORDER BY installed_rank")
              .getValues(0, String.class);
      migration(f.source, f.schema, null).migrate();
      assertThat(f.jsonRows("region")).isEqualTo(regionBefore);
      assertThat(f.jsonRows("zone")).isEqualTo(zoneBefore);
      assertThat(f.jsonRows("instance")).isEqualTo(instanceBefore);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(h)::text FROM flyway_schema_history_world_management_service h WHERE version IS DISTINCT FROM '1046' ORDER BY installed_rank")
                  .getValues(0, String.class))
          .isEqualTo(historyBefore);
      f.owner().read(request());
      assertThat(f.jsonRows("region")).isEqualTo(regionBefore);
      assertThat(f.count("world_instance")).isZero();
      assertThat(f.count("world_canonical_instance_preparation")).isZero();
      assertThat(f.count("world_canonical_instance_association")).isZero();
      migration(f.source, f.schema, null).validate();
    }
  }

  @Test
  void emptyUnclassifiedNewTableInvalidatesOldSnapshotAndRefusesNewSampling() throws Exception {
    try (Fixture f = fixture(null)) {
      Request read = request();
      f.owner().read(read);
      f.dsl.execute("CREATE TABLE unknown_runtime_family (id BIGINT PRIMARY KEY)");
      assertThat(f.count("unknown_runtime_family")).isZero();
      assertThatThrownBy(() -> f.owner().readExact(read)).hasMessageContaining("Unclassified");
      assertThatThrownBy(() -> f.owner().read(request())).hasMessageContaining("Unclassified");
      assertThat(f.count("world_runtime_family_catalog_snapshot")).isEqualTo(1);
    }
  }

  @Test
  void forwardInstallationRefusesAnAlreadyPresentUnclassifiedFamilyAtomically() throws Exception {
    try (Fixture f = fixture("1045")) {
      f.dsl.execute("CREATE TABLE unknown_runtime_family (id BIGINT PRIMARY KEY)");
      List<String> history =
          f.dsl
              .fetch(
                  "SELECT to_jsonb(h)::text FROM flyway_schema_history_world_management_service h ORDER BY installed_rank")
              .getValues(0, String.class);
      assertThatThrownBy(() -> migration(f.source, f.schema, null).migrate())
          .hasMessageContaining("Unclassified or changed");
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT to_regclass(?) IS NULL",
                      f.schema + ".world_runtime_family_catalog_epoch")
                  .get(0, Boolean.class))
          .isTrue();
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(h)::text FROM flyway_schema_history_world_management_service h ORDER BY installed_rank")
                  .getValues(0, String.class))
          .isEqualTo(history);
      assertThat(f.count("unknown_runtime_family")).isZero();
    }
  }

  @Test
  void schemaColumnRenameConstraintAndRoutineChangesCannotResampleUnderSameEpoch()
      throws Exception {
    for (String change :
        List.of(
            "ALTER TABLE world_event ADD COLUMN future_state TEXT",
            "ALTER TABLE world_event RENAME COLUMN processed TO processed_later",
            "ALTER TABLE world_event ADD CONSTRAINT catalog_probe CHECK (id>0)",
            "ALTER TABLE world_event RENAME TO renamed_world_event",
            "CREATE FUNCTION future_runtime_writer() RETURNS INTEGER LANGUAGE SQL AS 'SELECT 1'")) {
      try (Fixture f = fixture(null)) {
        Request read = request();
        f.owner().read(read);
        f.dsl.execute(change);
        assertThatThrownBy(() -> f.owner().readExact(read)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> f.owner().read(request())).isInstanceOf(RuntimeException.class);
        assertThat(f.count("world_runtime_family_catalog_snapshot")).isEqualTo(1);
      }
    }
  }

  @Test
  void removalAndClassificationChangeAreExplicitlyInvalidating() throws Exception {
    try (Fixture f = fixture(null)) {
      Request read = request();
      f.owner().read(read);
      f.dsl.execute("DROP TABLE world_event");
      assertThatThrownBy(() -> f.owner().readExact(read))
          .hasMessageContaining("Unclassified or changed");
    }
    try (Fixture f = fixture(null)) {
      Request read = request();
      f.owner().read(read);
      // Add a declared component without removing metadata guards: changed classification must
      // invalidate the existing epoch even though its table/column really exists and is empty.
      f.dsl.execute(
          "INSERT INTO world_runtime_family_classification VALUES"
              + " ('region_instance.name','region_instance','name','RUNTIME_PAYLOAD','S3','REQUIRED_TOPOLOGY')");
      assertThatThrownBy(() -> f.owner().readExact(read))
          .isInstanceOf(WorldRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThatThrownBy(() -> f.owner().read(request()))
          .isInstanceOf(WorldRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThat(f.count("world_runtime_family_catalog_epoch")).isEqualTo(1);
    }
  }

  @Test
  void oldMigrationFactChangeInvalidatesTheSealAndMissingOwnerEpochNeverMeansComplete()
      throws Exception {
    try (Fixture f = fixture(null)) {
      Request read = request();
      f.owner().read(read);
      f.dsl.execute(
          "UPDATE flyway_schema_history_world_management_service SET checksum=checksum+1 WHERE version='1'");
      assertThatThrownBy(() -> f.owner().readExact(read))
          .isInstanceOf(WorldRuntimeFamilyCatalogRepository.CatalogConflictException.class);
    }
    try (Fixture f = fixture(null)) {
      f.dsl.execute(
          "UPDATE flyway_schema_history_world_management_service SET success=false WHERE version='1046'");
      assertThatThrownBy(() -> f.owner().read(request()))
          .hasMessageContaining("No durable World catalog epoch");
      assertThat(f.count("world_runtime_family_catalog_snapshot")).isZero();
    }
  }

  @Test
  void explicitOwnerEpochAdvanceInvalidatesOldRequestsWithoutRewritingTheirEvidence()
      throws Exception {
    try (Fixture f = fixture(null)) {
      Request read = request();
      var original = f.owner().read(read);
      // Controlled owner-local revision installation, not a runtime catalog mutation API.
      f.dsl.execute(
          "INSERT INTO world_runtime_family_classification VALUES"
              + " ('region_instance.name','region_instance','name','RUNTIME_PAYLOAD','S3','REQUIRED_TOPOLOGY')");
      f.dsl.execute(
          "INSERT INTO world_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)"
              + " SELECT 2,bytes,'sha256:'||encode(sha256(bytes),'hex') FROM"
              + " (SELECT world_runtime_family_catalog_bytes(2) AS bytes) revised");
      assertThatThrownBy(() -> f.owner().readExact(read))
          .isInstanceOf(WorldRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThatThrownBy(() -> f.owner().read(read))
          .isInstanceOf(WorldRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT evidence_bytes FROM world_runtime_family_catalog_snapshot WHERE read_request_id=?",
                      read.readRequestId())
                  .get(0, byte[].class))
          .isEqualTo(WorldRuntimeFamilyCatalogCodec.evidenceBytes(original));
      var revised = f.owner().read(request());
      assertThat(revised.catalog().catalogEpoch()).isEqualTo(2);
      assertThat(revised.catalog().digest()).isNotEqualTo(original.catalog().digest());
      assertThat(f.count("world_runtime_family_catalog_epoch")).isEqualTo(2);
    }
  }

  @Test
  void epochMetadataAndSnapshotsAreRetainedAndWrongSealsCannotBeInserted() throws Exception {
    try (Fixture f = fixture(null)) {
      f.owner().read(request());
      for (String table :
          List.of(
              "world_runtime_family_catalog_epoch",
              "world_runtime_family_classification",
              "world_runtime_family_catalog_snapshot")) {
        assertThatThrownBy(() -> f.dsl.execute("DELETE FROM " + table))
            .hasMessageContaining("immutable");
        assertThatThrownBy(() -> f.dsl.execute("TRUNCATE " + table + " CASCADE"))
            .hasMessageContaining("immutable");
      }
      assertThatThrownBy(
              () -> f.dsl.execute("UPDATE world_runtime_family_catalog_epoch SET catalog_epoch=2"))
          .hasMessageContaining("immutable");
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "UPDATE world_runtime_family_classification SET fresh_disposition='LEGACY_EXCLUDED' WHERE family_id='instance'"))
          .hasMessageContaining("immutable");
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "INSERT INTO world_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)"
                          + " SELECT 2,catalog_bytes,catalog_digest FROM world_runtime_family_catalog_epoch WHERE catalog_epoch=1"))
          .hasMessageContaining("exact classified installed schema");
    }
  }

  @Test
  void actualProducerReturnsCompleteCorrelatedEvidenceWithoutMaterializingRuntime()
      throws Exception {
    try (Fixture f = fixture(null)) {
      Request read = request();
      var service = new WorldRuntimeFamilyCatalogReadGrpcService(f.owner(), "test");
      var collector = new Collector();
      var peer =
          GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-session-service")
              .orElseThrow();
      Context.current()
          .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
          .run(
              () ->
                  service.readWorldRuntimeFamilyCatalog(
                      WorldRuntimeFamilyCatalogCodec.toRequest(read), collector));
      assertThat(collector.error).isNull();
      assertThat(collector.completed).isTrue();
      var evidence = WorldRuntimeFamilyCatalogCodec.fromResponse(read, collector.value);
      assertThat(f.owner().readExact(read)).isEqualTo(evidence);
      assertThat(f.count("world_instance")).isZero();
      assertThat(f.count("character_location")).isZero();
      var transaction = new TransactionTemplate(f.manager);
      assertThatThrownBy(() -> transaction.execute(status -> f.owner().read(request())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("independent");
    }
  }

  private static Request request() {
    return new Request(UUID.randomUUID(), "test");
  }

  private static Fixture fixture(String target) {
    return fixture(target, "flyway_schema_history_world_management_service");
  }

  private static Fixture fixture(String target, String history) {
    String schema = "world_catalog_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    try {
      Flyway.configure()
          .configuration(migration(source, schema, target).getConfiguration())
          .table(history)
          .load()
          .migrate();
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
    WorldRuntimeFamilyCatalogRepository owner() {
      return new WorldRuntimeFamilyCatalogRepository(dsl, manager, schema, "test");
    }

    long count(String table) {
      return dsl.fetchSingle("SELECT count(*) FROM " + table).get(0, Long.class);
    }

    String jsonRows(String table) {
      return dsl.fetchSingle(
              "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb)::text FROM "
                  + table
                  + " t")
          .get(0, String.class);
    }

    @Override
    public void close() {
      dsl.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static final class Collector
      implements StreamObserver<ReadWorldRuntimeFamilyCatalogResponse> {
    private ReadWorldRuntimeFamilyCatalogResponse value;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(ReadWorldRuntimeFamilyCatalogResponse response) {
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
