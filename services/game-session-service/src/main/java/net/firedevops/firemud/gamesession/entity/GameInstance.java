package net.firedevops.firemud.gamesession.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "game_instances")
public class GameInstance {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(name = "runtime_version", nullable = false, length = 100)
  private String runtimeVersion;

  @Column(name = "script_patch_version", length = 100)
  private String scriptPatchVersion;

  @Column(name = "game_template_id")
  private Long gameTemplateId;

  @Column(name = "launch_descriptor_id", length = 64)
  private String launchDescriptorId;

  @Column(name = "version_id")
  private Long versionId;

  @Column(name = "release_bundle_id")
  private Long releaseBundleId;

  @Column(name = "version_state_epoch")
  private Long versionStateEpoch;

  @Column(name = "generation_config_revision", length = 128)
  private String generationConfigRevision;

  @Column(name = "remap_set_id", length = 64)
  private String remapSetId;

  @Column(name = "script_patch_pinned_at")
  private Instant scriptPatchPinnedAt;

  @Column(name = "script_patch_pinned_by", length = 200)
  private String scriptPatchPinnedBy;

  @Column(name = "script_patch_pinned_reason", length = 500)
  private String scriptPatchPinnedReason;

  @Column(nullable = false)
  private Long ownerAccountId;

  @Column(nullable = false, length = 20)
  private String status;

  @Version
  @Column(name = "row_version", nullable = false)
  private Long rowVersion;
}
