package net.firedevops.firemud.gamesession.client;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRecoveryEvidence;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryServiceGrpc;

/** Unregistered exact original-selection recovery; no caller echo becomes Entity closure proof. */
public final class CanonicalGameplayRosterRecoveryClient
    extends AbstractReloadingBlockingGrpcClient<
        CanonicalGameplayRosterRecoveryServiceGrpc
            .CanonicalGameplayRosterRecoveryServiceBlockingStub> {
  private final String namespace;

  public CanonicalGameplayRosterRecoveryClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Canonical roster recovery"),
        channels,
        CanonicalGameplayRosterRecoveryClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Fixed Game Session namespace required");
    }
    this.namespace = namespace;
  }

  public void init() throws IOException {
    initOnce("Canonical roster recovery client is closed", "Entity recovery unavailable");
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getEntityManagementService();
  }

  @Override
  protected String defaultTarget() {
    return "entity-management-service:6565";
  }

  @Override
  protected CanonicalGameplayRosterRecoveryServiceGrpc
          .CanonicalGameplayRosterRecoveryServiceBlockingStub
      buildStub(io.grpc.ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/entity-management-service";
    return CanonicalGameplayRosterRecoveryServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer))
        .withMaxInboundMessageSize(CanonicalGameplayRosterRecoveryEvidence.MAX_RESPONSE_BYTES);
  }

  public VerifiedRecovery read(CanonicalGameplayRosterRecoveryRequest request) {
    requireNoAmbientSql("Canonical roster recovery requires no ambient transaction");
    CanonicalGameplayRosterRecoveryEvidence.requireRequest(request);
    if (!namespace.equals(request.getOriginalSelection().getTargetNamespace())) {
      throw new IllegalArgumentException("Recovery request changed namespace");
    }
    var response =
        requireInitializedStub("Entity recovery unavailable")
            .withDeadlineAfter(5L, TimeUnit.SECONDS)
            .recoverOriginalSelection(request);
    try {
      CanonicalGameplayRosterRecoveryEvidence.requireResponse(request, response);
    } catch (IllegalArgumentException invalidResponse) {
      throw new IllegalStateException("Entity recovery response is invalid", invalidResponse);
    }
    return new VerifiedRecovery(request, response);
  }

  /** Constructed only after an actual authenticated Entity response has been verified. */
  public static final class VerifiedRecovery {
    private final CanonicalGameplayRosterRecoveryRequest request;
    private final CanonicalGameplayRosterRecoveryResponse response;

    private VerifiedRecovery(
        CanonicalGameplayRosterRecoveryRequest request,
        CanonicalGameplayRosterRecoveryResponse response) {
      this.request = request;
      this.response = response;
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Protobuf messages are immutable.")
    public CanonicalGameplayRosterRecoveryRequest request() {
      return request;
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Protobuf messages are immutable.")
    public CanonicalGameplayRosterRecoveryResponse response() {
      return response;
    }
  }
}
