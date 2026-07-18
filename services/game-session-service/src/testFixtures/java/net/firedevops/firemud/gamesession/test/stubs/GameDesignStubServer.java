package net.firedevops.firemud.gamesession.test.stubs;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamesession.test.GameInstanceTestFixtures;

/** Deterministic Game Design boundary for gameplay cross-service fixtures. */
public final class GameDesignStubServer implements AutoCloseable {
  private final Server server;
  private final AtomicInteger publishedReleaseBundleRequests = new AtomicInteger();

  public GameDesignStubServer(int port) throws IOException {
    this.server =
        ServerBuilder.forPort(port)
            .addService(
                new GameDesignServiceGrpc.GameDesignServiceImplBase() {
                  @Override
                  public void getPublishedReleaseBundle(
                      GetPublishedReleaseBundleRequest request,
                      StreamObserver<GetPublishedReleaseBundleResponse> responseObserver) {
                    publishedReleaseBundleRequests.incrementAndGet();
                    responseObserver.onNext(
                        GetPublishedReleaseBundleResponse.newBuilder()
                            .setBundle(
                                PublishedReleaseBundle.newBuilder()
                                    .setId(GameInstanceTestFixtures.PUBLISHED_RELEASE_BUNDLE_ID)
                                    .setVersionId(request.getVersionId())
                                    .build())
                            .build());
                    responseObserver.onCompleted();
                  }
                })
            .build()
            .start();
  }

  public String endpoint() {
    return "localhost:" + server.getPort();
  }

  public int publishedReleaseBundleRequests() {
    return publishedReleaseBundleRequests.get();
  }

  @Override
  public void close() {
    server.shutdownNow();
  }
}
