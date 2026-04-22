package net.firedevops.firemud.entitymanagement.service;

public interface EntityTemplateReferenceService {
  boolean exists(String tenantId, String versionId, String templateType, String templateId);
}
