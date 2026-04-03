package net.firedevops.firemud.gamesession.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import org.junit.jupiter.api.Test;

class LookViewOutputTest {

  @Test
  void moveRefreshWithLongDescriptionPrefersBrief() {
    LookViewOutput output =
        LookViewOutput.from(sampleLookResult(), true, LookViewOutput.RefreshReason.MOVE_REFRESH);

    assertThat(output.briefRenderingHint())
        .isEqualTo(LookViewOutput.BriefRenderingHint.PREFER_BRIEF);
  }

  @Test
  void reconnectRefreshWithLongDescriptionFollowsDefault() {
    LookViewOutput output =
        LookViewOutput.from(
            sampleLookResult(), true, LookViewOutput.RefreshReason.RECONNECT_REFRESH);

    assertThat(output.briefRenderingHint())
        .isEqualTo(LookViewOutput.BriefRenderingHint.FOLLOW_DEFAULT);
  }

  @Test
  void quickLookStyleViewWithoutLongDescriptionPrefersBrief() {
    LookViewOutput output =
        LookViewOutput.from(sampleLookResult(), false, LookViewOutput.RefreshReason.QUICKLOOK);

    assertThat(output.briefRenderingHint())
        .isEqualTo(LookViewOutput.BriefRenderingHint.PREFER_BRIEF);
  }

  private static LookResult sampleLookResult() {
    return LookResult.newBuilder()
        .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("R-101").build())
        .setRoomName("Bronze Hall")
        .setShortDescription("A low bronze-roofed hall.")
        .setLongDescription("Heat shimmers above the brazier pits along the walls.")
        .build();
  }
}
