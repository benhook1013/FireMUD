package net.firedevops.firemud.common.account.startsession;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountStartSessionWorldParticipationHistoricalReadServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit file-backed mTLS client for Account's unregistered historical World participation read.
 */
public final class AccountStartSessionWorldParticipationHistoricalReadClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountStartSessionWorldParticipationHistoricalReadServiceGrpc
            .AccountStartSessionWorldParticipationHistoricalReadServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public AccountStartSessionWorldParticipationHistoricalReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        AccountStartSessionWorldParticipationHistoricalReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when an owning World component explicitly starts this client. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Account historical participation client is closed");
    }
    if (initialized) return;
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Account historical participation client has no gRPC stub");
    }
    initialized = true;
  }

  /** Reads only the exact retained Account row; it does not admit or renew World execution. */
  public AccountStartSessionWorldParticipationHistoricalReadEvidence read(
      AccountStartSessionWorldParticipationHistoricalReadRequest request) {
    Objects.requireNonNull(request, "historical participation request is required");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Account historical participation request must use the configured workload namespace");
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Account historical participation read must start outside an ambient transaction");
    }
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .withMaxInboundMessageSize(
                AccountStartSessionWorldParticipationHistoricalReadGrpcCodec
                    .MAX_RESPONSE_WIRE_BYTES)
            .readHistoricalStartSessionWorldParticipation(
                AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.toRequest(request));
    try {
      return AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.fromResponse(
          request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Account returned invalid historical World participation evidence", invalid);
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getAccountService();
  }

  @Override
  protected String defaultTarget() {
    return "account-service:6565";
  }

  @Override
  protected AccountStartSessionWorldParticipationHistoricalReadServiceGrpc
          .AccountStartSessionWorldParticipationHistoricalReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
    return AccountStartSessionWorldParticipationHistoricalReadServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withMaxInboundMessageSize(
            AccountStartSessionWorldParticipationHistoricalReadGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
        .withCompression("gzip");
  }

  @Override
  public synchronized void close() throws IOException {
    closed = true;
    initialized = false;
    super.close();
  }

  private AccountStartSessionWorldParticipationHistoricalReadServiceGrpc
          .AccountStartSessionWorldParticipationHistoricalReadServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Account historical participation client is not initialized and available");
    }
    return currentStub;
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException(
          "Account historical participation read requires workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "Account historical participation read requires file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Account historical participation read requires file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Account historical participation read " + label + " must be a readable file-backed path",
          invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Account historical participation read " + label + " must be an existing readable file");
    }
  }
}
