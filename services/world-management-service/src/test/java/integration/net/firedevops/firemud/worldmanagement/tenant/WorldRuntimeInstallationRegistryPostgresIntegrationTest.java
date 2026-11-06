package net.firedevops.firemud.worldmanagement.tenant;

import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.migration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Actual World/Saga migration and PostgreSQL receiving-owner proof. The held installation source,
 * workload/catalog/input facts and launch callbacks are explicitly controlled synthetic fixtures.
 * These cases establish no genuine protected installation delivery, materialization or admission.
 */
@Testcontainers(disabledWithoutDocker = false)
@SuppressWarnings("resource")
class WorldRuntimeInstallationRegistryPostgresIntegrationTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final String NAMESPACE = "test";
  private static final String HISTORY = "flyway_schema_history_world_management_service";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void unavailableDefaultSourceRefusesBeforeAnyRegistrationSnapshotOrCallback() {
    try (Fixture f = fixture(null)) {
      var registry = new WorldRuntimeInstallationRegistry(f.dsl, f.manager, f.schema, NAMESPACE);
      AtomicInteger calls = new AtomicInteger();
      assertThatThrownBy(registry::register)
          .isInstanceOf(WorldRuntimeInstallationRegistry.RegistryUnavailableException.class);
      assertThatThrownBy(() -> registry.withLaunchOwners(UUID.randomUUID(), calls::incrementAndGet))
          .isInstanceOf(WorldRuntimeInstallationRegistry.RegistryUnavailableException.class);
      assertThatThrownBy(registry::current)
          .isInstanceOf(WorldRuntimeInstallationRegistry.RegistryUnavailableException.class);
      assertThat(calls.get()).isZero();
      assertThat(f.count("world_runtime_installation_registration")).isZero();
      assertThat(f.count("world_runtime_installation_current")).isZero();
      assertThat(f.count("world_runtime_required_owner_snapshot")).isZero();
      assertThat(f.count("world_instance")).isZero();
    }
  }

  @Test
  void exactRegistrationIndependentReadbackAndRetryRetainBytesAndXmin() {
    try (Fixture f = fixture(null)) {
      byte[] original = artifact();
      var source = new ControlledInstallationSource(original);
      var registry = f.registry(source);
      var registered = registry.register();
      var retained = f.retainedRows("world_runtime_installation_registration");
      var pointer = f.retainedRows("world_runtime_installation_current");
      var independent = f.registry(source).current();
      assertThat(registered.revision()).isPositive();
      assertThat(independent.revision()).isEqualTo(registered.revision());
      assertThat(independent.artifact().exactBytes()).isEqualTo(original);
      assertThat(independent.artifact().digest())
          .isEqualTo(WorldRuntimeFamilyCatalog.sha256(original));
      assertThat(
              f.dsl
                  .fetchSingle("SELECT artifact_bytes FROM world_runtime_installation_registration")
                  .get(0, byte[].class))
          .isEqualTo(original);
      assertThat(registry.register().revision()).isEqualTo(registered.revision());
      assertThat(f.retainedRows("world_runtime_installation_registration")).isEqualTo(retained);
      assertThat(f.retainedRows("world_runtime_installation_current")).isEqualTo(pointer);
      assertThat(f.count("world_runtime_installation_registration")).isEqualTo(1);
      assertThat(source.closed.get()).isEqualTo(2);
    }
  }

  @Test
  void concurrentExactRegistrationsConvergeWithoutRewritingTheRetainedRow() throws Exception {
    try (Fixture f = fixture(null);
        var workers = Executors.newFixedThreadPool(2)) {
      var source = new ControlledInstallationSource(artifact());
      var first = workers.submit(() -> f.registry(source).register());
      var second = workers.submit(() -> f.registry(source).register());
      var firstResult = first.get(30, TimeUnit.SECONDS);
      var secondResult = second.get(30, TimeUnit.SECONDS);
      assertThat(firstResult.revision()).isEqualTo(secondResult.revision());
      assertThat(firstResult.artifact().exactBytes())
          .isEqualTo(secondResult.artifact().exactBytes());
      assertThat(f.count("world_runtime_installation_registration")).isEqualTo(1);
      assertThat(f.count("world_runtime_installation_current")).isEqualTo(1);
    }
  }

  @Test
  void conflictingExactBytesUnderTheSameArtifactIdentityRefuseWithoutMutation() {
    try (Fixture f = fixture(null)) {
      byte[] original = artifact();
      var source = new ControlledInstallationSource(original);
      var registry = f.registry(source);
      registry.register();
      var registrations = f.retainedRows("world_runtime_installation_registration");
      var pointers = f.retainedRows("world_runtime_installation_current");
      ObjectNode changed = (ObjectNode) JSON.readTree(original);
      changed.put("generation", "changed-generation");
      source.replace(JSON.writeValueAsBytes(changed));
      assertThatThrownBy(registry::register)
          .isInstanceOf(WorldRuntimeInstallationRegistry.RegistryUnavailableException.class)
          .hasMessageContaining("changed");
      assertThat(f.retainedRows("world_runtime_installation_registration"))
          .isEqualTo(registrations);
      assertThat(f.retainedRows("world_runtime_installation_current")).isEqualTo(pointers);
      assertThat(registry.current().artifact().exactBytes()).isEqualTo(original);
    }
  }

  @Test
  void frozenOwnersAreCompleteAndNewInstallationInvalidatesOldFreezeWithoutResampling() {
    try (Fixture f = fixture(null)) {
      var source = new ControlledInstallationSource(artifact());
      var registry = f.registry(source);
      var first = registry.register();
      UUID gameInstanceId = UUID.randomUUID();
      AtomicInteger calls = new AtomicInteger();
      registry.preflightLaunchOwners(gameInstanceId);
      assertThat(f.count("world_runtime_required_owner_snapshot")).isZero();
      assertThat(registry.withLaunchOwners(gameInstanceId, calls::incrementAndGet)).isEqualTo(1);
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT jsonb_array_length(required_owners) FROM world_runtime_required_owner_snapshot")
                  .get(0, Integer.class))
          .isEqualTo(2);
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT required_owners=world_runtime_required_owners(?) FROM world_runtime_required_owner_snapshot",
                      first.artifact().exactBytes())
                  .get(0, Boolean.class))
          .isTrue();
      var frozen = f.retainedRows("world_runtime_required_owner_snapshot");
      registry.preflightLaunchOwners(gameInstanceId);
      assertThat(f.retainedRows("world_runtime_required_owner_snapshot")).isEqualTo(frozen);
      assertThat(registry.withLaunchOwners(gameInstanceId, calls::incrementAndGet)).isEqualTo(2);
      assertThat(f.retainedRows("world_runtime_required_owner_snapshot")).isEqualTo(frozen);

      source.replace(artifact());
      var second = registry.register();
      assertThat(second.revision()).isGreaterThan(first.revision());
      assertThatThrownBy(() -> registry.preflightLaunchOwners(gameInstanceId))
          .isInstanceOf(WorldRuntimeInstallationRegistry.RegistryUnavailableException.class)
          .hasMessageContaining("Frozen required-owner set is absent or invalidated");
      assertThat(f.retainedRows("world_runtime_required_owner_snapshot")).isEqualTo(frozen);
      assertThatThrownBy(() -> registry.withLaunchOwners(gameInstanceId, calls::incrementAndGet))
          .isInstanceOf(WorldRuntimeInstallationRegistry.RegistryUnavailableException.class);
      assertThat(calls.get()).isEqualTo(2);
      assertThat(f.retainedRows("world_runtime_required_owner_snapshot")).isEqualTo(frozen);
      assertThat(registry.withLaunchOwners(UUID.randomUUID(), calls::incrementAndGet)).isEqualTo(3);
      assertThat(f.count("world_runtime_required_owner_snapshot")).isEqualTo(2);
      assertThat(f.count("world_instance")).isZero();
    }
  }

  @Test
  void missingCatalogAndUnresolvedClassificationPreventSnapshotAndCallback() {
    try (Fixture f = fixture(null)) {
      ObjectNode unresolved = (ObjectNode) JSON.readTree(artifact());
      ((ObjectNode) unresolved.get("members").get(0).get("families").get(0))
          .put("replacementClass", "UNRESOLVED");
      var source = new ControlledInstallationSource(JSON.writeValueAsBytes(unresolved));
      var registry = f.registry(source);
      registry.register();
      AtomicInteger calls = new AtomicInteger();
      assertThatThrownBy(() -> registry.withLaunchOwners(UUID.randomUUID(), calls::incrementAndGet))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(f.count("world_runtime_required_owner_snapshot")).isZero();
      assertThat(calls.get()).isZero();

      ObjectNode missing = (ObjectNode) JSON.readTree(artifact());
      ((ObjectNode) missing.get("members").get(0)).putArray("catalogSources");
      source.replace(JSON.writeValueAsBytes(missing));
      assertThatThrownBy(registry::register).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> registry.withLaunchOwners(UUID.randomUUID(), calls::incrementAndGet))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(f.count("world_runtime_installation_registration")).isEqualTo(1);
      assertThat(f.count("world_runtime_required_owner_snapshot")).isZero();
      assertThat(calls.get()).isZero();
    }
  }

  @Test
  void heldSourceChangingInsideRegistrationRollsBackRowsAndCurrentPointer() {
    try (Fixture f = fixture(null)) {
      var source = new ControlledInstallationSource(artifact());
      source.changeOnCheck = 2;
      source.replacement = artifact();
      assertThatThrownBy(() -> f.registry(source).register())
          .isInstanceOf(WorldRuntimeInstallationRegistry.RegistryUnavailableException.class);
      assertThat(f.count("world_runtime_installation_registration")).isZero();
      assertThat(f.count("world_runtime_installation_current")).isZero();
      assertThat(f.count("world_runtime_required_owner_snapshot")).isZero();
      assertThat(source.closed.get()).isEqualTo(1);
    }
  }

  @Test
  void directSqlCannotFreezeACallerSelectedSubsetOrForeignRevision() {
    try (Fixture f = fixture(null)) {
      var registration = f.registry(new ControlledInstallationSource(artifact())).register();
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "INSERT INTO world_runtime_required_owner_snapshot"
                          + "(canonical_game_instance_id,target_namespace,registry_revision,artifact_digest,required_owners)"
                          + " SELECT ?,target_namespace,revision,artifact_digest,"
                          + " jsonb_build_array(world_runtime_required_owners(artifact_bytes)->0)"
                          + " FROM world_runtime_installation_registration WHERE revision=?",
                      UUID.randomUUID(),
                      registration.revision()))
          .hasMessageContaining("complete current installation");
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "INSERT INTO world_runtime_required_owner_snapshot"
                          + "(canonical_game_instance_id,target_namespace,registry_revision,artifact_digest,required_owners)"
                          + " VALUES (?,?,?,?,world_runtime_required_owners(?))",
                      UUID.randomUUID(),
                      NAMESPACE,
                      registration.revision() + 1,
                      registration.artifact().digest(),
                      registration.artifact().exactBytes()))
          .hasMessageContaining("Frozen owners must match the complete current installation")
          .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23514"));
      assertThat(f.count("world_runtime_required_owner_snapshot")).isZero();
    }
  }

  @Test
  void immutableRegistrationAndFrozenHistoryRejectUpdatesDeletesAndTruncate() {
    try (Fixture f = fixture(null)) {
      var registry = f.registry(new ControlledInstallationSource(artifact()));
      registry.register();
      registry.withLaunchOwners(UUID.randomUUID(), () -> "controlled callback");
      for (String table :
          List.of(
              "world_runtime_installation_registration", "world_runtime_required_owner_snapshot")) {
        var retained = f.retainedRows(table);
        String column =
            table.equals("world_runtime_installation_registration")
                ? "artifact_digest"
                : "required_owners";
        String truncate = "TRUNCATE " + table;
        if (table.equals("world_runtime_installation_registration")) {
          // Registration is FK-referenced by the current pointer and frozen snapshots.
          truncate += " CASCADE";
        }
        for (String statement :
            List.of(
                "UPDATE " + table + " SET " + column + "=" + column,
                "DELETE FROM " + table,
                truncate)) {
          assertThatThrownBy(() -> f.dsl.execute(statement))
              .hasMessageContaining("Runtime installation and required-owner history is immutable")
              .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("55000"));
          assertThat(f.retainedRows(table)).isEqualTo(retained);
        }
      }
    }
  }

  @Test
  void currentPointerOnlyAdvancesAndHistoricalRegistrationCannotBeReinstalled() {
    try (Fixture f = fixture(null)) {
      byte[] firstBytes = artifact();
      var source = new ControlledInstallationSource(firstBytes);
      var registry = f.registry(source);
      var first = registry.register();
      source.replace(artifact());
      var second = registry.register();
      var pointer = f.retainedRows("world_runtime_installation_current");
      for (long revision : List.of(first.revision(), second.revision())) {
        assertThatThrownBy(
                () ->
                    f.dsl.execute(
                        "UPDATE world_runtime_installation_current SET revision=?,artifact_digest=? WHERE target_namespace=?",
                        revision,
                        revision == first.revision()
                            ? first.artifact().digest()
                            : second.artifact().digest(),
                        NAMESPACE))
            .hasMessageContaining("monotonically");
      }
      for (String statement :
          List.of(
              "DELETE FROM world_runtime_installation_current",
              "TRUNCATE world_runtime_installation_current")) {
        assertThatThrownBy(() -> f.dsl.execute(statement))
            .hasMessageContaining("Current runtime installation cannot be deleted")
            .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("55000"));
      }
      source.replace(firstBytes);
      assertThatThrownBy(registry::register)
          .isInstanceOf(WorldRuntimeInstallationRegistry.RegistryUnavailableException.class)
          .hasMessageContaining("Historical");
      assertThat(f.retainedRows("world_runtime_installation_current")).isEqualTo(pointer);
      assertThat(f.count("world_runtime_installation_registration")).isEqualTo(2);
      assertThat(registry.current().revision()).isEqualTo(second.revision());
    }
  }

  @Test
  void installedLaunchPredicateRequiresTheExactCurrentFreezeOnControlledReceiverOnly() {
    try (Fixture f = fixture(null)) {
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid"
                          + " JOIN pg_namespace n ON n.oid=c.relnamespace"
                          + " WHERE n.nspname=? AND c.relname='world_instance'"
                          + " AND t.tgname='world_instance_registered_launch_owners' AND NOT t.tgisinternal",
                      f.schema)
                  .get(0, Long.class))
          .isEqualTo(1);
      // Separate schema avoids changing the genuine World catalog. This table exercises only
      // the installed predicate; no canonical materialization guards are removed or bypassed.
      String receiver = f.probeSchema() + ".launch_receiver";
      f.dsl.execute("CREATE SCHEMA " + f.probeSchema());
      f.dsl.execute(
          "CREATE TABLE "
              + receiver
              + "(canonical_game_instance_id UUID PRIMARY KEY,canonical_target_namespace TEXT,status TEXT)");
      f.dsl.execute(
          "CREATE TRIGGER controlled_launch_predicate BEFORE INSERT OR UPDATE ON "
              + receiver
              + " FOR EACH ROW EXECUTE FUNCTION "
              + f.schema
              + ".world_require_registered_launch_owners()");
      var source = new ControlledInstallationSource(artifact());
      var registry = f.registry(source);
      registry.register();
      UUID instanceId = UUID.randomUUID();
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "INSERT INTO " + receiver + " VALUES (?,?,'PREPARING')",
                      instanceId,
                      NAMESPACE))
          .hasMessageContaining("complete current frozen runtime owners");
      registry.withLaunchOwners(
          instanceId,
          () ->
              f.dsl.execute(
                  "INSERT INTO " + receiver + " VALUES (?,?,'PREPARING')", instanceId, NAMESPACE));
      source.replace(artifact());
      registry.register();
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "UPDATE "
                          + receiver
                          + " SET status='ACTIVE' WHERE canonical_game_instance_id=?",
                      instanceId))
          .hasMessageContaining("complete current frozen runtime owners");
      assertThat(f.dsl.fetchSingle("SELECT status FROM " + receiver).get(0, String.class))
          .isEqualTo("PREPARING");
      assertThat(f.count("world_instance")).isZero();
      assertThat(f.count("world_canonical_instance_preparation")).isZero();
      assertThat(f.count("character_location")).isZero();
    }
  }

  @Test
  void forward1048Preserves1047EpochsSnapshotsAndHistoryAndSealsEpochThree() {
    try (Fixture f = fixture("1047")) {
      var catalogOwner =
          new WorldRuntimeFamilyCatalogRepository(f.dsl, f.manager, f.schema, NAMESPACE);
      Request oldRequest = new Request(UUID.randomUUID(), NAMESPACE);
      var oldSnapshot = catalogOwner.read(oldRequest);
      assertThat(oldSnapshot.catalog().catalogEpoch()).isEqualTo(2);
      var epochs = f.retainedRows("world_runtime_family_catalog_epoch");
      var snapshots = f.retainedRows("world_runtime_family_catalog_snapshot");
      var history = f.retainedRows(HISTORY);
      assertThat(f.count("saga_instance")).isZero();

      migration(f.source, f.schema, null).migrate();
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(t)::text||':'||xmin::text AS value"
                          + " FROM world_runtime_family_catalog_epoch t WHERE catalog_epoch<=2 ORDER BY value")
                  .getValues(0, String.class))
          .isEqualTo(epochs);
      assertThat(f.retainedRows("world_runtime_family_catalog_snapshot")).isEqualTo(snapshots);
      assertThat(
              f.dsl
                  .fetch(
                      "SELECT to_jsonb(t)::text||':'||xmin::text AS value FROM "
                          + HISTORY
                          + " t WHERE version IS DISTINCT FROM '1048' ORDER BY value")
                  .getValues(0, String.class))
          .isEqualTo(history);
      assertThat(f.count("world_runtime_family_catalog_epoch")).isEqualTo(3);
      assertThatThrownBy(() -> catalogOwner.readExact(oldRequest))
          .isInstanceOf(WorldRuntimeFamilyCatalogRepository.CatalogConflictException.class);
      var current = catalogOwner.read(new Request(UUID.randomUUID(), NAMESPACE)).catalog();
      assertThat(current.catalogEpoch()).isEqualTo(3);
      assertThat(current.families())
          .anyMatch(family -> family.familyId().equals("world_runtime_installation_registration"));
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT catalog_bytes=world_runtime_family_catalog_bytes(catalog_epoch)"
                          + " FROM world_runtime_family_catalog_epoch WHERE catalog_epoch=3")
                  .get(0, Boolean.class))
          .isTrue();
      assertThat(f.count("world_runtime_installation_registration")).isZero();
      migration(f.source, f.schema, null).validate();
    }
  }

  private static byte[] artifact() {
    ObjectNode root = JSON.createObjectNode();
    root.put("schema", "firemud-runtime-installation-inventory/v1");
    root.put("artifactId", UUID.randomUUID().toString());
    root.put("installationId", UUID.randomUUID().toString());
    root.put("environment", "staging");
    root.put("generation", "controlled-installation-generation");
    root.put("clusterUid", "controlled-cluster-uid");
    root.put("namespaceUid", "controlled-namespace-uid");
    root.put("namespace", NAMESPACE);
    root.put("sourceCommit", "b".repeat(40));
    root.put("renderedManifestDigest", DIGEST);
    root.put("declarationsDigest", DIGEST);
    ObjectNode executor = root.putObject("executor");
    executor.put("identity", "controlled-synthetic-protected-source/v1");
    executor.put("versionDigest", DIGEST);
    ObjectNode bindings = root.putObject("inputBindings");
    for (String category :
        List.of(
            "migrations",
            "configurations",
            "mountedResources",
            "secretContracts",
            "externalBindings")) {
      bindings.putArray(category);
    }
    ArrayNode members = root.putArray("members");
    members.add(member("entity", "entity-management-service", "APPLICATION"));
    members.add(member("world", "world-management-service", "APPLICATION"));
    members.add(member("foreign-sidecar", "", "INIT"));
    return JSON.writeValueAsBytes(root);
  }

  private static ObjectNode member(String name, String owner, String kind) {
    ObjectNode member = JSON.createObjectNode();
    member.put("workloadApiVersion", "v1");
    member.put("workloadKind", "Pod");
    member.put("workloadNamespace", NAMESPACE);
    member.put("workloadName", "controlled-runtime");
    member.put("containerKind", kind);
    member.put("containerName", name);
    member.put("image", "registry.example/controlled/" + name + "@" + DIGEST);
    member.put("ownerId", owner);
    member.put("ownership", owner.isEmpty() ? "NO_INSTANCE_STATE" : "RUNTIME_OWNER");
    ArrayNode sources = member.putArray("catalogSources");
    ArrayNode families = member.putArray("families");
    if (!owner.isEmpty()) {
      ObjectNode source = sources.addObject();
      source.put("sourceId", owner + "-sql");
      source.put("contract", owner + "/catalog/v1");
      source.put("epoch", "1");
      source.put("digest", DIGEST);
      ObjectNode family = families.addObject();
      family.put("familyId", name + "-state");
      family.put("catalogSourceId", owner + "-sql");
      family.put("replacementClass", "S3");
      ObjectNode cleanup = family.putObject("cleanupContract");
      for (String field : List.of("operation", "requestIdentity", "acknowledgement", "retention")) {
        cleanup.put(field, owner + "/" + field + "/v1");
      }
    }
    return member;
  }

  /**
   * Synthetic hold only: production custody/completeness and external installation are unproved.
   */
  private static final class ControlledInstallationSource
      implements WorldRuntimeInstallationRegistry.ProtectedInstallationSource {
    private final AtomicReference<byte[]> current;
    private final AtomicInteger closed = new AtomicInteger();
    private int changeOnCheck = -1;
    private byte[] replacement;

    private ControlledInstallationSource(byte[] initial) {
      current = new AtomicReference<>(initial.clone());
    }

    private void replace(byte[] value) {
      current.set(value.clone());
    }

    @Override
    public WorldRuntimeInstallationRegistry.HeldInstallation acquireCurrent() {
      byte[] held = current.get().clone();
      return new WorldRuntimeInstallationRegistry.HeldInstallation() {
        private int checks;

        @Override
        public byte[] exactArtifactBytes() {
          return held.clone();
        }

        @Override
        public void requireCurrent() {
          if (++checks == changeOnCheck) replace(replacement);
          if (!Arrays.equals(held, current.get())) {
            throw new WorldRuntimeInstallationRegistry.RegistryUnavailableException(
                "Controlled held installation changed");
          }
        }

        @Override
        public void close() {
          closed.incrementAndGet();
        }
      };
    }
  }

  private static Fixture fixture(String target) {
    String schema = "world_registry_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    try {
      migration(source, schema, target).migrate();
      return new Fixture(schema, source, dsl, new DataSourceTransactionManager(source));
    } catch (RuntimeException failed) {
      dsl.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      throw failed;
    }
  }

  private static String sqlState(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof SQLException sqlException) return sqlException.getSQLState();
    }
    return null;
  }

  private record Fixture(
      String schema,
      DriverManagerDataSource source,
      DSLContext dsl,
      DataSourceTransactionManager manager)
      implements AutoCloseable {
    WorldRuntimeInstallationRegistry registry(ControlledInstallationSource source) {
      return new WorldRuntimeInstallationRegistry(dsl, manager, schema, NAMESPACE, source);
    }

    String probeSchema() {
      return schema + "_probe";
    }

    long count(String table) {
      return dsl.fetchSingle("SELECT count(*) FROM " + table).get(0, Long.class);
    }

    List<String> retainedRows(String table) {
      return dsl.fetch(
              "SELECT to_jsonb(t)::text||':'||xmin::text AS value FROM "
                  + table
                  + " t ORDER BY value")
          .getValues(0, String.class);
    }

    @Override
    public void close() {
      dsl.execute("DROP SCHEMA IF EXISTS " + probeSchema() + " CASCADE");
      dsl.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }
}
