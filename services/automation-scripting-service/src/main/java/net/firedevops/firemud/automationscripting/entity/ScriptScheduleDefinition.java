package net.firedevops.firemud.automationscripting.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class ScriptScheduleDefinition {
  private Long id;
  private Long tenantId;
  private String scriptPatchVersion;
  private String scriptId;
  private String pluginId = "";
  private String pluginVersionId = "";
  private String eventType;
  private String scheduleDefinitionId;
  private String scheduleKind;
  private long cadenceValue;
  private String cadenceUnit;
  private String priorityTag = "normal";
  private String scheduleMetadataJson;
  private String scheduleSemanticsHash;
  private Instant createdAt = Instant.now();
  private Instant updatedAt = Instant.now();
  private int rowVersion;
}
