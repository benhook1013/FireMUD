package net.firedevops.firemud.worldmanagement.tenant;

import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.HISTORY;
import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.migration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Historical SQL retention only; no current materialization or Account authority is supplied. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldOperationalRegionAssignmentPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void operationalIdentityUpgradePreservesOriginalRowsAndClosedPreparationFunction() {
    String schema = "world_operational_history_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    migration(source, schema, "1017").migrate();
    DSLContext sql = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    UUID retainedRegionIdentity = UUID.randomUUID();
    // Explicit predecessor-era rows, not fresh canonical runtime or producer evidence.
    long worldId =
        Objects.requireNonNull(
                sql.fetchOne(
                    "INSERT INTO world_instance (tenant_id, game_instance_id, game_template_id, "
                        + "control_plane_request_id, launch_descriptor_id, version_id, runtime_flags_json, "
                        + "generation_config_revision, release_bundle_id, published_release_bundle_ref, "
                        + "version_state_epoch, status) VALUES (760001, 761001, 71, ?, "
                        + "'retained-descriptor', 83, '{}', 'retained-config', 97, 'retained-release', 5, 'PREPARING') RETURNING id",
                    "retained-region-" + UUID.randomUUID()))
            .get("id", Long.class);
    long regionId =
        Objects.requireNonNull(
                sql.fetchOne(
                    "INSERT INTO region_instance (tenant_id, game_instance_id, world_instance_id, shard_id, "
                        + "name, weather, generation_seed, generator_type, generator_params, spacing_multiplier, "
                        + "version, canonical_region_instance_id) VALUES (760001, 761001, ?, 3, 'retained-region', "
                        + "'rain', 73, 'legacy', 'kept', 1.25, 4, ?) RETURNING id",
                    worldId,
                    retainedRegionIdentity))
            .get("id", Long.class);
    var beforeWorld = row(sql, "world_instance", worldId);
    var beforeRegion = row(sql, "region_instance", regionId);
    var beforeHistory = history(sql);
    var beforeFunction = function(sql, schema, "world_prepare_canonical_instance");
    assertThat(beforeFunction.publicExecute()).isFalse();
    assertThat(beforeRegion).doesNotContainKey("operational_region_id");

    migration(source, schema, "1018").migrate();
    var afterWorld = row(sql, "world_instance", worldId);
    var afterRegion = row(sql, "region_instance", regionId);
    assertThat(priorColumns(afterWorld, beforeWorld)).isEqualTo(beforeWorld);
    assertThat(priorColumns(afterRegion, beforeRegion)).isEqualTo(beforeRegion);
    assertThat(afterRegion)
        .containsEntry("operational_region_id", null)
        .containsEntry("canonical_region_instance_id", retainedRegionIdentity);
    assertThat(history(sql)).containsAll(beforeHistory);
    var afterFunction = function(sql, schema, "world_prepare_canonical_instance");
    assertThat(afterFunction.oid()).isEqualTo(beforeFunction.oid());
    assertThat(afterFunction.acl()).isEqualTo(beforeFunction.acl());
    assertThat(afterFunction.publicExecute()).isFalse();
    assertThat(afterFunction.definition())
        .isNotEqualTo(beforeFunction.definition())
        .contains("'operational_region_id', operational_runtime_uuid");
    var historyAfter1018 = history(sql);

    var current = migration(source, schema, null);
    current.migrate();
    current.validate();
    var currentWorld = row(sql, "world_instance", worldId);
    var currentRegion = row(sql, "region_instance", regionId);
    assertThat(priorColumns(currentWorld, afterWorld)).isEqualTo(afterWorld);
    assertThat(priorColumns(currentRegion, afterRegion)).isEqualTo(afterRegion);
    assertThat(history(sql)).containsAll(historyAfter1018);
    assertThat(currentWorld)
        .containsEntry("canonical_realm_id", null)
        .containsEntry("catalog_revision", null)
        .containsEntry("canonical_game_instance_id", null)
        .containsEntry("status", "PREPARING");
    assertThat(currentRegion)
        .containsEntry("operational_region_id", null)
        .containsEntry("canonical_region_instance_id", retainedRegionIdentity);
    var originalImplementation = function(sql, schema, "world_prepare_canonical_instance_v61_impl");
    assertThat(originalImplementation.oid()).isEqualTo(beforeFunction.oid());
    assertThat(originalImplementation.acl()).isEqualTo(beforeFunction.acl());
    assertThat(originalImplementation.publicExecute()).isFalse();
    assertThat(function(sql, schema, "world_prepare_canonical_instance").publicExecute()).isFalse();
    for (String table :
        List.of(
            "world_canonical_instance_association",
            "world_canonical_instance_preparation",
            "world_canonical_instance_execution")) {
      assertThat(sql.fetchCount(DSL.table(DSL.name(table)))).as(table).isZero();
    }
    assertThatThrownBy(
            () ->
                sql.execute(
                    "UPDATE region_instance SET operational_region_id = ? WHERE id = ?",
                    UUID.randomUUID(),
                    regionId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("operational assignment tuple is immutable");
    assertThatThrownBy(
            () ->
                sql.execute(
                    "UPDATE world_instance SET canonical_realm_id = ?, catalog_revision = 1 WHERE id = ?",
                    UUID.randomUUID(),
                    worldId))
        .isInstanceOf(DataAccessException.class);
    assertThat(row(sql, "world_instance", worldId)).isEqualTo(currentWorld);
    assertThat(row(sql, "region_instance", regionId)).isEqualTo(currentRegion);
  }

  private Map<String, Object> row(DSLContext sql, String table, long id) {
    return Objects.requireNonNull(
            sql.fetchOne("SELECT *, xmin::text AS xmin FROM " + table + " WHERE id = ?", id),
            "Required retained " + table + " row is absent")
        .intoMap();
  }

  private Map<String, Object> priorColumns(Map<String, Object> after, Map<String, Object> before) {
    assertThat(after.keySet()).containsAll(before.keySet());
    Map<String, Object> projected = new LinkedHashMap<>();
    before.keySet().forEach(key -> projected.put(key, after.get(key)));
    return projected;
  }

  private List<Map<String, Object>> history(DSLContext sql) {
    return sql.fetch("SELECT * FROM " + HISTORY + " ORDER BY installed_rank").intoMaps();
  }

  private FunctionMetadata function(DSLContext sql, String schema, String name) {
    var row =
        Objects.requireNonNull(
            sql.fetchOne(
                "SELECT p.oid::bigint AS oid, p.proacl::text AS acl, pg_get_functiondef(p.oid) AS definition, "
                    + "EXISTS (SELECT 1 FROM aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a "
                    + "WHERE a.grantee = 0 AND a.privilege_type = 'EXECUTE') AS public_execute "
                    + "FROM pg_proc p WHERE p.oid = to_regprocedure(? || '.' || ? || '(text,text)')",
                schema,
                name),
            "Preparation function is absent");
    return new FunctionMetadata(
        row.get("oid", Long.class),
        row.get("acl", String.class),
        row.get("definition", String.class),
        row.get("public_execute", Boolean.class));
  }

  private record FunctionMetadata(long oid, String acl, String definition, boolean publicExecute) {}
}
