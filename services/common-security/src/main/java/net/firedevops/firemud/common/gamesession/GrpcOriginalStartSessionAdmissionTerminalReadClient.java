package net.firedevops.firemud.common.gamesession;

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
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.OriginalStartSessionAdmissionTerminalReadServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit file-backed mTLS client for the unregistered Account-facing Game Session terminal read.
 */
public final class GrpcOriginalStartSessionAdmissionTerminalReadClient
    extends AbstractReloadingBlockingGrpcClient<
        OriginalStartSessionAdmissionTerminalReadServiceGrpc
            .OriginalStartSessionAdmissionTerminalReadServiceBlockingStub>
    implements OriginalStartSessionAdmissionTerminalReadClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public GrpcOriginalStartSessionAdmissionTerminalReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        GrpcOriginalStartSessionAdmissionTerminalReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when a consuming service explicitly starts this client. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Original StartSession terminal-read client is closed");
    }
    if (initialized) {
      return;
    }
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Original StartSession terminal-read stub is unavailable");
    }
    initialized = true;
  }

  /** Reads exact terminal evidence without treating it as current claim or admission authority. */
  @Override
  public OriginalStartSessionAdmissionTerminalResult read(
      OriginalStartSessionAdmissionTerminalRequest request) {
    if (SessionContext.hasAuthenticatedCallerContext()) {
      throw new IllegalStateException(
          "Original StartSession terminal read requires a workload-only caller context");
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Original StartSession terminal read must run outside ambient SQL");
    }
    Objects.requireNonNull(request, "original StartSession terminal-read request is required");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Original StartSession terminal-read request must use the configured workload namespace");
    }
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readOriginalStartSessionAdmissionTerminal(
                OriginalStartSessionAdmissionTerminalGrpcCodec.toRequest(request));
    try {
      return OriginalStartSessionAdmissionTerminalGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Game Session returned invalid original StartSession terminal evidence", invalid);
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameSessionService();
  }

  @Override
  protected String defaultTarget() {
    return "game-session-service:6565";
  }

  @Override
  protected OriginalStartSessionAdmissionTerminalReadServiceGrpc
          .OriginalStartSessionAdmissionTerminalReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
    return OriginalStartSessionAdmissionTerminalReadServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(
            OriginalStartSessionAdmissionTerminalGrpcCodec.MAX_RESPONSE_BYTES)
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

  private OriginalStartSessionAdmissionTerminalReadServiceGrpc
          .OriginalStartSessionAdmissionTerminalReadServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Original StartSession terminal-read client is not initialized and available");
    }
    return currentStub;
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException("Original StartSession terminal reads require mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "Original StartSession terminal reads require file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Original StartSession terminal reads require file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Original StartSession terminal-read " + label + " must be file-backed", invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Original StartSession terminal-read " + label + " must be an existing readable file");
    }
  }
}
