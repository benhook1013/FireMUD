package net.firedevops.firemud.gamesession.test.stubs;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.socialgroups.v1.SendMessageRequest;
import net.firedevops.firemud.socialgroups.v1.SendMessageResponse;
import net.firedevops.firemud.socialgroups.v1.SocialGroupsServiceGrpc;

public final class SocialGroupsStubServer implements AutoCloseable {
  private final Server server;
  private final int port;
  private final AtomicReference<SendMessageRequest> lastRequest = new AtomicReference<>();

  public SocialGroupsStubServer(int port) throws IOException {
    this.port = port;
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

  public Optional<SendMessageRequest> lastRequest() {
    return Optional.ofNullable(lastRequest.get());
  }

  @Override
  public void close() {
    if (server != null) {
      server.shutdownNow();
    }
  }
}
