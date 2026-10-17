package net.firedevops.firemud.gamelogic.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.common.gamelogic.GameLogicPublicationSourceReadBinding;
import net.firedevops.firemud.gamelogic.service.GameLogicDraftDesignDigestService;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicPublicationSourceReadService;
import org.junit.jupiter.api.Test;

class GameLogicDraftDesignDigestServiceImplTest {
  @Test
  void defaultSpringCompositionRemainsDeniedWithoutAConfiguredOwnerReader() {
    var service = new GameLogicDraftDesignDigestServiceImpl();

    GameLogicDraftDesignDigestService.UnsupportedDigestScopeException thrown =
        assertThrows(
            GameLogicDraftDesignDigestService.UnsupportedDigestScopeException.class,
            () -> service.getDraftDesignDigest(null));

    assertEquals(
        "Game Logic retained publication source reader is not configured", thrown.getMessage());
  }

  @Test
  void explicitlyComposedServiceReturnsTheExactRetainedReadResultForTheLookupBinding() {
    var sourceReader = mock(GameLogicPublicationSourceReadService.class);
    var binding = mock(GameLogicPublicationSourceReadBinding.class);
    var expected = mock(GameLogicPublicationSourceReadService.Result.class);
    when(sourceReader.read(binding)).thenReturn(expected);
    var service = new GameLogicDraftDesignDigestServiceImpl(sourceReader);

    assertSame(expected, service.getDraftDesignDigest(binding));

    verify(sourceReader).read(binding);
  }
}
