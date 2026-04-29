package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.config.ScriptRuntimeProperties;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchPinProjection;
import net.firedevops.firemud.automationscripting.repository.ScriptPatchPinProjectionRepository;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchPinProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ScriptPatchPinProjectionServiceImplTest {
  @Test
  void refreshesProjectionFromRuntimeStateWhenMissing() {
    ScriptPatchPinProjectionRepository repository =
        Mockito.mock(ScriptPatchPinProjectionRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    Mockito.when(repository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(Optional.empty());
    Mockito.when(repository.save(Mockito.any(ScriptPatchPinProjection.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Mockito.when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-7")
                        .setScriptPatchPinnedControlPlaneRequestId("req-7")
                        .setScriptPatchPinnedAtMs(700L)
                        .setWorldSlug("demo")
                        .setRealmSlug("production")
                        .setPointerVersion(17L)
                        .build())
                .build());

    ScriptPatchPinProjectionService service =
        new ScriptPatchPinProjectionServiceImpl(
            repository,
            gameSessionControlPlaneClient,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            Mockito.mock(ScriptScheduleInstanceService.class),
            runtimeProperties());

    ScriptPatchPinProjectionService.PinConvergenceLookup lookup =
        service.getPinConvergence("1", "game-1");

    assertThat(lookup.errorCode()).isBlank();
    assertThat(lookup.summary()).isPresent();
    assertThat(lookup.summary().get().observedPinnedScriptPatchVersion()).isEqualTo("patch-7");
    assertThat(lookup.summary().get().lastObservedControlPlaneRequestId()).isEqualTo("req-7");
    assertThat(lookup.summary().get().observedAtMs()).isEqualTo(700L);
    assertThat(lookup.summary().get().projectionLagMs()).isZero();
    assertThat(lookup.summary().get().projectionStale()).isFalse();
    assertThat(lookup.summary().get().worldSlug()).isEqualTo("demo");
    assertThat(lookup.summary().get().realmSlug()).isEqualTo("production");
    assertThat(lookup.summary().get().pointerVersion()).isEqualTo("17");
  }

  @Test
  void returnsStoredProjectionWhenRefreshFails() {
    ScriptPatchPinProjection existing = new ScriptPatchPinProjection();
    existing.setTenantId("1");
    existing.setGameInstanceId("game-1");
    existing.setObservedPinnedScriptPatchVersion("patch-4");
    existing.setLastObservedControlPlaneRequestId("req-4");
    existing.setObservedAt(Instant.ofEpochMilli(400L));
    existing.setProjectionRefreshedAt(Instant.now().minusSeconds(30));
    existing.setWorldSlug("demo");
    existing.setRealmSlug("production");
    existing.setPointerVersion("11");

    ScriptPatchPinProjectionRepository repository =
        Mockito.mock(ScriptPatchPinProjectionRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    Mockito.when(repository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(Optional.of(existing));
    Mockito.when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("GAME_SESSION_UNAVAILABLE")
                        .setMessage("unavailable")
                        .build())
                .build());

    ScriptPatchPinProjectionService service =
        new ScriptPatchPinProjectionServiceImpl(
            repository,
            gameSessionControlPlaneClient,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            Mockito.mock(ScriptScheduleInstanceService.class),
            runtimeProperties());

    ScriptPatchPinProjectionService.PinConvergenceLookup lookup =
        service.getPinConvergence("1", "game-1");

    assertThat(lookup.errorCode()).isBlank();
    assertThat(lookup.summary()).isPresent();
    assertThat(lookup.summary().get().observedPinnedScriptPatchVersion()).isEqualTo("patch-4");
    assertThat(lookup.summary().get().lastObservedControlPlaneRequestId()).isEqualTo("req-4");
    assertThat(lookup.summary().get().projectionStale()).isTrue();
    assertThat(lookup.summary().get().worldSlug()).isEqualTo("demo");
    assertThat(lookup.summary().get().realmSlug()).isEqualTo("production");
    assertThat(lookup.summary().get().pointerVersion()).isEqualTo("11");
  }

  private static ScriptRuntimeProperties runtimeProperties() {
    return new ScriptRuntimeProperties();
  }
}
