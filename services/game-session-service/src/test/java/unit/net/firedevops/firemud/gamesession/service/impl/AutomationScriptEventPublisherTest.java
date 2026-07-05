package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.AutomationScriptingClient;
import net.firedevops.firemud.gamesession.command.text.BuiltInTextCommandAliasResolver;
import net.firedevops.firemud.gamesession.command.text.TextCommandActionCategory;
import net.firedevops.firemud.gamesession.command.text.TextCommandActionTag;
import net.firedevops.firemud.gamesession.command.text.TextCommandMetadataResolver;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AutomationScriptEventPublisherTest {
  @Test
  void publishesCommandEventWithRuntimeFenceAndPinnedPatch() {
    AutomationScriptingClient client = Mockito.mock(AutomationScriptingClient.class);
    RuntimeRegionStatusRepository statusRepository =
        Mockito.mock(RuntimeRegionStatusRepository.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setScriptPatchVersion("patch-1");
    RuntimeRegionStatus status = new RuntimeRegionStatus();
    status.setRegionId("region-99");
    status.setRegionEpoch(7L);
    when(gameInstanceRepository.findById(99L)).thenReturn(Optional.of(instance));
    when(statusRepository.findByTenantIdAndGameInstanceId(9L, 99L)).thenReturn(Optional.of(status));
    when(client.triggerScriptEvent(Mockito.any()))
        .thenReturn(TriggerScriptEventResponse.newBuilder().setAdmitted(true).build());
    TextCommandMetadataResolver metadataResolver =
        commandToken ->
            Optional.of(
                new TextCommandMetadataResolver.ResolvedTextCommandMetadata(
                    TextCommandActionCategory.META, java.util.List.of(TextCommandActionTag.UI)));
    ScriptEventPublisher publisher =
        new AutomationScriptEventPublisher(
            client,
            statusRepository,
            gameInstanceRepository,
            metadataResolver,
            builtInAliasResolver(),
            Runnable::run);

    GameplayCommand command = command("cmd-1", "LOOK");
    command.setCommandText("l");
    publisher.publishCommandEvent(sharedGameplayContext("room"), command);

    ArgumentCaptor<TriggerScriptEventRequest> captor =
        ArgumentCaptor.forClass(TriggerScriptEventRequest.class);
    verify(client).triggerScriptEvent(captor.capture());
    TriggerScriptEventRequest request = captor.getValue();
    assertThat(request.getTenantId()).isEqualTo("9");
    assertThat(request.getGameInstanceId()).isEqualTo("99");
    assertThat(request.getRegionId()).isEqualTo("region-99");
    assertThat(request.getRegionEpoch()).isEqualTo(7L);
    assertThat(request.getEntityId()).isEqualTo("44");
    assertThat(request.getPlayableStateScope())
        .isEqualTo(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    assertThat(request.getWorldSlug()).isEqualTo("demo");
    assertThat(request.getRealmSlug()).isEqualTo("production");
    assertThat(request.getPointerVersion()).isEqualTo("7");
    assertThat(request.getEventType()).isEqualTo("onCommand");
    assertThat(request.getScriptPatchVersion()).isEqualTo("patch-1");
    assertThat(request.getScriptEventId()).isEqualTo("cmd-1");
    assertThat(request.getReadSnapshotToken()).contains("cmd-1");
    assertThat(request.getPayloadJson()).contains("\"commandId\":\"cmd-1\"");
    assertThat(request.getPayloadJson()).contains("\"commandName\":\"LOOK\"");
    assertThat(request.getPayloadJson()).contains("\"commandAlias\":\"look\"");
    assertThat(request.getPayloadJson()).contains("\"actionCategory\":\"META\"");
    assertThat(request.getPayloadJson()).contains("\"actionTags\":[\"UI\"]");
  }

  @Test
  void skipsWhenRuntimeOwnershipIsMissing() {
    AutomationScriptingClient client = Mockito.mock(AutomationScriptingClient.class);
    RuntimeRegionStatusRepository statusRepository =
        Mockito.mock(RuntimeRegionStatusRepository.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setScriptPatchVersion("patch-1");
    when(gameInstanceRepository.findById(99L)).thenReturn(Optional.of(instance));
    when(statusRepository.findByTenantIdAndGameInstanceId(9L, 99L)).thenReturn(Optional.empty());
    ScriptEventPublisher publisher =
        new AutomationScriptEventPublisher(
            client,
            statusRepository,
            gameInstanceRepository,
            commandToken -> Optional.empty(),
            builtInAliasResolver(),
            Runnable::run);

    publisher.publishCommandEvent(sharedGameplayContext("room"), command("cmd-1", "LOOK"));

    verify(client, never()).triggerScriptEvent(Mockito.any());
  }

  @Test
  void publishesCommandEventUsingPersistedCommandAuthorityWhenPresent() {
    AutomationScriptingClient client = Mockito.mock(AutomationScriptingClient.class);
    RuntimeRegionStatusRepository statusRepository =
        Mockito.mock(RuntimeRegionStatusRepository.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setScriptPatchVersion("patch-1");
    when(gameInstanceRepository.findById(99L)).thenReturn(Optional.of(instance));
    when(client.triggerScriptEvent(Mockito.any()))
        .thenReturn(TriggerScriptEventResponse.newBuilder().setAdmitted(true).build());
    ScriptEventPublisher publisher =
        new AutomationScriptEventPublisher(
            client,
            statusRepository,
            gameInstanceRepository,
            commandToken -> Optional.empty(),
            builtInAliasResolver(),
            Runnable::run);

    GameplayCommand command = command("cmd-1", "LOOK");
    command.setTenantId(9L);
    command.setGameInstanceId(99L);
    command.setCharacterId(44L);
    command.setTargetEntityId("44");
    command.setRegionId("region-staged");
    command.setRegionEpoch(11L);
    command.setPlayableStateScope("ISOLATED");
    command.setWorldSlug("staged-world");
    command.setRealmSlug("staged-realm");
    command.setPointerVersion(17L);

    publisher.publishCommandEvent(sharedGameplayContext("room"), command);

    ArgumentCaptor<TriggerScriptEventRequest> captor =
        ArgumentCaptor.forClass(TriggerScriptEventRequest.class);
    verify(client).triggerScriptEvent(captor.capture());
    TriggerScriptEventRequest request = captor.getValue();
    assertThat(request.getRegionId()).isEqualTo("region-staged");
    assertThat(request.getRegionEpoch()).isEqualTo(11L);
    assertThat(request.getPlayableStateScope())
        .isEqualTo(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED);
    assertThat(request.getWorldSlug()).isEqualTo("staged-world");
    assertThat(request.getRealmSlug()).isEqualTo("staged-realm");
    assertThat(request.getPointerVersion()).isEqualTo("17");
    assertThat(request.getReadSnapshotToken()).isEqualTo("game-session:onCommand:99:11:cmd-1");
    verify(statusRepository, never())
        .findByTenantIdAndGameInstanceId(Mockito.anyLong(), Mockito.anyLong());
  }

  @Test
  void publishesSpawnEventWithGameplayRoutingBundle() {
    AutomationScriptingClient client = Mockito.mock(AutomationScriptingClient.class);
    RuntimeRegionStatusRepository statusRepository =
        Mockito.mock(RuntimeRegionStatusRepository.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setScriptPatchVersion("patch-1");
    RuntimeRegionStatus status = new RuntimeRegionStatus();
    status.setRegionId("region-99");
    status.setRegionEpoch(7L);
    when(gameInstanceRepository.findById(99L)).thenReturn(Optional.of(instance));
    when(statusRepository.findByTenantIdAndGameInstanceId(9L, 99L)).thenReturn(Optional.of(status));
    when(client.triggerScriptEvent(Mockito.any()))
        .thenReturn(TriggerScriptEventResponse.newBuilder().setAdmitted(true).build());
    ScriptEventPublisher publisher =
        new AutomationScriptEventPublisher(
            client,
            statusRepository,
            gameInstanceRepository,
            commandToken -> Optional.empty(),
            builtInAliasResolver(),
            Runnable::run);

    publisher.publishSpawnEvent(
        sharedGameplayContext("room"), "play_entry", "play-spawn:17:99:44:7");

    ArgumentCaptor<TriggerScriptEventRequest> captor =
        ArgumentCaptor.forClass(TriggerScriptEventRequest.class);
    verify(client).triggerScriptEvent(captor.capture());
    TriggerScriptEventRequest request = captor.getValue();
    assertThat(request.getEventType()).isEqualTo("onSpawn");
    assertThat(request.getScriptEventId()).isEqualTo("play-spawn:17:99:44:7");
    assertThat(request.getPlayableStateScope())
        .isEqualTo(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    assertThat(request.getWorldSlug()).isEqualTo("demo");
    assertThat(request.getRealmSlug()).isEqualTo("production");
    assertThat(request.getPointerVersion()).isEqualTo("7");
    assertThat(request.getReadSnapshotToken())
        .isEqualTo("game-session:onSpawn:99:7:play-spawn:17:99:44:7");
    assertThat(request.getPayloadJson()).isEqualTo("{\"spawnReason\":\"play_entry\"}");
  }

  @Test
  void publishesRegionTransitionEventsWithDeterministicIds() {
    AutomationScriptingClient client = Mockito.mock(AutomationScriptingClient.class);
    RuntimeRegionStatusRepository statusRepository =
        Mockito.mock(RuntimeRegionStatusRepository.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setScriptPatchVersion("patch-1");
    RuntimeRegionStatus status = new RuntimeRegionStatus();
    status.setRegionId("region-99");
    status.setRegionEpoch(7L);
    when(gameInstanceRepository.findById(99L)).thenReturn(Optional.of(instance));
    when(statusRepository.findByTenantIdAndGameInstanceId(9L, 99L)).thenReturn(Optional.of(status));
    when(client.triggerScriptEvent(Mockito.any()))
        .thenReturn(TriggerScriptEventResponse.newBuilder().setAdmitted(true).build());
    ScriptEventPublisher publisher =
        new AutomationScriptEventPublisher(
            client,
            statusRepository,
            gameInstanceRepository,
            commandToken -> Optional.empty(),
            builtInAliasResolver(),
            Runnable::run);

    publisher.publishRegionTransitionEvents(
        sharedGameplayContext("room-a"), sharedGameplayContext("room-b"), "effect-1");

    ArgumentCaptor<TriggerScriptEventRequest> captor =
        ArgumentCaptor.forClass(TriggerScriptEventRequest.class);
    verify(client, Mockito.times(2)).triggerScriptEvent(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(TriggerScriptEventRequest::getEventType)
        .containsExactly("onLeaveRegion", "onEnterRegion");
    assertThat(captor.getAllValues())
        .extracting(TriggerScriptEventRequest::getScriptEventId)
        .containsExactly("effect-1:leave", "effect-1:enter");
    assertThat(captor.getAllValues())
        .extracting(TriggerScriptEventRequest::getPlayableStateScope)
        .containsOnly(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    assertThat(captor.getAllValues())
        .extracting(TriggerScriptEventRequest::getWorldSlug)
        .containsOnly("demo");
    assertThat(captor.getAllValues())
        .extracting(TriggerScriptEventRequest::getRealmSlug)
        .containsOnly("production");
    assertThat(captor.getAllValues())
        .extracting(TriggerScriptEventRequest::getPointerVersion)
        .containsOnly("7");
    assertThat(captor.getAllValues())
        .extracting(TriggerScriptEventRequest::getPayloadJson)
        .allSatisfy(
            payload -> {
              assertThat(payload).contains("\"fromRegionId\":\"room-a\"");
              assertThat(payload).contains("\"toRegionId\":\"room-b\"");
            });
  }

  @Test
  void publishesRegionExitEventWithUnknownDestinationAndOptionalExitReason() {
    AutomationScriptingClient client = Mockito.mock(AutomationScriptingClient.class);
    RuntimeRegionStatusRepository statusRepository =
        Mockito.mock(RuntimeRegionStatusRepository.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setScriptPatchVersion("patch-1");
    RuntimeRegionStatus status = new RuntimeRegionStatus();
    status.setRegionId("region-99");
    status.setRegionEpoch(7L);
    when(gameInstanceRepository.findById(99L)).thenReturn(Optional.of(instance));
    when(statusRepository.findByTenantIdAndGameInstanceId(9L, 99L)).thenReturn(Optional.of(status));
    when(client.triggerScriptEvent(Mockito.any()))
        .thenReturn(TriggerScriptEventResponse.newBuilder().setAdmitted(true).build());
    ScriptEventPublisher publisher =
        new AutomationScriptEventPublisher(
            client,
            statusRepository,
            gameInstanceRepository,
            commandToken -> Optional.empty(),
            builtInAliasResolver(),
            Runnable::run);

    publisher.publishRegionExitEvent(
        sharedGameplayContext("room-a"), "disconnect:transport_loss:17:99:44", "TRANSPORT_LOSS");

    ArgumentCaptor<TriggerScriptEventRequest> captor =
        ArgumentCaptor.forClass(TriggerScriptEventRequest.class);
    verify(client).triggerScriptEvent(captor.capture());
    TriggerScriptEventRequest request = captor.getValue();
    assertThat(request.getEventType()).isEqualTo("onLeaveRegion");
    assertThat(request.getScriptEventId()).isEqualTo("disconnect:transport_loss:17:99:44");
    assertThat(request.getReadSnapshotToken())
        .isEqualTo("game-session:onLeaveRegion:99:7:disconnect:transport_loss:17:99:44");
    assertThat(request.getPayloadJson()).contains("\"fromRegionId\":\"room-a\"");
    assertThat(request.getPayloadJson()).contains("\"toRegionId\":\"\"");
    assertThat(request.getPayloadJson()).contains("\"exitReason\":\"TRANSPORT_LOSS\"");
  }

  private static GameplayCommand command(String commandId, String commandName) {
    GameplayCommand command = new GameplayCommand();
    command.setCommandId(commandId);
    command.setCommandName(commandName);
    command.setAcceptedAt(Instant.now());
    return command;
  }

  @Test
  void publishesAuthoredCommandEventWithResolvedCommandIdMetadata() {
    AutomationScriptingClient client = Mockito.mock(AutomationScriptingClient.class);
    RuntimeRegionStatusRepository statusRepository =
        Mockito.mock(RuntimeRegionStatusRepository.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setScriptPatchVersion("patch-1");
    RuntimeRegionStatus status = new RuntimeRegionStatus();
    status.setRegionId("region-99");
    status.setRegionEpoch(7L);
    when(gameInstanceRepository.findById(99L)).thenReturn(Optional.of(instance));
    when(statusRepository.findByTenantIdAndGameInstanceId(9L, 99L)).thenReturn(Optional.of(status));
    when(client.triggerScriptEvent(Mockito.any()))
        .thenReturn(TriggerScriptEventResponse.newBuilder().setAdmitted(true).build());
    TextCommandMetadataResolver metadataResolver =
        commandToken ->
            "wave".equals(commandToken)
                ? Optional.of(
                    new TextCommandMetadataResolver.ResolvedTextCommandMetadata(
                        TextCommandActionCategory.SOCIAL,
                        java.util.List.of(TextCommandActionTag.COMMUNICATION)))
                : Optional.empty();
    ScriptEventPublisher publisher =
        new AutomationScriptEventPublisher(
            client,
            statusRepository,
            gameInstanceRepository,
            metadataResolver,
            builtInAliasResolver(),
            Runnable::run);

    GameplayCommand command = command("authored-1", "wave");
    command.setCommandText("wave");
    command.setExecutionHook("runtime.workflow.wave");
    publisher.publishCommandEvent(sharedGameplayContext("room"), command);

    ArgumentCaptor<TriggerScriptEventRequest> captor =
        ArgumentCaptor.forClass(TriggerScriptEventRequest.class);
    verify(client).triggerScriptEvent(captor.capture());
    assertThat(captor.getValue().getPayloadJson()).contains("\"actionCategory\":\"SOCIAL\"");
    assertThat(captor.getValue().getPayloadJson()).contains("\"actionTags\":[\"COMMUNICATION\"]");
    assertThat(captor.getValue().getPayloadJson())
        .contains("\"executionHook\":\"runtime.workflow.wave\"");
  }

  private static SessionContext sharedGameplayContext(String roomId) {
    return new SessionContext(
        17L,
        9L,
        3L,
        "demo",
        44L,
        "char",
        99L,
        roomId,
        "jwt",
        null,
        99L,
        "demo",
        "production",
        7L,
        "SHARED");
  }

  private static BuiltInTextCommandAliasResolver builtInAliasResolver() {
    BuiltInTextCommandAliasResolver resolver = Mockito.mock(BuiltInTextCommandAliasResolver.class);
    when(resolver.resolve("l")).thenReturn(Optional.of("look"));
    when(resolver.resolve("LOOK")).thenReturn(Optional.of("look"));
    when(resolver.resolve("wave")).thenReturn(Optional.empty());
    return resolver;
  }
}
