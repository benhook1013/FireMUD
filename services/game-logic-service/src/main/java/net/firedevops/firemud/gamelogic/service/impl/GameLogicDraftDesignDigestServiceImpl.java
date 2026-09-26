package net.firedevops.firemud.gamelogic.service.impl;

import net.firedevops.firemud.gamelogic.service.GameLogicDraftDesignDigestService;
import org.springframework.stereotype.Service;

@Service
public class GameLogicDraftDesignDigestServiceImpl implements GameLogicDraftDesignDigestService {

  @Override
  public GameLogicDraftDesignDigest getDraftDesignDigest(String tenantId, String versionId) {
    if (versionId == null || versionId.isBlank()) {
      throw new IllegalArgumentException("version_id is required");
    }
    throw new UnsupportedOperationException(
        "Game Logic owner-local manifest and provenance are unavailable");
  }
}
