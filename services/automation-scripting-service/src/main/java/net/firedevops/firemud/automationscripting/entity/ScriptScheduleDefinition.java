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
    name = "script_schedule_definitions",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_script_schedule_definition_scope",
          columnNames = {
            "tenant_id",
            "script_patch_version",
            "plugin_id",
            "plugin_version_id",
            "schedule_definition_id"
          })
    })
public class ScriptScheduleDefinition {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false, length = 128)
  private String scriptPatchVersion;

  @Column(nullable = false, length = 100)
  private String scriptId;

  @Column(nullable = false, length = 128)
  private String pluginId = "";

  @Column(nullable = false, length = 128)
  private String pluginVersionId = "";

  @Column(nullable = false, length = 64)
  private String eventType;

  @Column(nullable = false, length = 160)
  private String scheduleDefinitionId;

  @Column(nullable = false, length = 32)
  private String scheduleKind;

  @Column(nullable = false)
  private long cadenceValue;

  @Column(nullable = false, length = 32)
  private String cadenceUnit;

  @Column(nullable = false, length = 32)
  private String priorityTag = "normal";

  @Column(nullable = false, columnDefinition = "TEXT")
  private String scheduleMetadataJson;

  @Column(nullable = false, length = 64)
  private String scheduleSemanticsHash;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  @Column(nullable = false)
  private Instant updatedAt = Instant.now();

  @Version
  @Column(name = "row_version")
  private int rowVersion;
}
