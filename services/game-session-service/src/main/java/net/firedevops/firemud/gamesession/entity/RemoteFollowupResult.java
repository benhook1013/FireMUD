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
      @Index(name = "idx_remote_followup_result_followup_id", columnList = "tenant_id, followup_id")
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

  @Column(name = "origin_region_id", nullable = false, length = 64)
  private String originRegionId;

  @Column(name = "origin_region_epoch", nullable = false)
  private long originRegionEpoch;

  @Column(name = "target_region_id", nullable = false, length = 64)
  private String targetRegionId;

  @Column(name = "target_region_epoch", nullable = false)
  private long targetRegionEpoch;

  @Column(name = "outcome", nullable = false, length = 40)
  private String outcome;

  @Column(name = "result_payload_json", columnDefinition = "TEXT")
  private String resultPayloadJson;

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

  @Column(name = "observed_at", nullable = false)
  private Instant observedAt;
}
