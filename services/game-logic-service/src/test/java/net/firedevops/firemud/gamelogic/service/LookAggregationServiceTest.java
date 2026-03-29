package net.firedevops.firemud.gamelogic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomEntity;
import net.firedevops.firemud.gamelogic.v1.LookExit;
import net.firedevops.firemud.gamelogic.v1.LookRequest;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotResponse;
import net.firedevops.firemud.worldmanagement.v1.RoomExitSnapshot;
import net.firedevops.firemud.worldmanagement.v1.RoomSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LookAggregationServiceTest {
  @Mock private WorldManagementServiceGrpc.WorldManagementServiceBlockingStub worldStub;
  @Mock private EntityManagementServiceGrpc.EntityManagementServiceBlockingStub entityStub;
  @Mock private LookResultRenderer renderer;

  @InjectMocks private LookAggregationService service;

  private RoomSnapshot snapshot;
  private ListRoomEntitiesResponse entityResponse;
  private LookRequest request;

  @BeforeEach
  void setUp() {
    RoomSnapshot.Builder snapshotBuilder =
        RoomSnapshot.newBuilder()
            .setRoomInstanceId("1021")
            .setRoomName("Candle-lit Antechamber")
            .setShortDescription("short desc")
            .setLongDescription("long desc")
            .addExits(
                RoomExitSnapshot.newBuilder()
                    .setDirection("NORTH")
                    .setLabel("NORTH")
                    .setTargetRoomInstanceId("2045")
                    .setDescription("arch")
                    .build())
            .addRoomFlags("isQuestArea");
    applyLegacyAmbientState(snapshotBuilder);
    snapshot = snapshotBuilder.build();
    RoomEntity entity =
        RoomEntity.newBuilder()
            .setEntityId("NPC-1")
            .setDisplayName("Kobold")
            .setEntityType(net.firedevops.firemud.entitymanagement.v1.EntityType.NPC)
            .setRole("guard")
            .addStateFlags("isAlert")
            .build();
    entityResponse = ListRoomEntitiesResponse.newBuilder().addEntities(entity).build();
    when(worldStub.getRoomSnapshot(any()))
        .thenReturn(GetRoomSnapshotResponse.newBuilder().setSnapshot(snapshot).build());
    when(entityStub.listRoomEntities(any())).thenReturn(entityResponse);
    lenient().when(renderer.render(any())).thenReturn("rendered");
    request =
        LookRequest.newBuilder()
            .setTenantId("tenant-1")
            .setSessionId("session-1")
            .setCharacterId("player-1")
            .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("1021").build())
            .build();
  }

  @Test
  void resolvesLookResultFromSnapshotAndEntities() {
    LookResult result = service.resolve(request);
    assertThat(result.getRoomInstance().getRoomInstanceId()).isEqualTo("1021");
    assertThat(result.getRoomName()).isEqualTo("Candle-lit Antechamber");
    assertThat(result.getShortDescription()).isEqualTo("short desc");
    assertThat(result.getExitsList())
        .extracting(LookExit::getTargetRoomInstanceId)
        .containsExactly("2045");
    assertThat(result.getEntitiesList()).hasSize(1);
    assertThat(result.getEntitiesList().get(0).getDisplayName()).isEqualTo("Kobold");
    assertThat(result.getAmbientState().getWeather()).isEqualTo("dim");
  }

  @Test
  void propagatesWorldFailure() {
    StatusRuntimeException failure =
        new StatusRuntimeException(Status.UNAVAILABLE.withDescription("down"));
    when(worldStub.getRoomSnapshot(any())).thenThrow(failure);

    assertThatThrownBy(() -> service.resolve(request))
        .isInstanceOf(StatusRuntimeException.class)
        .hasMessageContaining("WorldManagement");
  }

  @Test
  void propagatesWorldErrorDetailResponse() {
    when(worldStub.getRoomSnapshot(any()))
        .thenReturn(
            GetRoomSnapshotResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("NOT_FOUND")
                        .setMessage("room missing")
                        .build())
                .build());

    assertThatThrownBy(() -> service.resolve(request))
        .isInstanceOf(StatusRuntimeException.class)
        .hasMessageContaining("WorldManagement")
        .hasMessageContaining("room missing");
  }

  private static void applyLegacyAmbientState(RoomSnapshot.Builder builder) {
    builder.setAmbientState(
        net.firedevops.firemud.worldmanagement.v1.RoomAmbientState.newBuilder()
            .setSchemaVersion(1)
            .setWeather("dim")
            .build());
  }
}
