package net.firedevops.firemud.gamesession.test.stubs;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.FindCharacterByNameRequest;
import net.firedevops.firemud.entitymanagement.v1.FindCharacterByNameResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.gamesession.test.ChatTestFixtures;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;

public final class EntityManagementStubServer implements AutoCloseable {
  private final Server server;
  private final int port;
  private final AtomicReference<ListRoomEntitiesResponse> roomEntities =
      new AtomicReference<>(LookTestFixtures.sampleEntities());

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
                    responseObserver.onNext(roomEntities.get());
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void findCharacterByName(
                      FindCharacterByNameRequest request,
                      StreamObserver<FindCharacterByNameResponse> responseObserver) {
                    FindCharacterByNameResponse.Builder builder =
                        FindCharacterByNameResponse.newBuilder();
                    var character = ChatTestFixtures.characterByName(request.getName());
                    if (!character.equals(character.getDefaultInstanceForType())) {
                      builder.setCharacter(character);
                    }
                    responseObserver.onNext(builder.build());
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

  public void setRoomEntities(ListRoomEntitiesResponse roomEntities) {
    this.roomEntities.set(roomEntities);
  }

  public void resetRoomEntities() {
    roomEntities.set(LookTestFixtures.sampleEntities());
  }

  @Override
  public void close() {
    if (server != null) {
      server.shutdownNow();
    }
  }
}
