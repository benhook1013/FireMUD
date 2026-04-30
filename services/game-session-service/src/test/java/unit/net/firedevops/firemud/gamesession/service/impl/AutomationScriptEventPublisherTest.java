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
    ScriptEventPublisher publisher =
        new AutomationScriptEventPublisher(
            client, statusRepository, gameInstanceRepository, Runnable::run);

    publisher.publishCommandEvent(sharedGameplayContext("room"), command("cmd-1", "LOOK"));

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
    assertThat(request.getPayloadJson()).contains("\"commandName\":\"LOOK\"");
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
            client, statusRepository, gameInstanceRepository, Runnable::run);

    publisher.publishCommandEvent(sharedGameplayContext("room"), command("cmd-1", "LOOK"));

    verify(client, never()).triggerScriptEvent(Mockito.any());
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
            client, statusRepository, gameInstanceRepository, Runnable::run);

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
            client, statusRepository, gameInstanceRepository, Runnable::run);

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

  private static GameplayCommand command(String commandId, String commandName) {
    GameplayCommand command = new GameplayCommand();
    command.setCommandId(commandId);
    command.setCommandName(commandName);
    command.setAcceptedAt(Instant.now());
    return command;
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
}
