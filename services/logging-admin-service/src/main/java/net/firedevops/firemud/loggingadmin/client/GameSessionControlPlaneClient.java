package net.firedevops.firemud.loggingadmin.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.v1.ExecutePreparedVersionCutoverRequest;
import net.firedevops.firemud.gamesession.v1.ExecutePreparedVersionCutoverResponse;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateRequest;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import net.firedevops.firemud.gamesession.v1.GetGameSessionPinConvergenceRequest;
import net.firedevops.firemud.gamesession.v1.GetGameSessionPinConvergenceResponse;
import net.firedevops.firemud.gamesession.v1.GetPinnedScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.GetPinnedScriptPatchVersionResponse;
import net.firedevops.firemud.gamesession.v1.GetPreparedVersionUpgradeRequest;
import net.firedevops.firemud.gamesession.v1.GetPreparedVersionUpgradeResponse;
import net.firedevops.firemud.gamesession.v1.GetRuntimeOwnershipStatusRequest;
import net.firedevops.firemud.gamesession.v1.GetRuntimeOwnershipStatusResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersResponse;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeRequest;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.PrepareVersionUpgradeRequest;
import net.firedevops.firemud.gamesession.v1.PrepareVersionUpgradeResponse;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeRequest;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerRequest;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerResponse;
import net.firedevops.firemud.gamesession.v1.ValidateInstanceCutoverCompatibilityRequest;
import net.firedevops.firemud.gamesession.v1.ValidateInstanceCutoverCompatibilityResponse;
import org.springframework.stereotype.Component;

@Component
public class GameSessionControlPlaneClient
    extends AbstractReloadingBlockingGrpcClient<
        GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub> {
  public GameSessionControlPlaneClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer, GameSessionControlPlaneClient.class);
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameSessionService();
  }

  @Override
  protected String defaultTarget() {
    return "game-session-service:6565";
  }

  @Override
  protected GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        GameSessionControlPlaneServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  public ListAdmissionPointersResponse listAdmissionPointers() {
    return stub().listAdmissionPointers(ListAdmissionPointersRequest.getDefaultInstance());
  }

  public ListAdmissionPointerAuditResponse listAdmissionPointerAudit(
      long tenantId, String worldSlug, String realmSlug) {
    return stub()
        .listAdmissionPointerAudit(
            ListAdmissionPointerAuditRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setWorldSlug(worldSlug)
                .setRealmSlug(realmSlug)
                .build());
  }

  public SetAdmissionPointerResponse setAdmissionPointer(SetAdmissionPointerRequest request) {
    return stub().setAdmissionPointer(request);
  }

  public ExecutePreparedVersionCutoverResponse executePreparedVersionCutover(
      ExecutePreparedVersionCutoverRequest request) {
    return stub().executePreparedVersionCutover(request);
  }

  public PrepareVersionUpgradeResponse prepareVersionUpgrade(PrepareVersionUpgradeRequest request) {
    return stub().prepareVersionUpgrade(request);
  }

  public GetPreparedVersionUpgradeResponse getPreparedVersionUpgrade(
      GetPreparedVersionUpgradeRequest request) {
    return stub().getPreparedVersionUpgrade(request);
  }

  public ValidateInstanceCutoverCompatibilityResponse validateInstanceCutoverCompatibility(
      long tenantId, long sourceGameInstanceId, long targetVersionId) {
    return stub()
        .validateInstanceCutoverCompatibility(
            ValidateInstanceCutoverCompatibilityRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setSourceGameInstanceId(Long.toString(sourceGameInstanceId))
                .setTargetVersionId(Long.toString(targetVersionId))
                .build());
  }

  public GetGameInstanceRuntimeStateResponse getGameInstanceRuntimeState(
      long tenantId, long gameInstanceId) {
    return stub()
        .getGameInstanceRuntimeState(
            GetGameInstanceRuntimeStateRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setGameInstanceId(Long.toString(gameInstanceId))
                .build());
  }

  public GetPinnedScriptPatchVersionResponse getPinnedScriptPatchVersion(
      long tenantId, long gameInstanceId) {
    return stub()
        .getPinnedScriptPatchVersion(
            GetPinnedScriptPatchVersionRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setGameInstanceId(Long.toString(gameInstanceId))
                .build());
  }

  public GetGameSessionPinConvergenceResponse getGameSessionPinConvergence(
      long tenantId, long gameInstanceId) {
    return stub()
        .getGameSessionPinConvergence(
            GetGameSessionPinConvergenceRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setGameInstanceId(Long.toString(gameInstanceId))
                .build());
  }

  public GetRuntimeOwnershipStatusResponse getRuntimeOwnershipStatus(
      long tenantId, String gameInstanceId, String regionId) {
    GetRuntimeOwnershipStatusRequest.Builder builder =
        GetRuntimeOwnershipStatusRequest.newBuilder().setTenantId(Long.toString(tenantId));
    if (gameInstanceId != null && !gameInstanceId.isBlank()) {
      builder.setGameInstanceId(gameInstanceId);
    }
    if (regionId != null && !regionId.isBlank()) {
      builder.setRegionId(regionId);
    }
    return stub().getRuntimeOwnershipStatus(builder.build());
  }

  public PauseTicksForScopeResponse pauseTicksForScope(PauseTicksForScopeRequest request) {
    return stub().pauseTicksForScope(request);
  }

  public ResumeTicksForScopeResponse resumeTicksForScope(ResumeTicksForScopeRequest request) {
    return stub().resumeTicksForScope(request);
  }
}
