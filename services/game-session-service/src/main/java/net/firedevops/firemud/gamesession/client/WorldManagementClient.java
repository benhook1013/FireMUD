package net.firedevops.firemud.gamesession.client;

import jakarta.annotation.PostConstruct;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.worldmanagement.v1.AcquireInitialAdmissionBindHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.AcquireInitialAdmissionBindHoldResponse;
import net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.FailPreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.FailPreparedWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleRequest;
import net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleResponse;
import net.firedevops.firemud.worldmanagement.v1.PingRequest;
import net.firedevops.firemud.worldmanagement.v1.PingResponse;
import net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.ValidateWorldUpgradeMappingsRequest;
import net.firedevops.firemud.worldmanagement.v1.ValidateWorldUpgradeMappingsResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import org.springframework.stereotype.Component;

/** gRPC client for the World Management Service. */
@Component
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
    return prepareWorldInstance(
        tenantId,
        gameInstanceId,
        gameTemplateId,
        controlPlaneRequestId,
        launchDescriptorId,
        versionId,
        scriptPatchVersion,
        runtimeFlagsJson,
        generationConfigRevision,
        releaseBundleId,
        publishedReleaseBundleRef,
        versionStateEpoch,
        null);
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
      long versionStateEpoch,
      String remapSetId) {
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
    if (remapSetId != null && !remapSetId.isBlank()) {
      builder.setRemapSetId(remapSetId);
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

  public GetWorldInstanceLifecycleResponse getWorldInstanceLifecycle(
      long tenantId, long gameInstanceId) {
    return callStub()
        .getWorldInstanceLifecycle(
            GetWorldInstanceLifecycleRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setGameInstanceId(Long.toString(gameInstanceId))
                .build());
  }

  /** Acquires the one-shot World hold for an exact initial admission pointer bind. */
  public AcquireInitialAdmissionBindHoldResponse acquireInitialAdmissionBindHold(
      long tenantId,
      long gameInstanceId,
      long versionId,
      long activeLifecycleEpoch,
      String initialAdmissionRequestId,
      String requestDigest,
      UUID realmId,
      UUID playableStateNamespaceId,
      PlayableStateScope playableStateScope,
      long catalogRevision) {
    return callStub()
        .acquireInitialAdmissionBindHold(
            buildAcquireInitialAdmissionBindHoldRequest(
                tenantId,
                gameInstanceId,
                versionId,
                activeLifecycleEpoch,
                initialAdmissionRequestId,
                requestDigest,
                realmId,
                playableStateNamespaceId,
                playableStateScope,
                catalogRevision));
  }

  static AcquireInitialAdmissionBindHoldRequest buildAcquireInitialAdmissionBindHoldRequest(
      long tenantId,
      long gameInstanceId,
      long versionId,
      long activeLifecycleEpoch,
      String initialAdmissionRequestId,
      String requestDigest,
      UUID realmId,
      UUID playableStateNamespaceId,
      PlayableStateScope playableStateScope,
      long catalogRevision) {
    if (tenantId <= 0L
        || gameInstanceId <= 0L
        || versionId <= 0L
        || activeLifecycleEpoch <= 0L
        || catalogRevision <= 0L
        || initialAdmissionRequestId == null
        || initialAdmissionRequestId.isBlank()
        || initialAdmissionRequestId.length() > 128
        || requestDigest == null
        || !requestDigest.matches("[0-9a-f]{64}")
        || realmId == null
        || playableStateNamespaceId == null
        || playableStateScope != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED) {
      throw new IllegalArgumentException("Initial admission bind request is incomplete");
    }
    return AcquireInitialAdmissionBindHoldRequest.newBuilder()
        .setTenantId(Long.toString(tenantId))
        .setGameInstanceId(Long.toString(gameInstanceId))
        .setVersionId(Long.toString(versionId))
        .setExpectedActiveLifecycleEpoch(activeLifecycleEpoch)
        .setInitialAdmissionRequestId(initialAdmissionRequestId)
        .setRequestDigest(requestDigest)
        .setRealmUuid(realmId.toString())
        .setPlayableStateNamespaceUuid(playableStateNamespaceId.toString())
        .setPlayableStateScope(playableStateScope)
        .setExpectedNoPriorPointer(true)
        .setExpectedCatalogRevision(catalogRevision)
        .build();
  }

  public TerminateWorldInstanceResponse terminateWorldInstance(
      long tenantId,
      long gameInstanceId,
      long expectedLifecycleEpoch,
      String terminationRequestId,
      String reason) {
    TerminateWorldInstanceRequest.Builder builder =
        TerminateWorldInstanceRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setGameInstanceId(Long.toString(gameInstanceId))
            .setExpectedLifecycleEpoch(expectedLifecycleEpoch)
            .setTerminationRequestId(terminationRequestId);
    if (reason != null && !reason.isBlank()) {
      builder.setReason(reason);
    }
    return callStub().terminateWorldInstance(builder.build());
  }

  public ValidateWorldUpgradeMappingsResponse validateWorldUpgradeMappings(
      long tenantId, long sourceGameInstanceId, long targetVersionId, String remapSetId) {
    ValidateWorldUpgradeMappingsRequest.Builder builder =
        ValidateWorldUpgradeMappingsRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setSourceGameInstanceId(Long.toString(sourceGameInstanceId))
            .setTargetVersionId(Long.toString(targetVersionId));
    if (remapSetId != null && !remapSetId.isBlank()) {
      builder.setRemapSetId(remapSetId);
    }
    return callStub().validateWorldUpgradeMappings(builder.build());
  }

  private WorldManagementServiceGrpc.WorldManagementServiceBlockingStub callStub() {
    return stub().withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
  }
}
