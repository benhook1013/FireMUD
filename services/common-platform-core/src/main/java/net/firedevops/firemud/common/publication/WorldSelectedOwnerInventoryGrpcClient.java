package net.firedevops.firemud.common.publication;

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
import net.firedevops.firemud.worldmanagement.v1.WorldSelectedOwnerInventoryReadServiceGrpc;

/** File-backed mTLS client for the recipient-specific retained World inventory read. */
public final class WorldSelectedOwnerInventoryGrpcClient
    extends AbstractReloadingBlockingGrpcClient<
        WorldSelectedOwnerInventoryReadServiceGrpc
            .WorldSelectedOwnerInventoryReadServiceBlockingStub>
    implements SelectedOwnerWorldInventoryReadClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public WorldSelectedOwnerInventoryGrpcClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        FileBackedMtls.require(tlsProperties, "World owner inventory read"),
        channelFactory,
        WorldSelectedOwnerInventoryGrpcClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws SSLException, IOException {
    initOnce("World owner inventory client is closed", "World owner inventory stub unavailable");
  }

  @Override
  public SelectedOwnerWorldInventoryReadEvidence read(
      SelectedOwnerWorldInventoryReadEvidence.Request request) {
    requireNoAmbientSql("World owner inventory read requires no ambient SQL");
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException("World owner inventory namespace differs from client");
    }
    var response =
        requireInitializedStub("World owner inventory client is not initialized")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readSelectedOwnerWorldInventory(
                SelectedOwnerWorldInventoryReadGrpcCodec.toRequest(request));
    try {
      return SelectedOwnerWorldInventoryReadGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("World returned invalid owner inventory evidence", invalid);
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
  protected WorldSelectedOwnerInventoryReadServiceGrpc
          .WorldSelectedOwnerInventoryReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service";
    return WorldSelectedOwnerInventoryReadServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(SelectedOwnerWorldInventoryReadEvidence.MAX_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }
}
