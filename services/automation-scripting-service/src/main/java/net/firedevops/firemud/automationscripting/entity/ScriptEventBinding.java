package net.firedevops.firemud.automationscripting.entity;

import lombok.Data;

@Data
public class ScriptEventBinding {
  private Long id;
  private Long tenantId;
  private String scriptPatchVersion;
  private String eventType;
  private String eventSchemaVersion;
  private String scriptId;
  private String bindingId;
  private String targetScopeType;
  private String targetScopeId;
  private int priority;
  private String priorityTag = "normal";
  private boolean requiresExclusiveEvent;
  private boolean enabled = true;
  private int rowVersion;
}
