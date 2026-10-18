package net.firedevops.firemud.common.automation.sourceintake;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.automationscripting.v1.AutomationSelectedSourceIntakeTerminalReadServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedMtls;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/** File-backed mTLS client for Automation's exact original selected-source terminal read. */
public final class AutomationSelectedSourceIntakeTerminalReadClient
    extends AbstractReloadingBlockingGrpcClient<
        AutomationSelectedSourceIntakeTerminalReadServiceGrpc
            .AutomationSelectedSourceIntakeTerminalReadServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public AutomationSelectedSourceIntakeTerminalReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        FileBackedMtls.require(tlsProperties, "Automation terminal read"),
        channelFactory,
        AutomationSelectedSourceIntakeTerminalReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws SSLException, IOException {
    initOnce(
        "Automation terminal-read client is closed", "Automation terminal-read stub unavailable");
  }

  /** Returns only an exact original COMMITTED_EMPTY receipt, outside owner SQL. */
  public AutomationSelectedSourceIntakeTerminalReadEvidence read(
      AutomationSelectedSourceIntakeTerminalReadEvidence.Request request) {
    requireNoAmbientSql("Automation terminal read must run outside owner SQL");
    Objects.requireNonNull(request, "terminal-read request is required");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Automation terminal read must use the configured workload namespace");
    }
    var response =
        requireInitializedStub("Automation terminal-read client is not initialized and available")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readSelectedSourceIntakeTerminal(
                AutomationSelectedSourceIntakeTerminalReadGrpcCodec.toRequest(request));
    try {
      return AutomationSelectedSourceIntakeTerminalReadGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Automation returned invalid terminal evidence")
          .withCause(invalid)
          .asRuntimeException();
    }
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
  protected AutomationSelectedSourceIntakeTerminalReadServiceGrpc
          .AutomationSelectedSourceIntakeTerminalReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/automation-scripting-service";
    return AutomationSelectedSourceIntakeTerminalReadServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(
            AutomationSelectedSourceIntakeTerminalReadGrpcCodec.MAX_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }
}
