package net.firedevops.firemud.common.entity.sourceintake;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedMtls;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.entitymanagement.v1.EntitySelectedSourceIntakeTerminalReadServiceGrpc;

/** File-backed mTLS client for Entity's exact original selected-source terminal read. */
public final class GrpcEntitySelectedSourceIntakeTerminalReadClient
    extends AbstractReloadingBlockingGrpcClient<
        EntitySelectedSourceIntakeTerminalReadServiceGrpc
            .EntitySelectedSourceIntakeTerminalReadServiceBlockingStub>
    implements EntitySelectedSourceIntakeTerminalReadClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;

  public GrpcEntitySelectedSourceIntakeTerminalReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        FileBackedMtls.require(tlsProperties, "Entity terminal read"),
        channelFactory,
        GrpcEntitySelectedSourceIntakeTerminalReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public void init() throws SSLException, IOException {
    initOnce("Entity terminal-read client is closed", "Entity terminal-read stub unavailable");
  }

  /** Returns only an exact original COMMITTED_EMPTY receipt, outside owner SQL. */
  @Override
  public EntitySelectedSourceIntakeTerminalReadEvidence read(
      EntitySelectedSourceIntakeTerminalReadEvidence.Request request) {
    requireNoAmbientSql("Entity terminal read must run outside owner SQL");
    Objects.requireNonNull(request, "terminal-read request is required");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Entity terminal read must use the configured workload namespace");
    }
    var response =
        requireInitializedStub("Entity terminal-read client is not initialized and available")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readSelectedSourceIntakeTerminal(
                EntitySelectedSourceIntakeTerminalReadGrpcCodec.toRequest(request));
    try {
      return EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Entity returned invalid terminal evidence")
          .withCause(invalid)
          .asRuntimeException();
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
  protected EntitySelectedSourceIntakeTerminalReadServiceGrpc
          .EntitySelectedSourceIntakeTerminalReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/entity-management-service";
    return EntitySelectedSourceIntakeTerminalReadServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(EntitySelectedSourceIntakeTerminalReadGrpcCodec.MAX_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }
}
