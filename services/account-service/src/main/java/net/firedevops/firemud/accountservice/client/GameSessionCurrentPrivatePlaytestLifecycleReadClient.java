package net.firedevops.firemud.accountservice.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadGrpcCodec;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.CurrentPrivatePlaytestLifecycleReadServiceGrpc;

/**
 * Explicit, unregistered mTLS reader of Game Session's current private preparation source. It mints
 * source-read evidence only; no returned value protects admission or a grant mutation.
 */
public final class GameSessionCurrentPrivatePlaytestLifecycleReadClient
    extends AbstractReloadingBlockingGrpcClient<
        CurrentPrivatePlaytestLifecycleReadServiceGrpc
            .CurrentPrivatePlaytestLifecycleReadServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private final String workloadNamespace;

  public GameSessionCurrentPrivatePlaytestLifecycleReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties, "Current private playtest source read"),
        channelFactory,
        GameSessionCurrentPrivatePlaytestLifecycleReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace))
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws IOException {
    requireNoAmbientSql(
        "Current private playtest client initialization requires no SQL or synchronization");
    initOnce(
        "Current private playtest source client is closed",
        "Current private playtest source client is not initialized");
  }

  /** Reads the actual peer outside Account SQL and exact-correlates a freshly allocated read ID. */
  public Verified read(Scope scope) {
    requireNoAmbientSql(
        "Current private playtest source read requires no Account SQL or synchronization");
    if (SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null) {
      throw new IllegalStateException(
          "Current private playtest source read requires workload-only context");
    }
    Objects.requireNonNull(scope, "scope");
    if (!workloadNamespace.equals(scope.targetNamespace()))
      throw new IllegalArgumentException(
          "Current private playtest source requires configured namespace");
    var expected =
        new CurrentPrivatePlaytestLifecycleReadGrpcCodec.Request(UUID.randomUUID(), scope);
    var response =
        requireInitializedStub("Current private playtest source client is not initialized")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .withMaxInboundMessageSize(
                CurrentPrivatePlaytestLifecycleReadGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
            .readCurrentPrivatePlaytestLifecycle(
                CurrentPrivatePlaytestLifecycleReadGrpcCodec.toRequest(expected));
    try {
      return new Verified(
          CurrentPrivatePlaytestLifecycleReadGrpcCodec.fromResponse(expected, response));
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Game Session returned invalid current private playtest source evidence", malformed);
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameSessionService();
  }

  @Override
  protected String defaultTarget() {
    return "game-session-service:6565";
  }

  @Override
  protected CurrentPrivatePlaytestLifecycleReadServiceGrpc
          .CurrentPrivatePlaytestLifecycleReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
    return CurrentPrivatePlaytestLifecycleReadServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer));
  }

  /** Opaque peer-qualified result. Only this actual client can construct it. */
  public static final class Verified {
    private final CurrentPrivatePlaytestLifecycleReadEvidence.Evidence evidence;

    private Verified(CurrentPrivatePlaytestLifecycleReadEvidence.Evidence evidence) {
      this.evidence = Objects.requireNonNull(evidence);
    }

    public CurrentPrivatePlaytestLifecycleReadEvidence.Evidence evidence() {
      return evidence;
    }
  }
}
