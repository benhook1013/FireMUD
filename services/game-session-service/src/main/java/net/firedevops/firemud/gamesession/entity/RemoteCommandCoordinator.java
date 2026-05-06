package net.firedevops.firemud.gamesession.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(
    name = "remote_command_coordinator",
    indexes = {
      @Index(
          name = "idx_remote_command_coordinator_coordinator_id",
          columnList = "coordinator_id",
          unique = true),
      @Index(
          name = "idx_remote_command_coordinator_origin_region_state",
          columnList = "tenant_id, origin_region_id, state"),
      @Index(
          name = "idx_remote_command_coordinator_followup_id",
          columnList = "tenant_id, followup_id"),
      @Index(
          name = "idx_remote_command_coordinator_target_scope_state",
          columnList =
              "tenant_id, target_game_instance_id, target_region_id, target_region_epoch, state, updated_at"),
      @Index(
          name = "idx_remote_command_coordinator_provenance_updated",
          columnList =
              "tenant_id, script_id, plugin_id, automation_dispatch_id, command_id, updated_at"),
      @Index(
          name = "idx_remote_command_coordinator_command_id",
          columnList = "tenant_id, command_id",
          unique = true)
    })
public class RemoteCommandCoordinator {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "coordinator_id", nullable = false, length = 64, unique = true)
  private String coordinatorId;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(name = "command_id", nullable = false, length = 64)
  private String commandId;

  @Column(name = "followup_id", nullable = false, length = 64)
  private String followupId;

  @Column(name = "origin_game_instance_id", nullable = false)
  private Long originGameInstanceId;

  @Column(name = "origin_region_id", nullable = false, length = 64)
  private String originRegionId;

  @Column(name = "origin_region_epoch", nullable = false)
  private long originRegionEpoch;

  @Column(name = "target_game_instance_id", nullable = false)
  private Long targetGameInstanceId;

  @Column(name = "target_region_id", nullable = false, length = 64)
  private String targetRegionId;

  @Column(name = "target_region_epoch", nullable = false)
  private long targetRegionEpoch;

  @Column(name = "target_due_tick_id", nullable = false)
  private long targetDueTickId;

  @Column(name = "origin_deadline_region_epoch", nullable = false)
  private long originDeadlineRegionEpoch;

  @Column(name = "origin_deadline_tick_id", nullable = false)
  private long originDeadlineTickId;

  @Column(name = "state", nullable = false, length = 40)
  private String state;

  @Column(name = "late_result_policy", nullable = false, length = 64)
  private String lateResultPolicy;

  @Column(name = "execution_outcome", length = 40)
  private String executionOutcome;

  @Column(name = "gameplay_result", length = 40)
  private String gameplayResult;

  @Column(name = "playable_state_scope", length = 32)
  private String playableStateScope;

  @Column(name = "world_slug", length = 64)
  private String worldSlug;

  @Column(name = "realm_slug", length = 64)
  private String realmSlug;

  @Column(name = "pointer_version")
  private Long pointerVersion;

  @Column(name = "script_patch_version", length = 128)
  private String scriptPatchVersion;

  @Column(name = "plugin_id", length = 128)
  private String pluginId;

  @Column(name = "plugin_version_id", length = 128)
  private String pluginVersionId;

  @Column(name = "automation_dispatch_id", length = 128)
  private String automationDispatchId;

  @Column(name = "automation_work_item_id", length = 128)
  private String automationWorkItemId;

  @Column(name = "script_id", length = 128)
  private String scriptId;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
