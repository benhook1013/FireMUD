package net.firedevops.firemud.gamesession.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(
    name = "gameplay_command",
    indexes = {
      @Index(name = "idx_gameplay_command_command_id", columnList = "command_id", unique = true),
      @Index(
          name = "idx_gameplay_command_tenant_instance_status",
          columnList = "tenant_id, game_instance_id, execution_outcome"),
      @Index(
          name = "idx_gameplay_command_automation_dispatch",
          columnList =
              "tenant_id, game_instance_id, region_id, region_epoch, automation_dispatch_id",
          unique = true)
    })
public class GameplayCommand {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "command_id", nullable = false, length = 64, unique = true)
  private String commandId;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(name = "game_instance_id", nullable = false)
  private Long gameInstanceId;

  @Column(name = "session_id", nullable = false)
  private Long sessionId;

  @Column(name = "account_id")
  private Long accountId;

  @Column(name = "character_id")
  private Long characterId;

  @Column(name = "command_name", nullable = false, length = 80)
  private String commandName;

  @Column(name = "command_text", nullable = false, length = 1000)
  private String commandText;

  @Column(name = "sanitized_command_text", nullable = false, length = 1000)
  private String sanitizedCommandText;

  @Column(name = "requires_solo_tick", nullable = false)
  private boolean requiresSoloTick;

  @Column(name = "execution_outcome", nullable = false, length = 40)
  private String executionOutcome;

  @Column(name = "gameplay_result", nullable = false, length = 40)
  private String gameplayResult;

  @Column(name = "accepted_at", nullable = false)
  private Instant acceptedAt;

  @Column(name = "staged_at")
  private Instant stagedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "last_attempt_at")
  private Instant lastAttemptAt;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "enqueue_seq", nullable = false, insertable = false, updatable = false)
  private Long enqueueSeq;

  @Column(name = "failure_code", length = 80)
  private String failureCode;

  @Column(name = "failure_message", length = 500)
  private String failureMessage;

  @Column(name = "source_type", nullable = false, length = 40)
  private String sourceType = "PLAYER";

  @Column(name = "automation_dispatch_id", length = 128)
  private String automationDispatchId;

  @Column(name = "automation_work_item_id", length = 128)
  private String automationWorkItemId;

  @Column(name = "script_id", length = 128)
  private String scriptId;

  @Column(name = "script_patch_version", length = 128)
  private String scriptPatchVersion;

  @Column(name = "plugin_id", length = 128)
  private String pluginId;

  @Column(name = "plugin_version_id", length = 128)
  private String pluginVersionId;

  @Column(name = "playable_state_scope", length = 32)
  private String playableStateScope;

  @Column(name = "world_slug", length = 64)
  private String worldSlug;

  @Column(name = "realm_slug", length = 64)
  private String realmSlug;

  @Column(name = "pointer_version")
  private Long pointerVersion;

  @Column(name = "origin_source_kind", length = 64)
  private String originSourceKind;

  @Column(name = "origin_source_state", length = 64)
  private String originSourceState;

  @Column(name = "origin_source_ordinal")
  private Long originSourceOrdinal;

  @Column(name = "origin_source_due_tick_id")
  private Long originSourceDueTickId;

  @Column(name = "origin_source_due_at_ms")
  private Long originSourceDueAtMs;

  @Column(name = "queue_source_kind", length = 64)
  private String queueSourceKind;

  @Column(name = "queue_source_state", length = 64)
  private String queueSourceState;

  @Column(name = "queue_source_ordinal")
  private Long queueSourceOrdinal;

  @Column(name = "target_entity_id", length = 64)
  private String targetEntityId;

  @Column(name = "region_id", length = 64)
  private String regionId;

  @Column(name = "region_epoch")
  private Long regionEpoch;

  @Column(name = "due_tick_id")
  private Long dueTickId;
}
