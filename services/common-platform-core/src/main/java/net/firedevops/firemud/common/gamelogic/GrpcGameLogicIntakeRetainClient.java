package net.firedevops.firemud.common.gamelogic;

import io.grpc.ManagedChannel;
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
import net.firedevops.firemud.gamelogic.v1.GameLogicGameplayRuleIntakeServiceGrpc;

/** File-backed mTLS client for the unregistered selected-source retain transport. */
public final class GrpcGameLogicIntakeRetainClient
    extends AbstractReloadingBlockingGrpcClient<
        GameLogicGameplayRuleIntakeServiceGrpc.GameLogicGameplayRuleIntakeServiceBlockingStub>
    implements GameLogicIntakeRetainClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public GrpcGameLogicIntakeRetainClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        FileBackedMtls.require(tlsProperties, "Game Logic intake retain"),
        channelFactory,
        GrpcGameLogicIntakeRetainClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "Game Logic intake retain client is closed", "Game Logic intake retain stub unavailable");
  }

  @Override
  public GameLogicIntakeRetainEvidence retain(GameLogicIntakeRetainEvidence.Request request) {
    requireNoAmbientSql("Game Logic intake retain transport must run outside SQL");
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Game Logic intake retain must use the configured workload namespace");
    }
    var response =
        requireInitializedStub("Game Logic intake retain client is not initialized and available")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .retainGameplayRuleIntake(GameLogicIntakeRetainProtoCodec.toRequest(request));
    try {
      return GameLogicIntakeRetainProtoCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Game Logic returned invalid intake retain evidence", invalid);
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameLogicService();
  }

  @Override
  protected String defaultTarget() {
    return "game-logic-service:6565";
  }

  @Override
  protected GameLogicGameplayRuleIntakeServiceGrpc.GameLogicGameplayRuleIntakeServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-logic-service";
    return GameLogicGameplayRuleIntakeServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withMaxInboundMessageSize(GameLogicIntakeRetainProtoCodec.MAX_WIRE_BYTES)
        .withCompression("gzip");
  }
}
