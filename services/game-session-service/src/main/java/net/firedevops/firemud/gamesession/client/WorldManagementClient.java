package net.firedevops.firemud.gamesession.client;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.FailPreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.FailPreparedWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.PingRequest;
import net.firedevops.firemud.worldmanagement.v1.PingResponse;
import net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** gRPC client for the World Management Service. */
@Component
@ConditionalOnProperty(
    name = "game-session.dev-isolated",
    havingValue = "false",
    matchIfMissing = false)
public final class WorldManagementClient
    extends AbstractBlockingGrpcClient<
        WorldManagementServiceGrpc.WorldManagementServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  public WorldManagementClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer);
  }

  @PostConstruct
  void init() throws SSLException {
    initClient();
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
  protected WorldManagementServiceGrpc.WorldManagementServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        WorldManagementServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  /** Simple ping to verify connectivity. */
  public PingResponse ping() {
    return callStub().ping(PingRequest.newBuilder().build());
  }

  public PrepareWorldInstanceResponse prepareWorldInstance(
      long tenantId,
      long gameInstanceId,
      long gameTemplateId,
      String controlPlaneRequestId,
      String launchDescriptorId,
      long versionId,
      String scriptPatchVersion,
      String runtimeFlagsJson,
      String generationConfigRevision,
      long releaseBundleId,
      String publishedReleaseBundleRef,
      long versionStateEpoch) {
    PrepareWorldInstanceRequest.Builder builder =
        PrepareWorldInstanceRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setGameInstanceId(Long.toString(gameInstanceId))
            .setGameTemplateId(Long.toString(gameTemplateId))
            .setControlPlaneRequestId(controlPlaneRequestId)
            .setLaunchDescriptorId(launchDescriptorId)
            .setVersionId(Long.toString(versionId))
            .setRuntimeFlagsJson(runtimeFlagsJson == null ? "{}" : runtimeFlagsJson)
            .setGenerationConfigRevision(generationConfigRevision)
            .setReleaseBundleId(Long.toString(releaseBundleId))
            .setPublishedReleaseBundleRef(publishedReleaseBundleRef)
            .setVersionStateEpoch(versionStateEpoch);
    if (scriptPatchVersion != null && !scriptPatchVersion.isBlank()) {
      builder.setScriptPatchVersion(scriptPatchVersion);
    }
    return callStub().prepareWorldInstance(builder.build());
  }

  public ActivatePreparedWorldInstanceResponse activatePreparedWorldInstance(
      long tenantId, long gameInstanceId, long expectedLifecycleEpoch) {
    return callStub()
        .activatePreparedWorldInstance(
            ActivatePreparedWorldInstanceRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setGameInstanceId(Long.toString(gameInstanceId))
                .setExpectedLifecycleEpoch(expectedLifecycleEpoch)
                .build());
  }

  public FailPreparedWorldInstanceResponse failPreparedWorldInstance(
      long tenantId, long gameInstanceId, long expectedLifecycleEpoch, String reason) {
    FailPreparedWorldInstanceRequest.Builder builder =
        FailPreparedWorldInstanceRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setGameInstanceId(Long.toString(gameInstanceId))
            .setExpectedLifecycleEpoch(expectedLifecycleEpoch);
    if (reason != null && !reason.isBlank()) {
      builder.setReason(reason);
    }
    return callStub().failPreparedWorldInstance(builder.build());
  }

  private WorldManagementServiceGrpc.WorldManagementServiceBlockingStub callStub() {
    return stub().withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
  }
}
