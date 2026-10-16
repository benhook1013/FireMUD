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
import net.firedevops.firemud.worldmanagement.v1.WorldOriginalStartSessionActivationTerminalReadServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit file-backed mTLS client pinned to World in the configured namespace. */
public final class GrpcWorldOriginalStartSessionActivationTerminalReadClient
    extends AbstractReloadingBlockingGrpcClient<
        WorldOriginalStartSessionActivationTerminalReadServiceGrpc
            .WorldOriginalStartSessionActivationTerminalReadServiceBlockingStub>
    implements WorldOriginalStartSessionActivationTerminalReadClient {
  private static final long CALL_DEADLINE_SECONDS = 180L;
  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public GrpcWorldOriginalStartSessionActivationTerminalReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        GrpcWorldOriginalStartSessionActivationTerminalReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("World workload namespace must be canonical");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public synchronized void init() throws SSLException, IOException {
    if (closed) throw new IllegalStateException("World terminal read client is closed");
    if (initialized) return;
    initReloadingClient();
    if (stub() == null)
      throw new IllegalStateException("World terminal read client has no gRPC stub");
    initialized = true;
  }

  @Override
  public WorldOriginalStartSessionActivationTerminalEvidence read(
      WorldOriginalStartSessionActivationTerminalReadInput input) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "World terminal read requires no ambient Account transaction");
    }
    Objects.requireNonNull(input, "World terminal read input required");
    input.requireTargetNamespace(workloadNamespace);
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readOriginalStartSessionActivationTerminalEvidence(
                WorldOriginalStartSessionActivationTerminalReadGrpcCodec.toRequest(
                    input, workloadNamespace));
    try {
      return WorldOriginalStartSessionActivationTerminalReadGrpcCodec.fromResponse(input, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "World returned invalid activation terminal evidence", invalid);
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
  protected WorldOriginalStartSessionActivationTerminalReadServiceGrpc
          .WorldOriginalStartSessionActivationTerminalReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service";
    return WorldOriginalStartSessionActivationTerminalReadServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(
            WorldOriginalStartSessionActivationTerminalReadGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
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

  private WorldOriginalStartSessionActivationTerminalReadServiceGrpc
          .WorldOriginalStartSessionActivationTerminalReadServiceBlockingStub
      requireStub() {
    var current = stub();
    if (closed || !initialized || current == null) {
      throw new IllegalStateException(
          "World terminal read client is not initialized and available");
    }
    return current;
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties properties) {
    if (properties == null || properties.isPlaintext()) {
      throw new IllegalArgumentException("World terminal reads require workload mTLS");
    }
    requireReadableFile(properties.getCertChain(), "certificate chain");
    requireReadableFile(properties.getPrivateKey(), "private key");
    requireReadableFile(properties.getCaCert(), "CA certificate");
    return properties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null
        || configuredPath.isBlank()
        || configuredPath.trim().startsWith("classpath:")) {
      throw new IllegalArgumentException("World terminal reads require file-backed TLS material");
    }
    Path path;
    try {
      path = Path.of(configuredPath.trim());
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("World terminal " + label + " path is invalid", invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException("World terminal " + label + " must be a readable file");
    }
  }
}
