package net.firedevops.firemud.gamesession.test;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public final class GameInstanceTestFixtures {
  public static final long PUBLISHED_RELEASE_BUNDLE_ID = 700L;

  private GameInstanceTestFixtures() {}

  public static void ensureGameInstancesTable(JdbcTemplate jdbc) {
    jdbc.execute(
        """
        CREATE TABLE IF NOT EXISTS game_instances (
          id BIGSERIAL PRIMARY KEY,
          tenant_id BIGINT NOT NULL,
          runtime_version VARCHAR(100) NOT NULL,
          script_patch_version VARCHAR(100),
          game_template_id BIGINT,
          launch_descriptor_id VARCHAR(64),
          version_id BIGINT,
          release_bundle_id BIGINT,
          version_state_epoch BIGINT,
          generation_config_revision VARCHAR(128),
          remap_set_id VARCHAR(64),
          script_patch_pinned_at TIMESTAMP NULL,
          script_patch_pinned_by VARCHAR(200) NULL,
          script_patch_pinned_reason VARCHAR(500) NULL,
          owner_account_id BIGINT NOT NULL,
          status VARCHAR(20) NOT NULL
        )
        """);
    jdbc.execute("ALTER TABLE game_instances ADD COLUMN IF NOT EXISTS game_template_id BIGINT");
    jdbc.execute(
        "ALTER TABLE game_instances ADD COLUMN IF NOT EXISTS launch_descriptor_id VARCHAR(64)");
    jdbc.execute("ALTER TABLE game_instances ADD COLUMN IF NOT EXISTS version_id BIGINT");
    jdbc.execute("ALTER TABLE game_instances ADD COLUMN IF NOT EXISTS release_bundle_id BIGINT");
    jdbc.execute("ALTER TABLE game_instances ADD COLUMN IF NOT EXISTS version_state_epoch BIGINT");
    jdbc.execute(
        "ALTER TABLE game_instances ADD COLUMN IF NOT EXISTS generation_config_revision VARCHAR(128)");
    jdbc.execute("ALTER TABLE game_instances ADD COLUMN IF NOT EXISTS remap_set_id VARCHAR(64)");
  }

  public static long insertRunningGameInstance(
      JdbcTemplate jdbc, long tenantId, long ownerAccountId, long gameTemplateId) {
    ensureGameInstancesTable(jdbc);
    return Optional.ofNullable(
            jdbc.queryForObject(
                """
                INSERT INTO game_instances (
                  tenant_id,
                  runtime_version,
                  script_patch_version,
                  game_template_id,
                  launch_descriptor_id,
                  version_id,
                  release_bundle_id,
                  version_state_epoch,
                  generation_config_revision,
                  remap_set_id,
                  owner_account_id,
                  status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """,
                Long.class,
                tenantId,
                "0.1.0",
                "initial",
                gameTemplateId,
                "stub-launch-descriptor",
                gameTemplateId,
                PUBLISHED_RELEASE_BUNDLE_ID,
                700L,
                "genrev:test:" + gameTemplateId,
                null,
                ownerAccountId,
                "ACTIVE"))
        .orElseThrow(() -> new IllegalStateException("Game instance insert did not return an id"));
  }
}
