package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayBindingRuntimeTestFixtures;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof for the V34.3 initial-admission intent request identity width. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayInitialAdmissionIntentRequestIdMigrationIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String RAW_SHA256 = "a".repeat(64);
  private static final String SHA256 = "sha256:" + RAW_SHA256;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void intentLedgerAcceptsAttemptWidthAndRejectsAnIdentityBeyond128Characters() {
    String schema = "gs_intent_width_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      migrate(schema, dataSource);
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      var schemaReadback = dsl.fetchOne("SELECT current_schema() AS actual_schema");
      assertThat(schemaReadback.get("actual_schema", String.class)).isEqualTo(schema);

      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget length121 =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
              dsl, UUID.randomUUID(), 2L, 24L, UUID.randomUUID());
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget length128 =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
              dsl, UUID.randomUUID(), 3L, 25L, UUID.randomUUID());
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget length129 =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
              dsl, UUID.randomUUID(), 4L, 26L, UUID.randomUUID());

      assertThat(insertIntent(dsl, length121, "r".repeat(121))).isEqualTo(1);
      assertThat(insertIntent(dsl, length128, "r".repeat(128))).isEqualTo(1);
      assertThatThrownBy(() -> insertIntent(dsl, length129, "r".repeat(129)))
          .isInstanceOfSatisfying(
              DataAccessException.class,
              exception -> assertThat(exception.sqlState()).isEqualTo("22001"));
    } finally {
      dropSchema(schema);
    }
  }

  private static int insertIntent(
      DSLContext dsl,
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget target,
      String requestId) {
    // Read the exact synthetic owner launch row so this width proof retains the same tuple and
    // complete binding evidence as the V44 insert guard.
    var launch =
        dsl.fetchOne(
            "SELECT captured_starting_row_version, original_start_session_tuple,"
                + " complete_launch_binding_evidence -> 'descriptor' ->> 'requestDigest' AS descriptor_request_digest,"
                + " complete_launch_binding_evidence -> 'descriptor' ->> 'resultDigest' AS descriptor_result_digest,"
                + " complete_launch_binding_evidence -> 'releaseAttestation' ->> 'evidenceDigest' AS release_attestation_digest"
                + " FROM game_session_canonical_instance_launch"
                + " WHERE target_namespace = ? AND control_plane_request_id = ?",
            target.targetNamespace(),
            target.controlPlaneRequestId());
    assertThat(launch).isNotNull();
    long capturedStartingRowVersion = launch.get("captured_starting_row_version", Long.class);
    byte[] originalStartSessionTuple = launch.get("original_start_session_tuple", byte[].class);
    String descriptorRequestDigest = launch.get("descriptor_request_digest", String.class);
    String descriptorResultDigest = launch.get("descriptor_result_digest", String.class);
    String releaseAttestationDigest = launch.get("release_attestation_digest", String.class);

    // World accepts the 128-character durable identity width. The 129-character case is rejected
    // by the database column width before its row trigger evaluates the canonical request bytes.
    String canonicalRequestId = requestId.length() > 128 ? requestId.substring(0, 128) : requestId;
    String requestDigest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            target.targetNamespace(),
            target.canonicalTenantId(),
            target.worldSlug(),
            target.realmId(),
            target.playableStateNamespaceId(),
            target.playableStateScope(),
            target.gameInstanceUuid(),
            target.canonicalVersionId(),
            target.activeWorldEpoch(),
            target.catalogRevision(),
            CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
            null,
            canonicalRequestId);
    var holdRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            target.targetNamespace(),
            target.canonicalTenantId(),
            target.worldSlug(),
            target.realmId(),
            target.playableStateNamespaceId(),
            target.playableStateScope(),
            target.gameInstanceUuid(),
            target.canonicalVersionId(),
            target.activeWorldEpoch(),
            canonicalRequestId,
            requestDigest,
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            target.catalogRevision(),
            null);
    byte[] lifecycleRequestBytes =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
                WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
                UUID.randomUUID(),
                target.targetNamespace(),
                target.canonicalTenantId(),
                target.worldSlug(),
                target.gameInstanceUuid(),
                target.playableStateNamespaceId(),
                target.playableStateScope(),
                true,
                target.controlPlaneRequestId(),
                target.canonicalVersionId(),
                descriptorRequestDigest,
                descriptorResultDigest,
                releaseAttestationDigest)
            .canonicalBytes();

    return dsl.execute(
        "INSERT INTO game_session_canonical_initial_admission_intent"
            + " (target_namespace, initial_admission_request_id, request_digest,"
            + " canonical_tenant_id, world_slug, realm_id, playable_state_namespace_id,"
            + " playable_state_scope, catalog_visible, catalog_public_production,"
            + " catalog_revision, catalog_creation_request_id, catalog_request_digest,"
            + " catalog_receipt_digest, tenant_association_operation_id, game_session_tenant_id,"
            + " canonical_game_instance_id, game_instance_id, canonical_version_id,"
            + " runtime_version_id, control_plane_request_id, launch_descriptor_id,"
            + " captured_starting_row_version, current_row_version, descriptor_request_digest,"
            + " descriptor_result_digest, release_attestation_digest, catalog_source_intake_operation_id,"
            + " catalog_source_intake_request_id, original_start_session_tuple, canonical_hold_request_bytes,"
            + " canonical_lifecycle_request_bytes, hold_identity_bytes, state, created_at,"
            + " updated_at, terminal_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, TRUE, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 'PENDING_HOLD',"
            + " now(), now(), NULL)",
        target.targetNamespace(),
        requestId,
        requestDigest,
        target.canonicalTenantId(),
        target.worldSlug(),
        target.realmId(),
        target.playableStateNamespaceId(),
        target.playableStateScope(),
        target.catalogRevision(),
        target.catalogCreationRequestId(),
        SHA256,
        SHA256,
        target.tenantAssociationOperationId(),
        target.gameSessionTenantId(),
        target.gameInstanceUuid(),
        target.gameInstanceId(),
        target.canonicalVersionId(),
        target.runtimeVersionId(),
        target.controlPlaneRequestId(),
        target.launchDescriptorId(),
        capturedStartingRowVersion,
        descriptorRequestDigest,
        descriptorResultDigest,
        releaseAttestationDigest,
        target.sourceIntakeOperationId(),
        target.sourceIntakeRequestId(),
        originalStartSessionTuple,
        holdRequest.canonicalRequestBytes(),
        lifecycleRequestBytes);
  }

  private static void migrate(String schema, DriverManagerDataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
  }

  private static DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    String jdbcUrl = postgres.getJdbcUrl();
    String separator = jdbcUrl.contains("?") ? "&" : "?";
    dataSource.setUrl(jdbcUrl + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static void dropSchema(String schema) {
    try (var connection =
            java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    } catch (Exception failure) {
      throw new IllegalStateException("Failed to dispose intent-width test schema", failure);
    }
  }
}
