package net.firedevops.firemud.gamedesign.entity;

import java.time.LocalDateTime;
import lombok.Data;
import net.firedevops.firemud.gamedesign.model.TemplateReferencePhase;

@Data
public class GameTemplate {
  private Long id;
  private String tenantId;
  private String name;
  private String description;
  private String config;
  private Long defaultVersionId;
  private String defaultScriptPatchVersion;
  private String defaultRuntimeFlagsJson = "{}";
  private TemplateReferencePhase templateReferencePhase = TemplateReferencePhase.ENFORCED;
  private LocalDateTime createdAt = LocalDateTime.now();
}
