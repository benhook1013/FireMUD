package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.config.ScriptRuntimeProperties;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchPinProjection;
import net.firedevops.firemud.automationscripting.repository.ScriptPatchPinProjectionRepository;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchPinProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.gamesession.v1.AdmissionPointerControlPlaneEntry;
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
    Mockito.when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", ""))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-7")
                        .setScriptPinEpoch(1L)
                        .setRegionId("region-7")
                        .setRegionEpoch(22L)
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_SHARED)
                        .setScriptPatchPinnedControlPlaneRequestId("req-7")
                        .setScriptPatchPinnedAtMs(700L)
                        .addCurrentAdmissionPointers(currentPointer("demo", "production", 17L))
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
    assertThat(lookup.summary().get().runtimeRegionId()).isEqualTo("region-7");
    assertThat(lookup.summary().get().runtimeRegionEpoch()).isEqualTo(22L);
    assertThat(lookup.summary().get().worldSlug()).isEqualTo("demo");
    assertThat(lookup.summary().get().realmSlug()).isEqualTo("production");
    assertThat(lookup.summary().get().pointerVersion()).isEqualTo("17");
  }

  @Test
  void treatsRuntimeErrorAsUnavailableEvenWhenRuntimeStateIsAlsoPresent() {
    ScriptPatchPinProjectionRepository repository =
        Mockito.mock(ScriptPatchPinProjectionRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    ScriptScheduleInstanceService scheduleInstanceService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    Mockito.when(repository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(Optional.empty());
    Mockito.when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", ""))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("GAME_SESSION_UNAVAILABLE")
                        .setMessage("unavailable"))
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-7")
                        .build())
                .build());

    ScriptPatchPinProjectionService service =
        new ScriptPatchPinProjectionServiceImpl(
            repository,
            gameSessionControlPlaneClient,
            rolloutProjectionService,
            scheduleInstanceService,
            runtimeProperties());

    ScriptPatchPinProjectionService.PinConvergenceLookup lookup =
        service.getPinConvergence("1", "game-1");

    assertThat(lookup.summary()).isEmpty();
    assertThat(lookup.errorCode()).isEqualTo("GAME_SESSION_UNAVAILABLE");
    assertThat(lookup.errorMessage()).isEqualTo("unavailable");
    verify(repository, never()).save(Mockito.any());
    verifyNoInteractions(rolloutProjectionService, scheduleInstanceService);
  }

  @Test
  void collapsesPartialRuntimeRoutingBundleWhenRefreshingProjection() {
    ScriptPatchPinProjectionRepository repository =
        Mockito.mock(ScriptPatchPinProjectionRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    Mockito.when(repository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(Optional.empty());
    Mockito.when(repository.save(Mockito.any(ScriptPatchPinProjection.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Mockito.when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", ""))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("patch-7")
                        .setScriptPinEpoch(1L)
                        .setScriptPatchPinnedControlPlaneRequestId("req-7")
                        .setWorldSlug("demo")
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
    assertThat(lookup.summary().get().worldSlug()).isBlank();
    assertThat(lookup.summary().get().realmSlug()).isBlank();
    assertThat(lookup.summary().get().pointerVersion()).isBlank();
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
    existing.setRuntimeRegionId("region-4");
    existing.setRuntimeRegionEpoch(44L);
    existing.setWorldSlug("demo");
    existing.setRealmSlug("production");
    existing.setPointerVersion("11");

    ScriptPatchPinProjectionRepository repository =
        Mockito.mock(ScriptPatchPinProjectionRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    Mockito.when(repository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(Optional.of(existing));
    Mockito.when(
            gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-4"))
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
    assertThat(lookup.summary().get().runtimeRegionId()).isEqualTo("region-4");
    assertThat(lookup.summary().get().runtimeRegionEpoch()).isEqualTo(44L);
    assertThat(lookup.summary().get().worldSlug()).isEqualTo("demo");
    assertThat(lookup.summary().get().realmSlug()).isEqualTo("production");
    assertThat(lookup.summary().get().pointerVersion()).isEqualTo("11");
  }

  @Test
  void rejectsMismatchedRuntimeScopeBeforeRefreshingProjection() {
    ScriptPatchPinProjectionRepository repository =
        Mockito.mock(ScriptPatchPinProjectionRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    ScriptScheduleInstanceService scheduleInstanceService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    Mockito.when(repository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(Optional.empty());
    Mockito.when(gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", ""))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("other-tenant")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("attacker-patch")
                        .build())
                .build());

    ScriptPatchPinProjectionService service =
        new ScriptPatchPinProjectionServiceImpl(
            repository,
            gameSessionControlPlaneClient,
            rolloutProjectionService,
            scheduleInstanceService,
            runtimeProperties());

    ScriptPatchPinProjectionService.PinConvergenceLookup lookup =
        service.getPinConvergence("1", "game-1");

    assertThat(lookup.summary()).isEmpty();
    assertThat(lookup.errorCode()).isEqualTo("RUNTIME_SCOPE_MISMATCH");
    verify(repository, Mockito.never()).save(Mockito.any(ScriptPatchPinProjection.class));
    verifyNoInteractions(rolloutProjectionService, scheduleInstanceService);
  }

  @Test
  void rejectsMismatchedRuntimeScopeBeforeRefreshingExistingProjection() {
    ScriptPatchPinProjection existing = new ScriptPatchPinProjection();
    existing.setTenantId("1");
    existing.setGameInstanceId("game-1");
    existing.setObservedPinnedScriptPatchVersion("patch-4");
    existing.setLastObservedControlPlaneRequestId("req-4");
    existing.setObservedAt(Instant.ofEpochMilli(400L));
    existing.setProjectionRefreshedAt(Instant.now().minusSeconds(30));
    existing.setRuntimeRegionId("region-4");

    ScriptPatchPinProjectionRepository repository =
        Mockito.mock(ScriptPatchPinProjectionRepository.class);
    GameSessionControlPlaneClient gameSessionControlPlaneClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    ScriptScheduleInstanceService scheduleInstanceService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    Mockito.when(repository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(Optional.of(existing));
    Mockito.when(
            gameSessionControlPlaneClient.getGameInstanceRuntimeState("1", "game-1", "region-4"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("other-tenant")
                        .setGameInstanceId("game-1")
                        .setPinnedScriptPatchVersion("attacker-patch")
                        .build())
                .build());

    ScriptPatchPinProjectionService service =
        new ScriptPatchPinProjectionServiceImpl(
            repository,
            gameSessionControlPlaneClient,
            rolloutProjectionService,
            scheduleInstanceService,
            runtimeProperties());

    ScriptPatchPinProjectionService.PinConvergenceLookup lookup =
        service.getPinConvergence("1", "game-1");

    assertThat(lookup.summary()).isEmpty();
    assertThat(lookup.errorCode()).isEqualTo("RUNTIME_SCOPE_MISMATCH");
    verify(repository, Mockito.never()).save(Mockito.any(ScriptPatchPinProjection.class));
    verifyNoInteractions(rolloutProjectionService, scheduleInstanceService);
  }

  @Test
  void ignoresMismatchedRuntimeScopeBeforeObservingProjection() {
    ScriptPatchPinProjectionRepository repository =
        Mockito.mock(ScriptPatchPinProjectionRepository.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    ScriptScheduleInstanceService scheduleInstanceService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    ScriptPatchPinProjectionService service =
        new ScriptPatchPinProjectionServiceImpl(
            repository,
            Mockito.mock(GameSessionControlPlaneClient.class),
            rolloutProjectionService,
            scheduleInstanceService,
            runtimeProperties());

    service.observeRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("other-tenant")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("attacker-patch")
            .build());

    verifyNoInteractions(repository, rolloutProjectionService, scheduleInstanceService);
  }

  @Test
  void ignoresOutOfOrderLowerEpochWithoutSavingOrReconciling() {
    ScriptPatchPinProjection existing = new ScriptPatchPinProjection();
    existing.setId(7L);
    existing.setRowVersion(2);
    existing.setTenantId("1");
    existing.setGameInstanceId("game-1");
    existing.setObservedPinnedScriptPatchVersion("patch-2");
    existing.setScriptPinEpoch(2L);
    existing.setLastObservedControlPlaneRequestId("req-2");

    ScriptPatchPinProjectionRepository repository =
        Mockito.mock(ScriptPatchPinProjectionRepository.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    ScriptScheduleInstanceService scheduleInstanceService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    Mockito.when(repository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(Optional.of(existing));
    ScriptPatchPinProjectionService service =
        new ScriptPatchPinProjectionServiceImpl(
            repository,
            Mockito.mock(GameSessionControlPlaneClient.class),
            rolloutProjectionService,
            scheduleInstanceService,
            runtimeProperties());

    service.observeRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-1")
            .setScriptPinEpoch(1L)
            .setScriptPatchPinnedControlPlaneRequestId("req-1")
            .build());

    assertThat(existing.getScriptPinEpoch()).isEqualTo(2L);
    verify(repository, never()).save(Mockito.any(ScriptPatchPinProjection.class));
    verifyNoInteractions(rolloutProjectionService, scheduleInstanceService);
  }

  @Test
  void ignoresSameEpochObservationWithDifferentOwnerRequestId() {
    ScriptPatchPinProjection existing = new ScriptPatchPinProjection();
    existing.setId(7L);
    existing.setRowVersion(2);
    existing.setTenantId("1");
    existing.setGameInstanceId("game-1");
    existing.setObservedPinnedScriptPatchVersion("patch-2");
    existing.setScriptPinEpoch(2L);
    existing.setLastObservedControlPlaneRequestId("req-2");

    ScriptPatchPinProjectionRepository repository =
        Mockito.mock(ScriptPatchPinProjectionRepository.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    ScriptScheduleInstanceService scheduleInstanceService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    Mockito.when(repository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(Optional.of(existing));
    ScriptPatchPinProjectionService service =
        new ScriptPatchPinProjectionServiceImpl(
            repository,
            Mockito.mock(GameSessionControlPlaneClient.class),
            rolloutProjectionService,
            scheduleInstanceService,
            runtimeProperties());

    service.observeRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("patch-2")
            .setScriptPinEpoch(2L)
            .setScriptPatchPinnedControlPlaneRequestId("different-request")
            .build());

    assertThat(existing.getScriptPinEpoch()).isEqualTo(2L);
    assertThat(existing.getLastObservedControlPlaneRequestId()).isEqualTo("req-2");
    verify(repository, never()).save(Mockito.any(ScriptPatchPinProjection.class));
    verifyNoInteractions(rolloutProjectionService, scheduleInstanceService);
  }

  @Test
  void replacesLegacyPartialProjectionOnFirstPositiveObservation() {
    ScriptPatchPinProjection existing = new ScriptPatchPinProjection();
    existing.setId(7L);
    existing.setRowVersion(2);
    existing.setTenantId("1");
    existing.setGameInstanceId("game-1");
    existing.setObservedPinnedScriptPatchVersion("legacy-patch");
    existing.setLastObservedControlPlaneRequestId("legacy-request");
    existing.setScriptPinEpoch(0L);

    ScriptPatchPinProjectionRepository repository =
        Mockito.mock(ScriptPatchPinProjectionRepository.class);
    Mockito.when(repository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(Optional.of(existing));
    Mockito.when(repository.save(Mockito.any(ScriptPatchPinProjection.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptPatchPinProjectionService service =
        new ScriptPatchPinProjectionServiceImpl(
            repository,
            Mockito.mock(GameSessionControlPlaneClient.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            Mockito.mock(ScriptScheduleInstanceService.class),
            runtimeProperties());

    service.observeRuntimeState(
        "1",
        "game-1",
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setPinnedScriptPatchVersion("authoritative-patch")
            .setScriptPinEpoch(4L)
            .setScriptPatchPinnedControlPlaneRequestId("authoritative-request")
            .build());

    assertThat(existing.getScriptPinEpoch()).isEqualTo(4L);
    assertThat(existing.getObservedPinnedScriptPatchVersion()).isEqualTo("authoritative-patch");
    assertThat(existing.getLastObservedControlPlaneRequestId()).isEqualTo("authoritative-request");
    verify(repository).save(existing);
  }

  private static ScriptRuntimeProperties runtimeProperties() {
    return new ScriptRuntimeProperties();
  }

  private static AdmissionPointerControlPlaneEntry currentPointer(
      String worldSlug, String realmSlug, long pointerVersion) {
    return AdmissionPointerControlPlaneEntry.newBuilder()
        .setWorldSlug(worldSlug)
        .setRealmSlug(realmSlug)
        .setTenantId("1")
        .setGameInstanceId("game-1")
        .setPointerVersion(pointerVersion)
        .setStateScope("SHARED")
        .build();
  }
}
