package net.firedevops.firemud.gamesession.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(
    name = "remote_followup_result",
    indexes = {
      @Index(
          name = "idx_remote_followup_result_result_id",
          columnList = "result_id",
          unique = true),
      @Index(
          name = "idx_remote_followup_result_coordinator_observed",
          columnList = "tenant_id, coordinator_id, observed_at"),
      @Index(
          name = "idx_remote_followup_result_followup_id",
          columnList = "tenant_id, followup_id"),
      @Index(
          name = "idx_remote_followup_result_scope_observed",
          columnList =
              "tenant_id, origin_game_instance_id, origin_region_id, origin_region_epoch, target_game_instance_id, target_region_id, target_region_epoch, observed_at"),
      @Index(
          name = "idx_remote_followup_result_routing_observed",
          columnList =
              "tenant_id, script_patch_version, plugin_version_id, playable_state_scope, world_slug, realm_slug, pointer_version, result_error_code, observed_at"),
      @Index(
          name = "idx_remote_followup_result_identity_observed",
          columnList = "tenant_id, automation_work_item_id, result_command_id, observed_at")
    })
public class RemoteFollowupResult {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "result_id", nullable = false, length = 64, unique = true)
  private String resultId;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(name = "coordinator_id", nullable = false, length = 64)
  private String coordinatorId;

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

  @Column(name = "outcome", nullable = false, length = 40)
  private String outcome;

  @Column(name = "result_payload_json", columnDefinition = "TEXT")
  private String resultPayloadJson;

  @Column(name = "result_command_id", length = 128)
  private String resultCommandId;

  @Column(name = "result_error_code", length = 80)
  private String resultErrorCode;

  @Column(name = "result_message", length = 500)
  private String resultMessage;

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

  @Column(name = "command_id", length = 128)
  private String commandId;

  @Column(name = "automation_dispatch_id", length = 128)
  private String automationDispatchId;

  @Column(name = "automation_work_item_id", length = 128)
  private String automationWorkItemId;

  @Column(name = "script_id", length = 128)
  private String scriptId;

  @Column(name = "observed_at", nullable = false)
  private Instant observedAt;
}
