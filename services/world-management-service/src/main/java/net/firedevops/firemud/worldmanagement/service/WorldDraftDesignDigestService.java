package net.firedevops.firemud.worldmanagement.service;

public interface WorldDraftDesignDigestService {
  WorldDraftDesignDigest getDraftDesignDigest(String tenantId, String versionId);

  record WorldDraftDesignDigest(
      String tenantId,
      String scopeValue,
      String appliedCommitId,
      String contentDigest,
      int digestSchemaVersion) {}
}
