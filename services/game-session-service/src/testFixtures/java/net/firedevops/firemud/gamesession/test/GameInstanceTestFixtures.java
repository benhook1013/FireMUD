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
}
