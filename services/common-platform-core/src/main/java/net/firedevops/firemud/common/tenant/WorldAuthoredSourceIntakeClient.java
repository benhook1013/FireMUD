package net.firedevops.firemud.common.tenant;

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
import net.firedevops.firemud.worldmanagement.v1.WorldAuthoredSourceIntakeServiceGrpc;

/** Explicit mTLS client for World's authenticated authored-source intake and exact readback. */
public final class WorldAuthoredSourceIntakeClient
    extends AbstractReloadingBlockingGrpcClient<
        WorldAuthoredSourceIntakeServiceGrpc.WorldAuthoredSourceIntakeServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public WorldAuthoredSourceIntakeClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        WorldAuthoredSourceIntakeClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when an owning service explicitly starts this client. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("World authored-source intake client is closed");
    }
    if (initialized) {
      return;
    }
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException(
          "World authored-source intake client did not initialize a gRPC stub");
    }
    initialized = true;
  }

  /** Sends one bounded intake request; transport failures are never retried blindly. */
  public WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt intake(
      WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest request) {
    Objects.requireNonNull(request, "request");
    requireNamespace(request.targetNamespace());
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .intakeAuthoredWorldSource(WorldAuthoredSourceIntakeGrpcCodec.toIntakeRequest(request));
    try {
      return WorldAuthoredSourceIntakeGrpcCodec.fromIntakeResponse(request, response);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "World returned an invalid authored-source intake receipt", exception);
    }
  }

  /** Reads one exact committed World receipt with its separate caller-owned read identity. */
  public WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt read(
      WorldAuthoredSourceIntakeGrpcCodec.ReadRequest request) {
    Objects.requireNonNull(request, "request");
    requireNamespace(request.binding().targetNamespace());
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readAuthoredWorldSourceIntake(
                WorldAuthoredSourceIntakeGrpcCodec.toReadRequest(request));
    try {
      return WorldAuthoredSourceIntakeGrpcCodec.fromReadResponse(request, response);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "World returned an invalid authored-source intake readback", exception);
    }
  }

  /** Reads a complete immutable source receipt by its retained intake identity. */
  public WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt readById(
      WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest request) {
    Objects.requireNonNull(request, "request");
    requireNamespace(request.targetNamespace());
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readAuthoredWorldSourceIntakeById(
                WorldAuthoredSourceIntakeGrpcCodec.toReadByIdRequest(request));
    try {
      return WorldAuthoredSourceIntakeGrpcCodec.fromReadByIdResponse(request, response);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "World returned an invalid authored-source intake by-ID readback", exception);
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
  protected WorldAuthoredSourceIntakeServiceGrpc.WorldAuthoredSourceIntakeServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedWorldPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service";
    return WorldAuthoredSourceIntakeServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedWorldPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedWorldPeerUri))
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

  private WorldAuthoredSourceIntakeServiceGrpc.WorldAuthoredSourceIntakeServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "World authored-source intake client is not initialized and available");
    }
    return currentStub;
  }

  private void requireNamespace(String targetNamespace) {
    if (!workloadNamespace.equals(targetNamespace)) {
      throw new IllegalArgumentException(
          "World authored-source intake request must use the configured workload namespace");
    }
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    return FileBackedGrpcMtlsPolicy.require(
        tlsProperties,
        "World authored-source intake",
        "gRPC mTLS configuration is required",
        "World authored-source intake requires workload mTLS");
  }
}
