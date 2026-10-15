package net.firedevops.firemud.common.authoring;

import io.grpc.ManagedChannel;
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
import net.firedevops.firemud.worldmanagement.v1.WorldOriginalDraftGraphApplyServiceGrpc;

/** Opt-in file-backed mTLS client for exact original World graph apply/recovery. */
public final class WorldOriginalDraftGraphApplyClient
    extends AbstractReloadingBlockingGrpcClient<
        WorldOriginalDraftGraphApplyServiceGrpc.WorldOriginalDraftGraphApplyServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public WorldOriginalDraftGraphApplyClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        FileBackedMtls.require(tlsProperties, "World original graph apply"),
        channelFactory,
        WorldOriginalDraftGraphApplyClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when the owning Game Design service explicitly starts this opt-in client. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("World original graph apply client is closed");
    }
    if (initialized) {
      return;
    }
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("World original graph apply client has no gRPC stub");
    }
    initialized = true;
  }

  /**
   * Applies or recovers the unchanged original operation; transport failures propagate unchanged.
   */
  public WorldOriginalDraftGraphApplyEvidence.Result apply(
      WorldOriginalDraftGraphApplyEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "World original graph apply request must use the configured workload namespace");
    }
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .applyOriginalDraftGraph(WorldOriginalDraftGraphApplyGrpcCodec.toRequest(request));
    try {
      return WorldOriginalDraftGraphApplyGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "World returned invalid original graph apply evidence", exception);
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
  protected WorldOriginalDraftGraphApplyServiceGrpc.WorldOriginalDraftGraphApplyServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service";
    return WorldOriginalDraftGraphApplyServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(WorldOriginalDraftGraphApplyGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }

  @Override
  public void close() throws IOException {
    synchronized (this) {
      closed = true;
      initialized = false;
    }
    super.close();
  }

  public String workloadNamespace() {
    return workloadNamespace;
  }

  private WorldOriginalDraftGraphApplyServiceGrpc.WorldOriginalDraftGraphApplyServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "World original graph apply client is not initialized and available");
    }
    return currentStub;
  }
}
