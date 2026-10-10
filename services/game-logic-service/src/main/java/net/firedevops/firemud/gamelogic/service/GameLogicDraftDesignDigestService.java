package net.firedevops.firemud.gamelogic.service;

import net.firedevops.firemud.common.gamelogic.GameLogicPublicationSourceReadBinding;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicPublicationSourceReadService;

public interface GameLogicDraftDesignDigestService {
  GameLogicPublicationSourceReadService.Result getDraftDesignDigest(
      GameLogicPublicationSourceReadBinding binding);

  final class UnsupportedDigestScopeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnsupportedDigestScopeException(String message) {
      super(message);
    }
  }
}
