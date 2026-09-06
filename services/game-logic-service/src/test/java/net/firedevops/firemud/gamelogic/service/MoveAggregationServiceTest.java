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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class MoveAggregationServiceTest {
  @Mock private WorldManagementServiceGrpc.WorldManagementServiceBlockingStub worldStub;

  private MoveAggregationService service;

  @BeforeEach
  void setUp() {
    service = new MoveAggregationService(worldStub, new SimpleMeterRegistry());
  }

  @Test
  void resolveReturnsDestinationRoomForMatchingDirection() {
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
    assertThat(result.getDestinationRoomInstance().getRoomInstanceId()).isEqualTo("R-2045");
    assertThat(result.getDestinationRoomInstance().getTenantId())
        .isEqualTo(LookTestFixtures.TENANT);
    assertThat(result.getDestinationRoomInstance().getGameInstanceId())
        .isEqualTo(LookTestFixtures.GAME_INSTANCE_ID);
    verify(worldStub).getRoomSnapshot(any());
  }

  @Test
  void resolveRejectsLegacyRuntimeRoomIdsBeforeWorldLookup() {
    MoveResult result =
        service.resolve(
            MoveRequest.newBuilder()
                .setTenantId(LookTestFixtures.TENANT)
                .setSessionId("session-1")
                .setCharacterId("player-1")
                .setRoomInstance(
                    RoomInstanceRef.newBuilder()
                        .setTenantId(LookTestFixtures.TENANT)
                        .setRoomInstanceId("room-1021")
                        .build())
                .setDirection("east")
                .build());

    assertThat(result.getSuccess()).isFalse();
    assertThat(result.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.getError().getMessage())
        .contains("room_instance.room_instance_id must be a runtime room id like R-1021");
    verify(worldStub, never()).getRoomSnapshot(any());
  }

  @Test
  void resolveFallsBackToRequestGameInstanceIdWhenSnapshotOmitsIt() {
    RoomSnapshot snapshot =
        RoomSnapshot.newBuilder()
            .setTenantId("")
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
                .setPreferredLocale("en-NZ")
                .setRoomInstance(
                    RoomInstanceRef.newBuilder()
                        .setTenantId(LookTestFixtures.TENANT)
                        .setGameInstanceId(LookTestFixtures.GAME_INSTANCE_ID)
                        .setRoomInstanceId(LookTestFixtures.ROOM_INSTANCE_ID)
                        .build())
                .setDirection("north")
                .build());

    assertThat(result.getSuccess()).isTrue();
    assertThat(result.getDestinationRoomInstance().getTenantId())
        .isEqualTo(LookTestFixtures.TENANT);
    assertThat(result.getDestinationRoomInstance().getGameInstanceId())
        .isEqualTo(LookTestFixtures.GAME_INSTANCE_ID);
    assertThat(result.getDestinationRoomInstance().getRoomInstanceId()).isEqualTo("R-3042");
  }

  @Test
  void resolvePrefersNonBlankSnapshotTenantOverRequestTenant() {
    RoomSnapshot snapshot =
        RoomSnapshot.newBuilder()
            .setTenantId("snapshot-tenant")
            .setGameInstanceId(LookTestFixtures.GAME_INSTANCE_ID)
            .setRoomInstanceId(LookTestFixtures.ROOM_INSTANCE_ID)
            .addExits(
                RoomExitSnapshot.newBuilder()
                    .setDirection("NORTH")
                    .setLabel("NORTH")
                    .setTargetRoomInstanceId("R-3042")
                    .build())
            .build();
    when(worldStub.getRoomSnapshot(any()))
        .thenReturn(GetRoomSnapshotResponse.newBuilder().setSnapshot(snapshot).build());

    MoveResult result =
        service.resolve(
            MoveRequest.newBuilder()
                .setTenantId("request-tenant")
                .setSessionId("session-1")
                .setCharacterId("player-1")
                .setRoomInstance(
                    RoomInstanceRef.newBuilder()
                        .setTenantId("request-tenant")
                        .setGameInstanceId(LookTestFixtures.GAME_INSTANCE_ID)
                        .setRoomInstanceId(LookTestFixtures.ROOM_INSTANCE_ID)
                        .build())
                .setDirection("north")
                .build());

    assertThat(result.getSuccess()).isTrue();
    assertThat(result.getDestinationRoomInstance().getTenantId()).isEqualTo("snapshot-tenant");
  }

  @Test
  void resolveRejectsNoncanonicalWorldDestinationAsWorldFailure() {
    RoomSnapshot snapshot =
        RoomSnapshot.newBuilder()
            .setTenantId(LookTestFixtures.TENANT)
            .setGameInstanceId(LookTestFixtures.GAME_INSTANCE_ID)
            .setRoomInstanceId(LookTestFixtures.ROOM_INSTANCE_ID)
            .addExits(
                RoomExitSnapshot.newBuilder()
                    .setDirection("NORTH")
                    .setLabel("NORTH")
                    .setTargetRoomInstanceId("room-3042")
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
                .setRoomInstance(
                    RoomInstanceRef.newBuilder()
                        .setTenantId(LookTestFixtures.TENANT)
                        .setRoomInstanceId(LookTestFixtures.ROOM_INSTANCE_ID)
                        .build())
                .setDirection("north")
                .build());

    assertThat(result.getSuccess()).isFalse();
    assertThat(result.getError().getCode()).isEqualTo("WORLD_UNAVAILABLE");
    assertThat(result.getError().getMessage()).contains("WorldManagementService");
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
