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
import net.firedevops.firemud.gamelogic.v1.GameLogicGameplayRuleIntakeTerminalReadServiceGrpc;

/** Explicit file-backed mTLS client for the exact same-namespace Game Logic terminal read. */
public final class GameLogicIntakeTerminalReadClient
    extends AbstractReloadingBlockingGrpcClient<
        GameLogicGameplayRuleIntakeTerminalReadServiceGrpc
            .GameLogicGameplayRuleIntakeTerminalReadServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public GameLogicIntakeTerminalReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        FileBackedMtls.require(tlsProperties, "Game Logic intake terminal read"),
        channelFactory,
        GameLogicIntakeTerminalReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "Game Logic intake terminal read client is closed",
        "Game Logic intake terminal read stub unavailable");
  }

  /**
   * Returns a validated terminal or explicit absence after exact Game Logic server mTLS
   * verification.
   */
  public GameLogicIntakeTerminalReadEvidence read(
      GameLogicIntakeTerminalReadEvidence.Request request) {
    requireNoAmbientSql("Game Logic terminal transport must run outside SQL");
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Game Logic intake terminal read must use the configured workload namespace");
    }
    var response =
        requireInitializedStub(
                "Game Logic intake terminal read client is not initialized and available")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readGameplayRuleIntakeTerminal(
                GameLogicIntakeTerminalReadGrpcCodec.toRequest(request));
    try {
      return GameLogicIntakeTerminalReadGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("Game Logic returned invalid terminal evidence", invalid);
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
  protected GameLogicGameplayRuleIntakeTerminalReadServiceGrpc
          .GameLogicGameplayRuleIntakeTerminalReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-logic-service";
    return GameLogicGameplayRuleIntakeTerminalReadServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withMaxInboundMessageSize(GameLogicGameplayRuleIntakeTerminal.MAX_WIRE_BYTES)
        .withCompression("gzip");
  }
}
