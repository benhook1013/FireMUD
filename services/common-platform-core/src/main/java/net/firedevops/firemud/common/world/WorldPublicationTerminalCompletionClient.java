package net.firedevops.firemud.common.world;

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
import net.firedevops.firemud.worldmanagement.v1.WorldPublicationTerminalCompletionServiceGrpc;

/** Explicit mTLS client for exact authenticated World publication terminal completion. */
public final class WorldPublicationTerminalCompletionClient
    extends AbstractReloadingBlockingGrpcClient<
        WorldPublicationTerminalCompletionServiceGrpc
            .WorldPublicationTerminalCompletionServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public WorldPublicationTerminalCompletionClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        WorldPublicationTerminalCompletionClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when the owning Game Design composition explicitly starts this client. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("World publication terminal completion client is closed");
    }
    if (initialized) return;
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException(
          "World publication terminal completion client has no gRPC stub");
    }
    initialized = true;
  }

  /**
   * Sends the complete original operation and immutable terminal evidence, then verifies exact
   * echo.
   */
  public WorldPublicationTerminalCompletionGrpcCodec.Response complete(
      WorldPublicationTerminalCompletionGrpcCodec.Request request) {
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "World terminal completion must use the configured workload namespace");
    }
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .completeWorldPublicationTerminal(
                WorldPublicationTerminalCompletionGrpcCodec.toRequest(request));
    try {
      return WorldPublicationTerminalCompletionGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "World returned invalid publication terminal completion evidence", invalid);
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
  protected WorldPublicationTerminalCompletionServiceGrpc
          .WorldPublicationTerminalCompletionServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service";
    return WorldPublicationTerminalCompletionServiceGrpc.newBlockingStub(channel)
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

  private WorldPublicationTerminalCompletionServiceGrpc
          .WorldPublicationTerminalCompletionServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "World publication terminal completion client is not initialized and available");
    }
    return currentStub;
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException(
          "World publication terminal completion requires workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "World publication terminal completion requires file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "World publication terminal completion requires file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "World publication terminal completion " + label + " must be a readable file-backed path",
          invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "World publication terminal completion " + label + " must be an existing readable file");
    }
  }
}
