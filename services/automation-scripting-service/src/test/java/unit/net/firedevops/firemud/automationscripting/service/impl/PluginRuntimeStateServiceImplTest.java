package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.client.GameDesignControlPlaneClient;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeEvent;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeEventRepository;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeStateRepository;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

class PluginRuntimeStateServiceImplTest {
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
    Mockito.verify(scheduleInstanceService)
        .reconcileObservedRuntimeState(Mockito.eq("1"), Mockito.eq("game-1"), Mockito.any());
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
    Mockito.verify(scheduleInstanceService)
        .reconcileObservedRuntimeState(Mockito.eq("1"), Mockito.eq("game-1"), Mockito.any());
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
    existing.setActorPrincipal("operator-1");
    existing.setLastChangedAt(java.time.Instant.ofEpochMilli(123));
    existing.setRuntimeRegionId("region-9");
    existing.setRuntimeRegionEpoch(33L);
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(existing));
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
    assertThat(status.get().actorPrincipal()).isEqualTo("operator-1");
    assertThat(status.get().activePublication()).isNotNull();
    assertThat(status.get().activePublication().publicationId()).isEqualTo(17L);
    assertThat(status.get().activePublication().publicationState())
        .isEqualTo(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED);
    assertThat(status.get().activePublication().statusReason()).isEqualTo("ready_for_activation");
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
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", ""))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setRuntimeVersionId("7")
                        .setStatus("RUNNING")
                        .build())
                .build());
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
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1", ""))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    GameInstanceRuntimeState.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("game-1")
                        .setRuntimeVersionId("7")
                        .setStatus("RUNNING")
                        .build())
                .build());
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
    when(repository.findByPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
            Mockito.eq(PluginState.PLUGIN_STATE_ENABLED.name()), Mockito.eq(""), Mockito.any()))
        .thenReturn(List.of(active));
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
            Mockito.mock(GameSessionControlPlaneClient.class),
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
  void reconciliationLeavesAllowedActivePluginEnabled() {
    PluginRuntimeState active = activePluginState();
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    when(repository.findByPluginStateAndActivePluginVersionIdNotOrderByLastChangedAtAsc(
            Mockito.eq(PluginState.PLUGIN_STATE_ENABLED.name()), Mockito.eq(""), Mockito.any()))
        .thenReturn(List.of(active));
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
    assertThat(active.getPluginState()).isEqualTo(PluginState.PLUGIN_STATE_ENABLED.name());
    assertThat(active.getLastPolicyCheckedAt()).isAfter(java.time.Instant.EPOCH);
    Mockito.verify(repository).save(active);
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
    active.setRuntimeRegionId("region-7");
    active.setRuntimeRegionEpoch(12L);
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            eventRepository,
            gameDesignClient,
            Mockito.mock(GameSessionControlPlaneClient.class),
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
  void activationNoOpsWhenTargetAlreadyApplied() {
    PluginRuntimeState existing = activePluginState();
    existing.setStatusReason("operator_activation");
    existing.setControlPlaneRequestId("req-existing");
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    PluginRuntimeEventRepository eventRepository = Mockito.mock(PluginRuntimeEventRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(existing));
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
                        .setRuntimeVersionId("7")
                        .setStatus("RUNNING")
                        .build())
                .build());
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

    PluginRuntimeStateService.ActivationResult result =
        service.setActiveVersion(
            new PluginRuntimeStateService.ActivationCommand(
                "1", "game-1", "plugin-1", "plugin-v1", "req-new", "admin", "operator_activation"));

    assertThat(result.previousPluginVersionId()).isEqualTo("plugin-v1");
    assertThat(result.activePluginVersionId()).isEqualTo("plugin-v1");
    assertThat(result.controlPlaneRequestId()).isEqualTo("req-existing");
    Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    Mockito.verifyNoInteractions(eventRepository);
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
    active.setPluginId("plugin-1");
    active.setActivePluginVersionId("plugin-v1");
    active.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    active.setStatusReason("activation");
    active.setLastChangedAt(java.time.Instant.EPOCH);
    active.setLastPolicyCheckedAt(java.time.Instant.EPOCH);
    return active;
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
