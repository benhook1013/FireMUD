package net.firedevops.firemud.gamelogic.test.stubs;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import net.firedevops.firemud.gamelogic.test.LookTestFixtures;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotRequest;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;

public final class WorldManagementStubServer implements AutoCloseable {
  private final Server server;
  private final int port;

  public WorldManagementStubServer(int port) throws IOException {
    this.port = port;
    server =
        ServerBuilder.forPort(port)
            .addService(
                new WorldManagementServiceGrpc.WorldManagementServiceImplBase() {
                  @Override
                  public void getRoomSnapshot(
                      GetRoomSnapshotRequest request,
                      StreamObserver<GetRoomSnapshotResponse> responseObserver) {
                    responseObserver.onNext(
                        GetRoomSnapshotResponse.newBuilder()
                            .setSnapshot(LookTestFixtures.sampleRoomSnapshot())
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
