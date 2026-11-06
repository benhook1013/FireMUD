package net.firedevops.firemud.gamesession.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.worldmanagement.v1.WorldRuntimeFamilyCatalogReadServiceGrpc;

/**
 * Manual concrete source consumer. It neither enumerates other owners nor prepares/adopts FRESH.
 */
public final class WorldRuntimeFamilyCatalogClient
    extends AbstractReloadingBlockingGrpcClient<
        WorldRuntimeFamilyCatalogReadServiceGrpc.WorldRuntimeFamilyCatalogReadServiceBlockingStub> {
  private final String namespace;

  public WorldRuntimeFamilyCatalogClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "World runtime catalog"),
        channels,
        WorldRuntimeFamilyCatalogClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Fixed workload namespace required");
    this.namespace = namespace;
  }

  public void init() throws IOException {
    requireNoAmbientSql("Catalog client initialization requires no ambient SQL");
    initOnce("World catalog client closed", "World catalog client unavailable");
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
  protected WorldRuntimeFamilyCatalogReadServiceGrpc
          .WorldRuntimeFamilyCatalogReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/world-management-service";
    return WorldRuntimeFamilyCatalogReadServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer));
  }

  /** Preserves the complete exact typed source or refuses; outage never becomes empty evidence. */
  public Evidence read(Request request) {
    requireNoAmbientSql("World catalog RPC requires no ambient SQL");
    Objects.requireNonNull(request, "request");
    if (!namespace.equals(request.targetNamespace()))
      throw new IllegalArgumentException("Wrong workload namespace");
    var response =
        requireInitializedStub("World catalog client unavailable")
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .withMaxInboundMessageSize(WorldRuntimeFamilyCatalogCodec.MAX_RESPONSE_BYTES)
            .withMaxOutboundMessageSize(WorldRuntimeFamilyCatalogCodec.MAX_REQUEST_BYTES)
            .readWorldRuntimeFamilyCatalog(WorldRuntimeFamilyCatalogCodec.toRequest(request));
    return WorldRuntimeFamilyCatalogCodec.fromResponse(request, response);
  }
}
