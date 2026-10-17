package net.firedevops.firemud.common.gamelogic;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedMtls;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamedesign.v1.GameDesignGameplayRuleSourceReadServiceGrpc;

/** Explicit file-backed mTLS client for the exact same-namespace GD source read. */
public final class GameplayRuleSourceReadClient
    extends AbstractReloadingBlockingGrpcClient<
        GameDesignGameplayRuleSourceReadServiceGrpc
            .GameDesignGameplayRuleSourceReadServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public GameplayRuleSourceReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        FileBackedMtls.require(tlsProperties, "Game Design complete gameplay source read"),
        channelFactory,
        GameplayRuleSourceReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "Game Design complete gameplay source read client is closed",
        "Game Design complete gameplay source read stub unavailable");
  }

  /** Returns exact source evidence only after Game Design server mTLS identity verification. */
  public GameplayRuleSourceReadEvidence read(GameplayRuleSourceReadEvidence.Request request) {
    requireNoAmbientSql("GD source transport must run outside SQL");
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Game Design complete gameplay source read must use the configured workload namespace");
    }
    GameDesignGameplayRuleSourceReadServiceGrpc.GameDesignGameplayRuleSourceReadServiceBlockingStub
        initializedStub;
    try {
      initializedStub =
          requireInitializedStub(
              "Game Design complete gameplay source read client is not initialized and available");
    } catch (IllegalStateException unavailable) {
      throw Status.UNAVAILABLE.asRuntimeException();
    }
    var response =
        initializedStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readSelectedGameplayRuleSource(GameplayRuleSourceReadGrpcCodec.toRequest(request));
    try {
      return GameplayRuleSourceReadGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Game Design returned invalid complete source evidence", invalid);
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
  protected GameDesignGameplayRuleSourceReadServiceGrpc
          .GameDesignGameplayRuleSourceReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-design-service";
    return GameDesignGameplayRuleSourceReadServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(GameplayRuleSourceReadGrpcCodec.MAX_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }
}
