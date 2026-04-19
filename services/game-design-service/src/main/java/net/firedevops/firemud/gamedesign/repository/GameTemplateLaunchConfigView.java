package net.firedevops.firemud.gamedesign.repository;

import net.firedevops.firemud.gamedesign.model.TemplateReferencePhase;

public interface GameTemplateLaunchConfigView {
  Long getId();

  String getTenantId();

  Long getDefaultVersionId();

  String getDefaultScriptPatchVersion();

  String getDefaultRuntimeFlagsJson();

  TemplateReferencePhase getTemplateReferencePhase();
}
