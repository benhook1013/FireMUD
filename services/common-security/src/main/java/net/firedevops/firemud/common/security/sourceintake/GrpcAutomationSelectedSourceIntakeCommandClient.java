package net.firedevops.firemud.common.security.sourceintake;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.automationscripting.v1.AutomationSelectedSourceIntakeCommandServiceGrpc;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeCommandEvidence;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeCommandEvidence.Request;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeCommandGrpcCodec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/** File-backed mTLS client for Automation's unregistered selected-source command. */
public final class GrpcAutomationSelectedSourceIntakeCommandClient
    extends AbstractReloadingBlockingGrpcClient<
        AutomationSelectedSourceIntakeCommandServiceGrpc
            .AutomationSelectedSourceIntakeCommandServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public GrpcAutomationSelectedSourceIntakeCommandClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        SelectedSourceIntakeClientTls.requireFileBackedMtls(
            tlsProperties, "Automation selected-source command"),
        channelFactory,
        GrpcAutomationSelectedSourceIntakeCommandClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public synchronized void init() throws SSLException, IOException {
    initOnce(
        "Automation selected-source command client is closed",
        "Automation selected-source command stub unavailable");
  }

  /** Returns only the exact immutable receipt echoed for this complete original request. */
  public AutomationSelectedSourceIntakeCommandEvidence retain(Request request) {
    requireWorkloadOnlyContext();
    Objects.requireNonNull(request, "selected-source command request is required");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Automation selected-source command must use the configured workload namespace");
    }
    var wireRequest = AutomationSelectedSourceIntakeCommandGrpcCodec.toRequest(request);
    var current =
        requireInitializedStub(
            "Automation selected-source command client is not initialized and available");
    try {
      var response =
          current
              .withMaxOutboundMessageSize(
                  AutomationSelectedSourceIntakeCommandGrpcCodec.MAX_REQUEST_WIRE_BYTES)
              .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
              .retainSelectedSource(wireRequest);
      return AutomationSelectedSourceIntakeCommandGrpcCodec.fromResponse(request, response);
    } catch (StatusRuntimeException denied) {
      throw Status.fromCode(denied.getStatus().getCode()).asRuntimeException();
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Automation returned invalid selected-source receipt evidence");
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
  protected AutomationSelectedSourceIntakeCommandServiceGrpc
          .AutomationSelectedSourceIntakeCommandServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = automationServerPeerUri(workloadNamespace);
    return AutomationSelectedSourceIntakeCommandServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(
            AutomationSelectedSourceIntakeCommandGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
        .withMaxOutboundMessageSize(
            AutomationSelectedSourceIntakeCommandGrpcCodec.MAX_REQUEST_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }

  private static void requireWorkloadOnlyContext() {
    requireNoAmbientSql(
        "Automation selected-source command requires no ambient SQL or synchronization");
    SelectedSourceIntakeClientTls.requireWorkloadOnlyContext("Automation selected-source command");
  }

  private static String automationServerPeerUri(String namespace) {
    return "spiffe://firemud/ns/" + namespace + "/sa/automation-scripting-service";
  }
}
