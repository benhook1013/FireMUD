package net.firedevops.firemud.gamelogic.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.firedevops.firemud.gamelogic.service.GameLogicDraftDesignDigestService;
import org.junit.jupiter.api.Test;

class GameLogicDraftDesignDigestServiceImplTest {
  private final GameLogicDraftDesignDigestServiceImpl service =
      new GameLogicDraftDesignDigestServiceImpl();

  @Test
  void getDraftDesignDigestFailsWhenOwnerManifestAndProvenanceAreUnavailable() {
    GameLogicDraftDesignDigestService.UnsupportedDigestScopeException thrown =
        assertThrows(
            GameLogicDraftDesignDigestService.UnsupportedDigestScopeException.class,
            () -> service.getDraftDesignDigest("tenant-1", "7"));

    assertEquals(
        "Game Logic owner-local manifest and provenance are unavailable", thrown.getMessage());
  }

  @Test
  void getDraftDesignDigestRejectsMissingVersionBeforeManifestCheck() {
    assertThrows(
        IllegalArgumentException.class, () -> service.getDraftDesignDigest("tenant-1", " "));
  }
}
