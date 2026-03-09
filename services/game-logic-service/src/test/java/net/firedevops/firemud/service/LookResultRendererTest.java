package net.firedevops.firemud.service;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.test.LookTestFixtures;
import org.junit.jupiter.api.Test;

class LookResultRendererTest {
  private final LookResultRenderer renderer = new LookResultRenderer();

  @Test
  void rendersCanonicalLook() {
    LookResult result = LookTestFixtures.sampleLookResult();
    String text = renderer.render(result);

    String canonicalBody =
        LookTestFixtures.canonicalLookText().replaceFirst("^OK LOOK\\n", "").trim();
    assertThat(text).isEqualTo(canonicalBody);
  }

  @Test
  void rendersEmptyRoom() {
    LookResult result =
        LookResult.newBuilder()
            .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("R-0001").build())
            .setRoomName("Empty Chamber")
            .setShortDescription("Quiet")
            .setLongDescription("No one is here.")
            .build();

    String text = renderer.render(result);

    assertThat(text).contains("Entities:");
    assertThat(text).contains("Exits: ");
  }
}
