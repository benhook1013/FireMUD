package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.client.GameDesignControlPlaneClient;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeEvent;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeRequestHistory;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeEventRepository;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeRequestHistoryRepository;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeStateRepository;
import net.firedevops.firemud.automationscripting.service.PluginActivationPreflightService;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.gamedesign.v1.GetPublishedPluginVersionResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PluginComponentPolicyDecision;
import net.firedevops.firemud.gamedesign.v1.PublishedPluginVersion;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

class PluginRuntimeStateServiceImplTest {
  @Test
  void listsActivePluginVersionsForCurrentRuntimeScope() {
    PluginRuntimeState enabledMatching = new PluginRuntimeState();
    enabledMatching.setTenantId("1");
    enabledMatching.setGameInstanceId("game-1");
    enabledMatching.setRuntimeRegionId("region-7");
    enabledMatching.setRuntimeRegionEpoch(12L);
    enabledMatching.setPluginId("plugin-1");
    enabledMatching.setActivePluginVersionId("plugin-v1");
    enabledMatching.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    enabledMatching.setLastChangedAt(Instant.ofEpochMilli(1));

    PluginRuntimeState enabledOtherRegion = new PluginRuntimeState();
    enabledOtherRegion.setTenantId("1");
    enabledOtherRegion.setGameInstanceId("game-1");
    enabledOtherRegion.setRuntimeRegionId("region-8");
    enabledOtherRegion.setRuntimeRegionEpoch(13L);
    enabledOtherRegion.setPluginId("plugin-2");
    enabledOtherRegion.setActivePluginVersionId("plugin-v2");
    enabledOtherRegion.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());

