package net.firedevops.firemud.automationscripting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "script_patch_instance_rollout_events")
public class ScriptPatchInstanceRolloutEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 80, unique = true)
  private String eventId;

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
  private Instant observedAt = Instant.EPOCH;

  @Column(nullable = false)
  private Instant projectionRefreshedAt = Instant.EPOCH;

  @Version
  @Column(name = "row_version")
  private int rowVersion;
}
