package net.firedevops.firemud.gamesession.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.automationscripting.v1.AutomationRuntimeFamilyCatalogReadServiceGrpc;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;

/**
 * Manual concrete source consumer only; no owner-set enumeration, FRESH coordinator or
 * registration.
 */
public final class AutomationRuntimeFamilyCatalogClient
    extends AbstractReloadingBlockingGrpcClient<
        AutomationRuntimeFamilyCatalogReadServiceGrpc
            .AutomationRuntimeFamilyCatalogReadServiceBlockingStub> {
  private final String namespace;

  public AutomationRuntimeFamilyCatalogClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Automation runtime catalog"),
        channels,
        AutomationRuntimeFamilyCatalogClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Fixed workload namespace required");
    this.namespace = namespace;
  }

  public void init() throws IOException {
    requireNoAmbientSql("Catalog client initialization requires no ambient SQL");
    initOnce("Automation catalog client closed", "Automation catalog client unavailable");
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
  protected AutomationRuntimeFamilyCatalogReadServiceGrpc
          .AutomationRuntimeFamilyCatalogReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/automation-scripting-service";
    return AutomationRuntimeFamilyCatalogReadServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer));
  }

  /** Complete exact evidence or refusal; unavailable is not empty or initialized state. */
  public Evidence read(Request request) {
    requireNoAmbientSql("Automation catalog RPC requires no ambient SQL");
    Objects.requireNonNull(request, "request");
    if (!namespace.equals(request.targetNamespace()))
      throw new IllegalArgumentException("Wrong workload namespace");
    var response =
        requireInitializedStub("Automation catalog client unavailable")
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .withMaxInboundMessageSize(AutomationRuntimeFamilyCatalogCodec.MAX_RESPONSE_BYTES)
            .withMaxOutboundMessageSize(AutomationRuntimeFamilyCatalogCodec.MAX_REQUEST_BYTES)
            .readAutomationRuntimeFamilyCatalog(
                AutomationRuntimeFamilyCatalogCodec.toRequest(request));
    return AutomationRuntimeFamilyCatalogCodec.fromResponse(request, response);
  }
}
