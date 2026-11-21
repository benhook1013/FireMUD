package net.firedevops.firemud.gamesession.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedGrpcMtlsPolicy;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.world.FixtureInitialAdmissionGrpcCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionHoldRequest;
import net.firedevops.firemud.gamesession.data.RunOwnedCanonicalInitialAdmissionOwner;
import net.firedevops.firemud.worldmanagement.v1.WorldFixtureInitialAdmissionHoldServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered fixed-leaf mTLS fixture transport, separate from Human lifecycle/hold authority. */
public final class GrpcWorldFixtureInitialAdmissionHoldClient
    extends AbstractReloadingBlockingGrpcClient<
        WorldFixtureInitialAdmissionHoldServiceGrpc
            .WorldFixtureInitialAdmissionHoldServiceBlockingStub>
    implements RunOwnedCanonicalInitialAdmissionOwner.WorldTransport {
  private final String namespace;
  private final String expectedWorldLeafSha256;

  public GrpcWorldFixtureInitialAdmissionHoldClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory factory,
      String namespace,
      String expectedWorldLeafSha256) {
    super(
        endpoints,
        FileBackedGrpcMtlsPolicy.require(
            tlsProperties,
            "Fixture initial-admission hold",
            "Fixture hold requires file-backed workload mTLS",
            "Fixture hold requires workload mTLS"),
        factory,
        GrpcWorldFixtureInitialAdmissionHoldClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)
        || expectedWorldLeafSha256 == null
        || !expectedWorldLeafSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Exact fixture World namespace and leaf required");
    }
    this.namespace = namespace;
    this.expectedWorldLeafSha256 = expectedWorldLeafSha256;
  }

  public void init() throws SSLException, IOException {
    initOnce("Fixture World client is closed", "Fixture World client has no gRPC stub");
  }

  @Override
  public HoldIdentity acquire(WorldFixtureInitialAdmissionHoldRequest request) {
    requireIndependent(request);
    var lookup = FixtureInitialAdmissionGrpcCodec.Lookup.fresh(request, null);
    var response =
        requireInitializedStub("Fixture World client is unavailable")
            .withDeadlineAfter(5L, TimeUnit.SECONDS)
            .acquireFixtureInitialAdmissionHold(
                FixtureInitialAdmissionGrpcCodec.toWorldRequest(lookup));
    return FixtureInitialAdmissionGrpcCodec.requireWorldIdentity(lookup, response);
  }

  /** Immutable original identity only; never renews the hold or claims fresh ACTIVE state. */
  public HoldIdentity readIdentity(WorldFixtureInitialAdmissionHoldRequest request) {
    requireIndependent(request);
    var lookup = FixtureInitialAdmissionGrpcCodec.Lookup.fresh(request, null);
    var response =
        requireInitializedStub("Fixture World client is unavailable")
            .withDeadlineAfter(5L, TimeUnit.SECONDS)
            .readFixtureInitialAdmissionHoldIdentity(
                FixtureInitialAdmissionGrpcCodec.toWorldRequest(lookup));
    return FixtureInitialAdmissionGrpcCodec.requireWorldIdentity(lookup, response);
  }

  @Override
  public void verifyActive(WorldFixtureInitialAdmissionHoldRequest request, HoldIdentity identity) {
    requireIndependent(request);
    var lookup = FixtureInitialAdmissionGrpcCodec.Lookup.fresh(request, identity);
    var response =
        requireInitializedStub("Fixture World client is unavailable")
            .withDeadlineAfter(5L, TimeUnit.SECONDS)
            .readFixtureInitialAdmissionHoldState(
                FixtureInitialAdmissionGrpcCodec.toWorldRequest(lookup));
    FixtureInitialAdmissionGrpcCodec.requireWorldState(lookup, response).requirePendingActive();
  }

  private void requireIndependent(WorldFixtureInitialAdmissionHoldRequest request) {
    Objects.requireNonNull(request, "request");
    if (!namespace.equals(request.selector().targetNamespace())
        || request.selector().initialAdmissionOrigin()
            != net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold
                .InitialAdmissionOrigin.NO_PRIOR_POINTER) {
      throw new IllegalArgumentException(
          "Fixture first OPEN requires its exact namespace and origin");
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Fixture World operation requires no ambient SQL");
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getWorldManagementService();
  }

  @Override
  protected String defaultTarget() {
    return "world-management-service:6565";
  }

  @Override
  protected WorldFixtureInitialAdmissionHoldServiceGrpc
          .WorldFixtureInitialAdmissionHoldServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/world-management-service";
    return WorldFixtureInitialAdmissionHoldServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(FixtureInitialAdmissionGrpcCodec.MAX_WIRE_BYTES)
        .withCallCredentials(
            new GrpcServerPeerIdentityCallCredentials(peer, expectedWorldLeafSha256))
        .withInterceptors(
            new GrpcServerPeerIdentityClientInterceptor(peer, expectedWorldLeafSha256))
        .withCompression("gzip");
  }
}
