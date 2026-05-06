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
    name = "script_patch_readiness_projections",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_script_patch_readiness_projection_scope",
          columnNames = {"tenant_id", "script_patch_version"})
    })
public class ScriptPatchReadinessProjection {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64)
  private String tenantId;

  @Column(nullable = false, length = 128)
  private String scriptPatchVersion;

  @Column(nullable = false, length = 64)
  private String readinessStatus = "PENDING_VALIDATION";

  @Column(nullable = false, length = 256)
  private String statusReason = "pending_validation";

  @Column(nullable = false, length = 128)
  private String supersededByScriptPatchVersion = "";

  @Column(nullable = false)
  private Instant lastChangedAt = Instant.EPOCH;

  @Version
  @Column(name = "row_version")
  private int rowVersion;
}
