package net.firedevops.firemud.gamelogic.test.stubs;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.gamelogic.test.LookTestFixtures;

public final class EntityManagementStubServer implements AutoCloseable {
  private final Server server;
  private final int port;

  public EntityManagementStubServer(int port) throws IOException {
    this.port = port;
    server =
        ServerBuilder.forPort(port)
            .addService(
                new EntityManagementServiceGrpc.EntityManagementServiceImplBase() {
                  @Override
                  public void listRoomEntities(
                      ListRoomEntitiesRequest request,
                      StreamObserver<ListRoomEntitiesResponse> responseObserver) {
                    responseObserver.onNext(
                        ListRoomEntitiesResponse.newBuilder()
                            .setTenantId(LookTestFixtures.TENANT)
                            .setGameInstanceId(LookTestFixtures.GAME_INSTANCE_ID)
                            .setRoomInstanceId(LookTestFixtures.ROOM_INSTANCE_ID)
                            .setEntitySnapshotId(
                                LookTestFixtures.TENANT
                                    + ":"
                                    + LookTestFixtures.GAME_INSTANCE_ID
                                    + ":"
                                    + LookTestFixtures.ROOM_INSTANCE_ID)
                            .addAllEntities(LookTestFixtures.sampleEntities().getEntitiesList())
                            .build());
                    responseObserver.onCompleted();
                  }
                })
            .build()
            .start();
  }

  public String endpoint() {
    return "localhost:" + port;
  }

  @Override
  public void close() {
    if (server != null) {
      server.shutdown();
    }
  }
}
