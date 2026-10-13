package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.worldmanagement.repository.InitialAdmissionBindHoldRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL definitions for the V46-to-V47 initial-admission hold upgrade boundary. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class WorldCanonicalInitialAdmissionHoldMigrationPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void v46ToV47RetainsLegacyHoldRowsAndDoesNotInferCanonicalIdentity() throws Exception {
    String schema = newSchema("initial_hold_retained_");
    try {
      migrate(schema, "46");
      List<LegacySnapshot> before = new ArrayList<>();
      try (Connection connection = connection()) {
        DSLContext dsl = schemaContext(connection, schema);
        for (String status : List.of("PENDING", "COMMITTED", "ABORTED")) {
          WorldFixture world = worldFixture();
          insertLegacyWorld(dsl, world);
          before.add(insertLegacyHold(dsl, world, status));
        }
      }

      migrate(schema, "47");

      try (Connection connection = connection()) {
        DSLContext dsl = schemaContext(connection, schema);
        for (LegacySnapshot snapshot : before) {
          var retained =
              Objects.requireNonNull(
                  dsl.fetchOne(
                      "SELECT (to_jsonb(h) - ARRAY["
                          + "'canonical_target_namespace', 'canonical_tenant_id', 'canonical_world_slug', "
                          + "'canonical_game_instance_id', 'canonical_version_id', "
                          + "'initial_admission_origin', 'expected_prior_pointer_version', "
                          + "'canonical_request_bytes', 'hold_binding_digest']::text[]) = ?::jsonb AS original_row, "
                          + "h.canonical_target_namespace IS NULL "
                          + "AND h.canonical_tenant_id IS NULL "
                          + "AND h.canonical_world_slug IS NULL "
                          + "AND h.canonical_game_instance_id IS NULL "
                          + "AND h.canonical_version_id IS NULL "
                          + "AND h.initial_admission_origin IS NULL "
                          + "AND h.expected_prior_pointer_version IS NULL "
                          + "AND h.canonical_request_bytes IS NULL "
                          + "AND h.hold_binding_digest IS NULL AS unmapped "
                          + "FROM initial_admission_bind_hold h "
                          + "WHERE h.initial_admission_request_id = ?",
                      snapshot.rowJson(),
                      snapshot.requestId()));
          assertThat(retained.get("original_row", Boolean.class))
              .as("all V23 hold, owner-proof, timestamp, and row-version fields remain unchanged")
              .isTrue();
          assertThat(retained.get("unmapped", Boolean.class))
              .as("V47 must not infer canonical identity for a retained numeric hold")
              .isTrue();
        }
        assertThat(
                Objects.requireNonNull(
                        dsl.fetchOne("SELECT count(*) FROM initial_admission_bind_hold"))
                    .get(0, Long.class))
            .isEqualTo(3L);
      }
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void incompleteCanonicalMetadataIsRejectedByTheV47Constraint() throws Exception {
    String schema = newSchema("initial_hold_partial_");
    try {
      migrate(schema, "47");
      try (Connection connection = connection()) {
        DSLContext dsl = schemaContext(connection, schema);
        WorldFixture world = worldFixture();
        insertLegacyWorld(dsl, world);

        assertThatThrownBy(
                () ->
                    dsl.execute(
                        "INSERT INTO initial_admission_bind_hold (hold_id, hold_fence, tenant_id, "
                            + "realm_uuid, playable_state_namespace_uuid, playable_state_scope, "
                            + "game_instance_id, version_id, active_lifecycle_epoch, "
                            + "initial_admission_request_id, request_digest, expected_no_prior_pointer, "
                            + "expected_catalog_revision, status, diagnostic_expires_at, "
                            + "canonical_target_namespace) "
                            + "VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, 'SHARED', ?, 71, 4, ?, ?, "
                            + "TRUE, 1, 'PENDING', CURRENT_TIMESTAMP, 'forged-space')",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        world.tenantId(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        world.gameInstanceId(),
                        "partial-canonical-" + UUID.randomUUID(),
                        "a".repeat(64)))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("ck_initial_admission_bind_canonical_metadata");
      }
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void forgedCompleteTypedHoldWithoutAnActualWorldAssociationIsRejected() throws Exception {
    String schema = newSchema("initial_hold_forged_assoc_");
    try {
      migrate(schema, "47");
      try (Connection connection = connection()) {
        DSLContext dsl = schemaContext(connection, schema);
        WorldFixture world = worldFixture();
        insertLegacyWorld(dsl, world);

        UUID realmId = UUID.randomUUID();
        UUID playableNamespaceId = UUID.randomUUID();
        UUID canonicalTenantId = UUID.randomUUID();
        UUID canonicalGameInstanceId = UUID.randomUUID();
        UUID canonicalVersionId = UUID.randomUUID();
        String requestId = "forged-canonical-" + UUID.randomUUID();
        String requestDigest = "b".repeat(64);
        WorldCanonicalInitialAdmissionHold.Request holdRequest =
            new WorldCanonicalInitialAdmissionHold.Request(
                "test-space",
                canonicalTenantId,
                "test-world",
                realmId,
                playableNamespaceId,
                "SHARED",
                canonicalGameInstanceId,
                canonicalVersionId,
                4L,
                requestId,
                requestDigest,
                WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
                1L,
                null);
        byte[] requestBytes = holdRequest.canonicalRequestBytes();
        String bindingDigest = holdRequest.holdBindingDigest();

        assertThatThrownBy(
                () ->
                    dsl.execute(
                        "INSERT INTO initial_admission_bind_hold (hold_id, hold_fence, tenant_id, "
                            + "realm_uuid, playable_state_namespace_uuid, playable_state_scope, "
                            + "game_instance_id, version_id, active_lifecycle_epoch, "
                            + "initial_admission_request_id, request_digest, expected_no_prior_pointer, "
                            + "expected_catalog_revision, status, diagnostic_expires_at, "
                            + "canonical_target_namespace, canonical_tenant_id, canonical_world_slug, "
                            + "canonical_game_instance_id, canonical_version_id, initial_admission_origin, "
                            + "canonical_request_bytes, hold_binding_digest) "
                            + "VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, 'SHARED', ?, 71, 4, ?, ?, "
                            + "TRUE, 1, 'PENDING', CURRENT_TIMESTAMP, 'test-space', ?::uuid, "
                            + "'test-world', ?::uuid, ?::uuid, 'NO_PRIOR_POINTER', ?::bytea, ?)",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        world.tenantId(),
                        realmId,
                        playableNamespaceId,
                        world.gameInstanceId(),
                        requestId,
                        requestDigest,
                        canonicalTenantId,
                        canonicalGameInstanceId,
                        canonicalVersionId,
                        requestBytes,
                        bindingDigest))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining(
                "Canonical initial-admission hold lacks its actual World association");
      }
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void legacyReconciliationAndTerminalProofRemainAvailableAndNonterminalRowsBlockRealm()
      throws Exception {
    String schema = newSchema("initial_hold_legacy_path_");
    try {
      migrate(schema, "47");
      try (Connection connection = connection()) {
        DSLContext dsl = schemaContext(connection, schema);
        WorldFixture world = worldFixture();
        insertLegacyWorld(dsl, world);
        LegacySnapshot snapshot = insertLegacyHold(dsl, world, "PENDING");
        InitialAdmissionBindHoldRepository repository = new InitialAdmissionBindHoldRepository(dsl);

        var pending =
            repository
                .findByTenantIdAndRequestId(world.tenantId(), snapshot.requestId())
                .orElseThrow();
        assertThat(repository.hasNonterminalForRealm(world.tenantId(), pending.realmUuid()))
            .isTrue();

        var reconciling =
            repository
                .markReconciliationRequired(
                    pending, "LEGACY_RECONCILIATION_TEST", Instant.parse("2026-01-01T00:00:00Z"))
                .orElseThrow();
        assertThat(reconciling.status()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(repository.hasNonterminalForRealm(world.tenantId(), reconciling.realmUuid()))
            .isTrue();

        var committed =
            repository
                .recordTerminalProof(
                    reconciling,
                    "COMMITTED",
                    "legacy-owner-proof",
                    "c".repeat(64),
                    "legacy-pointer-audit",
                    19L,
                    Instant.parse("2026-01-01T00:00:01Z"))
                .orElseThrow();
        assertThat(committed.status()).isEqualTo("COMMITTED");
        assertThat(committed.ownerProofId()).isEqualTo("legacy-owner-proof");
        assertThat(committed.ownerPointerVersion()).isEqualTo(19L);
        assertThat(committed.rowVersion()).isEqualTo(reconciling.rowVersion() + 1L);
        assertThat(repository.hasNonterminalForRealm(world.tenantId(), committed.realmUuid()))
            .isFalse();
      }
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void migrationBackedReadOnlySnapshotObservesStoredStatusWithoutChangingSyntheticRows()
      throws Exception {
    // These are migration-fixture rows only: they do not supply canonical hold identity,
    // authenticated Game Session authority, or lifecycle-owner evidence for the new RPC.
    String schema = newSchema("initial_hold_state_snapshot_");
    try {
      migrate(schema, "47");
      WorldFixture world = worldFixture();
      Map<String, LegacySnapshot> rows = new java.util.LinkedHashMap<>();
      try (Connection connection = connection()) {
        DSLContext dsl = schemaContext(connection, schema);
        insertLegacyWorld(dsl, world);
        for (String status : List.of("PENDING", "COMMITTED", "ABORTED")) {
          rows.put(status, insertLegacyHold(dsl, world, status));
        }
        InitialAdmissionBindHoldRepository repository = new InitialAdmissionBindHoldRepository(dsl);
        var pending =
            repository
                .findByTenantIdAndRequestId(world.tenantId(), rows.get("PENDING").requestId())
                .orElseThrow();
        var reconciling =
            repository
                .markReconciliationRequired(
                    pending, "SYNTHETIC_READBACK_FIXTURE", Instant.parse("2026-01-01T00:00:00Z"))
                .orElseThrow();
        assertThat(reconciling.status()).isEqualTo("RECONCILIATION_REQUIRED");
        LegacySnapshot pendingSnapshot = rows.remove("PENDING");
        rows.put("RECONCILIATION_REQUIRED", pendingSnapshot);
      }

      Map<String, Long> observedVersions = new java.util.LinkedHashMap<>();
      try (Connection connection = connection()) {
        DSLContext dsl = schemaContext(connection, schema);
        connection.setAutoCommit(false);
        connection.setReadOnly(true);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        for (Map.Entry<String, LegacySnapshot> entry : rows.entrySet()) {
          var row =
              Objects.requireNonNull(
                  dsl.fetchOne(
                      "SELECT status, row_version FROM initial_admission_bind_hold "
                          + "WHERE initial_admission_request_id = ?",
                      entry.getValue().requestId()));
          assertThat(row.get("status", String.class)).isEqualTo(entry.getKey());
          observedVersions.put(entry.getKey(), row.get("row_version", Long.class));
        }
        var transaction =
            Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT current_setting('transaction_isolation') AS isolation, "
                        + "current_setting('transaction_read_only') AS read_only"));
        assertThat(transaction.get("isolation", String.class)).isEqualTo("repeatable read");
        assertThat(transaction.get("read_only", String.class)).isEqualTo("on");
        connection.rollback();
      }

      try (Connection connection = connection()) {
        DSLContext dsl = schemaContext(connection, schema);
        for (Map.Entry<String, LegacySnapshot> entry : rows.entrySet()) {
          var row =
              Objects.requireNonNull(
                  dsl.fetchOne(
                      "SELECT status, row_version FROM initial_admission_bind_hold "
                          + "WHERE initial_admission_request_id = ?",
                      entry.getValue().requestId()));
          assertThat(row.get("status", String.class)).isEqualTo(entry.getKey());
          assertThat(row.get("row_version", Long.class))
              .isEqualTo(observedVersions.get(entry.getKey()));
        }
      }
    } finally {
      dropSchema(schema);
    }
  }

  private LegacySnapshot insertLegacyHold(DSLContext dsl, WorldFixture world, String status) {
    String requestId = "legacy-" + status.toLowerCase() + "-" + UUID.randomUUID();
    UUID holdId = UUID.randomUUID();
    UUID holdFence = UUID.randomUUID();
    UUID realmId = UUID.randomUUID();
    UUID playableNamespaceId = UUID.randomUUID();
    String proofId = status.equals("PENDING") ? null : "retained-proof-" + status.toLowerCase();
    String proofDigest = status.equals("PENDING") ? null : "d".repeat(64);
    String pointerAudit = status.equals("COMMITTED") ? "retained-audit" : null;
    Long pointerVersion = status.equals("COMMITTED") ? 23L : null;
    Instant terminalAt = status.equals("PENDING") ? null : Instant.parse("2025-02-03T04:05:06Z");
    String sql =
        "INSERT INTO initial_admission_bind_hold (hold_id, hold_fence, tenant_id, realm_uuid, "
            + "playable_state_namespace_uuid, playable_state_scope, game_instance_id, version_id, "
            + "active_lifecycle_epoch, initial_admission_request_id, request_digest, "
            + "expected_no_prior_pointer, expected_catalog_revision, status, diagnostic_expires_at, "
            + "owner_proof_id, owner_proof_digest, owner_pointer_audit_id, owner_pointer_version, "
            + "terminal_at, row_version) VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, 'SHARED', ?, 71, "
            + "4, ?, ?, TRUE, 1, ?, TIMESTAMP '2099-01-01 00:00:00', ?, ?, ?, ?, ?, 17) "
            + "RETURNING to_jsonb(initial_admission_bind_hold)::text AS row_json";
    String rowJson =
        Objects.requireNonNull(
                dsl.fetchOne(
                    sql,
                    holdId,
                    holdFence,
                    world.tenantId(),
                    realmId,
                    playableNamespaceId,
                    world.gameInstanceId(),
                    requestId,
                    "e".repeat(64),
                    status,
                    proofId,
                    proofDigest,
                    pointerAudit,
                    pointerVersion,
                    terminalAt == null ? null : java.time.LocalDateTime.of(2025, 2, 3, 4, 5, 6)))
            .get("row_json", String.class);
    return new LegacySnapshot(requestId, rowJson);
  }

  private void insertLegacyWorld(DSLContext dsl, WorldFixture world) {
    dsl.execute(
        "INSERT INTO world_instance (tenant_id, game_instance_id, game_template_id, "
            + "control_plane_request_id, launch_descriptor_id, version_id, runtime_flags_json, "
            + "generation_config_revision, release_bundle_id, published_release_bundle_ref, "
            + "version_state_epoch, lifecycle_epoch, status, row_version) "
            + "VALUES (?, ?, 81, ?, 'legacy-hold-descriptor', 71, '{}', 'legacy-hold-config', "
            + "91, 'legacy-hold-release', 31, 4, 'ACTIVE', 0)",
        world.tenantId(),
        world.gameInstanceId(),
        "legacy-hold-world-" + UUID.randomUUID());
  }

  private WorldFixture worldFixture() {
    long suffix = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000L);
    return new WorldFixture(810_000L + suffix, 820_000L + suffix);
  }

  private String newSchema(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "");
  }

  private void migrate(String schema, String targetVersion) {
    var configuration =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history_world_management_service")
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (targetVersion != null) {
      configuration.target(MigrationVersion.fromVersion(targetVersion));
    }
    configuration.load().migrate();
  }

  private Connection connection() throws Exception {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private DSLContext schemaContext(Connection connection, String schema) {
    DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
    dsl.execute("SET search_path TO " + schema + ", public");
    return dsl;
  }

  private void dropSchema(String schema) throws Exception {
    try (Connection connection = connection()) {
      DSL.using(connection, SQLDialect.POSTGRES)
          .execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private record WorldFixture(long tenantId, long gameInstanceId) {}

  private record LegacySnapshot(String requestId, String rowJson) {}
}
