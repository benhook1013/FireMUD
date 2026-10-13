package net.firedevops.firemud.common.authoring;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedGrpcMtlsPolicy;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.worldmanagement.v1.WorldAuthoredVersionIdentityServiceGrpc;

/** Opt-in file-backed mTLS client for exact authored Version identity association. */
public final class WorldAuthoredVersionIdentityClient
    extends AbstractReloadingBlockingGrpcClient<
        WorldAuthoredVersionIdentityServiceGrpc.WorldAuthoredVersionIdentityServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public WorldAuthoredVersionIdentityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        WorldAuthoredVersionIdentityClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when the owning Game Design service explicitly starts this opt-in client. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("World authored Version association client is closed");
    }
    if (initialized) {
      return;
    }
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("World authored Version association client has no gRPC stub");
    }
    initialized = true;
  }

  /**
   * Applies or recovers the unchanged association request; transport failures propagate unchanged.
   */
  public WorldAuthoredVersionIdentityEvidence.Result associate(
      WorldAuthoredVersionIdentityEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "World authored Version association request must use the configured workload namespace");
    }
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .associateAuthoredWorldVersion(
                WorldAuthoredVersionIdentityGrpcCodec.toRequest(request));
    try {
      return WorldAuthoredVersionIdentityGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "World returned invalid authored Version association evidence", exception);
    }
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
  protected WorldAuthoredVersionIdentityServiceGrpc.WorldAuthoredVersionIdentityServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service";
    return WorldAuthoredVersionIdentityServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(WorldAuthoredVersionIdentityGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }

  @Override
  public synchronized void close() throws IOException {
    closed = true;
    initialized = false;
    super.close();
  }

  public String workloadNamespace() {
    return workloadNamespace;
  }

  private WorldAuthoredVersionIdentityServiceGrpc.WorldAuthoredVersionIdentityServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "World authored Version association client is not initialized and available");
    }
    return currentStub;
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    return FileBackedGrpcMtlsPolicy.require(
        tlsProperties,
        "World authored Version association",
        "World authored Version association requires workload mTLS",
        "World authored Version association requires workload mTLS");
  }
}
