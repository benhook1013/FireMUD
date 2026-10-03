package net.firedevops.firemud.common.tenant;

import io.grpc.CallCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
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
        .withCallCredentials(new ExactWorldServerCallCredentials(expectedWorldPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedWorldPeerUri))
        .withCompression("gzip");
  }

  @Override
  public synchronized void close() throws IOException {
    closed = true;
    initialized = false;
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

  /** Authenticates the negotiated server before gRPC releases request headers or body. */
  private static final class ExactWorldServerCallCredentials extends CallCredentials {
    private final String expectedPeerUri;

    private ExactWorldServerCallCredentials(String expectedPeerUri) {
      this.expectedPeerUri = expectedPeerUri;
    }

    @Override
    public void applyRequestMetadata(
        CallCredentials.RequestInfo requestInfo,
        Executor appExecutor,
        CallCredentials.MetadataApplier applier) {
      var sslSession =
          requestInfo == null || requestInfo.getTransportAttrs() == null
              ? null
              : requestInfo.getTransportAttrs().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
      boolean exactPeer =
          GrpcPeerIdentity.fromSslSession(sslSession)
              .map(peer -> expectedPeerUri.equals(peer.uri()))
              .orElse(false);
      if (!exactPeer) {
        applier.fail(
            Status.UNAUTHENTICATED.withDescription(
                "World intake requires the exact authenticated World workload identity"));
        return;
      }
      applier.apply(new Metadata());
    }
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null) {
      throw new IllegalArgumentException("gRPC mTLS configuration is required");
    }
    if (tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException("World authored-source intake requires workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "World authored-source intake requires file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "World authored-source intake requires file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(
          "World authored-source intake " + label + " must be a readable file-backed path",
          exception);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "World authored-source intake " + label + " must be an existing readable file");
    }
  }
}
