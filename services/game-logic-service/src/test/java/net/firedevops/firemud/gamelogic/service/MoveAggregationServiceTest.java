package net.firedevops.firemud.gamelogic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.firedevops.firemud.gamelogic.test.LookTestFixtures;
import net.firedevops.firemud.gamelogic.v1.LookRequest;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamelogic.v1.MoveRequest;
import net.firedevops.firemud.gamelogic.v1.MoveResult;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotResponse;
import net.firedevops.firemud.worldmanagement.v1.RoomExitSnapshot;
import net.firedevops.firemud.worldmanagement.v1.RoomSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class MoveAggregationServiceTest {
  @Mock private WorldManagementServiceGrpc.WorldManagementServiceBlockingStub worldStub;
  @Mock private LookAggregationService lookAggregationService;

  private MoveAggregationService service;

  @BeforeEach
  void setUp() {
    service =
        new MoveAggregationService(worldStub, lookAggregationService, new SimpleMeterRegistry());
  }

  @Test
  void resolveReturnsDestinationLookForMatchingDirection() {
    RoomSnapshot snapshot =
        RoomSnapshot.newBuilder()
            .setTenantId(LookTestFixtures.TENANT)
            .setGameInstanceId(LookTestFixtures.GAME_INSTANCE_ID)
            .setRoomInstanceId(LookTestFixtures.ROOM_INSTANCE_ID)
            .setRoomName(LookTestFixtures.ROOM_NAME)
            .setShortDescription("start short")
            .setLongDescription("start long")
            .addExits(
                RoomExitSnapshot.newBuilder()
                    .setDirection("NORTH")
                    .setLabel("NORTH")
                    .setTargetRoomInstanceId("R-3042")
                    .setDescription("arched passage")
                    .build())
            .addExits(
                RoomExitSnapshot.newBuilder()
                    .setDirection("EAST")
                    .setLabel("EAST")
                    .setTargetRoomInstanceId("R-2045")
                    .setDescription("narrow fissure")
                    .build())
            .build();
    when(worldStub.getRoomSnapshot(any()))
        .thenReturn(GetRoomSnapshotResponse.newBuilder().setSnapshot(snapshot).build());
    LookResult destination =
        LookTestFixtures.sampleLookResult().toBuilder()
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(LookTestFixtures.TENANT)
                    .setGameInstanceId(LookTestFixtures.GAME_INSTANCE_ID)
                    .setRoomInstanceId("R-2045")
                    .build())
            .build();
    when(lookAggregationService.resolve(any())).thenReturn(destination);

    MoveResult result =
        service.resolve(
            MoveRequest.newBuilder()
                .setTenantId(LookTestFixtures.TENANT)
                .setSessionId("session-1")
                .setCharacterId("player-1")
                .setPreferredLocale("fr")
                .setRoomInstance(
                    RoomInstanceRef.newBuilder()
                        .setTenantId(LookTestFixtures.TENANT)
                        .setRoomInstanceId(LookTestFixtures.ROOM_INSTANCE_ID)
                        .build())
                .setDirection("east")
                .build());

    assertThat(result.getSuccess()).isTrue();
    assertThat(result.getDestinationLook().getRoomInstance().getRoomInstanceId())
        .isEqualTo("R-2045");

    ArgumentCaptor<LookRequest> lookRequestCaptor = ArgumentCaptor.forClass(LookRequest.class);
    verify(lookAggregationService).resolve(lookRequestCaptor.capture());
    assertThat(lookRequestCaptor.getValue().getRoomInstance().getRoomInstanceId())
        .isEqualTo("R-2045");
    assertThat(lookRequestCaptor.getValue().getPreferredLocale()).isEqualTo("fr");
    verify(worldStub).getRoomSnapshot(any());
  }

  @Test
  void resolveRejectsMissingExitDirection() {
    RoomSnapshot snapshot =
        RoomSnapshot.newBuilder()
            .setTenantId(LookTestFixtures.TENANT)
            .setGameInstanceId(LookTestFixtures.GAME_INSTANCE_ID)
            .setRoomInstanceId(LookTestFixtures.ROOM_INSTANCE_ID)
            .setRoomName(LookTestFixtures.ROOM_NAME)
            .addExits(
                RoomExitSnapshot.newBuilder()
                    .setDirection("NORTH")
                    .setLabel("NORTH")
                    .setTargetRoomInstanceId("R-3042")
                    .setDescription("arched passage")
                    .build())
            .build();
    when(worldStub.getRoomSnapshot(any()))
        .thenReturn(GetRoomSnapshotResponse.newBuilder().setSnapshot(snapshot).build());

    MoveResult result =
        service.resolve(
            MoveRequest.newBuilder()
                .setTenantId(LookTestFixtures.TENANT)
                .setSessionId("session-1")
                .setCharacterId("player-1")
                .setPreferredLocale("fr")
                .setRoomInstance(
                    RoomInstanceRef.newBuilder()
                        .setTenantId(LookTestFixtures.TENANT)
                        .setRoomInstanceId(LookTestFixtures.ROOM_INSTANCE_ID)
                        .build())
                .setDirection("east")
                .build());

    assertThat(result.getSuccess()).isFalse();
    assertThat(result.getError().getCode()).isEqualTo("INVALID_EXIT");
    verify(lookAggregationService, never()).resolve(any());
  }

  @Test
  void resolveConvertsWorldFailureToAppError() {
    when(worldStub.getRoomSnapshot(any()))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("down")));

    MoveResult result =
        service.resolve(
            MoveRequest.newBuilder()
                .setTenantId(LookTestFixtures.TENANT)
                .setSessionId("session-1")
                .setCharacterId("player-1")
                .setPreferredLocale("fr")
                .setRoomInstance(
                    RoomInstanceRef.newBuilder()
                        .setTenantId(LookTestFixtures.TENANT)
                        .setRoomInstanceId(LookTestFixtures.ROOM_INSTANCE_ID)
                        .build())
                .setDirection("east")
                .build());

    assertThat(result.getSuccess()).isFalse();
    assertThat(result.getError().getCode()).isEqualTo("WORLD_UNAVAILABLE");
    assertThat(result.getError().getMessage()).contains("WorldManagementService");
  }

  @Test
  void resolveAddsGameplayLoggingContext() {
    when(worldStub.getRoomSnapshot(any()))
        .thenAnswer(
            ignored -> {
              assertThat(MDC.get("tenantId")).isEqualTo(LookTestFixtures.TENANT);
              assertThat(MDC.get("characterId")).isEqualTo("player-1");
              assertThat(MDC.get("gameInstanceId")).isNull();
              return GetRoomSnapshotResponse.newBuilder()
                  .setSnapshot(
                      RoomSnapshot.newBuilder()
                          .setTenantId(LookTestFixtures.TENANT)
                          .setGameInstanceId(LookTestFixtures.GAME_INSTANCE_ID)
                          .setRoomInstanceId(LookTestFixtures.ROOM_INSTANCE_ID)
                          .setRoomName(LookTestFixtures.ROOM_NAME)
                          .addExits(
                              RoomExitSnapshot.newBuilder()
                                  .setDirection("EAST")
                                  .setLabel("EAST")
                                  .setTargetRoomInstanceId("R-2045")
                                  .build())
                          .build())
                  .build();
            });
    when(lookAggregationService.resolve(any()))
        .thenAnswer(
            ignored -> {
              assertThat(MDC.get("tenantId")).isEqualTo(LookTestFixtures.TENANT);
              assertThat(MDC.get("characterId")).isEqualTo("player-1");
              assertThat(MDC.get("gameInstanceId")).isNull();
              return LookTestFixtures.sampleLookResult();
            });

    service.resolve(
        MoveRequest.newBuilder()
            .setTenantId(LookTestFixtures.TENANT)
            .setSessionId("session-1")
            .setCharacterId("player-1")
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setRoomInstanceId(LookTestFixtures.ROOM_INSTANCE_ID)
                    .build())
            .setDirection("east")
            .build());

    assertThat(MDC.get("tenantId")).isNull();
    assertThat(MDC.get("characterId")).isNull();
    assertThat(MDC.get("gameInstanceId")).isNull();
  }
}
