package net.firedevops.firemud.gamelogic.service;

public interface GameLogicDraftDesignDigestService {
  GameLogicDraftDesignDigest getDraftDesignDigest(String tenantId, String versionId);

  final class UnsupportedDigestScopeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnsupportedDigestScopeException(String message) {
      super(message);
    }
  }

  record GameLogicDraftDesignDigest(
      String tenantId,
      String scopeValue,
      String appliedCommitId,
      String contentDigest,
      int digestSchemaVersion) {}
}
