package net.firedevops.firemud.worldmanagement.client;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateRequest;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateRequest;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.ListVersionsRequest;
import net.firedevops.firemud.gamedesign.v1.ListVersionsResponse;
import org.springframework.stereotype.Component;

/** gRPC client for communicating with the Game Design Service. */
@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected configuration and channel references are not exposed")
public class GameDesignClient
    extends AbstractReloadingBlockingGrpcClient<
        GameDesignServiceGrpc.GameDesignServiceBlockingStub> {
  public GameDesignClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer, GameDesignClient.class);
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameDesignService();
  }

  @Override
  protected String defaultTarget() {
    return "game-design-service:6565";
  }

  @Override
  protected GameDesignServiceGrpc.GameDesignServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        GameDesignServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  /** Returns published versions for the given tenant. */
  public ListVersionsResponse listVersions(long tenantId) {
    ListVersionsRequest request =
        ListVersionsRequest.newBuilder().setTenantId(String.valueOf(tenantId)).build();
    return stub().listVersions(request);
  }

  public GetPublishedReleaseBundleResponse getPublishedReleaseBundle(
      long tenantId, long versionId) {
    GetPublishedReleaseBundleRequest request =
        GetPublishedReleaseBundleRequest.newBuilder()
            .setTenantId(String.valueOf(tenantId))
            .setVersionId(versionId)
            .build();
    return stub().getPublishedReleaseBundle(request);
  }

  public GetVersionStateResponse getVersionState(long tenantId, long versionId) {
    GetVersionStateRequest request =
        GetVersionStateRequest.newBuilder()
            .setTenantId(String.valueOf(tenantId))
            .setVersionId(versionId)
            .build();
    return stub().getVersionState(request);
  }

  public GetVersionAssetArtifactStateResponse getVersionAssetArtifactState(
      long tenantId, long versionId) {
    GetVersionAssetArtifactStateRequest request =
        GetVersionAssetArtifactStateRequest.newBuilder()
            .setTenantId(String.valueOf(tenantId))
            .setVersionId(versionId)
            .build();
    return stub().getVersionAssetArtifactState(request);
  }
}
