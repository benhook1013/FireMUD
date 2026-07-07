package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AutomationRuntimeScopeSupportTest {
  @Test
  void currentRuntimeScopeReturnsUnknownWhenRuntimeStateIsMissing() {
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(GetGameInstanceRuntimeStateResponse.newBuilder().build());

    AutomationRuntimeScopeSupport.RuntimeScope runtimeScope =
        AutomationRuntimeScopeSupport.currentRuntimeScope(
            gameSessionControlPlaneClient, "1", "game-1", "region-1");

    assertThat(runtimeScope).isEqualTo(AutomationRuntimeScopeSupport.RuntimeScope.UNKNOWN);
  }

  @Test
  void matchesRejectsStateWithBlankRegionWhenRuntimeScopeIsKnown() {
    PluginRuntimeState state = new PluginRuntimeState();
    state.setRuntimeRegionId("");
    state.setRuntimeRegionEpoch(7L);

    assertThat(
            AutomationRuntimeScopeSupport.matches(
                state, new AutomationRuntimeScopeSupport.RuntimeScope("region-1", 7L)))
        .isFalse();
  }

  @Test
  void currentRuntimeScopeReturnsObservedRegionWhenRuntimeStateIsPresent() {
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setRegionId("region-live")
                        .setRegionEpoch(9L)
                        .build())
                .build());

    AutomationRuntimeScopeSupport.RuntimeScope runtimeScope =
        AutomationRuntimeScopeSupport.currentRuntimeScope(
            gameSessionControlPlaneClient, "1", "game-1", "region-1");

    assertThat(runtimeScope.regionId()).isEqualTo("region-live");
    assertThat(runtimeScope.regionEpoch()).isEqualTo(9L);
  }
}
