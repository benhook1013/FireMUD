package net.firedevops.firemud.worldmanagement.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(
    name = "world_instance",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_world_instance_tenant_game_instance",
          columnNames = {"tenant_id", "game_instance_id"})
    })
public class WorldInstance {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(name = "game_instance_id", nullable = false)
  private Long gameInstanceId;

  @Column(name = "game_template_id", nullable = false)
  private Long gameTemplateId;

  @Column(name = "control_plane_request_id", nullable = false, length = 128)
  private String controlPlaneRequestId;

  @Column(name = "launch_descriptor_id", nullable = false, length = 64)
  private String launchDescriptorId;

  @Column(name = "version_id", nullable = false)
  private Long versionId;

  @Column(name = "script_patch_version", length = 100)
  private String scriptPatchVersion;

  @Column(name = "runtime_flags_json", columnDefinition = "TEXT")
  private String runtimeFlagsJson;

  @Column(name = "generation_config_revision", nullable = false, length = 128)
  private String generationConfigRevision;

  @Column(name = "release_bundle_id", nullable = false)
  private Long releaseBundleId;

  @Column(name = "published_release_bundle_ref", nullable = false, length = 128)
  private String publishedReleaseBundleRef;

  @Column(name = "version_state_epoch", nullable = false)
  private Long versionStateEpoch;

  @Column(name = "lifecycle_epoch", nullable = false)
  private Long lifecycleEpoch = 1L;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "failure_reason", length = 500)
  private String failureReason;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();

  @PrePersist
  @PreUpdate
  void touchUpdatedAt() {
    updatedAt = Instant.now();
  }
}
