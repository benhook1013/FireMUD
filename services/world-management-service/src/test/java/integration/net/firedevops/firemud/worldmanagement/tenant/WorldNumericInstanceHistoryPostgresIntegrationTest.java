package net.firedevops.firemud.worldmanagement.tenant;

import static net.firedevops.firemud.worldmanagement.WorldPostgresMigrationTestSupport.migration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Retained numeric history is not canonical creation authority. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldNumericInstanceHistoryPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void originalNumericWorldSurvivesCanonicalAndCurrentMigrationsWithoutPromotion() {
    String schema = "world_numeric_history_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    migration(source, schema, "1007").migrate();
    DSLContext sql = DSL.using(source, SQLDialect.POSTGRES);
    assertThat(sql.fetchValue("SHOW session_replication_role", String.class)).isEqualTo("origin");
    long id =
        sql.fetchSingle(
                "INSERT INTO world_instance (tenant_id, game_instance_id, game_template_id, "
                    + "control_plane_request_id, launch_descriptor_id, version_id, "
                    + "generation_config_revision, release_bundle_id, published_release_bundle_ref, "
                    + "version_state_epoch, status) VALUES (8100001, 8200001, 71, "
                    + "'retained-request', 'retained-descriptor', 83, 'retained-config', 97, "
                    + "'retained-release', 5, 'LEGACY_FIXTURE') RETURNING id")
            .get(0, Long.class);
    Map<String, Object> original = row(sql, id);
    assertThat(original).doesNotContainKey("canonical_game_instance_id");

    for (String version : List.of("1008", "latest")) {
      var migrations = migration(source, schema, version.equals("latest") ? null : version);
      migrations.migrate();
      migrations.validate();
      assertThat(row(sql, id)).containsAllEntriesOf(original);
      for (String column :
          List.of(
              "canonical_game_instance_id",
              "canonical_target_namespace",
              "canonical_tenant_id",
              "canonical_world_slug",
              "playable_state_namespace_id",
              "playable_state_scope",
              "public_production",
              "playtest_lifecycle_id",
              "playtest_state_generation",
              "canonical_launch_binding_operation_id")) {
        assertThat(row(sql, id)).containsEntry(column, null);
      }
      assertThat(sql.fetchCount(DSL.table("world_canonical_instance_association"))).isZero();
      Map<String, Object> beforeRefusal = row(sql, id);
      assertThatThrownBy(
              () ->
                  sql.execute(
                      "UPDATE world_instance SET canonical_game_instance_id = ? WHERE id = ?",
                      UUID.randomUUID(),
                      id))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining(
              "A retained World instance cannot acquire canonical creation identity");
      assertThat(row(sql, id)).isEqualTo(beforeRefusal);
      assertThat(sql.fetchCount(DSL.table("world_canonical_instance_association"))).isZero();
    }
  }

  private static Map<String, Object> row(DSLContext sql, long id) {
    return sql.fetchSingle(
            "SELECT *, xmin::text AS retained_xmin FROM world_instance WHERE id = ?", id)
        .intoMap();
  }
}
