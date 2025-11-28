package net.firedevops.firemud.service;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.test.LookTestFixtures;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import org.junit.jupiter.api.Test;

class LookResultRendererTest {
  private final LookResultRenderer renderer = new LookResultRenderer();

  @Test
  void rendersCanonicalLook() {
    LookResult result = LookTestFixtures.sampleLookResult();
    String text = renderer.render(result);

    assertThat(text).isEqualTo(LookTestFixtures.canonicalLookText().trim());
  }

  @Test
  void rendersEmptyRoom() {
    LookResult result =
        LookResult.newBuilder()
            .setRoomId("R-0001")
            .setRoomName("Empty Chamber")
            .setShortDescription("Quiet")
            .setLongDescription("No one is here.")
            .build();

    String text = renderer.render(result);

    assertThat(text).contains("Entities:");
    assertThat(text).contains("Exits: ");
  }
}
