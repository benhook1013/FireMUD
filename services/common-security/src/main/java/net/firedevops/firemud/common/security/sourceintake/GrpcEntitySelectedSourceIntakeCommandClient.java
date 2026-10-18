package net.firedevops.firemud.common.security.sourceintake;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeCommandEvidence;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeCommandGrpcCodec;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.entitymanagement.v1.EntitySelectedSourceIntakeCommandServiceGrpc;

/** File-backed mTLS client for Entity's unregistered selected-source command. */
public final class GrpcEntitySelectedSourceIntakeCommandClient
    extends AbstractReloadingBlockingGrpcClient<
        EntitySelectedSourceIntakeCommandServiceGrpc
            .EntitySelectedSourceIntakeCommandServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public GrpcEntitySelectedSourceIntakeCommandClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        SelectedSourceIntakeClientTls.requireFileBackedMtls(
            tlsProperties, "Entity selected-source command"),
        channelFactory,
        GrpcEntitySelectedSourceIntakeCommandClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public synchronized void init() throws SSLException, IOException {
    initOnce(
        "Entity selected-source command client is closed",
        "Entity selected-source command stub unavailable");
  }

  /** Returns only the exact immutable Entity receipt echoed for this complete original request. */
  public EntitySelectedSourceIntakeCommandEvidence retain(
      EntitySelectedSourceIntakeCommandEvidence.Request request) {
    requireWorkloadOnlyContext();
    Objects.requireNonNull(request, "selected-source command request is required");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Entity selected-source command must use the configured workload namespace");
    }
    var wireRequest = EntitySelectedSourceIntakeCommandGrpcCodec.toRequest(request);
    var current =
        requireInitializedStub(
            "Entity selected-source command client is not initialized and available");
    try {
      var response =
          current
              .withMaxOutboundMessageSize(
                  EntitySelectedSourceIntakeCommandGrpcCodec.MAX_REQUEST_WIRE_BYTES)
              .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
              .retainSelectedEntitySource(wireRequest);
      return EntitySelectedSourceIntakeCommandGrpcCodec.fromResponse(request, response);
    } catch (StatusRuntimeException denied) {
      throw Status.fromCode(denied.getStatus().getCode()).asRuntimeException();
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException("Entity returned invalid selected-source receipt evidence");
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getEntityManagementService();
  }

  @Override
  protected String defaultTarget() {
    return "entity-management-service:6565";
  }

  @Override
  protected EntitySelectedSourceIntakeCommandServiceGrpc
          .EntitySelectedSourceIntakeCommandServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = entityServerPeerUri(workloadNamespace);
    return EntitySelectedSourceIntakeCommandServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(
            EntitySelectedSourceIntakeCommandGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
        .withMaxOutboundMessageSize(
            EntitySelectedSourceIntakeCommandGrpcCodec.MAX_REQUEST_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }

  private static void requireWorkloadOnlyContext() {
    requireNoAmbientSql(
        "Entity selected-source command requires no ambient SQL or synchronization");
    SelectedSourceIntakeClientTls.requireWorkloadOnlyContext("Entity selected-source command");
  }

  private static String entityServerPeerUri(String namespace) {
    return "spiffe://firemud/ns/" + namespace + "/sa/entity-management-service";
  }
}
