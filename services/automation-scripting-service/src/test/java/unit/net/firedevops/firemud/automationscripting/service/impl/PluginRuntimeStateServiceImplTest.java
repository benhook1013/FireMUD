package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.automationscripting.client.GameDesignControlPlaneClient;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeStateRepository;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.gamedesign.v1.GetPublishedPluginVersionResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedPluginVersion;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PluginRuntimeStateServiceImplTest {
  @Test
  void createsRuntimeActivationState() {
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    GameDesignControlPlaneClient gameDesignClient =
        Mockito.mock(GameDesignControlPlaneClient.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
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
                        .build())
                .build());
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1"))
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
        new PluginRuntimeStateServiceImpl(repository, gameDesignClient, gameSessionClient);

    PluginRuntimeStateService.ActivationResult result =
        service.setActiveVersion(
            new PluginRuntimeStateService.ActivationCommand(
                "1", "game-1", "plugin-1", "plugin-v1", "req-1", "admin", "activation"));

    assertThat(result.previousPluginVersionId()).isEmpty();
    assertThat(result.activePluginVersionId()).isEqualTo("plugin-v1");
    assertThat(result.controlPlaneRequestId()).isEqualTo("req-1");
    ArgumentCaptor<PluginRuntimeState> stateCaptor =
        ArgumentCaptor.forClass(PluginRuntimeState.class);
    Mockito.verify(repository).save(stateCaptor.capture());
    assertThat(stateCaptor.getValue().getPluginState())
        .isEqualTo(PluginState.PLUGIN_STATE_ENABLED.name());
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
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(existing));
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            Mockito.mock(GameDesignControlPlaneClient.class),
            Mockito.mock(GameSessionControlPlaneClient.class));

    boolean disabled =
        service.disable(
            new PluginRuntimeStateService.PluginStateCommand(
                "1", "game-1", "plugin-1", "req-2", "admin", "maintenance"));

    assertThat(disabled).isTrue();
    assertThat(existing.getPluginState()).isEqualTo(PluginState.PLUGIN_STATE_DISABLED.name());
    assertThat(existing.getStatusReason()).isEqualTo("maintenance");
    Mockito.verify(repository).save(existing);
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
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
    when(repository.findByTenantIdAndGameInstanceIdAndPluginId("1", "game-1", "plugin-1"))
        .thenReturn(Optional.of(existing));
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            repository,
            Mockito.mock(GameDesignControlPlaneClient.class),
            Mockito.mock(GameSessionControlPlaneClient.class));

    Optional<PluginRuntimeStateService.PluginRuntimeStatus> status =
        service.getStatus("1", "game-1", "plugin-1");

    assertThat(status).isPresent();
    assertThat(status.get().activePluginVersionId()).isEqualTo("plugin-v1");
    assertThat(status.get().pluginState()).isEqualTo(PluginState.PLUGIN_STATE_DRAINING);
    assertThat(status.get().lastChangedAtMs()).isEqualTo(123L);
    assertThat(status.get().controlPlaneRequestId()).isEqualTo("req-7");
    assertThat(status.get().actorPrincipal()).isEqualTo("operator-1");
  }

  @Test
  void rejectsMissingActivationTarget() {
    PluginRuntimeStateService service =
        new PluginRuntimeStateServiceImpl(
            Mockito.mock(PluginRuntimeStateRepository.class),
            Mockito.mock(GameDesignControlPlaneClient.class),
            Mockito.mock(GameSessionControlPlaneClient.class));

    assertThatThrownBy(
            () ->
                service.setActiveVersion(
                    new PluginRuntimeStateService.ActivationCommand(
                        "1", "game-1", "plugin-1", "", "req-1", "admin", "")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("target_plugin_version_id is required");
  }

  @Test
  void rejectsActivationWhenBaseVersionDoesNotMatchRuntime() {
    PluginRuntimeStateRepository repository = Mockito.mock(PluginRuntimeStateRepository.class);
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
                        .build())
                .build());
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1"))
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
        new PluginRuntimeStateServiceImpl(repository, gameDesignClient, gameSessionClient);

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
                        .build())
                .build());
    when(gameSessionClient.getGameInstanceRuntimeState("1", "game-1"))
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
        new PluginRuntimeStateServiceImpl(repository, gameDesignClient, gameSessionClient);

    assertThatThrownBy(
            () ->
                service.setActiveVersion(
                    new PluginRuntimeStateService.ActivationCommand(
                        "1", "game-1", "plugin-1", "plugin-v1", "req-1", "admin", "activation")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "PLUGIN_ABILITY_SCHEMA_MISMATCH: plugin ability schema digest does not match runtime version");
  }
}
