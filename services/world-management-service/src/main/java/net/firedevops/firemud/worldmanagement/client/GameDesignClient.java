package net.firedevops.firemud.worldmanagement.client;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateRequest;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateRequest;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.v1.ListVersionsRequest;
import net.firedevops.firemud.gamedesign.v1.ListVersionsResponse;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

/** gRPC client for communicating with the Game Design Service. */
@Component
public class GameDesignClient
    extends AbstractReloadingBlockingGrpcClient<
        GameDesignServiceGrpc.GameDesignServiceBlockingStub> {
  private static final Logger logger = LoggingUtil.getLogger(GameDesignClient.class);
  private static final long LAUNCH_DESCRIPTOR_READ_DEADLINE_SECONDS = 5L;
  private static final String GAME_DESIGN_DEV_PEER_URI =
      "spiffe://firemud/ns/dev/sa/game-design-service";

  private enum DescriptorReadStage {
    STUB_CONFIGURE,
    REQUEST_ENCODE,
    RPC_CALL,
    RESPONSE_DECODE
  }

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

  /** Performs an exact run-owned descriptor read authenticated to the dev Game Design workload. */
  public LaunchDescriptorReadback getLaunchDescriptor(
      AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest request) {
    DescriptorReadStage stage = DescriptorReadStage.STUB_CONFIGURE;
    try {
      var readStub =
          stub()
              .withCallCredentials(
                  new GrpcServerPeerIdentityCallCredentials(GAME_DESIGN_DEV_PEER_URI))
              .withInterceptors(
                  new GrpcServerPeerIdentityClientInterceptor(GAME_DESIGN_DEV_PEER_URI))
              .withDeadlineAfter(LAUNCH_DESCRIPTOR_READ_DEADLINE_SECONDS, TimeUnit.SECONDS);

      stage = DescriptorReadStage.REQUEST_ENCODE;
      var encodedRequest = AuthoredWorldLaunchDescriptorGrpcCodec.toGetRequest(request);

      stage = DescriptorReadStage.RPC_CALL;
      var response = readStub.getLaunchDescriptor(encodedRequest);

      stage = DescriptorReadStage.RESPONSE_DECODE;
      AuthoredWorldLaunchDescriptorEvidence evidence =
          AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(request, response);
      return new LaunchDescriptorReadback(response.getLaunchDescriptor(), evidence);
    } catch (RuntimeException exception) {
      logger.warn(
          "Run-owned World Game Design descriptor read failed; stage={} statusCode={}",
          stage,
          Status.fromThrowable(exception).getCode());
      throw exception;
    }
  }

  /** Exact protobuf owner value paired with its verified request/result digest projection. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "Generated protobuf LaunchDescriptor and AuthoredWorldLaunchDescriptorEvidence are immutable value objects.")
  public record LaunchDescriptorReadback(
      LaunchDescriptor message, AuthoredWorldLaunchDescriptorEvidence evidence) {}
}
