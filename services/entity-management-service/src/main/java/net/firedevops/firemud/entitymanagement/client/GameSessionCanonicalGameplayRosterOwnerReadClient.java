package net.firedevops.firemud.entitymanagement.client;

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
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadGrpcCodec;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadResponse;

/** Entity client for closed, non-admitting Game Session roster-owner source evidence. */
public final class GameSessionCanonicalGameplayRosterOwnerReadClient
    extends AbstractReloadingBlockingGrpcClient<
        CanonicalGameplayRosterOwnerReadServiceGrpc
            .CanonicalGameplayRosterOwnerReadServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private final GrpcServerPeerIdentityClientInterceptor serverPeerIdentityInterceptor;

  public GameSessionCanonicalGameplayRosterOwnerReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        EntityManagementSourceClientValidation.requireEntityManagementMtls(
            tlsProps, "Gameplay roster owner reads"),
        channelFactory,
        GameSessionCanonicalGameplayRosterOwnerReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException(
          "Entity Management workload namespace must be one DNS label");
    }
    this.workloadNamespace = workloadNamespace;
    this.serverPeerIdentityInterceptor =
        new GrpcServerPeerIdentityClientInterceptor(
            "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service");
  }

  /** Initializes file-backed mTLS only when the explicit owner-read switch is enabled. */
  public void init() throws SSLException, IOException {
    initOnce(
        "Game Session canonical gameplay roster owner read client is closed",
        "Game Session canonical gameplay roster owner read client is not initialized");
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
  protected CanonicalGameplayRosterOwnerReadServiceGrpc
          .CanonicalGameplayRosterOwnerReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    return CanonicalGameplayRosterOwnerReadServiceGrpc.newBlockingStub(channel)
        .withInterceptors(serverPeerIdentityInterceptor)
        .withCompression("gzip");
  }

  /**
   * Reads complete request-bound owner source evidence. It is not Account authority, actor
   * ownership by itself, or gameplay admission.
   */
  public CanonicalGameplayRosterOwnerReadEvidence getCanonicalGameplayRosterOwnerRead(
      CanonicalGameplayRosterOwnerReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Gameplay roster owner read target namespace does not match Entity Management");
    }

    CanonicalGameplayRosterOwnerReadServiceGrpc.CanonicalGameplayRosterOwnerReadServiceBlockingStub
        currentStub =
            requireInitializedStub(
                "Game Session canonical gameplay roster owner read client is not initialized");

    GetCanonicalGameplayRosterOwnerReadResponse response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .getCanonicalGameplayRosterOwnerRead(
                CanonicalGameplayRosterOwnerReadGrpcCodec.toRequest(request));
    if (response == null) {
      throw new IllegalStateException(
          "Game Session canonical gameplay roster owner read response is absent");
    }

    try {
      return CanonicalGameplayRosterOwnerReadGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Game Session canonical gameplay roster owner read response is invalid", exception);
    }
  }
}
