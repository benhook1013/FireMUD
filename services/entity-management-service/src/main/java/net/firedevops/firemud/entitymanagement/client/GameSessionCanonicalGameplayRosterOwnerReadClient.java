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
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadGrpcCodec;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementRequest;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementResponse;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementConfirmationRequest;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementConfirmationResponse;
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
        .withCallCredentials(
            new GrpcServerPeerIdentityCallCredentials(
                "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service"))
        .withInterceptors(serverPeerIdentityInterceptor)
        .withCompression("gzip");
  }

  public net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse
      getCanonicalGameplayRosterReference(
          net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest
              request) {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive()
        || org.springframework.transaction.support.TransactionSynchronizationManager
            .isSynchronizationActive()) {
      throw new IllegalStateException(
          "Original roster owner transport requires no ambient transaction");
    }
    var checkedRequest =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.requireRequest(
            request);
    if (!workloadNamespace.equals(request.getTargetNamespace())) {
      throw new IllegalArgumentException("Original roster selection belongs to another namespace");
    }
    var current =
        requireInitializedStub("Game Session original roster owner client is not initialized");
    if (net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.target(
                checkedRequest)
            .getEntryPolicy()
        == net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy
            .CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED) {
      current =
          current.withMaxInboundMessageSize(
              net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence
                  .MAX_PLAYER_CREATED_RESPONSE_BYTES);
    }
    var response =
        current
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .getCanonicalGameplayRosterReference(request);
    try {
      return net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence
          .requireResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Game Session original roster owner response is invalid", invalid);
    }
  }

  public CanonicalGameplayRosterRetirementAcknowledgementResponse
      acknowledgeCanonicalGameplayRosterRetirement(
          CanonicalGameplayRosterRetirementAcknowledgementRequest request) {
    requireRetirementTransport();
    CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(request);
    requireRetirementNamespace(request);
    var response =
        requireInitializedStub("Game Session retirement owner client is not initialized")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .acknowledgeCanonicalGameplayRosterRetirement(request);
    return CanonicalGameplayRosterRetirementEvidence.requireAcknowledgementResponse(
        request, response);
  }

  public CanonicalGameplayRosterRetirementConfirmationResponse
      confirmCanonicalGameplayRosterRetirementAcknowledgement(
          CanonicalGameplayRosterRetirementConfirmationRequest request) {
    requireRetirementTransport();
    CanonicalGameplayRosterRetirementEvidence.requireConfirmation(request);
    requireRetirementNamespace(request.getAcknowledgementRequest());
    var response =
        requireInitializedStub("Game Session retirement owner client is not initialized")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .confirmCanonicalGameplayRosterRetirementAcknowledgement(request);
    return CanonicalGameplayRosterRetirementEvidence.requireConfirmationResponse(request, response);
  }

  private static void requireRetirementTransport() {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive()
        || org.springframework.transaction.support.TransactionSynchronizationManager
            .isSynchronizationActive()) {
      throw new IllegalStateException(
          "Roster retirement transport requires no ambient transaction");
    }
  }

  private void requireRetirementNamespace(
      CanonicalGameplayRosterRetirementAcknowledgementRequest request) {
    if (!workloadNamespace.equals(request.getOriginalSelection().getTargetNamespace())) {
      throw new IllegalArgumentException("Roster retirement belongs to another namespace");
    }
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
