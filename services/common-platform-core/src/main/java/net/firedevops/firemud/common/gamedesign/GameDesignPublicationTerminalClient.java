package net.firedevops.firemud.common.gamedesign;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamedesign.v1.GameDesignPublicationTerminalReadServiceGrpc;

/** Explicit mTLS client for the exact Game Design publication terminal read. */
public final class GameDesignPublicationTerminalClient
    extends AbstractReloadingBlockingGrpcClient<
        GameDesignPublicationTerminalReadServiceGrpc
            .GameDesignPublicationTerminalReadServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public GameDesignPublicationTerminalClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties, "Game Design publication terminal read"),
        channelFactory,
        GameDesignPublicationTerminalClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when the owning Account or World service explicitly starts this client. */
  public void init() throws SSLException, IOException {
    initOnce(
        "Game Design publication terminal client is closed",
        "Game Design publication terminal client has no gRPC stub");
  }

  /**
   * Reads and validates Game Design-owned publication terminal evidence for this exact canonical
   * request.
   */
  public GameDesignPublicationTerminalReadEvidence.ReadResult read(
      GameDesignPublicationTerminalReadEvidence.ReadRequest request) {
    requireNoAmbientSql("Game Design publication terminal read requires no owner transaction");
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Game Design publication terminal request must use the configured workload namespace");
    }
    var response =
        requireInitializedStub(
                "Game Design publication terminal client is not initialized and available")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readGameDesignPublicationTerminal(
                GameDesignPublicationTerminalReadGrpcCodec.toRequest(request));
    try {
      return GameDesignPublicationTerminalReadGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Game Design returned invalid publication terminal read evidence", invalid);
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameDesignService();
  }

  @Override
  protected String defaultTarget() {
    return "game-design-service:6565";
  }

  @Override
  protected GameDesignPublicationTerminalReadServiceGrpc
          .GameDesignPublicationTerminalReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-design-service";
    return GameDesignPublicationTerminalReadServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }
}
