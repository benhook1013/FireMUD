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
    name = "automation_admission_states",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_automation_admission_scope",
            columnNames = {"tenant_id", "game_instance_id", "region_id"}))
public class AutomationAdmissionState {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64)
  private String tenantId;

  @Column(nullable = false, length = 64)
  private String gameInstanceId;

  @Column(nullable = false, length = 64)
  private String regionId;

  @Column(nullable = false, length = 64)
  private String mode = "NORMAL";

  @Column(nullable = false)
  private long admissionEpoch = 1L;

  @Column(length = 128)
  private String controlPlaneRequestId;

  @Column(length = 128)
  private String actorPrincipal;

  @Column(length = 256)
  private String reason;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  @Column(nullable = false)
  private Instant updatedAt = Instant.now();

  @Version
  @Column(name = "row_version")
  private int rowVersion;
}
