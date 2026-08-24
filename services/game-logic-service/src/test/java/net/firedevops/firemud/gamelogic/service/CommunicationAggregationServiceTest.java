package net.firedevops.firemud.gamelogic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.firedevops.firemud.common.config.FiremudCommandCapabilitiesProperties;
import net.firedevops.firemud.common.settings.EffectiveCommandCapabilitiesSettingsResolver;
import net.firedevops.firemud.common.settings.PlayerCommandCapability;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.common.settings.SharedSettingsAuthorityReader;
import net.firedevops.firemud.entitymanagement.v1.EntityType;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomEntity;
import net.firedevops.firemud.gamelogic.config.CommunicationProperties;
import net.firedevops.firemud.gamelogic.config.EffectiveCommunicationSettingsResolver;
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
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class CommunicationAggregationServiceTest {
  private static final String VALID_ACCOUNT_ID = "42";

  @Mock private SocialGroupsServiceGrpc.SocialGroupsServiceBlockingStub socialStub;

  @Mock
  private net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc
          .EntityManagementServiceBlockingStub
      entityStub;

  @Mock private SharedSettingsAuthorityReader sharedSettingsAuthorityReader;

  private MeterRegistry meterRegistry;
  private CommunicationAggregationService service;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    service = serviceWith(new CommunicationProperties(512, true), allCapabilitiesEnabled());
  }

  @Test
  void disabledSocialCapabilityReturnsApplicationErrorWithoutCallingDownstreamServices() {
    when(sharedSettingsAuthorityReader.readOverrides(1L, null))
        .thenReturn(ScopedSettingsSnapshot.empty());
    service =
        serviceWith(
            new CommunicationProperties(512, true),
            new FiremudCommandCapabilitiesProperties(false, true, true, true));

    SendCommunicationResponse resp =
        service.send(
            SendCommunicationRequest.newBuilder()
                .setTenantId("1")
                .setSessionId("sess-1")
                .setCharacterId("player-0")
                .setAccountId(VALID_ACCOUNT_ID)
                .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("R-7").build())
                .setType(CommunicationType.SAY)
                .setText("Hello travelers")
                .build());

    assertThat(resp.getSuccess()).isFalse();
    assertThat(resp.getError().getCode()).isEqualTo("FEATURE_UNAVAILABLE");
    assertThat(resp.getError().getMessage())
        .isEqualTo("Social commands are unavailable for this game");
    verify(entityStub, never()).listRoomEntities(any());
    verify(socialStub, never()).sendMessage(any());
  }

  @Test
  void unavailableSocialCapabilityAuthorityFailsClosedWithoutCallingDownstreamServices() {
    EffectiveCommandCapabilitiesSettingsResolver failingCapabilitiesResolver =
        mock(EffectiveCommandCapabilitiesSettingsResolver.class);
    when(failingCapabilitiesResolver.isEnabled(eq(PlayerCommandCapability.SOCIAL), eq(1L), eq(7L)))
        .thenThrow(new IllegalStateException("settings authority unavailable"));
    service =
        new CommunicationAggregationService(
            socialStub,
            entityStub,
            new EffectiveCommunicationSettingsResolver(
                new CommunicationProperties(512, true), sharedSettingsAuthorityReader),
            failingCapabilitiesResolver,
            meterRegistry);

    SendCommunicationResponse resp =
        service.send(
            SendCommunicationRequest.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("7")
                .setSessionId("sess-1")
                .setCharacterId("player-0")
                .setAccountId(VALID_ACCOUNT_ID)
                .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("R-7").build())
                .setType(CommunicationType.SAY)
                .setText("Hello travelers")
                .build());

    assertThat(resp.getSuccess()).isFalse();
    assertThat(resp.getError().getCode()).isEqualTo("FEATURE_UNAVAILABLE");
    verify(entityStub, never()).listRoomEntities(any());
    verify(socialStub, never()).sendMessage(any());
  }

  @Test
  void rejectsMissingMalformedAndNonPositiveAccountIdsBeforeDownstreamCalls() {
    for (String[] accountIdCase :
        new String[][] {
          {"empty", ""},
          {"whitespace-only", "   "},
          {"malformed", "not-a-number"},
          {"zero", "0"},
          {"negative", "-1"},
        }) {
      String accountIdCaseDescription = accountIdCase[0];
      String accountId = accountIdCase[1];
      SendCommunicationResponse resp =
          service.send(
              SendCommunicationRequest.newBuilder()
                  .setTenantId("1")
                  .setSessionId("sess-1")
                  .setCharacterId("player-0")
                  .setAccountId(accountId)
                  .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("R-7").build())
                  .setType(CommunicationType.SAY)
                  .setText("Hello travelers")
                  .build());

      assertThat(resp.getSuccess()).as(accountIdCaseDescription).isFalse();
      assertThat(resp.getError().getCode())
          .as(accountIdCaseDescription)
          .isEqualTo("INVALID_ARGUMENT");
      assertThat(resp.getError().getMessage())
          .as(accountIdCaseDescription)
          .isEqualTo("account_id must be a positive numeric account id");
    }

    verify(entityStub, never()).listRoomEntities(any());
    verify(socialStub, never()).sendMessage(any());
  }

  @Test
  void normalizesPaddedNoncanonicalAccountIdForSocialSender() {
    when(socialStub.sendMessage(any()))
        .thenReturn(SendMessageResponse.newBuilder().setSuccess(true).build());

    SendCommunicationResponse resp =
        service.send(
            SendCommunicationRequest.newBuilder()
                .setTenantId("tenant-1")
                .setSessionId("sess-1")
                .setCharacterId("player-0")
                .setAccountId(" 0042 ")
                .setType(CommunicationType.TELL)
                .setTargetCharacterId("player-9")
                .setTargetCharacterName("Sora")
                .setText("Meet me outside")
                .build());

    assertThat(resp.getSuccess()).isTrue();
    ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
    verify(socialStub).sendMessage(captor.capture());
    assertThat(captor.getValue().getSenderId()).isEqualTo("42");
  }

  @Test
  void rejectsLegacyRuntimeRoomIdsBeforeAudienceLookup() {
    SendCommunicationResponse resp =
        service.send(
            SendCommunicationRequest.newBuilder()
                .setTenantId("tenant-1")
                .setSessionId("sess-1")
                .setCharacterId("player-0")
                .setAccountId(VALID_ACCOUNT_ID)
                .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("room-7").build())
                .setType(CommunicationType.SAY)
                .setText("Hello travelers")
                .build());

    assertThat(resp.getSuccess()).isFalse();
    assertThat(resp.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(resp.getError().getMessage())
        .contains("room_instance.room_instance_id must be a runtime room id like R-1021");
    verify(entityStub, never()).listRoomEntities(any());
    verify(socialStub, never()).sendMessage(any());
  }

  @Test
  void gameInstanceOverrideCanDisableSocialCapability() {
    when(sharedSettingsAuthorityReader.readOverrides(1L, 7L))
        .thenReturn(
            new net.firedevops.firemud.common.settings.ScopedSettingsSnapshot(
                net.firedevops.firemud.common.settings.ScopedSettingsOverrides.empty(),
                new net.firedevops.firemud.common.settings.ScopedSettingsOverrides(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    new net.firedevops.firemud.common.settings.ScopedSettingsOverrides
                        .CommandCapabilitiesOverride(false, null, null, null))));

    SendCommunicationResponse resp =
        service.send(
            SendCommunicationRequest.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("7")
                .setSessionId("sess-1")
                .setCharacterId("player-0")
                .setAccountId(VALID_ACCOUNT_ID)
                .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("R-7").build())
                .setType(CommunicationType.SAY)
                .setText("Hello travelers")
                .build());

    assertThat(resp.getSuccess()).isFalse();
    assertThat(resp.getError().getCode()).isEqualTo("FEATURE_UNAVAILABLE");
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
                .setAccountId(VALID_ACCOUNT_ID)
                .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("R-7").build())
                .setType(CommunicationType.SAY)
                .setText("  Hello travelers  ")
                .build());

    assertThat(resp.getSuccess()).isTrue();
    assertThat(resp.getMessage()).isEqualTo("Hello travelers");
    assertThat(resp.getDeliveredToList()).containsExactly("Emberline", "Kobold Scout", "Sora");
    assertThat(resp.getNpcEchoesList()).containsExactly("Kobold Scout");
    assertThat(resp.getRecipientViewsList()).hasSize(2);
    assertThat(resp.getRecipientViewsList().get(0))
        .satisfies(
            view -> {
              assertThat(view.getRecipientId()).isEqualTo("player-0");
              assertThat(view.getRecipientName()).isEqualTo("Emberline");
              assertThat(view.getRole())
                  .isEqualTo(CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_ACTOR);
            });
    assertThat(resp.getRecipientViewsList().get(1))
        .satisfies(
            view -> {
              assertThat(view.getRecipientId()).isEqualTo("player-1");
              assertThat(view.getRecipientName()).isEqualTo("Sora");
              assertThat(view.getRole())
                  .isEqualTo(CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_TARGET);
              assertThat(view.getPerception())
                  .isEqualTo(CommunicationPerception.COMMUNICATION_PERCEPTION_FULL_CONTENT);
              assertThat(view.getSpeakerName()).isEqualTo("Emberline");
            });

    ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
    verify(socialStub).sendMessage(captor.capture());
    assertThat(captor.getValue().getContent()).isEqualTo("Hello travelers");
    assertThat(captor.getValue().getType()).isEqualTo(ChatType.CHAT_TYPE_SAY);
    assertThat(captor.getValue().getSenderId()).isEqualTo(VALID_ACCOUNT_ID);
  }

  @Test
  void forwardsEffectIdToSocialGroupsRequest() {
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
    when(socialStub.sendMessage(any()))
        .thenReturn(SendMessageResponse.newBuilder().setSuccess(true).build());

    SendCommunicationResponse resp =
        service.send(
            SendCommunicationRequest.newBuilder()
                .setTenantId("tenant-1")
                .setSessionId("sess-1")
                .setCharacterId("player-0")
                .setAccountId(VALID_ACCOUNT_ID)
                .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("R-7").build())
                .setType(CommunicationType.SAY)
                .setText("Hello travelers")
                .setEffectId("fx-comm-9")
                .build());

    assertThat(resp.getSuccess()).isTrue();
    ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
    verify(socialStub).sendMessage(captor.capture());
    assertThat(captor.getValue().getEffectId()).isEqualTo("fx-comm-9");
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
                .setAccountId(VALID_ACCOUNT_ID)
                .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("R-7").build())
                .setType(CommunicationType.WHISPER)
                .setTargetCharacterName("Sora")
                .setText("Keep quiet")
                .build());

    assertThat(resp.getSuccess()).isTrue();
    assertThat(resp.getSpeakerName()).isEqualTo("Emberline");
    assertThat(resp.getDeliveredToList()).containsExactly("Emberline", "Sora");
    assertThat(resp.getRecipientViewsList()).hasSize(2);
    assertThat(resp.getRecipientViewsList().get(0))
        .satisfies(
            view -> {
              assertThat(view.getRecipientId()).isEqualTo("player-0");
              assertThat(view.getRecipientName()).isEqualTo("Emberline");
              assertThat(view.getRole())
                  .isEqualTo(CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_ACTOR);
              assertThat(view.getSpeakerName()).isEqualTo("Emberline");
              assertThat(view.getTargetName()).isEqualTo("Sora");
            });
    assertThat(resp.getRecipientViewsList().get(1))
        .satisfies(
            view -> {
              assertThat(view.getRecipientId()).isEqualTo("player-1");
              assertThat(view.getRecipientName()).isEqualTo("Sora");
              assertThat(view.getRole())
                  .isEqualTo(CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_TARGET);
              assertThat(view.getSpeakerName()).isEqualTo("Emberline");
              assertThat(view.getTargetName()).isEqualTo("Sora");
            });

    ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
    verify(socialStub).sendMessage(captor.capture());
    assertThat(captor.getValue().getType()).isEqualTo(ChatType.CHAT_TYPE_WHISPER);
    assertThat(captor.getValue().getRecipientId()).isEqualTo("player-1");
  }

  @Test
  void sendAddsGameplayLoggingContext() {
    when(sharedSettingsAuthorityReader.readOverrides(1L, 7L))
        .thenReturn(
            new net.firedevops.firemud.common.settings.ScopedSettingsSnapshot(
                net.firedevops.firemud.common.settings.ScopedSettingsOverrides.empty(),
                net.firedevops.firemud.common.settings.ScopedSettingsOverrides.empty()));
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
    when(entityStub.listRoomEntities(any()))
        .thenAnswer(
            ignored -> {
              assertThat(MDC.get("tenantId")).isEqualTo("1");
              assertThat(MDC.get("gameInstanceId")).isEqualTo("7");
              assertThat(MDC.get("characterId")).isEqualTo("player-0");
              return roomEntities;
            });
    when(socialStub.sendMessage(any()))
        .thenAnswer(
            ignored -> {
              assertThat(MDC.get("tenantId")).isEqualTo("1");
              assertThat(MDC.get("gameInstanceId")).isEqualTo("7");
              assertThat(MDC.get("characterId")).isEqualTo("player-0");
              return SendMessageResponse.newBuilder().setSuccess(true).build();
            });

    service.send(
        SendCommunicationRequest.newBuilder()
            .setTenantId("1")
            .setGameInstanceId("7")
            .setSessionId("sess-1")
            .setCharacterId("player-0")
            .setAccountId(VALID_ACCOUNT_ID)
            .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("R-7").build())
            .setType(CommunicationType.WHISPER)
            .setTargetCharacterName("Sora")
            .setText("Keep quiet")
            .build());

    assertThat(MDC.get("tenantId")).isNull();
    assertThat(MDC.get("gameInstanceId")).isNull();
    assertThat(MDC.get("characterId")).isNull();
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
                .setAccountId(VALID_ACCOUNT_ID)
                .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("R-7").build())
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
              assertThat(view.getSpeakerName()).isEqualTo("Emberline");
              assertThat(view.getTargetName()).isEqualTo("Sora");
            });
  }

  @Test
  void whisperObserverMetadataCanBeDisabled() {
    service = serviceWith(new CommunicationProperties(512, false), allCapabilitiesEnabled());
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
                .setAccountId(VALID_ACCOUNT_ID)
                .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("R-7").build())
                .setType(CommunicationType.WHISPER)
                .setTargetCharacterName("Sora")
                .setText("Keep quiet")
                .build());

    assertThat(resp.getSuccess()).isTrue();
    assertThat(resp.getRecipientViewsList())
        .filteredOn(
            view ->
                view.getRole() == CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_OBSERVER)
        .isEmpty();
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
                .setAccountId(VALID_ACCOUNT_ID)
                .setSpeakerName("Emberline")
                .setType(CommunicationType.TELL)
                .setTargetCharacterId("player-9")
                .setTargetCharacterName("Sora")
                .setText("Meet me outside")
                .build());

    assertThat(resp.getSuccess()).isTrue();
    assertThat(resp.getSpeakerName()).isEqualTo("Emberline");
    assertThat(resp.getRecipientViewsList()).hasSize(2);
    assertThat(resp.getRecipientViewsList().get(0))
        .satisfies(
            view -> {
              assertThat(view.getRecipientId()).isEqualTo("player-0");
              assertThat(view.getRecipientName()).isEqualTo("Emberline");
              assertThat(view.getRole())
                  .isEqualTo(CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_ACTOR);
              assertThat(view.getSpeakerName()).isEqualTo("Emberline");
              assertThat(view.getTargetName()).isEqualTo("Sora");
            });
    assertThat(resp.getRecipientViewsList().get(1))
        .satisfies(
            view -> {
              assertThat(view.getRecipientId()).isEqualTo("player-9");
              assertThat(view.getRecipientName()).isEqualTo("Sora");
              assertThat(view.getRole())
                  .isEqualTo(CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_TARGET);
              assertThat(view.getSpeakerName()).isEqualTo("Emberline");
              assertThat(view.getTargetName()).isEqualTo("Sora");
            });

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
                .setAccountId(VALID_ACCOUNT_ID)
                .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("R-7").build())
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
                .setAccountId(VALID_ACCOUNT_ID)
                .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("R-7").build())
                .setType(CommunicationType.SAY)
                .setText("Hi")
                .build());

    assertThat(resp.getSuccess()).isFalse();
    assertThat(resp.getError()).isEqualTo(detail);
  }

  private CommunicationAggregationService serviceWith(
      CommunicationProperties communicationProperties,
      FiremudCommandCapabilitiesProperties commandCapabilitiesProperties) {
    return new CommunicationAggregationService(
        socialStub,
        entityStub,
        new EffectiveCommunicationSettingsResolver(
            communicationProperties, sharedSettingsAuthorityReader),
        new EffectiveCommandCapabilitiesSettingsResolver(
            commandCapabilitiesProperties, sharedSettingsAuthorityReader),
        meterRegistry);
  }

  private FiremudCommandCapabilitiesProperties allCapabilitiesEnabled() {
    return new FiremudCommandCapabilitiesProperties(true, true, true, true);
  }
}
