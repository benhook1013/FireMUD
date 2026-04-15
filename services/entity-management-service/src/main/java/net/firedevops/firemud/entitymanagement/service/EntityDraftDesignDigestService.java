package net.firedevops.firemud.entitymanagement.service;

public interface EntityDraftDesignDigestService {
  EntityDraftDesignDigest getDraftDesignDigest(String tenantId, String versionId);

  record EntityDraftDesignDigest(
      String tenantId,
      String scopeValue,
      String appliedCommitId,
      String contentDigest,
      int digestSchemaVersion) {}
}
