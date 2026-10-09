package net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class LaunchDescriptorRepositoryTest {
  @Test
  void findBoundByRequestConvertsMetadataBackedSmallintUuidAndTimestamp() throws Exception {
    UUID canonicalTenantId = UUID.fromString("09cb7579-69b0-492b-82eb-7bb2231c0211");
    UUID sourceOperationId = UUID.fromString("c4651f34-8cb8-4ef8-b6f5-4aa19f8c1c42");
    LocalDateTime createdAt = LocalDateTime.of(2026, 10, 3, 10, 15, 30, 123_000_000);

    try (var connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:launch-descriptor-readback;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")) {
      DSLContext dsl = DSL.using(connection, SQLDialect.H2);
      dsl.execute(
          "CREATE TABLE launch_descriptor ("
              + "id BIGINT, launch_descriptor_id VARCHAR(64), tenant_id VARCHAR(36), "
              + "game_template_id BIGINT, control_plane_request_id VARCHAR(64), "
              + "request_hash VARCHAR(128), version_id BIGINT, script_patch_version VARCHAR(100), "
              + "runtime_flags_json VARCHAR(4000), generation_config_revision TEXT, "
              + "version_state_epoch BIGINT, release_bundle_id BIGINT, "
              + "published_release_bundle_ref VARCHAR(128), remap_set_id VARCHAR(64), "
              + "descriptor_schema_version SMALLINT, target_namespace VARCHAR(63), "
              + "canonical_tenant_id UUID, world_slug VARCHAR(120), "
              + "authored_world_source_operation_id UUID, "
              + "authored_world_source_evidence_digest VARCHAR(72), request_digest VARCHAR(72), "
              + "result_digest VARCHAR(72), original_request_json VARCHAR(4000), "
              + "source_evidence_json VARCHAR(4000), outcome_status VARCHAR(16), "
              + "failure_code VARCHAR(64), failure_message VARCHAR(1000), created_at TIMESTAMP)");
      dsl.execute(
          "INSERT INTO launch_descriptor (id, launch_descriptor_id, tenant_id, game_template_id, "
              + "control_plane_request_id, request_hash, version_id, script_patch_version, "
              + "runtime_flags_json, generation_config_revision, version_state_epoch, "
              + "release_bundle_id, published_release_bundle_ref, remap_set_id, "
              + "descriptor_schema_version, target_namespace, canonical_tenant_id, world_slug, "
              + "authored_world_source_operation_id, authored_world_source_evidence_digest, "
              + "request_digest, result_digest, original_request_json, source_evidence_json, "
              + "outcome_status, failure_code, failure_message, created_at) VALUES "
              + "(17, 'launch-17', 'private-tenant', 23, 'request-17', 'sha256:request', 29, "
              + "'patch-4', '{}', '"
              + "generation-6:"
              + "g".repeat(180)
              + "', 8, 31, 'release-31', NULL, 1, 'preview', '"
              + canonicalTenantId
              + "', 'verdant-harbor', '"
              + sourceOperationId
              + "', 'sha256:source', 'sha256:request', 'sha256:result', '{}', '{}', 'SUCCESS', "
              + "NULL, NULL, TIMESTAMP '2026-10-03 10:15:30.123')");

      var descriptor =
          new LaunchDescriptorRepository(dsl)
              .findBoundByRequest("preview", canonicalTenantId, "request-17")
              .orElseThrow();

      assertThat(descriptor.getDescriptorSchemaVersion()).isEqualTo(1);
      assertThat(descriptor.getCanonicalTenantId()).isEqualTo(canonicalTenantId.toString());
      assertThat(descriptor.getAuthoredWorldSourceOperationId())
          .isEqualTo(sourceOperationId.toString());
      assertThat(descriptor.getGenerationConfigRevision())
          .isEqualTo("generation-6:" + "g".repeat(180));
      assertThat(descriptor.getCreatedAt()).isEqualTo(createdAt);
    }
  }
}
