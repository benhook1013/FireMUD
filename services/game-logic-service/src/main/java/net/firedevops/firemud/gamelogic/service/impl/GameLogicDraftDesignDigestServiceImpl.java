package net.firedevops.firemud.gamelogic.service.impl;

import java.util.Objects;
import net.firedevops.firemud.common.gamelogic.GameLogicPublicationSourceReadBinding;
import net.firedevops.firemud.gamelogic.service.GameLogicDraftDesignDigestService;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicPublicationSourceReadService;
import org.springframework.stereotype.Service;

@Service
public final class GameLogicDraftDesignDigestServiceImpl
    implements GameLogicDraftDesignDigestService {
  private final GameLogicPublicationSourceReadService publicationSourceReadService;

  /** Spring's default owner keeps this route denied until explicit composition provides storage. */
  public GameLogicDraftDesignDigestServiceImpl() {
    publicationSourceReadService = null;
  }

  /** Explicit composition for owner-local proofs and tests; this constructor is not auto-wired. */
  public GameLogicDraftDesignDigestServiceImpl(
      GameLogicPublicationSourceReadService publicationSourceReadService) {
    this.publicationSourceReadService =
        Objects.requireNonNull(publicationSourceReadService, "publicationSourceReadService");
  }

  @Override
  public GameLogicPublicationSourceReadService.Result getDraftDesignDigest(
      GameLogicPublicationSourceReadBinding binding) {
    if (publicationSourceReadService == null) {
      throw new UnsupportedDigestScopeException(
          "Game Logic retained publication source reader is not configured");
    }
    return publicationSourceReadService.read(binding);
  }
}
