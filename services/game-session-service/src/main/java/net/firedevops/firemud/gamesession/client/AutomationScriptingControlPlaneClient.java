package net.firedevops.firemud.gamesession.client;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingControlPlaneServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.GetPluginStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetPluginStatusResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public final class AutomationScriptingControlPlaneClient
    extends AbstractBlockingGrpcClient<
        AutomationScriptingControlPlaneServiceGrpc
            .AutomationScriptingControlPlaneServiceBlockingStub> {
  private static final Logger LOG =
      LoggerFactory.getLogger(AutomationScriptingControlPlaneClient.class);
  private static final long PLUGIN_STATUS_DEADLINE_MILLIS = 250L;
  private static final long SCRIPT_PATCH_STATUS_DEADLINE_MILLIS = 2_000L;

  public AutomationScriptingControlPlaneClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties grpcClientProperties,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, grpcClientProperties, channelFactory, stubCustomizer);
  }

  @PostConstruct
  void init() throws Exception {
    initClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getAutomationScriptingService();
  }

  @Override
  protected String defaultTarget() {
    return "automation-scripting-service:6565";
  }

  @Override
  protected AutomationScriptingControlPlaneServiceGrpc
          .AutomationScriptingControlPlaneServiceBlockingStub
      buildStub(io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        AutomationScriptingControlPlaneServiceGrpc.newBlockingStub(channel)
            .withCompression("gzip"));
  }

  public GetPluginStatusResponse getPluginStatus(
      long tenantId, long gameInstanceId, String pluginId) {
    if (stub() == null) {
      return unavailable();
    }
    try {
      return stub()
          .withDeadlineAfter(PLUGIN_STATUS_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
          .getPluginStatus(
              GetPluginStatusRequest.newBuilder()
                  .setTenantId(Long.toString(tenantId))
                  .setGameInstanceId(Long.toString(gameInstanceId))
                  .setPluginId(pluginId)
                  .build());
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() != Status.Code.UNAVAILABLE) {
        throw ex;
      }
      LOG.warn("Automation & Scripting getPluginStatus failed", ex);
      return unavailable();
    }
  }

  /** Reads the authoritative tenant-scoped readiness projection for one script patch. */
  public GetScriptPatchStatusResponse getScriptPatchStatus(
      long tenantId, String scriptPatchVersion) {
    if (stub() == null) {
      return unavailablePatchStatus();
    }
    try {
      return stub()
          .withDeadlineAfter(SCRIPT_PATCH_STATUS_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
          .getScriptPatchStatus(
              GetScriptPatchStatusRequest.newBuilder()
                  .setTenantId(Long.toString(tenantId))
                  .setScriptPatchVersion(scriptPatchVersion)
                  .build());
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() != Status.Code.UNAVAILABLE) {
        throw ex;
      }
      LOG.warn("Automation & Scripting getScriptPatchStatus failed", ex);
      return unavailablePatchStatus();
    }
  }

  private static GetPluginStatusResponse unavailable() {
    return GetPluginStatusResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("AUTOMATION_SCRIPTING_UNAVAILABLE")
                .setMessage("Automation & Scripting service unavailable"))
        .build();
  }

  private static GetScriptPatchStatusResponse unavailablePatchStatus() {
    return GetScriptPatchStatusResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("AUTOMATION_SCRIPTING_UNAVAILABLE")
                .setMessage("Automation & Scripting service unavailable"))
        .build();
  }
}
