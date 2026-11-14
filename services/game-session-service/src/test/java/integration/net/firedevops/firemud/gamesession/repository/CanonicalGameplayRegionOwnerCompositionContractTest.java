package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import net.firedevops.firemud.gamesession.service.CanonicalInitialPlayerLocationRegionResolver;
import org.junit.jupiter.api.Test;

class CanonicalGameplayRegionOwnerCompositionContractTest {
  @Test
  void distinctResolversAreRejectedBeforeAnySourceOrStorageEffect() {
    var first = mock(CanonicalInitialPlayerLocationRegionResolver.class);
    var second = mock(CanonicalInitialPlayerLocationRegionResolver.class);
    var secondBinding =
        new CanonicalGameplayRegionOwnerCompositionFixture.SecondBinding(null, null, second, null);
    assertThatThrownBy(
            () ->
                CanonicalGameplayRegionOwnerCompositionFixture.installAndBridgeTwoCandidates(
                    null, null, 0, null, null, first, null, secondBinding))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Both bindings require the same resolver routing their exact World requests");
    verifyNoInteractions(first, second);
  }
}
