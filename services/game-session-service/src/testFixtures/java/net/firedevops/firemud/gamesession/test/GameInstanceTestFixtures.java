package net.firedevops.firemud.gamesession.test;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class GameInstanceTestFixtures {
  public static final long PUBLISHED_RELEASE_BUNDLE_ID = 700L;
  public static final String TEST_OWNER_ACCOUNT_UUID = "123e4567-e89b-12d3-a456-426614174000";
  private static final long INITIAL_SCRIPT_PIN_EPOCH = 1L;
  private static final String INITIAL_SCRIPT_PIN_REQUEST_ID = "test-fixture-initial";

  private GameInstanceTestFixtures() {}

  public static long insertRunningGameInstance(
      JdbcTemplate jdbc, long tenantId, String ownerAccountUuid, long gameTemplateId) {
    return Optional.ofNullable(
            jdbc.queryForObject(
                """
                INSERT INTO game_instances (
                  tenant_id,
                  runtime_version,
                  script_patch_version,
                  script_pin_epoch,
                  script_patch_pinned_control_plane_request_id,
                  game_template_id,
                  launch_descriptor_id,
                  version_id,
                  release_bundle_id,
                  version_state_epoch,
                  generation_config_revision,
                  remap_set_id,
                  owner_account_uuid,
                  status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """,
                Long.class,
                tenantId,
                "0.1.0",
                "initial",
                INITIAL_SCRIPT_PIN_EPOCH,
                INITIAL_SCRIPT_PIN_REQUEST_ID,
                gameTemplateId,
                "stub-launch-descriptor",
                gameTemplateId,
                PUBLISHED_RELEASE_BUNDLE_ID,
                700L,
                "genrev:test:" + gameTemplateId,
                null,
                UUID.fromString(ownerAccountUuid),
                "ACTIVE"))
        .orElseThrow(() -> new IllegalStateException("Game instance insert did not return an id"));
  }

  /**
   * Reuses one explicitly declared synthetic instance row without replacing its tenant or owner.
   * This is only for test fixtures that model retained pointers with stable instance identifiers.
   */
  public static long ensureDeclaredRunningGameInstance(
      JdbcTemplate jdbc,
      long instanceId,
      long tenantId,
      String ownerAccountUuid,
      long gameTemplateId) {
    if (instanceId <= 0) {
      throw new IllegalArgumentException("Declared fixture game instance id must be positive");
    }
    UUID declaredOwner = UUID.fromString(ownerAccountUuid);
    var existing =
        jdbc.query(
            "SELECT tenant_id, owner_account_uuid FROM game_instances WHERE id = ?",
            (row, index) ->
                new ExistingIdentity(
                    row.getLong("tenant_id"), row.getObject("owner_account_uuid", UUID.class)),
            instanceId);
    if (existing.isEmpty()) {
      jdbc.update(
          """
          INSERT INTO game_instances (
            id,
            tenant_id,
            runtime_version,
            script_patch_version,
            script_pin_epoch,
            script_patch_pinned_control_plane_request_id,
            game_template_id,
            launch_descriptor_id,
            version_id,
            release_bundle_id,
            version_state_epoch,
            generation_config_revision,
            remap_set_id,
            owner_account_uuid,
            status
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          instanceId,
          tenantId,
          "0.1.0",
          "initial",
          INITIAL_SCRIPT_PIN_EPOCH,
          INITIAL_SCRIPT_PIN_REQUEST_ID,
          gameTemplateId,
          "stub-launch-descriptor",
          gameTemplateId,
          PUBLISHED_RELEASE_BUNDLE_ID,
          700L,
          "genrev:test:" + gameTemplateId,
          null,
          declaredOwner,
          "ACTIVE");
    } else {
      ExistingIdentity identity = existing.get(0);
      if (identity.tenantId() != tenantId || !declaredOwner.equals(identity.ownerAccountUuid())) {
        throw new IllegalStateException(
            "Declared fixture game instance id "
                + instanceId
                + " has contradictory tenant or owner identity");
      }
      jdbc.update(
          """
          UPDATE game_instances SET
            runtime_version = ?,
            script_patch_version = ?,
            script_pin_epoch = ?,
            script_patch_pinned_control_plane_request_id = ?,
            game_template_id = ?,
            launch_descriptor_id = ?,
            version_id = ?,
            release_bundle_id = ?,
            version_state_epoch = ?,
            generation_config_revision = ?,
            remap_set_id = ?,
            status = ?
          WHERE id = ?
          """,
          "0.1.0",
          "initial",
          INITIAL_SCRIPT_PIN_EPOCH,
          INITIAL_SCRIPT_PIN_REQUEST_ID,
          gameTemplateId,
          "stub-launch-descriptor",
          gameTemplateId,
          PUBLISHED_RELEASE_BUNDLE_ID,
          700L,
          "genrev:test:" + gameTemplateId,
          null,
          "ACTIVE",
          instanceId);
    }
    jdbc.execute(
        "SELECT setval('game_instances_id_seq', "
            + "GREATEST((SELECT COALESCE(MAX(id), 1) FROM game_instances), "
            + "(SELECT last_value FROM game_instances_id_seq)), true)");
    return instanceId;
  }

  private record ExistingIdentity(long tenantId, UUID ownerAccountUuid) {}
}
