package net.firedevops.firemud.gamesession.client;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
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
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;
import org.springframework.stereotype.Component;

@Component
public final class GameDesignClient
    extends AbstractBlockingGrpcClient<GameDesignServiceGrpc.GameDesignServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  public GameDesignClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer);
  }

  @PostConstruct
  void init() throws Exception {
    initClient();
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

  public ResolveLaunchDescriptorResponse resolveLaunchDescriptor(
      long tenantId, long gameTemplateId, String controlPlaneRequestId) {
    return resolveLaunchDescriptor(tenantId, gameTemplateId, controlPlaneRequestId, null, null);
  }

  public ResolveLaunchDescriptorResponse resolveLaunchDescriptor(
      long tenantId,
      long gameTemplateId,
      String controlPlaneRequestId,
      Long sourceVersionId,
      Long targetVersionId) {
    ResolveLaunchDescriptorRequest.Builder request =
        ResolveLaunchDescriptorRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setGameTemplateId(gameTemplateId)
            .setControlPlaneRequestId(controlPlaneRequestId);
    if (sourceVersionId != null) {
      request.setSourceVersionId(sourceVersionId);
    }
    if (targetVersionId != null) {
      request.setTargetVersionId(targetVersionId);
    }
    return callStub().resolveLaunchDescriptor(request.build());
  }

  public GetPublishedReleaseBundleResponse getPublishedReleaseBundle(
      long tenantId, long versionId) {
    return callStub()
        .getPublishedReleaseBundle(
            GetPublishedReleaseBundleRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setVersionId(versionId)
                .build());
  }

  public GetVersionStateResponse getVersionState(long tenantId, long versionId) {
    return callStub()
        .getVersionState(
            GetVersionStateRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setVersionId(versionId)
                .build());
  }

  public GetVersionAssetArtifactStateResponse getVersionAssetArtifactState(
      long tenantId, long versionId) {
    return callStub()
        .getVersionAssetArtifactState(
            GetVersionAssetArtifactStateRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setVersionId(versionId)
                .build());
  }

  private GameDesignServiceGrpc.GameDesignServiceBlockingStub callStub() {
    return stub().withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
  }
}
