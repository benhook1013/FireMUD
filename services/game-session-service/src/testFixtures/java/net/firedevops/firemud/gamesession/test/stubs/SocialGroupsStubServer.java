package net.firedevops.firemud.gamesession.test.stubs;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.socialgroups.v1.ListFriendPresenceRequest;
import net.firedevops.firemud.socialgroups.v1.ListFriendPresenceResponse;
import net.firedevops.firemud.socialgroups.v1.SendMessageRequest;
import net.firedevops.firemud.socialgroups.v1.SendMessageResponse;
import net.firedevops.firemud.socialgroups.v1.SocialGroupsServiceGrpc;

public final class SocialGroupsStubServer implements AutoCloseable {
  private final Server server;
  private final int port;
  private final AtomicReference<SendMessageRequest> lastRequest = new AtomicReference<>();
  private final AtomicReference<ListFriendPresenceRequest> lastPresenceRequest =
      new AtomicReference<>();
  private final AtomicReference<ListFriendPresenceResponse> friendPresenceResponse =
      new AtomicReference<>(ListFriendPresenceResponse.newBuilder().build());

  public SocialGroupsStubServer(int port) throws IOException {
    this.server =
        ServerBuilder.forPort(port)
            .addService(
                new SocialGroupsServiceGrpc.SocialGroupsServiceImplBase() {
                  @Override
                  public void sendMessage(
                      SendMessageRequest request,
                      StreamObserver<SendMessageResponse> responseObserver) {
                    lastRequest.set(request);
                    SendMessageResponse response =
                        SendMessageResponse.newBuilder().setSuccess(true).build();
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void listFriendPresence(
                      ListFriendPresenceRequest request,
                      StreamObserver<ListFriendPresenceResponse> responseObserver) {
                    lastPresenceRequest.set(request);
                    responseObserver.onNext(friendPresenceResponse.get());
                    responseObserver.onCompleted();
                  }
                })
            .build()
            .start();
    this.port = server.getPort();
  }

  public String endpoint() {
    return "localhost:" + port;
  }

  public int port() {
    return port;
  }

  public Optional<SendMessageRequest> lastRequest() {
    return Optional.ofNullable(lastRequest.get());
  }

  public Optional<ListFriendPresenceRequest> lastPresenceRequest() {
    return Optional.ofNullable(lastPresenceRequest.get());
  }

  public void setFriendPresenceResponse(ListFriendPresenceResponse response) {
    friendPresenceResponse.set(response);
  }

  public void setFriendPresenceEntries(
      List<net.firedevops.firemud.socialgroups.v1.FriendPresenceEntry> entries) {
    friendPresenceResponse.set(
        ListFriendPresenceResponse.newBuilder().addAllPresences(entries).build());
  }

  @Override
  public void close() {
    if (server != null) {
      server.shutdownNow();
    }
  }
}
