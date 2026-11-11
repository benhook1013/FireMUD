package net.firedevops.firemud.gamesession.client;

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Result;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/**
 * Unregistered exact Account capacity adapter. It performs no admission or owner activation CAS.
 */
public final class AccountTenantCapacityClient
    extends AbstractReloadingBlockingGrpcClient<AccountServiceGrpc.AccountServiceBlockingStub> {
  private final String namespace;
  private final Clock clock;
  private final String expectedPeer;

  public AccountTenantCapacityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace,
      Clock clock) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Tenant capacity commitment"),
        channels,
        AccountTenantCapacityClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Namespace required");
    this.namespace = namespace;
    this.expectedPeer = "spiffe://firemud/ns/" + namespace + "/sa/account-service";
    this.clock = Objects.requireNonNull(clock);
  }

  public void init() throws IOException {
    requireNoAmbientSql("Capacity client initialization requires no SQL transaction");
    initOnce("Capacity client is closed", "Account capacity unavailable");
  }

  /**
   * The caller must obtain its typed context from current owned player state, not text/metadata.
   * Retries retain this exact request. The Account call consumes the remaining absolute admission
   * budget, capped at five seconds and the incoming deadline. A timeout is indeterminate and never
   * allocates another ID.
   */
  public Result commit(Request request, long absoluteDeadlineMillis) {
    requireNoAmbientSql("Capacity commitment requires no ambient SQL");
    request.requireStructurallyValid();
    if (request.context().owner() != TenantCapacityAdmission.Owner.GAME_SESSION
        || !namespace.equals(request.targetNamespace()))
      throw new IllegalArgumentException("Exact Game Session context required");
    long remainingMillis = Math.subtractExact(absoluteDeadlineMillis, clock.millis());
    Deadline deadline =
        Deadline.after(
            Math.min(
                TimeUnit.SECONDS.toMillis(TenantCapacityAdmissionGrpcCodec.CALL_DEADLINE_SECONDS),
                remainingMillis),
            TimeUnit.MILLISECONDS);
    Deadline incoming = Context.current().getDeadline();
    if (incoming != null) deadline = deadline.minimum(incoming);
    var stub =
        requireInitializedStub("Account capacity unavailable")
            .withDeadline(deadline)
            .withMaxOutboundMessageSize(TenantCapacityAdmissionGrpcCodec.MAX_REQUEST_WIRE_BYTES)
            .withMaxInboundMessageSize(TenantCapacityAdmissionGrpcCodec.MAX_RESPONSE_WIRE_BYTES);
    try {
      // Exact retry may replay an expired/terminal reservation. The final owner still checks it.
      return TenantCapacityAdmissionGrpcCodec.requireResponse(
          request,
          stub.commitTenantCapacityAdmission(TenantCapacityAdmissionGrpcCodec.toRequest(request)));
    } catch (StatusRuntimeException failure) {
      throw TenantCapacityAdmissionGrpcCodec.validateFailure(failure);
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getAccountService();
  }

  @Override
  protected String defaultTarget() {
    return "account-service:6565";
  }

  @Override
  protected AccountServiceGrpc.AccountServiceBlockingStub buildStub(ManagedChannel channel) {
    return AccountServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeer));
  }
}
