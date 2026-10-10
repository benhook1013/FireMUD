package net.firedevops.firemud.common.gamesession;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamesession.v1.OriginalStartSessionCurrentAttemptReadServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit file-backed mTLS client for the unregistered Account-authenticated Game Session read.
 */
public final class GrpcOriginalStartSessionCurrentAttemptClient
    extends AbstractReloadingBlockingGrpcClient<
        OriginalStartSessionCurrentAttemptReadServiceGrpc
            .OriginalStartSessionCurrentAttemptReadServiceBlockingStub>
    implements OriginalStartSessionCurrentAttemptClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public GrpcOriginalStartSessionCurrentAttemptClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        GrpcOriginalStartSessionCurrentAttemptClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when an owning caller explicitly starts this client. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Current original StartSession attempt client is closed");
    }
    if (initialized) {
      return;
    }
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException(
          "Current original StartSession attempt client has no gRPC stub");
    }
    initialized = true;
  }

  /** Reads one bounded live-attempt observation outside all ambient SQL transaction state. */
  @Override
  public Result read(Request request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Current original StartSession attempt read requires no ambient transaction or synchronization");
    }
    Objects.requireNonNull(request, "current original StartSession attempt request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Current original StartSession attempt must use the configured workload namespace");
    }
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readOriginalStartSessionCurrentAttempt(
                OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toRequest(request));
    try {
      return OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Game Session returned invalid current original StartSession attempt evidence", invalid);
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
  protected OriginalStartSessionCurrentAttemptReadServiceGrpc
          .OriginalStartSessionCurrentAttemptReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
    return OriginalStartSessionCurrentAttemptReadServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(
            OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.MAX_RESPONSE_BYTES)
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

  private OriginalStartSessionCurrentAttemptReadServiceGrpc
          .OriginalStartSessionCurrentAttemptReadServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Current original StartSession attempt client is not initialized and available");
    }
    return currentStub;
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException(
          "Current original StartSession attempt reads require workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "Current original StartSession attempt reads require file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Current original StartSession attempt reads require file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Current original StartSession attempt " + label + " must be a readable file-backed path",
          invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Current original StartSession attempt " + label + " must be an existing readable file");
    }
  }
}
