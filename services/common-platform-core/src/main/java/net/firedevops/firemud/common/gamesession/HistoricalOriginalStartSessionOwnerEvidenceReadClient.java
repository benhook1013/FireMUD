package net.firedevops.firemud.common.gamesession;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence.Request;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence.Result;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedGrpcMtlsPolicy;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamesession.v1.HistoricalOriginalStartSessionOwnerEvidenceReadServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit file-backed mTLS client for the unregistered historical Game Session evidence read. */
public final class HistoricalOriginalStartSessionOwnerEvidenceReadClient
    extends AbstractReloadingBlockingGrpcClient<
        HistoricalOriginalStartSessionOwnerEvidenceReadServiceGrpc
            .HistoricalOriginalStartSessionOwnerEvidenceReadServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final int MAX_INBOUND_MESSAGE_BYTES = 16 * 1024 * 1024;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public HistoricalOriginalStartSessionOwnerEvidenceReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        HistoricalOriginalStartSessionOwnerEvidenceReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when a consuming owner explicitly starts this client. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Historical StartSession evidence client is closed");
    }
    if (initialized) {
      return;
    }
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Historical StartSession evidence client has no gRPC stub");
    }
    initialized = true;
  }

  /**
   * Reads immutable evidence outside any owner transaction; the result is not execution authority.
   */
  public Result read(Request request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Historical StartSession evidence read requires no ambient owner transaction");
    }
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.associationSelector().targetNamespace())) {
      throw new IllegalArgumentException(
          "Historical StartSession request must use the configured workload namespace");
    }
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readHistoricalOriginalStartSessionOwnerEvidence(
                HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.toRequest(request));
    try {
      return HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Game Session returned invalid historical original StartSession evidence", invalid);
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
  protected HistoricalOriginalStartSessionOwnerEvidenceReadServiceGrpc
          .HistoricalOriginalStartSessionOwnerEvidenceReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
    return HistoricalOriginalStartSessionOwnerEvidenceReadServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(MAX_INBOUND_MESSAGE_BYTES)
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

  private HistoricalOriginalStartSessionOwnerEvidenceReadServiceGrpc
          .HistoricalOriginalStartSessionOwnerEvidenceReadServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Historical StartSession evidence client is not initialized and available");
    }
    return currentStub;
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    return FileBackedGrpcMtlsPolicy.require(
        tlsProperties,
        "Historical StartSession evidence reads",
        "Historical StartSession evidence reads require mTLS",
        "Historical StartSession evidence reads require mTLS");
  }
}
