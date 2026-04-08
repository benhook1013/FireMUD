package net.firedevops.firemud.gamedesign.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.NotifyScriptVersionUpdateRequest;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.GrpcClientAuth;
import net.firedevops.firemud.common.security.JwtUtil;
import org.springframework.stereotype.Component;

/** gRPC client for Automation & Scripting Service. */
@Component
public class AutomationScriptingClient
    extends AbstractReloadingBlockingGrpcClient<
        AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub> {
  private final JwtUtil jwtUtil;

  public AutomationScriptingClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      JwtUtil jwtUtil,
      GrpcChannelFactory channelFactory) {
    super(endpoints, tlsProps, channelFactory, AutomationScriptingClient.class);
    this.jwtUtil = jwtUtil;
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
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
  protected AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return GrpcClientAuth.attach(
        AutomationScriptingServiceGrpc.newBlockingStub(channel).withCompression("gzip"), jwtUtil);
  }

  /** Notify the Automation service that a new script patch version is active. */
  public void notifyScriptVersionUpdate(
      String tenantId, String patchVersion, List<String> scripts) {
    NotifyScriptVersionUpdateRequest request =
        NotifyScriptVersionUpdateRequest.newBuilder()
            .setTenantId(tenantId)
            .setScriptPatchVersion(patchVersion)
            .addAllAffectedScripts(scripts)
            .build();
    stub().notifyScriptVersionUpdate(request);
  }
}
