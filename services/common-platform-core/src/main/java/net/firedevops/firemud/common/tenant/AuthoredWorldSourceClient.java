package net.firedevops.firemud.common.tenant;

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
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;

/** Explicit mTLS client for exact, non-mutating Game Design authored-world source reads. */
public final class AuthoredWorldSourceClient
    extends AbstractReloadingBlockingGrpcClient<
        TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public AuthoredWorldSourceClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        AuthoredWorldSourceClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when an owning service explicitly starts this client. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Authored-world source client is closed");
    }
    if (initialized) {
      return;
    }
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException(
          "Authored-world source client did not initialize a gRPC stub");
    }
    initialized = true;
  }

  /** Reads the exact persisted source receipt without registering a World association. */
  public AuthoredWorldSourceEvidence read(AuthoredWorldSourceGrpcCodec.ReadRequest request) {
    Objects.requireNonNull(request, "request");
    requireNamespace(request.targetNamespace());
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub currentStub = requireStub();
    var response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .resolveAuthoredWorldSource(AuthoredWorldSourceGrpcCodec.toReadRequest(request));
    try {
      return AuthoredWorldSourceGrpcCodec.fromReadResponse(request, response);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Game Design returned invalid authored-world source evidence", exception);
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
  protected TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub buildStub(
      ManagedChannel channel) {
    return TenantIdentityServiceGrpc.newBlockingStub(channel)
        .withInterceptors(
            new GrpcServerPeerIdentityClientInterceptor(
                "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-design-service"))
        .withCompression("gzip");
  }

  @Override
  public synchronized void close() throws IOException {
    closed = true;
    initialized = false;
    super.close();
  }

  private TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub requireStub() {
    TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Authored-world source client is not initialized and available");
    }
    return currentStub;
  }

  private void requireNamespace(String targetNamespace) {
    if (!workloadNamespace.equals(targetNamespace)) {
      throw new IllegalArgumentException(
          "Authored-world source request must use the configured workload namespace");
    }
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null) {
      throw new IllegalArgumentException("gRPC mTLS configuration is required");
    }
    if (tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException("Authored-world source reads require workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "Authored-world source reads require file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Authored-world source reads require file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(
          "Authored-world source " + label + " must be a readable file-backed path", exception);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Authored-world source " + label + " must be an existing readable file");
    }
  }
}
