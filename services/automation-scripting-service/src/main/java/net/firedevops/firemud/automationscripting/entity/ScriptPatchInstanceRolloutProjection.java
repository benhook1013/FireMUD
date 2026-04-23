package net.firedevops.firemud.automationscripting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(
    name = "script_patch_instance_rollout_projections",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_script_patch_instance_rollout_projection_scope",
          columnNames = {"tenant_id", "game_instance_id", "script_patch_version"})
    })
public class ScriptPatchInstanceRolloutProjection {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64)
  private String tenantId;

  @Column(nullable = false, length = 64)
  private String gameInstanceId;

  @Column(nullable = false, length = 128)
  private String scriptPatchVersion;

  @Column(nullable = false, length = 64)
  private String rolloutStatus;

  @Column(nullable = false, length = 256)
  private String statusReason;

  @Column(nullable = false)
  private Instant lastChangedAt = Instant.EPOCH;

  @Column(nullable = false)
  private Instant projectionRefreshedAt = Instant.EPOCH;

  @Version
  @Column(name = "row_version")
  private int rowVersion;
}
