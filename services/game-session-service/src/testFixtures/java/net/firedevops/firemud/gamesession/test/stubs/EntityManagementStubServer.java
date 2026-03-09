package net.firedevops.firemud.gamesession.test.stubs;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;

public final class EntityManagementStubServer implements AutoCloseable {
  private final Server server;
  private final int port;

  public EntityManagementStubServer(int port) throws IOException {
    this.port = port;
    this.server =
        ServerBuilder.forPort(port)
            .addService(
                new EntityManagementServiceGrpc.EntityManagementServiceImplBase() {
                  @Override
                  public void listRoomEntities(
                      ListRoomEntitiesRequest request,
                      StreamObserver<ListRoomEntitiesResponse> responseObserver) {
                    responseObserver.onNext(LookTestFixtures.sampleEntities());
                    responseObserver.onCompleted();
                  }
                })
            .build()
            .start();
  }

  public String endpoint() {
    return "localhost:" + port;
  }

  public int port() {
    return port;
  }

  @Override
  public void close() {
    if (server != null) {
      server.shutdownNow();
    }
  }
}
