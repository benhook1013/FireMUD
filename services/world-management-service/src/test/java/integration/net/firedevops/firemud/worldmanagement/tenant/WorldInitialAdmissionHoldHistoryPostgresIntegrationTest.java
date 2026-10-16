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
import net.firedevops.firemud.worldmanagement.repository.InitialAdmissionBindHoldRepository;
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

/**
 * Historical numeric rows only; fabricated owner digests never supply canonical admission proof.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldInitialAdmissionHoldHistoryPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private static final List<String> CANONICAL_REQUEST_COLUMNS =
      List.of(
          "canonical_target_namespace",
          "canonical_tenant_id",
          "canonical_world_slug",
          "canonical_game_instance_id",
          "canonical_version_id",
          "initial_admission_origin",
          "expected_prior_pointer_version",
          "canonical_request_bytes",
          "hold_binding_digest");

  @Test
  void originalNumericHoldsSurviveCanonicalBindingAndCurrentMigrationsWithoutPromotion() {
    String schema = "world_hold_history_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    migration(source, schema, "1019").migrate();
    DSLContext sql = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    assertThat(sql.fetchValue("SHOW session_replication_role", String.class)).isEqualTo("origin");
    var beforeWorld = new LinkedHashMap<Long, Map<String, Object>>();
    var beforeHold = new LinkedHashMap<UUID, Map<String, Object>>();
    int index = 0;
    for (String status : List.of("PENDING", "RECONCILIATION_REQUIRED", "COMMITTED", "ABORTED")) {
      long tenant = 870001L + index;
      long instance = 880001L + index++;
      long worldId = insertHistoricalWorld(sql, tenant, instance);
      UUID holdId = insertHistoricalHold(sql, tenant, instance, status);
      beforeWorld.put(worldId, worldRow(sql, worldId));
      beforeHold.put(holdId, holdRow(sql, holdId));
      assertThat(beforeHold.get(holdId).keySet())
          .doesNotContainAnyElementsOf(CANONICAL_REQUEST_COLUMNS);
    }
    var beforeHistory = history(sql);
    assertThat(beforeHistory).isNotEmpty();

    migration(source, schema, "1020").migrate();
    assertRetained(sql, beforeWorld, beforeHold);
    assertUnmapped(sql, false);
    assertThat(history(sql)).containsAll(beforeHistory);
    var after1020World = new LinkedHashMap<Long, Map<String, Object>>();
    var after1020Hold = new LinkedHashMap<UUID, Map<String, Object>>();
    beforeWorld.keySet().forEach(id -> after1020World.put(id, worldRow(sql, id)));
    beforeHold.keySet().forEach(id -> after1020Hold.put(id, holdRow(sql, id)));
    var after1020History = history(sql);
    assertPromotionDenied(sql, beforeHold.keySet().iterator().next());
    assertRetained(sql, after1020World, after1020Hold);

    var current = migration(source, schema, null);
    current.migrate();
    current.validate();
    assertRetained(sql, after1020World, after1020Hold);
    assertUnmapped(sql, true);
    assertThat(history(sql)).containsAll(after1020History);

    // Actual current repository reads of retained rows, not a canonical finalizer or owner call.
    var repository = new InitialAdmissionBindHoldRepository(sql);
    beforeHold.forEach(
        (id, before) -> {
          var retained = repository.findByHoldId(id.toString()).orElseThrow();
          assertThat(retained.status()).isEqualTo(before.get("status"));
          assertThat(retained.rowVersion()).isEqualTo(before.get("row_version"));
          assertThat(retained.ownerProofId()).isEqualTo(before.get("owner_proof_id"));
          assertThat(retained.ownerProofDigest()).isEqualTo(before.get("owner_proof_digest"));
          assertThat(retained.ownerPointerAuditId())
              .isEqualTo(before.get("owner_pointer_audit_id"));
          assertThat(retained.ownerPointerVersion()).isEqualTo(before.get("owner_pointer_version"));
          assertThat(
                  repository.findByTenantIdAndRequestId(
                      retained.tenantId(), retained.initialAdmissionRequestId()))
              .contains(retained);
          assertThat(repository.hasNonterminalForRealm(retained.tenantId(), retained.realmUuid()))
              .isEqualTo(List.of("PENDING", "RECONCILIATION_REQUIRED").contains(retained.status()));
        });
    assertThat(repository.findNonterminal(256)).hasSize(2);
    assertPromotionDenied(sql, beforeHold.keySet().iterator().next());
    assertRetained(sql, after1020World, after1020Hold);
    assertUnmapped(sql, true);
    assertThat(history(sql)).containsAll(after1020History);
    for (String table :
        List.of(
            "world_canonical_instance_association",
            "world_canonical_instance_execution",
            "world_canonical_initial_admission_hold_finalization_manifest",
            "world_canonical_initial_player_location_operation",
            "character_location",
            "world_canonical_player_admission_hold")) {
      assertThat(sql.fetchCount(DSL.table(DSL.name(table)))).as(table).isZero();
    }
  }

  private long insertHistoricalWorld(DSLContext sql, long tenant, long instance) {
    // This predecessor-era ACTIVE marker is stipulated SQL data, not qualified activation.
    return Objects.requireNonNull(
            sql.fetchOne(
                "INSERT INTO world_instance (tenant_id, game_instance_id, game_template_id, "
                    + "control_plane_request_id, launch_descriptor_id, version_id, runtime_flags_json, "
                    + "generation_config_revision, release_bundle_id, published_release_bundle_ref, "
                    + "version_state_epoch, lifecycle_epoch, status, row_version) "
                    + "VALUES (?, ?, 81, ?, 'legacy-hold-descriptor', 71, '{}', 'legacy-hold-config', "
                    + "91, 'legacy-hold-release', 31, 4, 'ACTIVE', 7) RETURNING id",
                tenant,
                instance,
                "legacy-hold-world-" + UUID.randomUUID()))
        .get("id", Long.class);
  }

  private UUID insertHistoricalHold(DSLContext sql, long tenant, long instance, String status) {
    UUID id = UUID.randomUUID();
    boolean terminal = List.of("COMMITTED", "ABORTED").contains(status);
    boolean committed = "COMMITTED".equals(status);
    sql.execute(
        "INSERT INTO initial_admission_bind_hold (hold_id, hold_fence, tenant_id, realm_uuid, "
            + "playable_state_namespace_uuid, playable_state_scope, game_instance_id, version_id, "
            + "active_lifecycle_epoch, initial_admission_request_id, request_digest, "
            + "expected_no_prior_pointer, expected_catalog_revision, status, diagnostic_expires_at, "
            + "owner_proof_id, owner_proof_digest, owner_pointer_audit_id, owner_pointer_version, "
            + "terminal_at, reconciliation_error, row_version) "
            + "VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, 'SHARED', ?, 71, 4, ?, ?, TRUE, 1, ?, "
            + "TIMESTAMP '2025-01-01 00:00:00', ?, ?, ?, ?, ?::timestamp, ?, 17)",
        id,
        UUID.randomUUID(),
        tenant,
        UUID.randomUUID(),
        UUID.randomUUID(),
        instance,
        "legacy-hold-" + UUID.randomUUID(),
        "e".repeat(64),
        status,
        terminal ? "fabricated-legacy-proof-" + status.toLowerCase(java.util.Locale.ROOT) : null,
        terminal ? "d".repeat(64) : null,
        committed ? "fabricated-legacy-audit" : null,
        committed ? 23L : null,
        terminal ? "2025-02-03 04:05:06.123456" : null,
        "RECONCILIATION_REQUIRED".equals(status) ? "RETAINED_UNAVAILABLE_OWNER" : null);
    return id;
  }

  private void assertUnmapped(DSLContext sql, boolean current) {
    var columns = new java.util.ArrayList<>(CANONICAL_REQUEST_COLUMNS);
    if (current)
      columns.addAll(List.of("canonical_owner_proof_bytes", "canonical_owner_proof_digest"));
    assertThat(sql.fetchCount(DSL.table(DSL.name("initial_admission_bind_hold")))).isEqualTo(4);
    for (Map<String, Object> row :
        sql.fetch("SELECT * FROM initial_admission_bind_hold").intoMaps()) {
      for (String column : columns) assertThat(row).containsEntry(column, null);
    }
  }

  private void assertPromotionDenied(DSLContext sql, UUID holdId) {
    assertThatThrownBy(
            () ->
                sql.execute(
                    "UPDATE initial_admission_bind_hold SET canonical_target_namespace = 'forged-space' WHERE hold_id = ?",
                    holdId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("Initial-admission hold acquisition identity is immutable");
  }

  private void assertRetained(
      DSLContext sql, Map<Long, Map<String, Object>> worlds, Map<UUID, Map<String, Object>> holds) {
    worlds.forEach((id, before) -> assertPriorColumns(worldRow(sql, id), before));
    holds.forEach((id, before) -> assertPriorColumns(holdRow(sql, id), before));
  }

  private void assertPriorColumns(Map<String, Object> after, Map<String, Object> before) {
    assertThat(after.keySet()).containsAll(before.keySet());
    var prior = new LinkedHashMap<String, Object>();
    before.keySet().forEach(column -> prior.put(column, after.get(column)));
    assertThat(prior).as("all original values and PostgreSQL xmin").isEqualTo(before);
  }

  private Map<String, Object> worldRow(DSLContext sql, long id) {
    return Objects.requireNonNull(
            sql.fetchOne("SELECT *, xmin::text AS xmin FROM world_instance WHERE id = ?", id))
        .intoMap();
  }

  private Map<String, Object> holdRow(DSLContext sql, UUID id) {
    return Objects.requireNonNull(
            sql.fetchOne(
                "SELECT *, xmin::text AS xmin FROM initial_admission_bind_hold WHERE hold_id = ?",
                id))
        .intoMap();
  }

  private List<Map<String, Object>> history(DSLContext sql) {
    return sql.fetch("SELECT * FROM " + HISTORY + " ORDER BY installed_rank").intoMaps();
  }
}
