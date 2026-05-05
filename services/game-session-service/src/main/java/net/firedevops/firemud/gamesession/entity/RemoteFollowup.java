package net.firedevops.firemud.gamesession.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(
    name = "remote_followup",
    indexes = {
      @Index(name = "idx_remote_followup_followup_id", columnList = "followup_id", unique = true),
      @Index(
          name = "idx_remote_followup_target_region_status_due",
          columnList = "tenant_id, target_region_id, status, due_tick_id"),
      @Index(
          name = "idx_remote_followup_target_region_epoch_effect",
          columnList = "tenant_id, target_region_id, target_region_epoch, effect_key",
          unique = true)
    })
public class RemoteFollowup {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "followup_id", nullable = false, length = 64, unique = true)
  private String followupId;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

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

  @Column(name = "due_tick_id", nullable = false)
  private long dueTickId;

  @Column(name = "effect_key", nullable = false, length = 128)
  private String effectKey;

  @Column(name = "target_entity_id", length = 64)
  private String targetEntityId;

  @Column(name = "status", nullable = false, length = 40)
  private String status;

  @Column(name = "claimed_tick_batch_id", length = 64)
  private String claimedTickBatchId;

  @Column(name = "claim_ordinal")
  private Long claimOrdinal;

  @Column(name = "payload_json", columnDefinition = "TEXT")
  private String payloadJson;

  @Column(name = "payload_kind", length = 80)
  private String payloadKind;

  @Column(name = "requested_command", length = 500)
  private String requestedCommand;

  @Column(name = "requires_solo_tick", nullable = false)
  private boolean requiresSoloTick;

  @Column(name = "failure_code", length = 80)
  private String failureCode;

  @Column(name = "failure_message", length = 500)
  private String failureMessage;

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

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
