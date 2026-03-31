package net.firedevops.firemud.gamelogic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.firedevops.firemud.entitymanagement.v1.EntityType;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomEntity;
import net.firedevops.firemud.gamelogic.v1.CommunicationPerception;
import net.firedevops.firemud.gamelogic.v1.CommunicationRecipientRole;
import net.firedevops.firemud.gamelogic.v1.CommunicationType;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationRequest;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.socialgroups.v1.ChatType;
import net.firedevops.firemud.socialgroups.v1.SendMessageRequest;
import net.firedevops.firemud.socialgroups.v1.SendMessageResponse;
import net.firedevops.firemud.socialgroups.v1.SocialGroupsServiceGrpc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CommunicationAggregationServiceTest {
  @Mock private SocialGroupsServiceGrpc.SocialGroupsServiceBlockingStub socialStub;

  @Mock
  private net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc
          .EntityManagementServiceBlockingStub
      entityStub;

  private MeterRegistry meterRegistry;
  private CommunicationAggregationService service;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    service = new CommunicationAggregationService(socialStub, entityStub, meterRegistry);
  }

  @Test
  void sayIncludesDeliveryMetadata() {
    ListRoomEntitiesResponse roomEntities =
        ListRoomEntitiesResponse.newBuilder()
            .addEntities(
                RoomEntity.newBuilder()
                    .setEntityId("player-0")
                    .setDisplayName("Emberline")
                    .setEntityType(EntityType.PLAYER)
                    .build())
            .addEntities(
                RoomEntity.newBuilder()
                    .setEntityId("player-1")
                    .setDisplayName("Sora")
                    .setEntityType(EntityType.PLAYER)
                    .build())
            .addEntities(
                RoomEntity.newBuilder()
                    .setEntityId("npc-1")
                    .setDisplayName("Kobold Scout")
                    .setEntityType(EntityType.NPC)
                    .build())
            .build();
    when(entityStub.listRoomEntities(any())).thenReturn(roomEntities);
    when(socialStub.sendMessage(any()))
        .thenReturn(SendMessageResponse.newBuilder().setSuccess(true).build());

    SendCommunicationResponse resp =
        service.send(
            SendCommunicationRequest.newBuilder()
                .setTenantId("tenant-1")
                .setSessionId("sess-1")
                .setCharacterId("player-0")
                .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("room-7").build())
                .setType(CommunicationType.SAY)
                .setText("  Hello travelers  ")
                .build());

    assertThat(resp.getSuccess()).isTrue();
    assertThat(resp.getMessage()).isEqualTo("Hello travelers");
    assertThat(resp.getDeliveredToList()).containsExactly("Emberline", "Kobold Scout", "Sora");
    assertThat(resp.getNpcEchoesList()).containsExactly("Kobold Scout");
    assertThat(resp.getRecipientViewsList()).hasSize(1);
    assertThat(resp.getRecipientViews(0).getRole())
        .isEqualTo(CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_ACTOR);

    ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
    verify(socialStub).sendMessage(captor.capture());
    assertThat(captor.getValue().getContent()).isEqualTo("Hello travelers");
    assertThat(captor.getValue().getType()).isEqualTo(ChatType.CHAT_TYPE_SAY);
  }

  @Test
  void whisperTargetsSingleRoomPlayer() {
    ListRoomEntitiesResponse roomEntities =
        ListRoomEntitiesResponse.newBuilder()
            .addEntities(
                RoomEntity.newBuilder()
                    .setEntityId("player-0")
                    .setDisplayName("Emberline")
                    .setEntityType(EntityType.PLAYER)
                    .build())
            .addEntities(
                RoomEntity.newBuilder()
                    .setEntityId("player-1")
                    .setDisplayName("Sora")
                    .setEntityType(EntityType.PLAYER)
                    .build())
            .build();
    when(entityStub.listRoomEntities(any())).thenReturn(roomEntities);
    when(socialStub.sendMessage(any()))
        .thenReturn(SendMessageResponse.newBuilder().setSuccess(true).build());

    SendCommunicationResponse resp =
        service.send(
            SendCommunicationRequest.newBuilder()
                .setTenantId("tenant-1")
                .setSessionId("sess-1")
                .setCharacterId("player-0")
                .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("room-7").build())
                .setType(CommunicationType.WHISPER)
                .setTargetCharacterName("Sora")
                .setText("Keep quiet")
                .build());

    assertThat(resp.getSuccess()).isTrue();
    assertThat(resp.getDeliveredToList()).containsExactly("Emberline", "Sora");
    assertThat(resp.getRecipientViewsList())
        .extracting(view -> view.getRenderedText())
        .containsExactly(
            "You whisper to Sora, \"Keep quiet\"", "Emberline whispers to you, \"Keep quiet\"");

    ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
    verify(socialStub).sendMessage(captor.capture());
    assertThat(captor.getValue().getType()).isEqualTo(ChatType.CHAT_TYPE_WHISPER);
    assertThat(captor.getValue().getRecipientId()).isEqualTo("player-1");
  }

  @Test
  void whisperAddsMetadataOnlyObserverViewWhenFlagged() {
    ListRoomEntitiesResponse roomEntities =
        ListRoomEntitiesResponse.newBuilder()
            .addEntities(
                RoomEntity.newBuilder()
                    .setEntityId("player-0")
                    .setDisplayName("Emberline")
                    .setEntityType(EntityType.PLAYER)
                    .build())
            .addEntities(
                RoomEntity.newBuilder()
                    .setEntityId("player-1")
                    .setDisplayName("Sora")
                    .setEntityType(EntityType.PLAYER)
                    .build())
            .addEntities(
                RoomEntity.newBuilder()
                    .setEntityId("player-2")
                    .setDisplayName("Nyx")
                    .setEntityType(EntityType.PLAYER)
                    .addStateFlags("observer_metadata_only")
                    .build())
            .addEntities(
                RoomEntity.newBuilder()
                    .setEntityId("player-3")
                    .setDisplayName("Bystander")
                    .setEntityType(EntityType.PLAYER)
                    .build())
            .build();
    when(entityStub.listRoomEntities(any())).thenReturn(roomEntities);
    when(socialStub.sendMessage(any()))
        .thenReturn(SendMessageResponse.newBuilder().setSuccess(true).build());

    SendCommunicationResponse resp =
        service.send(
            SendCommunicationRequest.newBuilder()
                .setTenantId("tenant-1")
                .setSessionId("sess-1")
                .setCharacterId("player-0")
                .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("room-7").build())
                .setType(CommunicationType.WHISPER)
                .setTargetCharacterName("Sora")
                .setText("Keep quiet")
                .build());

    assertThat(resp.getSuccess()).isTrue();
    assertThat(resp.getRecipientViewsList()).hasSize(3);
    assertThat(resp.getRecipientViewsList())
        .filteredOn(
            view ->
                view.getRole() == CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_OBSERVER)
        .singleElement()
        .satisfies(
            view -> {
              assertThat(view.getRecipientId()).isEqualTo("player-2");
              assertThat(view.getRecipientName()).isEqualTo("Nyx");
              assertThat(view.getPerception())
                  .isEqualTo(CommunicationPerception.COMMUNICATION_PERCEPTION_METADATA_ONLY);
              assertThat(view.getRenderedText()).isEqualTo("Emberline whispers something to Sora.");
            });
  }

  @Test
  void tellUsesDirectRecipientId() {
    when(socialStub.sendMessage(any()))
        .thenReturn(SendMessageResponse.newBuilder().setSuccess(true).build());

    SendCommunicationResponse resp =
        service.send(
            SendCommunicationRequest.newBuilder()
                .setTenantId("tenant-1")
                .setSessionId("sess-1")
                .setCharacterId("player-0")
                .setSpeakerName("Emberline")
                .setType(CommunicationType.TELL)
                .setTargetCharacterId("player-9")
                .setTargetCharacterName("Sora")
                .setText("Meet me outside")
                .build());

    assertThat(resp.getSuccess()).isTrue();
    assertThat(resp.getRecipientViewsList())
        .extracting(view -> view.getRenderedText())
        .containsExactly(
            "You tell Sora, \"Meet me outside\"", "Emberline tells you, \"Meet me outside\"");

    ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
    verify(socialStub).sendMessage(captor.capture());
    assertThat(captor.getValue().getType()).isEqualTo(ChatType.CHAT_TYPE_TELL);
    assertThat(captor.getValue().getRecipientId()).isEqualTo("player-9");
  }

  @Test
  void rejectsEmptyMessages() {
    SendCommunicationResponse resp =
        service.send(
            SendCommunicationRequest.newBuilder()
                .setTenantId("tenant-1")
                .setSessionId("sess-1")
                .setCharacterId("player-0")
                .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("room-7").build())
                .setType(CommunicationType.SAY)
                .setText("   ")
                .build());

    assertThat(resp.getSuccess()).isFalse();
    assertThat(resp.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
  }

  @Test
  void propagatesSocialErrors() {
    ListRoomEntitiesResponse roomEntities =
        ListRoomEntitiesResponse.newBuilder()
            .addEntities(
                RoomEntity.newBuilder()
                    .setEntityId("player-0")
                    .setDisplayName("Emberline")
                    .setEntityType(EntityType.PLAYER)
                    .build())
            .build();
    when(entityStub.listRoomEntities(any())).thenReturn(roomEntities);
    ErrorDetail detail =
        ErrorDetail.newBuilder().setCode("PERMISSION_DENIED").setMessage("silenced").build();
    when(socialStub.sendMessage(any()))
        .thenReturn(SendMessageResponse.newBuilder().setSuccess(false).setError(detail).build());

    SendCommunicationResponse resp =
        service.send(
            SendCommunicationRequest.newBuilder()
                .setTenantId("tenant-1")
                .setSessionId("sess-1")
                .setCharacterId("player-0")
                .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("room-7").build())
                .setType(CommunicationType.SAY)
                .setText("Hi")
                .build());

    assertThat(resp.getSuccess()).isFalse();
    assertThat(resp.getError()).isEqualTo(detail);
  }
}
