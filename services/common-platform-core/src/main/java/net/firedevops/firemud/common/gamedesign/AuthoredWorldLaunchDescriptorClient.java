package net.firedevops.firemud.common.gamedesign;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;

/**
 * Explicit mTLS client for exact authored-world launch descriptor resolution and owner readback.
 */
public final class AuthoredWorldLaunchDescriptorClient
    extends AbstractReloadingBlockingGrpcClient<
        GameDesignServiceGrpc.GameDesignServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public AuthoredWorldLaunchDescriptorClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        AuthoredWorldLaunchDescriptorClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when an owning service explicitly starts this client. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Authored-world launch descriptor client is closed");
    }
    if (initialized) {
      return;
    }
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException(
          "Authored-world launch descriptor client did not initialize a gRPC stub");
    }
    initialized = true;
  }

  /** Resolves a descriptor for exactly the supplied authored-world request tuple. */
  public AuthoredWorldLaunchDescriptorEvidence resolve(
      AuthoredWorldLaunchDescriptorEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    requireNamespace(request.targetNamespace());
    GameDesignServiceGrpc.GameDesignServiceBlockingStub currentStub = requireStub();
    var response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .resolveLaunchDescriptor(
                AuthoredWorldLaunchDescriptorGrpcCodec.toResolveRequest(request));
    try {
      return AuthoredWorldLaunchDescriptorGrpcCodec.fromResolveResponse(request, response);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Game Design returned invalid authored-world launch descriptor evidence", exception);
    }
  }

  /** Reads back the immutable result using a separate read request UUID and the complete tuple. */
  public AuthoredWorldLaunchDescriptorEvidence get(
      AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest request) {
    Objects.requireNonNull(request, "request");
    requireNamespace(request.expectedRequest().targetNamespace());
    GameDesignServiceGrpc.GameDesignServiceBlockingStub currentStub = requireStub();
    var response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .getLaunchDescriptor(AuthoredWorldLaunchDescriptorGrpcCodec.toGetRequest(request));
    try {
      return AuthoredWorldLaunchDescriptorGrpcCodec.fromGetResponse(request, response);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Game Design returned invalid exact authored-world launch descriptor readback",
          exception);
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameDesignService();
  }

  @Override
  protected String defaultTarget() {
    return "game-design-service:6565";
  }

  @Override
  protected GameDesignServiceGrpc.GameDesignServiceBlockingStub buildStub(ManagedChannel channel) {
    String expectedGameDesignPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-design-service";
    return GameDesignServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedGameDesignPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedGameDesignPeerUri))
        .withCompression("gzip");
  }

  @Override
  public synchronized void close() throws IOException {
    closed = true;
    initialized = false;
    super.close();
  }

  private GameDesignServiceGrpc.GameDesignServiceBlockingStub requireStub() {
    GameDesignServiceGrpc.GameDesignServiceBlockingStub currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Authored-world launch descriptor client is not initialized and available");
    }
    return currentStub;
  }

  private void requireNamespace(String targetNamespace) {
    if (!workloadNamespace.equals(targetNamespace)) {
      throw new IllegalArgumentException(
          "Launch descriptor request must use the configured workload namespace");
    }
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null) {
      throw new IllegalArgumentException("gRPC mTLS configuration is required");
    }
    if (tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException("Launch descriptor reads require workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "Launch descriptor reads require file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Launch descriptor reads require file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(
          "Launch descriptor " + label + " must be a readable file-backed path", exception);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Launch descriptor " + label + " must be an existing readable file");
    }
  }
}
