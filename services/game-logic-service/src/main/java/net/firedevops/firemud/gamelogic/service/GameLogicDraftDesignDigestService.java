package net.firedevops.firemud.gamelogic.service;

public interface GameLogicDraftDesignDigestService {
  GameLogicDraftDesignDigest getDraftDesignDigest(String tenantId, String versionId);

  record GameLogicDraftDesignDigest(
      String tenantId,
      String scopeValue,
      String appliedCommitId,
      String contentDigest,
      int digestSchemaVersion) {}
}
