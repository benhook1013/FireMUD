package net.firedevops.firemud.gamesession.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.entitymanagement.v1.EntityRuntimeFamilyCatalogReadServiceGrpc;

/** Manual exact Entity source consumer. No FRESH coordinator, registration or allocation wiring. */
public final class EntityRuntimeFamilyCatalogClient
    extends AbstractReloadingBlockingGrpcClient<
        EntityRuntimeFamilyCatalogReadServiceGrpc
            .EntityRuntimeFamilyCatalogReadServiceBlockingStub> {
  private final String namespace;

  public EntityRuntimeFamilyCatalogClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Entity runtime catalog"),
        channels,
        EntityRuntimeFamilyCatalogClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Fixed workload namespace required");
    this.namespace = namespace;
  }

  public void init() throws IOException {
    requireNoAmbientSql("Catalog client initialization requires no ambient SQL");
    initOnce("Entity catalog client closed", "Entity catalog client unavailable");
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
  protected EntityRuntimeFamilyCatalogReadServiceGrpc
          .EntityRuntimeFamilyCatalogReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/entity-management-service";
    return EntityRuntimeFamilyCatalogReadServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer));
  }

  public Evidence read(Request request) {
    requireNoAmbientSql("Entity catalog RPC requires no ambient SQL");
    Objects.requireNonNull(request, "request");
    if (!namespace.equals(request.targetNamespace()))
      throw new IllegalArgumentException("Wrong workload namespace");
    var response =
        requireInitializedStub("Entity catalog client unavailable")
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .withMaxInboundMessageSize(EntityRuntimeFamilyCatalogCodec.MAX_RESPONSE_BYTES)
            .withMaxOutboundMessageSize(EntityRuntimeFamilyCatalogCodec.MAX_REQUEST_BYTES)
            .readEntityRuntimeFamilyCatalog(EntityRuntimeFamilyCatalogCodec.toRequest(request));
    return EntityRuntimeFamilyCatalogCodec.fromResponse(request, response);
  }
}