    PluginRuntimeState disabledMatching = new PluginRuntimeState();
    disabledMatching.setTenantId("1");
    disabledMatching.setGameInstanceId("game-1");
    disabledMatching.setRuntimeRegionId("region-7");
    disabledMatching.setRuntimeRegionEpoch(12L);
    disabledMatching.setPluginId("plugin-3");
    disabledMatching.setActivePluginVersionId("plugin-v3");
    disabledMatching.setPluginState(PluginState.PLUGIN_STATE_DISABLED.name());

    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    when(repository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of(enabledMatching, enabledOtherRegion, disabledMatching));

    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            Mockito.mock(PluginRuntimeEventRepository.class),
            Mockito.mock(GameDesignControlPlaneClient.class),
            Mockito.mock(GameSessionControlPlaneClient.class),
            Mockito.mock(ScriptScheduleInstanceService.class));

    Map<String, String> active = service.getActivePluginVersions("1", "game-1", "region-7", 12L);

    assertThat(active).containsExactly(Map.entry("plugin-1", "plugin-v1"));
  }

  @Test
  void listsAllEnabledPluginVersionsWhenRuntimeScopeIsUnknown() {
    PluginRuntimeState enabledScopedA = new PluginRuntimeState();
    enabledScopedA.setTenantId("1");
    enabledScopedA.setGameInstanceId("game-1");
    enabledScopedA.setRuntimeRegionId("region-7");
    enabledScopedA.setRuntimeRegionEpoch(12L);
    enabledScopedA.setPluginId("plugin-1");
    enabledScopedA.setActivePluginVersionId("plugin-v1");
    enabledScopedA.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());

    PluginRuntimeState enabledScopedB = new PluginRuntimeState();
    enabledScopedB.setTenantId("1");
    enabledScopedB.setGameInstanceId("game-1");
    enabledScopedB.setRuntimeRegionId("region-8");
    enabledScopedB.setRuntimeRegionEpoch(13L);
    enabledScopedB.setPluginId("plugin-2");
    enabledScopedB.setActivePluginVersionId("plugin-v2");
    enabledScopedB.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());

    PluginRuntimeState disabledScoped = new PluginRuntimeState();
    disabledScoped.setTenantId("1");
    disabledScoped.setGameInstanceId("game-1");
    disabledScoped.setRuntimeRegionId("region-9");
    disabledScoped.setRuntimeRegionEpoch(14L);
    disabledScoped.setPluginId("plugin-3");
    disabledScoped.setActivePluginVersionId("plugin-v3");
    disabledScoped.setPluginState(PluginState.PLUGIN_STATE_DISABLED.name());

    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    when(repository.findByTenantIdAndGameInstanceId("1", "game-1"))
        .thenReturn(List.of(enabledScopedA, enabledScopedB, disabledScoped));

    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            Mockito.mock(PluginRuntimeEventRepository.class),
            Mockito.mock(GameDesignControlPlaneClient.class),
            Mockito.mock(GameSessionControlPlaneClient.class),
            Mockito.mock(ScriptScheduleInstanceService.class));

    Map<String, String> active = service.getActivePluginVersions("1", "game-1", "", 0L);

    assertThat(active)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("plugin-1", "plugin-v1", "plugin-2", "plugin-v2"));
  }

  @Test
  void createsRuntimeActivationState() {
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptScheduleInstanceService scheduleInstanceService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.empty());
    when(repository.save(Mockito.any(PluginRuntimeState.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenReturn(
            GetPublishedPluginVersionResponse.newBuilder()
                .setPluginVersion(
                    PublishedPluginVersion.newBuilder()
                        .setTenantId("1")
                        .setPluginId("plugin-1")
                        .setPluginVersionId("plugin-v1")
                        .setBaseVersionId(7L)
                        .setPublicationState(
                            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setAbilitySchemaDigest("ability-1")
                        .setBundleDigest("bundle-1")
                        .setManifestSchemaVersion(1)
                        .setSignerKeyId("signer-1")
                        .setComponentPolicyDecision(
                            PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED)
                        .build())
                .build());
    GetGameInstanceRuntimeStateResponse runtimeState =
        GetGameInstanceRuntimeStateResponse.newBuilder()
            .setRuntimeState(
                GameInstanceRuntimeState.newBuilder()
                    .setTenantId("1")
                    .setGameInstanceId("game-1")
                    .setRegionId("region-7")
                    .setRegionEpoch(12L)
                    .setRuntimeVersionId("7")
                    .setStatus("RUNNING")
                    .build())
            .build();
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "")).thenReturn(runtimeState);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "region-7"))
        .thenReturn(runtimeState);
    when(gameDesignClient.getPublishedReleaseBundle("1", 7L))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    PublishedReleaseBundle.newBuilder()
                        .setVersionId(7L)
                        .addParticipantDigests(
                            ParticipantDigest.newBuilder()
                                .setParticipantKey("AUTOMATION_SCRIPTING")
                                .setContentDigest("ability-1")
                                .build())
                        .build())
                .build());
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            gameSessionClient,
            scheduleInstanceService);

    PluginRuntimeStateService.ActivationResult result =
        service.setActiveVersion(
            new PluginRuntimeStateService.ActivationCommand(
                "1", "game-1", "plugin-1", "plugin-v1", "req-1", "admin", "activation"));

    assertThat(result.previousPluginVersionId()).isEmpty();
    assertThat(result.activePluginVersionId()).isEqualTo("plugin-v1");
    assertThat(result.controlPlaneRequestId()).isEqualTo("req-1");
    ArgumentCaptor<PluginRuntimeState> stateCaptor =
        ArgumentCaptor.forClass(PluginRuntimeState.class);
    Mockito.verify(repository, Mockito.atLeastOnce()).save(stateCaptor.capture());
    Mockito.verify(eventRepository).save(Mockito.any(PluginRuntimeEvent.class));
    ArgumentCaptor<Instant> transitionSeedCaptor = ArgumentCaptor.forClass(Instant.class);
    Mockito.verify(scheduleInstanceService)
        .reconcileObservedRuntimeState(
            Mockito.eq("1"),
            Mockito.eq("game-1"),
            Mockito.any(),
            transitionSeedCaptor.capture(),
            Mockito.eq("plugin-1"));
    assertThat(transitionSeedCaptor.getValue())
        .isEqualTo(stateCaptor.getValue().getLastChangedAt());
    assertThat(stateCaptor.getValue().getPluginState())
        .isEqualTo(PluginState.PLUGIN_STATE_ENABLED.name());
    assertThat(stateCaptor.getValue().getRuntimeRegionId()).isEqualTo("region-7");
    assertThat(stateCaptor.getValue().getRuntimeRegionEpoch()).isEqualTo(12L);
    assertThat(stateCaptor.getValue().getStatusReason()).isEqualTo("activation");
  }

  @Test
  void disablesExistingPluginState() {
    PluginRuntimeState existing = new PluginRuntimeState();
    existing.setTenantId("1");
    existing.setGameInstanceId("game-1");
    existing.setPluginId("plugin-1");
    existing.setActivePluginVersionId("plugin-v1");
    existing.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    existing.setStatusReason("activation");
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    ScriptScheduleInstanceService scheduleInstanceService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(existing));
    when(repository.save(existing)).thenReturn(existing);
    when(repository.save(Mockito.any(PluginRuntimeState.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    GetGameInstanceRuntimeStateResponse runtimeState =
        GetGameInstanceRuntimeStateResponse.newBuilder()
            .setRuntimeState(
                GameInstanceRuntimeState.newBuilder()
                    .setTenantId("1")
                    .setGameInstanceId("game-1")
                    .setRegionId("region-8")
                    .setRegionEpoch(21L)
                    .setPinnedScriptPatchVersion("patch-1")
                    .build())
            .build();
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "")).thenReturn(runtimeState);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "region-8"))
        .thenReturn(runtimeState);
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            Mockito.mock(GameDesignControlPlaneClient.class),
            gameSessionClient,
            scheduleInstanceService);

    boolean disabled =
        service.disable(
            new PluginRuntimeStateService.PluginStateCommand(
                "1", "game-1", "plugin-1", "req-2", "admin", "maintenance"));

    assertThat(disabled).isTrue();
    assertThat(existing.getPluginState()).isEqualTo(PluginState.PLUGIN_STATE_DISABLED.name());
    assertThat(existing.getStatusReason()).isEqualTo("maintenance");
    assertThat(existing.getRuntimeRegionId()).isEqualTo("region-8");
    assertThat(existing.getRuntimeRegionEpoch()).isEqualTo(21L);
    Mockito.verify(repository, Mockito.atLeast(2)).save(existing);
    Mockito.verify(eventRepository).save(Mockito.any(PluginRuntimeEvent.class));
    ArgumentCaptor<Instant> transitionSeedCaptor = ArgumentCaptor.forClass(Instant.class);
    Mockito.verify(scheduleInstanceService)
        .reconcileObservedRuntimeState(
            Mockito.eq("1"),
            Mockito.eq("game-1"),
            Mockito.any(),
            transitionSeedCaptor.capture(),
            Mockito.eq("plugin-1"));
    assertThat(transitionSeedCaptor.getValue()).isEqualTo(existing.getLastChangedAt());
  }

  @Test
  void readsRuntimeStatus() {
    PluginRuntimeState existing = new PluginRuntimeState();
    existing.setTenantId("1");
    existing.setGameInstanceId("game-1");
    existing.setPluginId("plugin-1");
    existing.setActivePluginVersionId("plugin-v1");
    existing.setPendingPluginVersionId("");
    existing.setPluginState(PluginState.PLUGIN_STATE_DRAINING.name());
    existing.setStatusReason("operator_drain");
    existing.setControlPlaneRequestId("req-7");
    existing.setPluginActivationEpoch(4L);
    existing.setLifecycleRevision(9L);
    existing.setActorPrincipal("operator-1");
    existing.setLastChangedAt(java.time.Instant.ofEpochMilli(123));
    existing.setRuntimeRegionId("region-9");
    existing.setRuntimeRegionEpoch(33L);
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(existing));
    when(repository.save(existing)).thenReturn(existing);
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenReturn(
            GetPublishedPluginVersionResponse.newBuilder()
                .setPluginVersion(
                    PublishedPluginVersion.newBuilder()
                        .setTenantId("1")
                        .setPluginId("plugin-1")
                        .setPluginVersionId("plugin-v1")
                        .setPublicationId(17L)
                        .setPublicationState(
                            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setStatusReason("ready_for_activation")
                        .setLastChangedAtMs(777L)
                        .build())
                .build());
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            Mockito.mock(PluginRuntimeEventRepository.class),
            gameDesignClient,
            Mockito.mock(GameSessionControlPlaneClient.class),
            Mockito.mock(ScriptScheduleInstanceService.class));

    Optional<PluginRuntimeStateService.PluginRuntimeStatus> status =
        service.getStatus("1", "game-1", "plugin-1");

    assertThat(status).isPresent();
    assertThat(status.get().activePluginVersionId()).isEqualTo("plugin-v1");
    assertThat(status.get().runtimeRegionId()).isEqualTo("region-9");
    assertThat(status.get().runtimeRegionEpoch()).isEqualTo(33L);
    assertThat(status.get().pluginState()).isEqualTo(PluginState.PLUGIN_STATE_DRAINING);
    assertThat(status.get().lastChangedAtMs()).isEqualTo(123L);
    assertThat(status.get().controlPlaneRequestId()).isEqualTo("req-7");
    assertThat(status.get().pluginActivationEpoch()).isEqualTo(4L);
    assertThat(status.get().lifecycleRevision()).isEqualTo(9L);
    assertThat(status.get().actorPrincipal()).isEqualTo("operator-1");
    assertThat(status.get().activePublication()).isNotNull();
    assertThat(status.get().activePublication().publicationId()).isEqualTo(17L);
    assertThat(status.get().activePublication().publicationState())
        .isEqualTo(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED);
    assertThat(status.get().activePublication().statusReason()).isEqualTo("ready_for_activation");
  }

  @Test
  void readsLocalLifecycleStatusWithoutPublicationLookups() {
    PluginRuntimeState existing = new PluginRuntimeState();
    existing.setTenantId("1");
    existing.setGameInstanceId("game-1");
    existing.setPluginId("plugin-1");
    existing.setActivePluginVersionId("plugin-v1");
    existing.setPendingPluginVersionId("plugin-v2");
    existing.setPluginState(PluginState.PLUGIN_STATE_DRAINING.name());
    existing.setStatusReason("drain_started");
    existing.setLastChangedAt(Instant.ofEpochMilli(123L));
    existing.setControlPlaneRequestId("req-7");
    existing.setActorPrincipal("operator-1");
    existing.setLastPolicyCheckedAt(Instant.ofEpochMilli(456L));
    existing.setRuntimeRegionId("region-9");
    existing.setRuntimeRegionEpoch(33L);
    existing.setPluginActivationEpoch(4L);
    existing.setLifecycleRevision(9L);
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(existing));
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            Mockito.mock(PluginRuntimeEventRepository.class),
            gameDesignClient,
            Mockito.mock(GameSessionControlPlaneClient.class),
            Mockito.mock(ScriptScheduleInstanceService.class));

    Optional<PluginRuntimeStateService.PluginRuntimeStatus> status =
        service.getLocalLifecycleStatus("1", "game-1", "plugin-1");

    assertThat(status).isPresent();
    assertThat(status.get().activePluginVersionId()).isEqualTo("plugin-v1");
    assertThat(status.get().pendingPluginVersionId()).isEqualTo("plugin-v2");
    assertThat(status.get().runtimeRegionId()).isEqualTo("region-9");
    assertThat(status.get().runtimeRegionEpoch()).isEqualTo(33L);
    assertThat(status.get().pluginState()).isEqualTo(PluginState.PLUGIN_STATE_DRAINING);
    assertThat(status.get().statusReason()).isEqualTo("drain_started");
    assertThat(status.get().lastChangedAtMs()).isEqualTo(123L);
    assertThat(status.get().controlPlaneRequestId()).isEqualTo("req-7");
    assertThat(status.get().actorPrincipal()).isEqualTo("operator-1");
    assertThat(status.get().lastPolicyCheckedAtMs()).isEqualTo(456L);
    assertThat(status.get().pluginActivationEpoch()).isEqualTo(4L);
    assertThat(status.get().lifecycleRevision()).isEqualTo(9L);
    assertThat(status.get().activePublication()).isNull();
    assertThat(status.get().pendingPublication()).isNull();
    Mockito.verifyNoInteractions(gameDesignClient);
  }

  @Test
  void alreadyDisabledReasonOnlyCommandIsMutationFree() {
    PluginRuntimeState existing = new PluginRuntimeState();
    existing.setTenantId("1");
    existing.setGameInstanceId("game-1");
    existing.setPluginId("plugin-1");
    existing.setActivePluginVersionId("plugin-v1");
    existing.setPluginState(PluginState.PLUGIN_STATE_DISABLED.name());
    existing.setPluginActivationEpoch(3L);
    existing.setLifecycleRevision(4L);
    existing.setStatusReason("previous-reason");

    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(existing));
    when(repository.save(existing)).thenReturn(existing);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            Mockito.mock(GameDesignControlPlaneClient.class),
            Mockito.mock(GameSessionControlPlaneClient.class),
            Mockito.mock(ScriptScheduleInstanceService.class));

    assertThat(
            service.disable(
                new PluginRuntimeStateService.PluginStateCommand(
                    "1", "game-1", "plugin-1", "req-reason-only", "operator", "new-reason")))
        .isTrue();

    assertThat(existing.getPluginActivationEpoch()).isEqualTo(3L);
    assertThat(existing.getLifecycleRevision()).isEqualTo(4L);
    assertThat(existing.getStatusReason()).isEqualTo("previous-reason");
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(eventRepository);
  }

  @Test
  void alreadyDisabledNoopStoresReceiptAndExactRetryCannotMutateAfterReenable() {
    PluginRuntimeState existing = activePluginState();
    existing.setPluginState(PluginState.PLUGIN_STATE_DISABLED.name());
    existing.setPluginActivationEpoch(3L);
    existing.setLifecycleRevision(4L);
    existing.setStatusReason("previous-reason");
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    PluginRuntimeRequestHistoryRepository historyRepository =
        Mockito.mock(PluginRuntimeRequestHistoryRepository.class);
    PluginRuntimeRequestHistory[] receipt = new PluginRuntimeRequestHistory[1];
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(existing));
    when(historyRepository.find("1", "game-1", "plugin-1", "disable-noop"))
        .thenAnswer(invocation -> Optional.ofNullable(receipt[0]));
    when(historyRepository.insertOrGet(Mockito.any(PluginRuntimeRequestHistory.class)))
        .thenAnswer(
            invocation -> {
              receipt[0] = invocation.getArgument(0);
              return receipt[0];
            });
    PluginRuntimeStateServiceImpl service = service(repository, eventRepository, historyRepository);
    PluginRuntimeStateService.PluginStateCommand command =
        new PluginRuntimeStateService.PluginStateCommand(
            "1", "game-1", "plugin-1", "disable-noop", "operator", "new-reason");

    assertThat(service.disable(command)).isTrue();
    assertThat(receipt[0].getOperation()).isEqualTo("DISABLE");
    assertThat(receipt[0].getRequestOutcome()).isEqualTo("SUCCEEDED");
    assertThat(receipt[0].getPluginState()).isEqualTo(PluginState.PLUGIN_STATE_DISABLED.name());
    assertThat(receipt[0].getPluginActivationEpoch()).isEqualTo(3L);
    assertThat(receipt[0].getLifecycleRevision()).isEqualTo(4L);
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(eventRepository);

    // A later state change cannot make the exact no-op request mutate the plugin on retry.
    existing.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    existing.setPluginActivationEpoch(5L);
    existing.setLifecycleRevision(6L);
    assertThat(service.disable(command)).isTrue();
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verify(historyRepository, Mockito.times(1))
        .insertOrGet(Mockito.any(PluginRuntimeRequestHistory.class));
  }

  @Test
  void alreadyEnabledActivationStoresReceiptWithoutChangingRuntimeState() {
    PluginRuntimeState existing = activePluginState();
    existing.setPluginActivationEpoch(4L);
    existing.setLifecycleRevision(7L);
    existing.setLastChangedAt(Instant.ofEpochMilli(42L));
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptScheduleInstanceService scheduleService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    PluginRuntimeRequestHistoryRepository historyRepository =
        Mockito.mock(PluginRuntimeRequestHistoryRepository.class);
    PluginRuntimeRequestHistory[] receipt = new PluginRuntimeRequestHistory[1];
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(existing));
    when(historyRepository.find("1", "game-1", "plugin-1", "activate-noop"))
        .thenAnswer(invocation -> Optional.ofNullable(receipt[0]));
    when(historyRepository.insertOrGet(Mockito.any(PluginRuntimeRequestHistory.class)))
        .thenAnswer(
            invocation -> {
              receipt[0] = invocation.getArgument(0);
              return receipt[0];
            });
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenReturn(
            publishedPluginVersion(
                PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED, false));
    GetGameInstanceRuntimeStateResponse runtimeState =
        GetGameInstanceRuntimeStateResponse.newBuilder()
            .setRuntimeState(
                GameInstanceRuntimeState.newBuilder()
                    .setTenantId("1")
                    .setGameInstanceId("game-1")
                    .setRegionId("region-7")
                    .setRegionEpoch(12L)
                    .setRuntimeVersionId("7")
                    .setStatus("RUNNING")
                    .build())
            .build();
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "region-7"))
        .thenReturn(runtimeState);
    when(gameDesignClient.getPublishedReleaseBundle("1", 7L))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    PublishedReleaseBundle.newBuilder()
                        .setVersionId(7L)
                        .addParticipantDigests(
                            ParticipantDigest.newBuilder()
                                .setParticipantKey("AUTOMATION_SCRIPTING")
                                .setContentDigest("ability-1")
                                .build())
                        .build())
                .build());
    PluginRuntimeStateServiceImpl service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            gameSessionClient,
            scheduleService,
            (tenantId, gameInstanceId, scriptPatchVersion, pluginId, pluginVersionId) -> {},
            historyRepository);

    PluginRuntimeStateService.ActivationCommand command =
        new PluginRuntimeStateService.ActivationCommand(
            "1", "game-1", "plugin-1", "plugin-v1", "activate-noop", "operator", "activate");
    assertThat(service.setActiveVersion(command).activePluginVersionId()).isEqualTo("plugin-v1");
    assertThat(receipt[0].getOperation()).isEqualTo("ACTIVATE");
    assertThat(receipt[0].getRequestOutcome()).isEqualTo("SUCCEEDED");
    assertThat(receipt[0].getActivePluginVersionId()).isEqualTo("plugin-v1");
    assertThat(receipt[0].getPluginActivationEpoch()).isEqualTo(4L);
    assertThat(receipt[0].getLifecycleRevision()).isEqualTo(7L);
    assertThat(existing.getLastChangedAt()).isEqualTo(Instant.ofEpochMilli(42L));
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(eventRepository, scheduleService);
  }

  @Test
  void drainOfAbsentStateRecordsOneStableFailureAndCannotBeReplayedAfterActivation() {
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    PluginRuntimeRequestHistoryRepository historyRepository =
        Mockito.mock(PluginRuntimeRequestHistoryRepository.class);
    PluginRuntimeState enabledLater = activePluginState();
    enabledLater.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    enabledLater.setPluginActivationEpoch(2L);
    enabledLater.setLifecycleRevision(3L);
    PluginRuntimeRequestHistory[] receipt = new PluginRuntimeRequestHistory[1];
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.empty(), Optional.of(enabledLater));
    when(historyRepository.find("1", "game-1", "plugin-1", "drain-1"))
        .thenAnswer(invocation -> Optional.ofNullable(receipt[0]));
    when(historyRepository.insertOrGet(Mockito.any(PluginRuntimeRequestHistory.class)))
        .thenAnswer(
            invocation -> {
              receipt[0] = invocation.getArgument(0);
              return receipt[0];
            });
    PluginRuntimeStateServiceImpl service = service(repository, eventRepository, historyRepository);
    PluginRuntimeStateService.PluginStateCommand command =
        new PluginRuntimeStateService.PluginStateCommand(
            "1", "game-1", "plugin-1", "drain-1", "operator", "operator_drain");

    assertThat(service.drain(command)).isFalse();
    assertThat(receipt[0].getRequestOutcome()).isEqualTo("FAILED");
    assertThat(receipt[0].getFailureCode()).isEqualTo("FAILED_PRECONDITION");
    assertThat(receipt[0].getPluginState()).isEqualTo(PluginState.PLUGIN_STATE_DISABLED.name());
    assertThat(receipt[0].getPluginActivationEpoch()).isZero();
    assertThat(receipt[0].getLifecycleRevision()).isZero();
    assertThat(service.drain(command)).isFalse();
    assertThatThrownBy(
            () ->
                service.drain(
                    new PluginRuntimeStateService.PluginStateCommand(
                        "1", "game-1", "plugin-1", "drain-1", "operator", "changed-reason")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("control_plane_request_id already records a different plugin request");

    Mockito.verify(repository, Mockito.times(3)).lockLifecycleScope("1", "game-1", "plugin-1");
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verify(eventRepository, Mockito.never()).save(Mockito.any());
    Mockito.verify(historyRepository).insertOrGet(Mockito.any(PluginRuntimeRequestHistory.class));
  }

  @Test
  void drainOfDisabledStateRecordsFailureWithoutChangingStateOrEmittingEvent() {
    PluginRuntimeState existing = activePluginState();
    existing.setPluginState(PluginState.PLUGIN_STATE_DISABLED.name());
    existing.setPluginActivationEpoch(4L);
    existing.setLifecycleRevision(9L);
    existing.setStatusReason("previous");
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    PluginRuntimeRequestHistoryRepository historyRepository =
        Mockito.mock(PluginRuntimeRequestHistoryRepository.class);
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(existing));
    when(historyRepository.find("1", "game-1", "plugin-1", "drain-2")).thenReturn(Optional.empty());
    when(historyRepository.insertOrGet(Mockito.any(PluginRuntimeRequestHistory.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    PluginRuntimeStateServiceImpl service = service(repository, eventRepository, historyRepository);

    assertThat(
            service.drain(
                new PluginRuntimeStateService.PluginStateCommand(
                    "1", "game-1", "plugin-1", "drain-2", "operator", "operator_drain")))
        .isFalse();
    assertThat(existing.getPluginState()).isEqualTo(PluginState.PLUGIN_STATE_DISABLED.name());
    assertThat(existing.getPluginActivationEpoch()).isEqualTo(4L);
    assertThat(existing.getLifecycleRevision()).isEqualTo(9L);
    assertThat(existing.getStatusReason()).isEqualTo("previous");
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verify(eventRepository, Mockito.never()).save(Mockito.any());
  }

  @Test
  void alreadyDrainingAndNeverActiveDisableRemainMutationFree() {
    PluginRuntimeState draining = activePluginState();
    draining.setPluginState(PluginState.PLUGIN_STATE_DRAINING.name());
    draining.setPluginActivationEpoch(4L);
    draining.setLifecycleRevision(9L);
    PluginRuntimeState neverActive = new PluginRuntimeState();
    neverActive.setTenantId("1");
    neverActive.setGameInstanceId("game-1");
    neverActive.setPluginId("plugin-2");
    neverActive.setPluginState(PluginState.PLUGIN_STATE_DISABLED.name());
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    PluginRuntimeRequestHistoryRepository historyRepository =
        Mockito.mock(PluginRuntimeRequestHistoryRepository.class);
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(draining));
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-2"))
        .thenReturn(Optional.of(neverActive));
    when(historyRepository.find(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(Optional.empty());
    when(historyRepository.insertOrGet(Mockito.any(PluginRuntimeRequestHistory.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    PluginRuntimeStateServiceImpl service = service(repository, eventRepository, historyRepository);

    assertThat(
            service.drain(
                new PluginRuntimeStateService.PluginStateCommand(
                    "1", "game-1", "plugin-1", "drain-3", "operator", "operator_drain")))
        .isTrue();
    assertThat(
            service.disable(
                new PluginRuntimeStateService.PluginStateCommand(
                    "1", "game-1", "plugin-2", "disable-1", "operator", "operator_disable")))
        .isTrue();
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verify(eventRepository, Mockito.never()).save(Mockito.any());
    Mockito.verify(historyRepository, Mockito.times(2))
        .insertOrGet(Mockito.any(PluginRuntimeRequestHistory.class));
  }

  private static PluginRuntimeStateServiceImpl service(
      PluginRuntimeStateRepository repository,
      PluginRuntimeEventRepository eventRepository,
      PluginRuntimeRequestHistoryRepository historyRepository) {
    return new PluginRuntimeStateServiceImpl(
        repository,
        eventRepository,
        Mockito.mock(GameDesignControlPlaneClient.class),
        Mockito.mock(GameSessionControlPlaneClient.class),
        Mockito.mock(ScriptScheduleInstanceService.class),
        (tenantId, gameInstanceId, scriptPatchVersion, pluginId, pluginVersionId) -> {},
        historyRepository);
  }

  @Test
  void staleActivationRetryUsesImmutableRequestHistoryAfterLaterTransition() throws Exception {
    PluginRuntimeState current = new PluginRuntimeState();
    current.setTenantId("1");
    current.setGameInstanceId("game-1");
    current.setPluginId("plugin-1");
    current.setActivePluginVersionId("plugin-v2");
    current.setPluginState(PluginState.PLUGIN_STATE_DISABLED.name());
    current.setPluginActivationEpoch(4L);
    current.setLifecycleRevision(5L);
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(current));
    PluginRuntimeRequestHistory history = new PluginRuntimeRequestHistory();
    history.setPreviousPluginVersionId("plugin-v1");
    history.setActivePluginVersionId("plugin-v1");
    PluginRuntimeRequestHistoryRepository historyRepository =
        Mockito.mock(PluginRuntimeRequestHistoryRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    ScriptScheduleInstanceService scheduleService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    history.setOperation("ACTIVATE");
    when(historyRepository.find("1", "game-1", "plugin-1", "request-a"))
        .thenReturn(Optional.of(history));
    PluginRuntimeStateServiceImpl service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            Mockito.mock(GameDesignControlPlaneClient.class),
            Mockito.mock(GameSessionControlPlaneClient.class),
            scheduleService,
            (tenantId, gameInstanceId, scriptPatchVersion, pluginId, pluginVersionId) -> {},
            historyRepository);

    PluginRuntimeStateService.ActivationCommand command =
        new PluginRuntimeStateService.ActivationCommand(
            "1", "game-1", "plugin-1", "plugin-v1", "request-a", "operator", "activate");
    // Golden SHA-256 of the production NUL-delimited activation fingerprint tuple.
    history.setRequestFingerprint(
        "8569b5dae95b4130618709656c2e441beaee931657724882162d44d5e293a82a");

    PluginRuntimeStateService.ActivationResult result = service.setActiveVersion(command);

    assertThat(result.previousPluginVersionId()).isEqualTo("plugin-v1");
    assertThat(result.activePluginVersionId()).isEqualTo("plugin-v1");
    assertThat(result.controlPlaneRequestId()).isEqualTo("request-a");
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(eventRepository, scheduleService);
  }

  @Test
  void activationRejectsRequestIdRecordedForDrainBeforeAnyMutation() {
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptScheduleInstanceService scheduleService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    PluginRuntimeRequestHistoryRepository historyRepository =
        Mockito.mock(PluginRuntimeRequestHistoryRepository.class);
    PluginActivationPreflightService preflightService =
        Mockito.mock(PluginActivationPreflightService.class);
    when(historyRepository.find("1", "game-1", "plugin-1", "shared-request"))
        .thenReturn(Optional.of(requestHistory("DRAIN", "drain-digest")));
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            gameSessionClient,
            scheduleService,
            preflightService,
            historyRepository);

    assertThatThrownBy(
            () ->
                service.setActiveVersion(
                    new PluginRuntimeStateService.ActivationCommand(
                        "1",
                        "game-1",
                        "plugin-1",
                        "plugin-v1",
                        "shared-request",
                        "operator",
                        "activate")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("control_plane_request_id already records a different plugin request");

    Mockito.verify(repository).lockLifecycleScope("1", "game-1", "plugin-1");
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(
        eventRepository, gameDesignClient, gameSessionClient, scheduleService, preflightService);
  }

  @Test
  void drainRejectsRequestIdRecordedForActivationBeforeAnyMutation() {
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    ScriptScheduleInstanceService scheduleService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    PluginRuntimeRequestHistoryRepository historyRepository =
        Mockito.mock(PluginRuntimeRequestHistoryRepository.class);
    when(historyRepository.find("1", "game-1", "plugin-1", "shared-request"))
        .thenReturn(Optional.of(requestHistory("ACTIVATE", "activation-digest")));
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            Mockito.mock(GameDesignControlPlaneClient.class),
            Mockito.mock(GameSessionControlPlaneClient.class),
            scheduleService,
            (tenantId, gameInstanceId, scriptPatchVersion, pluginId, pluginVersionId) -> {},
            historyRepository);

    assertThatThrownBy(
            () ->
                service.drain(
                    new PluginRuntimeStateService.PluginStateCommand(
                        "1", "game-1", "plugin-1", "shared-request", "operator", "operator_drain")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("control_plane_request_id already records a different plugin request");

    Mockito.verify(repository).lockLifecycleScope("1", "game-1", "plugin-1");
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(eventRepository, scheduleService);
  }

  @Test
  void changedActivationInputWithSameRequestIdIsRejectedBeforePreflight() {
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptScheduleInstanceService scheduleService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    PluginRuntimeRequestHistoryRepository historyRepository =
        Mockito.mock(PluginRuntimeRequestHistoryRepository.class);
    PluginActivationPreflightService preflightService =
        Mockito.mock(PluginActivationPreflightService.class);
    when(historyRepository.find("1", "game-1", "plugin-1", "activation-request"))
        .thenReturn(Optional.of(requestHistory("ACTIVATE", "original-digest")));
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            gameSessionClient,
            scheduleService,
            preflightService,
            historyRepository);

    assertThatThrownBy(
            () ->
                service.setActiveVersion(
                    new PluginRuntimeStateService.ActivationCommand(
                        "1",
                        "game-1",
                        "plugin-1",
                        "plugin-v2",
                        "activation-request",
                        "operator",
                        "activate")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("control_plane_request_id already records a different plugin request");

    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(
        eventRepository, gameDesignClient, gameSessionClient, scheduleService, preflightService);
  }

  @Test
  void activationRejectsCurrentRowRequestIdWithoutImmutableHistory() {
    PluginRuntimeState existing = activePluginState();
    existing.setControlPlaneRequestId("reused-request");
    existing.setControlPlaneRequestFingerprint("");
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    PluginRuntimeRequestHistoryRepository historyRepository =
        Mockito.mock(PluginRuntimeRequestHistoryRepository.class);
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(existing));
    PluginRuntimeStateService service = service(repository, eventRepository, historyRepository);

    assertThatThrownBy(
            () ->
                service.setActiveVersion(
                    new PluginRuntimeStateService.ActivationCommand(
                        "1",
                        "game-1",
                        "plugin-1",
                        "plugin-v1",
                        "reused-request",
                        "operator",
                        "activate")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("control_plane_request_id has no immutable activation request history");

    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(eventRepository);
  }

  @Test
  void transitionRejectsCurrentRowRequestIdWithoutImmutableHistory() {
    PluginRuntimeState existing = activePluginState();
    existing.setControlPlaneRequestId("reused-request");
    existing.setControlPlaneRequestFingerprint("matching-looking-fingerprint");
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    PluginRuntimeRequestHistoryRepository historyRepository =
        Mockito.mock(PluginRuntimeRequestHistoryRepository.class);
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(existing));
    PluginRuntimeStateService service = service(repository, eventRepository, historyRepository);

    assertThatThrownBy(
            () ->
                service.disable(
                    new PluginRuntimeStateService.PluginStateCommand(
                        "1", "game-1", "plugin-1", "reused-request", "operator", "disable")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("control_plane_request_id has no immutable plugin state request history");

    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(eventRepository);
  }

  @Test
  void readsRuntimeStatusWithPublicationLookupFailureMetadata() {
    PluginRuntimeState existing = new PluginRuntimeState();
    existing.setTenantId("1");
    existing.setGameInstanceId("game-1");
    existing.setPluginId("plugin-1");
    existing.setActivePluginVersionId("plugin-v1");
    existing.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    existing.setStatusReason("operator_activation");
    existing.setLastChangedAt(java.time.Instant.ofEpochMilli(123));
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(existing));
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenReturn(
            GetPublishedPluginVersionResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("GAME_DESIGN_UNAVAILABLE")
                        .setMessage("Game Design service unavailable"))
                .build());
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            Mockito.mock(PluginRuntimeEventRepository.class),
            gameDesignClient,
            Mockito.mock(GameSessionControlPlaneClient.class),
            Mockito.mock(ScriptScheduleInstanceService.class));

    Optional<PluginRuntimeStateService.PluginRuntimeStatus> status =
        service.getStatus("1", "game-1", "plugin-1");

    assertThat(status).isPresent();
    assertThat(status.get().activePublication()).isNotNull();
    assertThat(status.get().activePublication().pluginVersionId()).isEqualTo("plugin-v1");
    assertThat(status.get().lastChangedAtMs()).isEqualTo(123L);
    assertThat(status.get().activePublication().lookupErrorCode())
        .isEqualTo("GAME_DESIGN_UNAVAILABLE");
  }

  @Test
  void listsRuntimeEventsWithPublicationCrossLinks() {
    PluginRuntimeEvent event = new PluginRuntimeEvent();
    event.setEventId("event-1");
    event.setTenantId("1");
    event.setGameInstanceId("game-1");
    event.setRuntimeRegionId("region-7");
    event.setRuntimeRegionEpoch(12L);
    event.setPluginId("plugin-1");
    event.setPreviousPluginVersionId("plugin-v0");
    event.setActivePluginVersionId("plugin-v1");
    event.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    event.setStatusReason("operator_activation");
    event.setControlPlaneRequestId("req-1");
    event.setActorPrincipal("operator-1");
    event.setObservedAt(java.time.Instant.ofEpochMilli(15));
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    when(eventRepository.findEvents(
            Mockito.eq("1"),
            Mockito.eq("game-1"),
            Mockito.eq("plugin-1"),
            Mockito.eq(PluginState.PLUGIN_STATE_ENABLED.name()),
            Mockito.eq("plugin-v1"),
            Mockito.eq(java.time.Instant.ofEpochMilli(10)),
            Mockito.eq(java.time.Instant.ofEpochMilli(20)),
            Mockito.any(Pageable.class)))
        .thenReturn(List.of(event));
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v0"))
        .thenReturn(
            GetPublishedPluginVersionResponse.newBuilder()
                .setPluginVersion(
                    PublishedPluginVersion.newBuilder()
                        .setPluginVersionId("plugin-v0")
                        .setPublicationId(16L)
                        .setPublicationState(
                            VersionLifecycleState.VERSION_LIFECYCLE_STATE_SUPERSEDED)
                        .setStatusReason("superseded")
                        .setLastChangedAtMs(14L)
                        .build())
                .build());
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenReturn(
            GetPublishedPluginVersionResponse.newBuilder()
                .setPluginVersion(
                    PublishedPluginVersion.newBuilder()
                        .setPluginVersionId("plugin-v1")
                        .setPublicationId(17L)
                        .setPublicationState(
                            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setStatusReason("ready_for_activation")
                        .setLastChangedAtMs(15L)
                        .build())
                .build());
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            Mockito.mock(PluginRuntimeStateRepository.class),
            eventRepository,
            gameDesignClient,
            Mockito.mock(GameSessionControlPlaneClient.class),
            Mockito.mock(ScriptScheduleInstanceService.class));

    List<PluginRuntimeStateService.PluginRuntimeEventSummary> events =
        service.listEvents(
            "1", "game-1", "plugin-1", PluginState.PLUGIN_STATE_ENABLED, "plugin-v1", 10L, 20L, 50);

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().runtimeRegionId()).isEqualTo("region-7");
    assertThat(events.getFirst().runtimeRegionEpoch()).isEqualTo(12L);
    assertThat(events.getFirst().previousPublication()).isNotNull();
    assertThat(events.getFirst().previousPublication().publicationId()).isEqualTo(16L);
    assertThat(events.getFirst().activePublication()).isNotNull();
    assertThat(events.getFirst().activePublication().publicationId()).isEqualTo(17L);
  }

  @Test
  void rejectsMissingActivationTarget() {
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            Mockito.mock(PluginRuntimeStateRepository.class),
            Mockito.mock(PluginRuntimeEventRepository.class),
            Mockito.mock(GameDesignControlPlaneClient.class),
            Mockito.mock(GameSessionControlPlaneClient.class),
            Mockito.mock(ScriptScheduleInstanceService.class));

    assertThatThrownBy(
            () ->
                service.setActiveVersion(
                    new PluginRuntimeStateService.ActivationCommand(
                        "1", "game-1", "plugin-1", "", "req-1", "admin", "")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("target_plugin_version_id is required");
  }

  @Test
  void rejectsOversizedActivationRequestIdBeforePersistence() {
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            Mockito.mock(GameDesignControlPlaneClient.class),
            Mockito.mock(GameSessionControlPlaneClient.class),
            Mockito.mock(ScriptScheduleInstanceService.class));

    assertThatThrownBy(
            () ->
                service.setActiveVersion(
                    new PluginRuntimeStateService.ActivationCommand(
                        "1", "game-1", "plugin-1", "plugin-v1", "r".repeat(129), "admin", "")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("control_plane_request_id must be at most 128 characters");
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(eventRepository);
  }

  @Test
  void rejectsOversizedLifecycleRequestIdBeforePersistence() {
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            Mockito.mock(GameDesignControlPlaneClient.class),
            Mockito.mock(GameSessionControlPlaneClient.class),
            Mockito.mock(ScriptScheduleInstanceService.class));

    assertThatThrownBy(
            () ->
                service.disable(
                    new PluginRuntimeStateService.PluginStateCommand(
                        "1", "game-1", "plugin-1", "r".repeat(129), "admin", "")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("control_plane_request_id must be at most 128 characters");
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(eventRepository);
  }

  @Test
  void rejectsActivationWhenSignerIsRevoked() {
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenReturn(
            GetPublishedPluginVersionResponse.newBuilder()
                .setPluginVersion(
                    PublishedPluginVersion.newBuilder()
                        .setTenantId("1")
                        .setPluginId("plugin-1")
                        .setPluginVersionId("plugin-v1")
                        .setBaseVersionId(7L)
                        .setPublicationState(
                            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setAbilitySchemaDigest("ability-1")
                        .setBundleDigest("bundle-1")
                        .setManifestSchemaVersion(1)
                        .setSignerKeyId("signer-1")
                        .setSignerRevoked(true)
                        .setComponentPolicyDecision(
                            PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED)
                        .build())
                .build());
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            gameSessionClient,
            Mockito.mock(ScriptScheduleInstanceService.class));

    assertThatThrownBy(
            () ->
                service.setActiveVersion(
                    new PluginRuntimeStateService.ActivationCommand(
                        "1", "game-1", "plugin-1", "plugin-v1", "req-1", "admin", "activation")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("PLUGIN_SIGNER_REVOKED: plugin signer is revoked for activation");
    Mockito.verifyNoInteractions(gameSessionClient);
    Mockito.verifyNoInteractions(eventRepository);
  }

  @Test
  void rejectsActivationWhenComponentPolicyBlocksPlugin() {
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenReturn(
            GetPublishedPluginVersionResponse.newBuilder()
                .setPluginVersion(
                    PublishedPluginVersion.newBuilder()
                        .setTenantId("1")
                        .setPluginId("plugin-1")
                        .setPluginVersionId("plugin-v1")
                        .setBaseVersionId(7L)
                        .setPublicationState(
                            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setAbilitySchemaDigest("ability-1")
                        .setBundleDigest("bundle-1")
                        .setManifestSchemaVersion(1)
                        .setSignerKeyId("signer-1")
                        .setComponentPolicyDecision(
                            PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_BLOCKED)
                        .build())
                .build());
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            gameSessionClient,
            Mockito.mock(ScriptScheduleInstanceService.class));

    assertThatThrownBy(
            () ->
                service.setActiveVersion(
                    new PluginRuntimeStateService.ActivationCommand(
                        "1", "game-1", "plugin-1", "plugin-v1", "req-1", "admin", "activation")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("PLUGIN_COMPONENT_POLICY_BLOCKED: plugin component policy blocks activation");
    Mockito.verifyNoInteractions(gameSessionClient);
    Mockito.verifyNoInteractions(eventRepository);
  }

  @Test
  void rejectsActivationWhenBaseVersionDoesNotMatchRuntime() {
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenReturn(
            GetPublishedPluginVersionResponse.newBuilder()
                .setPluginVersion(
                    PublishedPluginVersion.newBuilder()
                        .setTenantId("1")
                        .setPluginId("plugin-1")
                        .setPluginVersionId("plugin-v1")
                        .setBaseVersionId(9L)
                        .setPublicationState(
                            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setAbilitySchemaDigest("ability-1")
                        .setBundleDigest("bundle-1")
                        .setManifestSchemaVersion(1)
                        .setSignerKeyId("signer-1")
                        .setComponentPolicyDecision(
                            PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED)
                        .build())
                .build());
    GetGameInstanceRuntimeStateResponse runtimeState =
        GetGameInstanceRuntimeStateResponse.newBuilder()
            .setRuntimeState(
                GameInstanceRuntimeState.newBuilder()
                    .setTenantId("1")
                    .setGameInstanceId("game-1")
                    .setRegionId("region-7")
                    .setRegionEpoch(12L)
                    .setRuntimeVersionId("7")
                    .setStatus("RUNNING")
                    .build())
            .build();
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "")).thenReturn(runtimeState);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "region-7"))
        .thenReturn(runtimeState);
    when(gameDesignClient.getPublishedReleaseBundle("1", 7L))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    PublishedReleaseBundle.newBuilder()
                        .setVersionId(7L)
                        .addParticipantDigests(
                            ParticipantDigest.newBuilder()
                                .setParticipantKey("AUTOMATION_SCRIPTING")
                                .setContentDigest("ability-1")
                                .build())
                        .build())
                .build());
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            gameSessionClient,
            Mockito.mock(ScriptScheduleInstanceService.class));

    assertThatThrownBy(
            () ->
                service.setActiveVersion(
                    new PluginRuntimeStateService.ActivationCommand(
                        "1", "game-1", "plugin-1", "plugin-v1", "req-1", "admin", "activation")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "PLUGIN_BASE_VERSION_MISMATCH: plugin base version does not match runtime version");
  }

  @Test
  void rejectsActivationWhenRuntimeVersionIdIsNonPositiveBeforeReleaseBundleLookup() {
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptScheduleInstanceService scheduleInstanceService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenReturn(
            publishedPluginVersion(
                PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED, false));
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", ""))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setRuntimeVersionId("0")
                        .setStatus("RUNNING")
                        .build())
                .build());
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            gameSessionClient,
            scheduleInstanceService);

    assertThatThrownBy(
            () ->
                service.setActiveVersion(
                    new PluginRuntimeStateService.ActivationCommand(
                        "1", "game-1", "plugin-1", "plugin-v1", "req-1", "admin", "activation")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("GAME_INSTANCE_RUNTIME_UNAVAILABLE: runtimeVersionId must be positive");
    Mockito.verify(gameDesignClient, Mockito.never())
        .getPublishedReleaseBundle(Mockito.any(), Mockito.anyLong());
    Mockito.verifyNoInteractions(eventRepository, scheduleInstanceService);
  }

  @Test
  void rejectsActivationWhenAbilitySchemaDigestDoesNotMatchRuntimeVersion() {
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenReturn(
            GetPublishedPluginVersionResponse.newBuilder()
                .setPluginVersion(
                    PublishedPluginVersion.newBuilder()
                        .setTenantId("1")
                        .setPluginId("plugin-1")
                        .setPluginVersionId("plugin-v1")
                        .setBaseVersionId(7L)
                        .setPublicationState(
                            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setAbilitySchemaDigest("ability-plugin")
                        .setBundleDigest("bundle-1")
                        .setManifestSchemaVersion(1)
                        .setSignerKeyId("signer-1")
                        .setComponentPolicyDecision(
                            PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED)
                        .build())
                .build());
    GetGameInstanceRuntimeStateResponse runtimeState =
        GetGameInstanceRuntimeStateResponse.newBuilder()
            .setRuntimeState(
                GameInstanceRuntimeState.newBuilder()
                    .setTenantId("1")
                    .setGameInstanceId("game-1")
                    .setRegionId("region-7")
                    .setRegionEpoch(12L)
                    .setRuntimeVersionId("7")
                    .setStatus("RUNNING")
                    .build())
            .build();
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "")).thenReturn(runtimeState);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "region-7"))
        .thenReturn(runtimeState);
    when(gameDesignClient.getPublishedReleaseBundle("1", 7L))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    PublishedReleaseBundle.newBuilder()
                        .setVersionId(7L)
                        .addParticipantDigests(
                            ParticipantDigest.newBuilder()
                                .setParticipantKey("AUTOMATION_SCRIPTING")
                                .setContentDigest("ability-runtime")
                                .build())
                        .build())
                .build());
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            gameSessionClient,
            Mockito.mock(ScriptScheduleInstanceService.class));

    assertThatThrownBy(
            () ->
                service.setActiveVersion(
                    new PluginRuntimeStateService.ActivationCommand(
                        "1", "game-1", "plugin-1", "plugin-v1", "req-1", "admin", "activation")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "PLUGIN_ABILITY_SCHEMA_MISMATCH: plugin ability schema digest does not match runtime version");
  }

  @Test
  void reconciliationDisablesActivePluginWhenSignerIsRevoked() {
    PluginRuntimeState active = activePluginState();
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(repository.findByPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
            Mockito.eq(PluginState.PLUGIN_STATE_ENABLED.name()), Mockito.eq(""), Mockito.any()))
        .thenReturn(List.of(active));
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(active));
    when(repository.save(Mockito.any(PluginRuntimeState.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenReturn(
            publishedPluginVersion(
                PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED, true));
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            gameSessionClient,
            Mockito.mock(ScriptScheduleInstanceService.class));

    PluginRuntimeStateService.PolicyReconciliationResult result =
        service.reconcileActivePluginPolicy(10);

    assertThat(result.inspectedCount()).isEqualTo(1);
    assertThat(result.disabledCount()).isEqualTo(1);
    assertThat(active.getPluginState()).isEqualTo(PluginState.PLUGIN_STATE_DISABLED.name());
    assertThat(active.getStatusReason()).isEqualTo("signer_revoked");
    assertThat(active.getControlPlaneRequestId()).startsWith("policy-reconcile-");
    assertThat(active.getActorPrincipal()).isEqualTo("automation-scripting-policy-reconciler");
    Mockito.verify(repository).save(active);
    Mockito.verify(eventRepository).save(Mockito.any(PluginRuntimeEvent.class));
  }

  @Test
  void policyReconciliationChecksWholeBatchBeforeOrderedLifecycleMutations() {
    PluginRuntimeState pluginB = activePluginState();
    pluginB.setPluginId("plugin-b");
    PluginRuntimeState pluginA = activePluginState();
    pluginA.setPluginId("plugin-a");
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptScheduleInstanceService scheduleInstanceService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    GameInstanceRuntimeState runtimeState =
        GameInstanceRuntimeState.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("game-1")
            .setRegionId("region-7")
            .setRegionEpoch(12L)
            .build();
    GetGameInstanceRuntimeStateResponse runtimeResponse =
        GetGameInstanceRuntimeStateResponse.newBuilder().setRuntimeState(runtimeState).build();
    when(repository.findByPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
            Mockito.eq(PluginState.PLUGIN_STATE_ENABLED.name()), Mockito.eq(""), Mockito.any()))
        .thenReturn(List.of(pluginB, pluginA));
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-a"))
        .thenReturn(Optional.of(pluginA));
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-b"))
        .thenReturn(Optional.of(pluginB));
    when(repository.save(Mockito.any(PluginRuntimeState.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(gameDesignClient.getPublishedPluginVersion(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            publishedPluginVersion(
                PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED, true));
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "region-7"))
        .thenReturn(runtimeResponse);
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            gameSessionClient,
            scheduleInstanceService);

    PluginRuntimeStateService.PolicyReconciliationResult result =
        service.reconcileActivePluginPolicy(10);

    assertThat(result.inspectedCount()).isEqualTo(2);
    assertThat(result.disabledCount()).isEqualTo(2);
    InOrder order = Mockito.inOrder(gameDesignClient, gameSessionClient, repository);
    order.verify(gameDesignClient).getPublishedPluginVersion("1", "plugin-b", "plugin-v1");
    order.verify(gameDesignClient).getPublishedPluginVersion("1", "plugin-a", "plugin-v1");
    order
        .verify(gameSessionClient, Mockito.times(2))
        .getGameInstanceRuntimeState("1", "game-1", "region-7");
    order.verify(repository).lockLifecycleScope("1", "game-1", "plugin-a");
    order.verify(repository).findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-a");
    order.verify(repository).save(pluginA);
    order.verify(repository).lockLifecycleScope("1", "game-1", "plugin-b");
    order.verify(repository).findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-b");
    order.verify(repository).save(pluginB);
    Mockito.verifyNoMoreInteractions(gameDesignClient);
    Mockito.verifyNoMoreInteractions(gameSessionClient);
    Mockito.verify(scheduleInstanceService)
        .reconcileObservedRuntimeState(
            Mockito.eq("1"),
            Mockito.eq("game-1"),
            Mockito.eq(runtimeState),
            Mockito.any(Instant.class),
            Mockito.eq("plugin-a"));
    Mockito.verify(scheduleInstanceService)
        .reconcileObservedRuntimeState(
            Mockito.eq("1"),
            Mockito.eq("game-1"),
            Mockito.eq(runtimeState),
            Mockito.any(Instant.class),
            Mockito.eq("plugin-b"));
  }

  @Test
  void policyDisableUsesBoundedReplayableRequestIdentityAndDurableHistory() {
    PluginRuntimeState active = activePluginState();
    active.setTenantId("t".repeat(64));
    active.setGameInstanceId("g".repeat(64));
    active.setPluginId("p".repeat(128));
    active.setActivePluginVersionId("v".repeat(128));
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    PluginRuntimeRequestHistoryRepository historyRepository =
        Mockito.mock(PluginRuntimeRequestHistoryRepository.class);
    when(historyRepository.insertOrGet(Mockito.any(PluginRuntimeRequestHistory.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(repository.findByPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
            Mockito.eq(PluginState.PLUGIN_STATE_ENABLED.name()), Mockito.eq(""), Mockito.any()))
        .thenReturn(List.of(active));
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId(
            active.getTenantId(), active.getGameInstanceId(), active.getPluginId()))
        .thenReturn(Optional.of(active));
    when(repository.save(Mockito.any(PluginRuntimeState.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(gameDesignClient.getPublishedPluginVersion(
            active.getTenantId(), active.getPluginId(), active.getActivePluginVersionId()))
        .thenReturn(
            publishedPluginVersion(
                PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED, true));
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            Mockito.mock(GameSessionControlPlaneClient.class),
            Mockito.mock(ScriptScheduleInstanceService.class),
            (tenantId, gameInstanceId, scriptPatchVersion, pluginId, pluginVersionId) -> {},
            historyRepository);

    service.reconcileActivePluginPolicy(10);

    assertThat(active.getControlPlaneRequestId()).startsWith("policy-reconcile-");
    assertThat(active.getControlPlaneRequestId()).hasSize(81);
    ArgumentCaptor<PluginRuntimeRequestHistory> historyCaptor =
        ArgumentCaptor.forClass(PluginRuntimeRequestHistory.class);
    Mockito.verify(historyRepository).insertOrGet(historyCaptor.capture());
    assertThat(historyCaptor.getValue().getOperation()).isEqualTo("DISABLE");
    assertThat(historyCaptor.getValue().getControlPlaneRequestId())
        .isEqualTo(active.getControlPlaneRequestId());
    assertThat(historyCaptor.getValue().getPreviousPluginVersionId()).isEqualTo("v".repeat(128));
  }

  @Test
  void policyDisableRequestIdentityIsStableForRetryAndDistinctAfterReenable() {
    PluginRuntimeState firstAttempt = activePluginState();
    firstAttempt.setPluginActivationEpoch(4L);
    firstAttempt.setLifecycleRevision(9L);
    firstAttempt.setActivePluginVersionId("plugin-v1");

    // A transaction retry reloads the pre-transition row, so it must derive the same request ID.
    PluginRuntimeState retryAttempt = activePluginState();
    retryAttempt.setPluginActivationEpoch(4L);
    retryAttempt.setLifecycleRevision(9L);
    retryAttempt.setActivePluginVersionId("plugin-v1");

    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    PluginRuntimeRequestHistoryRepository historyRepository =
        Mockito.mock(PluginRuntimeRequestHistoryRepository.class);
    when(historyRepository.insertOrGet(Mockito.any(PluginRuntimeRequestHistory.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(repository.findByPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
            Mockito.eq(PluginState.PLUGIN_STATE_ENABLED.name()), Mockito.eq(""), Mockito.any()))
        .thenReturn(List.of(firstAttempt), List.of(retryAttempt), List.of(retryAttempt));
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(
            Optional.of(firstAttempt), Optional.of(retryAttempt), Optional.of(retryAttempt));
    when(repository.save(Mockito.any(PluginRuntimeState.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(gameDesignClient.getPublishedPluginVersion(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(
            publishedPluginVersion(
                PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED, true));
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            Mockito.mock(GameSessionControlPlaneClient.class),
            Mockito.mock(ScriptScheduleInstanceService.class),
            (tenantId, gameInstanceId, scriptPatchVersion, pluginId, pluginVersionId) -> {},
            historyRepository);

    service.reconcileActivePluginPolicy(10);
    service.reconcileActivePluginPolicy(10);

    // Model the subsequent successful re-enable: both durable fences advance before the next
    // policy transition, while the active plugin version remains the same.
    retryAttempt.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    retryAttempt.setPluginActivationEpoch(6L);
    retryAttempt.setLifecycleRevision(11L);
    service.reconcileActivePluginPolicy(10);

    ArgumentCaptor<PluginRuntimeRequestHistory> historyCaptor =
        ArgumentCaptor.forClass(PluginRuntimeRequestHistory.class);
    Mockito.verify(historyRepository, Mockito.times(3)).insertOrGet(historyCaptor.capture());
    List<PluginRuntimeRequestHistory> histories = historyCaptor.getAllValues();
    assertThat(histories).hasSize(3);
    assertThat(histories.get(0).getControlPlaneRequestId())
        .isEqualTo(histories.get(1).getControlPlaneRequestId());
    assertThat(histories.get(2).getControlPlaneRequestId())
        .isNotEqualTo(histories.get(0).getControlPlaneRequestId());
    assertThat(histories)
        .allSatisfy(
            history -> assertThat(history.getPreviousPluginVersionId()).isEqualTo("plugin-v1"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"changed_epoch", "changed_state", "missing_row"})
  void policyDisableSkipsWhenLifecycleNoLongerMatchesBatchSnapshot(String race) {
    PluginRuntimeState snapshot = activePluginState();
    snapshot.setPluginActivationEpoch(4L);
    snapshot.setLifecycleRevision(9L);
    PluginRuntimeState current = activePluginState();
    current.setPluginActivationEpoch(4L);
    current.setLifecycleRevision(9L);
    Optional<PluginRuntimeState> currentRow = Optional.of(current);
    if ("changed_epoch".equals(race)) {
      current.setPluginActivationEpoch(5L);
      current.setLifecycleRevision(10L);
    } else if ("changed_state".equals(race)) {
      current.setPluginState(PluginState.PLUGIN_STATE_DRAINING.name());
      current.setLifecycleRevision(10L);
    } else {
      currentRow = Optional.empty();
    }

    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    ScriptScheduleInstanceService scheduleInstanceService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    when(repository.findByPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
            Mockito.eq(PluginState.PLUGIN_STATE_ENABLED.name()), Mockito.eq(""), Mockito.any()))
        .thenReturn(List.of(snapshot));
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(currentRow);
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenReturn(
            publishedPluginVersion(
                PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED, true));
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            Mockito.mock(GameSessionControlPlaneClient.class),
            scheduleInstanceService);

    PluginRuntimeStateService.PolicyReconciliationResult result =
        service.reconcileActivePluginPolicy(10);

    assertThat(result.inspectedCount()).isEqualTo(1);
    assertThat(result.disabledCount()).isZero();
    assertThat(snapshot.getPluginState()).isEqualTo(PluginState.PLUGIN_STATE_ENABLED.name());
    assertThat(snapshot.getPluginActivationEpoch()).isEqualTo(4L);
    assertThat(snapshot.getLifecycleRevision()).isEqualTo(9L);
    Mockito.verify(repository).lockLifecycleScope("1", "game-1", "plugin-1");
    Mockito.verify(repository, Mockito.never()).save(Mockito.any(PluginRuntimeState.class));
    Mockito.verifyNoInteractions(eventRepository, scheduleInstanceService);
  }

  @Test
  void reconciliationLeavesAllowedActivePluginEnabled() {
    PluginRuntimeState snapshot = activePluginState();
    PluginRuntimeState current = activePluginState();
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    when(repository.findByPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
            Mockito.eq(PluginState.PLUGIN_STATE_ENABLED.name()), Mockito.eq(""), Mockito.any()))
        .thenReturn(List.of(snapshot));
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(current));
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenReturn(
            publishedPluginVersion(
                PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED, false));
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            Mockito.mock(GameSessionControlPlaneClient.class),
            Mockito.mock(ScriptScheduleInstanceService.class));

    PluginRuntimeStateService.PolicyReconciliationResult result =
        service.reconcileActivePluginPolicy(10);

    assertThat(result.inspectedCount()).isEqualTo(1);
    assertThat(result.disabledCount()).isZero();
    assertThat(snapshot.getLastPolicyCheckedAt()).isEqualTo(java.time.Instant.EPOCH);
    assertThat(current.getPluginState()).isEqualTo(PluginState.PLUGIN_STATE_ENABLED.name());
    assertThat(current.getLastPolicyCheckedAt()).isAfter(java.time.Instant.EPOCH);
    assertThat(current.getLastChangedAt()).isEqualTo(java.time.Instant.EPOCH);
    Mockito.verify(repository).save(current);
    Mockito.verifyNoInteractions(eventRepository);
  }

  @ParameterizedTest
  @ValueSource(strings = {"changed_epoch", "changed_state", "changed_version", "missing_row"})
  void allowedPolicyCheckSkipsWhenLifecycleNoLongerMatchesBatchSnapshot(String race) {
    PluginRuntimeState snapshot = activePluginState();
    snapshot.setPluginActivationEpoch(4L);
    snapshot.setActivePluginVersionId("plugin-v1");
    PluginRuntimeState current = activePluginState();
    current.setPluginActivationEpoch(4L);
    current.setActivePluginVersionId("plugin-v1");
    Optional<PluginRuntimeState> currentRow = Optional.of(current);
    if ("changed_epoch".equals(race)) {
      current.setPluginActivationEpoch(5L);
    } else if ("changed_state".equals(race)) {
      current.setPluginState(PluginState.PLUGIN_STATE_DRAINING.name());
    } else if ("changed_version".equals(race)) {
      current.setActivePluginVersionId("plugin-v2");
    } else {
      currentRow = Optional.empty();
    }

    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    when(repository.findByPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
            Mockito.eq(PluginState.PLUGIN_STATE_ENABLED.name()), Mockito.eq(""), Mockito.any()))
        .thenReturn(List.of(snapshot));
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(currentRow);
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenReturn(
            publishedPluginVersion(
                PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED, false));
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            Mockito.mock(GameSessionControlPlaneClient.class),
            Mockito.mock(ScriptScheduleInstanceService.class));

    PluginRuntimeStateService.PolicyReconciliationResult result =
        service.reconcileActivePluginPolicy(10);

    assertThat(result.inspectedCount()).isEqualTo(1);
    assertThat(result.disabledCount()).isZero();
    assertThat(snapshot.getLastPolicyCheckedAt()).isEqualTo(java.time.Instant.EPOCH);
    Mockito.verify(repository).lockLifecycleScope("1", "game-1", "plugin-1");
    Mockito.verify(repository, Mockito.never()).save(Mockito.any(PluginRuntimeState.class));
    Mockito.verifyNoInteractions(eventRepository);
  }

  @Test
  void reconciliationLeavesReportOnlyActivePluginEnabled() {
    PluginRuntimeState active = activePluginState();
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    when(repository.findByPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
            Mockito.eq(PluginState.PLUGIN_STATE_ENABLED.name()), Mockito.eq(""), Mockito.any()))
        .thenReturn(List.of(active));
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(active));
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenReturn(
            publishedPluginVersion(
                PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_REPORT_ONLY, false));
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            Mockito.mock(GameSessionControlPlaneClient.class),
            Mockito.mock(ScriptScheduleInstanceService.class));

    PluginRuntimeStateService.PolicyReconciliationResult result =
        service.reconcileActivePluginPolicy(10);

    assertThat(result.inspectedCount()).isEqualTo(1);
    assertThat(result.disabledCount()).isZero();
    assertThat(active.getPluginState()).isEqualTo(PluginState.PLUGIN_STATE_ENABLED.name());
    assertThat(active.getLastChangedAt()).isEqualTo(java.time.Instant.EPOCH);
    Mockito.verify(repository).save(active);
    Mockito.verifyNoInteractions(eventRepository);
  }

  @Test
  void policyConvergenceReportsFailClosedActivePlugins() {
    PluginRuntimeState active = activePluginState();
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(repository
            .findByTenantIdAndGameInstanceIdAndPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
                Mockito.eq("1"),
                Mockito.eq("game-1"),
                Mockito.eq(PluginState.PLUGIN_STATE_ENABLED.name()),
                Mockito.eq(""),
                Mockito.any()))
        .thenReturn(List.of(active));
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenReturn(
            publishedPluginVersion(
                PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_BLOCKED, false));
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "region-7"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setRegionId("region-7")
                        .build())
                .build());
    active.setRuntimeRegionId("region-7");
    active.setRuntimeRegionEpoch(12L);
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            gameSessionClient,
            Mockito.mock(ScriptScheduleInstanceService.class));

    PluginRuntimeStateService.PluginPolicyConvergence convergence =
        service.getPluginPolicyConvergence("1", "game-1", 10);

    assertThat(convergence.inspectedCount()).isEqualTo(1);
    assertThat(convergence.failClosedCount()).isEqualTo(1);
    assertThat(convergence.converged()).isFalse();
    assertThat(convergence.violations()).hasSize(1);
    assertThat(convergence.violations().get(0).runtimeRegionId()).isEqualTo("region-7");
    assertThat(convergence.violations().get(0).runtimeRegionEpoch()).isEqualTo(12L);
    assertThat(convergence.violations().get(0).reason())
        .isEqualTo("plugin_component_policy_blocked");
    assertThat(convergence.violations().get(0).activePublication()).isNotNull();
    assertThat(convergence.violations().get(0).activePublication().pluginVersionId())
        .isEqualTo("plugin-v1");
  }

  @Test
  void policyConvergenceIgnoresStatesFromDifferentObservedRuntimeRegion() {
    PluginRuntimeState active = activePluginState();
    active.setRuntimeRegionId("region-stale");
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(repository
            .findByTenantIdAndGameInstanceIdAndPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
                Mockito.eq("1"),
                Mockito.eq("game-1"),
                Mockito.eq(PluginState.PLUGIN_STATE_ENABLED.name()),
                Mockito.eq(""),
                Mockito.any()))
        .thenReturn(List.of(active));
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "region-stale"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setRegionId("region-live")
                        .setRegionEpoch(12L)
                        .build())
                .build());
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            gameSessionClient,
            Mockito.mock(ScriptScheduleInstanceService.class));

    PluginRuntimeStateService.PluginPolicyConvergence convergence =
        service.getPluginPolicyConvergence("1", "game-1", 10);

    assertThat(convergence.inspectedCount()).isZero();
    assertThat(convergence.failClosedCount()).isZero();
    assertThat(convergence.converged()).isTrue();
    assertThat(convergence.violations()).isEmpty();
    Mockito.verifyNoInteractions(gameDesignClient);
  }

  @Test
  void policyConvergenceIgnoresStatesFromDifferentObservedRuntimeEpoch() {
    PluginRuntimeState active = activePluginState();
    active.setRuntimeRegionId("region-7");
    active.setRuntimeRegionEpoch(11L);
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(repository
            .findByTenantIdAndGameInstanceIdAndPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
                Mockito.eq("1"),
                Mockito.eq("game-1"),
                Mockito.eq(PluginState.PLUGIN_STATE_ENABLED.name()),
                Mockito.eq(""),
                Mockito.any()))
        .thenReturn(List.of(active));
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "region-7"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setRegionId("region-7")
                        .setRegionEpoch(12L)
                        .build())
                .build());
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            gameSessionClient,
            Mockito.mock(ScriptScheduleInstanceService.class));

    PluginRuntimeStateService.PluginPolicyConvergence convergence =
        service.getPluginPolicyConvergence("1", "game-1", 10);

    assertThat(convergence.inspectedCount()).isZero();
    assertThat(convergence.failClosedCount()).isZero();
    assertThat(convergence.converged()).isTrue();
    assertThat(convergence.violations()).isEmpty();
    Mockito.verifyNoInteractions(gameDesignClient);
  }

  @Test
  void activationAlreadyAppliedFromFreshRequestIsMutationFree() {
    PluginRuntimeState existing = activePluginState();
    existing.setStatusReason("operator_activation");
    existing.setControlPlaneRequestId("req-existing");
    existing.setControlPlaneRequestFingerprint("existing-fingerprint");
    existing.setActorPrincipal("original-operator");
    existing.setLastChangedAt(Instant.ofEpochMilli(42L));
    existing.setLastPolicyCheckedAt(Instant.ofEpochMilli(43L));
    existing.setPluginActivationEpoch(4L);
    existing.setLifecycleRevision(9L);
    long originalActivationEpoch = existing.getPluginActivationEpoch();
    long originalLifecycleRevision = existing.getLifecycleRevision();
    Instant originalLastChangedAt = existing.getLastChangedAt();
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptScheduleInstanceService scheduleInstanceService =
        Mockito.mock(ScriptScheduleInstanceService.class);
    PluginActivationPreflightService preflightService =
        Mockito.mock(PluginActivationPreflightService.class);
    PluginRuntimeRequestHistoryRepository historyRepository =
        Mockito.mock(PluginRuntimeRequestHistoryRepository.class);
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(existing));
    when(historyRepository.insertOrGet(Mockito.any(PluginRuntimeRequestHistory.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenReturn(
            publishedPluginVersion(
                PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED, false));
    GetGameInstanceRuntimeStateResponse runtimeState =
        GetGameInstanceRuntimeStateResponse.newBuilder()
            .setRuntimeState(
                GameInstanceRuntimeState.newBuilder()
                    .setTenantId("1")
                    .setGameInstanceId("game-1")
                    .setRegionId("region-7")
                    .setRegionEpoch(12L)
                    .setRuntimeVersionId("7")
                    .setStatus("RUNNING")
                    .build())
            .build();
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "")).thenReturn(runtimeState);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", "region-7"))
        .thenReturn(runtimeState);
    when(gameDesignClient.getPublishedReleaseBundle("1", 7L))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    PublishedReleaseBundle.newBuilder()
                        .setVersionId(7L)
                        .addParticipantDigests(
                            ParticipantDigest.newBuilder()
                                .setParticipantKey("AUTOMATION_SCRIPTING")
                                .setContentDigest("ability-1")
                                .build())
                        .build())
                .build());
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            gameSessionClient,
            scheduleInstanceService,
            preflightService,
            historyRepository);

    PluginRuntimeStateService.ActivationResult result =
        service.setActiveVersion(
            new PluginRuntimeStateService.ActivationCommand(
                "1", "game-1", "plugin-1", "plugin-v1", "req-new", "admin", "operator_activation"));

    assertThat(result.previousPluginVersionId()).isEqualTo("plugin-v1");
    assertThat(result.activePluginVersionId()).isEqualTo("plugin-v1");
    assertThat(result.controlPlaneRequestId()).isEqualTo("req-new");
    assertThat(existing.getControlPlaneRequestId()).isEqualTo("req-existing");
    assertThat(existing.getControlPlaneRequestFingerprint()).isEqualTo("existing-fingerprint");
    assertThat(existing.getActorPrincipal()).isEqualTo("original-operator");
    assertThat(existing.getPluginActivationEpoch()).isEqualTo(originalActivationEpoch);
    assertThat(existing.getLifecycleRevision()).isEqualTo(originalLifecycleRevision);
    assertThat(existing.getLastChangedAt()).isEqualTo(originalLastChangedAt);
    Mockito.verify(preflightService).validateActivation("1", "game-1", "", "plugin-1", "plugin-v1");
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verify(eventRepository, Mockito.never()).save(Mockito.any());
    ArgumentCaptor<PluginRuntimeRequestHistory> historyCaptor =
        ArgumentCaptor.forClass(PluginRuntimeRequestHistory.class);
    Mockito.verify(historyRepository).insertOrGet(historyCaptor.capture());
    assertThat(historyCaptor.getValue().getOperation()).isEqualTo("ACTIVATE");
    assertThat(historyCaptor.getValue().getRequestOutcome()).isEqualTo("SUCCEEDED");
    assertThat(historyCaptor.getValue().getPluginActivationEpoch()).isEqualTo(4L);
    assertThat(historyCaptor.getValue().getLifecycleRevision()).isEqualTo(9L);
    Mockito.verifyNoInteractions(scheduleInstanceService);
  }

  @Test
  void listsPluginRuntimeEventsFromReadModel() {
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    PluginRuntimeEvent event = new PluginRuntimeEvent();
    event.setEventId("event-1");
    event.setTenantId("1");
    event.setGameInstanceId("game-1");
    event.setPluginId("plugin-1");
    event.setPreviousPluginVersionId("plugin-v0");
    event.setActivePluginVersionId("plugin-v1");
    event.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    event.setStatusReason("operator_activation");
    event.setControlPlaneRequestId("req-1");
    event.setActorPrincipal("operator-1");
    event.setObservedAt(java.time.Instant.ofEpochMilli(123L));
    when(eventRepository.findEvents(
            Mockito.eq("1"),
            Mockito.eq("game-1"),
            Mockito.eq("plugin-1"),
            Mockito.eq(PluginState.PLUGIN_STATE_ENABLED.name()),
            Mockito.eq("plugin-v1"),
            Mockito.any(),
            Mockito.any(),
            Mockito.any()))
        .thenReturn(List.of(event));
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v0"))
        .thenReturn(
            GetPublishedPluginVersionResponse.newBuilder()
                .setPluginVersion(
                    PublishedPluginVersion.newBuilder()
                        .setPluginVersionId("plugin-v0")
                        .setPublicationId(16L)
                        .setPublicationState(
                            VersionLifecycleState.VERSION_LIFECYCLE_STATE_SUPERSEDED)
                        .build())
                .build());
    when(gameDesignClient.getPublishedPluginVersion("1", "plugin-1", "plugin-v1"))
        .thenReturn(
            GetPublishedPluginVersionResponse.newBuilder()
                .setPluginVersion(
                    PublishedPluginVersion.newBuilder()
                        .setPluginVersionId("plugin-v1")
                        .setPublicationId(17L)
                        .setPublicationState(
                            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .build())
                .build());
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            Mockito.mock(PluginRuntimeStateRepository.class),
            eventRepository,
            gameDesignClient,
            Mockito.mock(GameSessionControlPlaneClient.class),
            Mockito.mock(ScriptScheduleInstanceService.class));

    List<PluginRuntimeStateService.PluginRuntimeEventSummary> events =
        service.listEvents(
            "1",
            "game-1",
            "plugin-1",
            PluginState.PLUGIN_STATE_ENABLED,
            "plugin-v1",
            10L,
            200L,
            50);

    assertThat(events).hasSize(1);
    assertThat(events.get(0).eventId()).isEqualTo("event-1");
    assertThat(events.get(0).previousPluginVersionId()).isEqualTo("plugin-v0");
    assertThat(events.get(0).activePluginVersionId()).isEqualTo("plugin-v1");
    assertThat(events.get(0).pluginState()).isEqualTo(PluginState.PLUGIN_STATE_ENABLED);
    assertThat(events.get(0).previousPublication().publicationId()).isEqualTo(16L);
    assertThat(events.get(0).activePublication().publicationId()).isEqualTo(17L);
  }

  private static PluginRuntimeState activePluginState() {
    PluginRuntimeState active = new PluginRuntimeState();
    active.setTenantId("1");
    active.setGameInstanceId("game-1");
    active.setRuntimeRegionId("region-7");
    active.setRuntimeRegionEpoch(12L);
    active.setPluginId("plugin-1");
    active.setActivePluginVersionId("plugin-v1");
    active.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    active.setStatusReason("activation");
    active.setLastChangedAt(java.time.Instant.EPOCH);
    active.setLastPolicyCheckedAt(java.time.Instant.EPOCH);
    return active;
  }

  private static PluginRuntimeRequestHistory requestHistory(
      String operation, String requestFingerprint) {
    PluginRuntimeRequestHistory history = new PluginRuntimeRequestHistory();
    history.setTenantId("1");
    history.setGameInstanceId("game-1");
    history.setPluginId("plugin-1");
    history.setOperation(operation);
    history.setControlPlaneRequestId("shared-request");
    history.setRequestFingerprint(requestFingerprint);
    history.setRequestOutcome("SUCCEEDED");
    history.setFailureCode("");
    history.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    return history;
  }

  private static GetPublishedPluginVersionResponse publishedPluginVersion(
      PluginComponentPolicyDecision componentPolicyDecision, boolean signerRevoked) {
    return GetPublishedPluginVersionResponse.newBuilder()
        .setPluginVersion(
            PublishedPluginVersion.newBuilder()
                .setTenantId("1")
                .setPluginId("plugin-1")
                .setPluginVersionId("plugin-v1")
                .setBaseVersionId(7L)
                .setPublicationState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
                .setAbilitySchemaDigest("ability-1")
                .setBundleDigest("bundle-1")
                .setManifestSchemaVersion(1)
                .setSignerKeyId("signer-1")
                .setSignerRevoked(signerRevoked)
                .setComponentPolicyDecision(componentPolicyDecision)
                .build())
        .build();
  }
}
