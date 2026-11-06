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
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.socialgroups.v1.SocialRuntimeFamilyCatalogReadServiceGrpc;

/**
 * Manual concrete Social source consumer; no owner aggregation, manifest, FRESH or admission
 * wiring.
 */
public final class SocialRuntimeFamilyCatalogClient
    extends AbstractReloadingBlockingGrpcClient<
        SocialRuntimeFamilyCatalogReadServiceGrpc
            .SocialRuntimeFamilyCatalogReadServiceBlockingStub> {
  private final String namespace;

  public SocialRuntimeFamilyCatalogClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Social runtime catalog"),
        channels,
        SocialRuntimeFamilyCatalogClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Fixed workload namespace required");
    }
    this.namespace = namespace;
  }

  public void init() throws IOException {
    requireNoAmbientSql("Social catalog client initialization requires no ambient SQL");
    initOnce("Social catalog client closed", "Social catalog client unavailable");
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getSocialGroupsService();
  }

  @Override
  protected String defaultTarget() {
    return "social-groups-service:6565";
  }

  @Override
  protected SocialRuntimeFamilyCatalogReadServiceGrpc
          .SocialRuntimeFamilyCatalogReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/social-groups-service";
    return SocialRuntimeFamilyCatalogReadServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer));
  }

  /** Preserves exact complete typed source evidence; outages never become empty success. */
  public Evidence read(Request request) {
    requireNoAmbientSql("Social catalog RPC requires no ambient SQL");
    Objects.requireNonNull(request, "request");
    if (!namespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException("Wrong Social workload namespace");
    }
    var response =
        requireInitializedStub("Social catalog client unavailable")
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .withMaxInboundMessageSize(SocialRuntimeFamilyCatalogCodec.MAX_RESPONSE_BYTES)
            .withMaxOutboundMessageSize(SocialRuntimeFamilyCatalogCodec.MAX_REQUEST_BYTES)
            .readSocialRuntimeFamilyCatalog(SocialRuntimeFamilyCatalogCodec.toRequest(request));
    return SocialRuntimeFamilyCatalogCodec.fromResponse(request, response);
  }
}
