package net.firedevops.firemud.gamesession.test.stubs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotRequest;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.TestSocketUtils;

class WorldManagementStubServerTest {

  @Test
  void returnsRoomSnapshotForRequestedRoomInstanceId() throws Exception {
    int port = TestSocketUtils.findAvailableTcpPort();
    try (WorldManagementStubServer server = new WorldManagementStubServer(port)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
      try {
        WorldManagementServiceGrpc.WorldManagementServiceBlockingStub stub =
            WorldManagementServiceGrpc.newBlockingStub(channel);

        GetRoomSnapshotResponse source =
            stub.getRoomSnapshot(
                GetRoomSnapshotRequest.newBuilder()
                    .setTenantId(LookTestFixtures.TENANT)
                    .setRoomInstance(
                        RoomInstanceRef.newBuilder()
                            .setTenantId(LookTestFixtures.TENANT)
                            .setRoomInstanceId(LookTestFixtures.ROOM_INSTANCE_ID)
                            .build())
                    .build());
        assertEquals(LookTestFixtures.ROOM_NAME, source.getSnapshot().getRoomName());
        assertEquals("NORTH", source.getSnapshot().getExits(0).getDirection());

        GetRoomSnapshotResponse destination =
            stub.getRoomSnapshot(
                GetRoomSnapshotRequest.newBuilder()
                    .setTenantId(LookTestFixtures.TENANT)
                    .setRoomInstance(
                        RoomInstanceRef.newBuilder()
                            .setTenantId(LookTestFixtures.TENANT)
                            .setRoomInstanceId(LookTestFixtures.DESTINATION_ROOM_ID)
                            .build())
                    .build());
        assertEquals(
            LookTestFixtures.DESTINATION_ROOM_NAME, destination.getSnapshot().getRoomName());
        assertEquals("SOUTH", destination.getSnapshot().getExits(0).getDirection());
      } finally {
        channel.shutdownNow();
      }
    }
  }

  @Test
  void returnsRoomSnapshotInTheRequestedRuntimeScope() throws Exception {
    int port = TestSocketUtils.findAvailableTcpPort();
    try (WorldManagementStubServer server = new WorldManagementStubServer(port)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
      try {
        WorldManagementServiceGrpc.WorldManagementServiceBlockingStub stub =
            WorldManagementServiceGrpc.newBlockingStub(channel);
        RoomInstanceRef requestedRoom =
            RoomInstanceRef.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("42")
                .setRoomInstanceId(LookTestFixtures.ROOM_INSTANCE_ID)
                .build();

        GetRoomSnapshotResponse response =
            stub.getRoomSnapshot(
                GetRoomSnapshotRequest.newBuilder()
                    .setTenantId("1")
                    .setRoomInstance(requestedRoom)
                    .build());

        assertEquals("1", response.getSnapshot().getTenantId());
        assertEquals("42", response.getSnapshot().getGameInstanceId());
        assertEquals(LookTestFixtures.ROOM_INSTANCE_ID, response.getSnapshot().getRoomInstanceId());
      } finally {
        channel.shutdownNow();
      }
    }
  }

  @Test
  void unknownRoomInstanceIdReturnsNotFound() throws Exception {
    int port = TestSocketUtils.findAvailableTcpPort();
    try (WorldManagementStubServer server = new WorldManagementStubServer(port)) {
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
      try {
        WorldManagementServiceGrpc.WorldManagementServiceBlockingStub stub =
            WorldManagementServiceGrpc.newBlockingStub(channel);

        assertThrows(
            StatusRuntimeException.class,
            () ->
                stub.getRoomSnapshot(
                    GetRoomSnapshotRequest.newBuilder()
                        .setTenantId(LookTestFixtures.TENANT)
                        .setRoomInstance(
                            RoomInstanceRef.newBuilder().setRoomInstanceId("unknown-room").build())
                        .build()));
      } finally {
        channel.shutdownNow();
      }
    }
  }
}
