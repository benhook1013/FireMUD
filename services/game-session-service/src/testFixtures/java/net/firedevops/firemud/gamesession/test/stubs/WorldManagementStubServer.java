package net.firedevops.firemud.gamesession.test.stubs;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotRequest;
import net.firedevops.firemud.worldmanagement.v1.GetRoomSnapshotResponse;
import net.firedevops.firemud.worldmanagement.v1.RoomSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;

public final class WorldManagementStubServer implements AutoCloseable {
  private final Server server;
  private final int port;
  private final AtomicReference<StatusRuntimeException> nextFailure = new AtomicReference<>();

  public WorldManagementStubServer(int port) throws IOException {
    this.server =
        ServerBuilder.forPort(port)
            .addService(
                new WorldManagementServiceGrpc.WorldManagementServiceImplBase() {
                  @Override
                  public void getRoomSnapshot(
                      GetRoomSnapshotRequest request,
                      StreamObserver<GetRoomSnapshotResponse> responseObserver) {
                    StatusRuntimeException failure = nextFailure.getAndSet(null);
                    if (failure != null) {
                      responseObserver.onError(failure);
                      return;
                    }
                    try {
                      RoomSnapshot snapshot =
                          LookTestFixtures.sampleRoomSnapshot(
                              request.getRoomInstance().getRoomInstanceId());
                      RoomSnapshot.Builder scopedSnapshot = snapshot.toBuilder();
                      if (!request.getRoomInstance().getTenantId().isBlank()) {
                        scopedSnapshot.setTenantId(request.getRoomInstance().getTenantId());
                      } else if (!request.getTenantId().isBlank()) {
                        scopedSnapshot.setTenantId(request.getTenantId());
                      }
                      if (!request.getRoomInstance().getGameInstanceId().isBlank()) {
                        scopedSnapshot.setGameInstanceId(
                            request.getRoomInstance().getGameInstanceId());
                      }
                      responseObserver.onNext(
                          GetRoomSnapshotResponse.newBuilder().setSnapshot(scopedSnapshot).build());
                      responseObserver.onCompleted();
                    } catch (IllegalArgumentException ex) {
                      responseObserver.onError(
                          Status.NOT_FOUND.withDescription(ex.getMessage()).asRuntimeException());
                    }
                  }
                })
            .build()
            .start();
    this.port = server.getPort();
  }

  public void triggerNotFound(String description) {
    nextFailure.set(Status.NOT_FOUND.withDescription(description).asRuntimeException());
  }

  public void resetFailures() {
    nextFailure.set(null);
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
