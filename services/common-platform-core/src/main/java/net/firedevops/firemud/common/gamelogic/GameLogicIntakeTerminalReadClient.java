package net.firedevops.firemud.common.gamelogic;

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
import net.firedevops.firemud.gamelogic.v1.GameLogicGameplayRuleIntakeTerminalReadServiceGrpc;

/** Explicit file-backed mTLS client for the exact same-namespace Game Logic terminal read. */
public final class GameLogicIntakeTerminalReadClient
    extends AbstractReloadingBlockingGrpcClient<
        GameLogicGameplayRuleIntakeTerminalReadServiceGrpc
            .GameLogicGameplayRuleIntakeTerminalReadServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public GameLogicIntakeTerminalReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        GameLogicIntakeTerminalReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public synchronized void init() throws SSLException, IOException {
    if (closed) throw new IllegalStateException("Game Logic intake terminal read client is closed");
    if (initialized) return;
    initReloadingClient();
    if (stub() == null)
      throw new IllegalStateException("Game Logic intake terminal read stub unavailable");
    initialized = true;
  }

  /**
   * Returns a validated terminal or explicit absence after exact Game Logic server mTLS
   * verification.
   */
  public GameLogicIntakeTerminalReadEvidence read(
      GameLogicIntakeTerminalReadEvidence.Request request) {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive()
        || org.springframework.transaction.support.TransactionSynchronizationManager
            .isSynchronizationActive())
      throw new IllegalStateException("Game Logic terminal transport must run outside SQL");
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Game Logic intake terminal read must use the configured workload namespace");
    }
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readGameplayRuleIntakeTerminal(
                GameLogicIntakeTerminalReadGrpcCodec.toRequest(request));
    try {
      return GameLogicIntakeTerminalReadGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("Game Logic returned invalid terminal evidence", invalid);
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameLogicService();
  }

  @Override
  protected String defaultTarget() {
    return "game-logic-service:6565";
  }

  @Override
  protected GameLogicGameplayRuleIntakeTerminalReadServiceGrpc
          .GameLogicGameplayRuleIntakeTerminalReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-logic-service";
    return GameLogicGameplayRuleIntakeTerminalReadServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withMaxInboundMessageSize(GameLogicGameplayRuleIntakeTerminal.MAX_WIRE_BYTES)
        .withCompression("gzip");
  }

  @Override
  public synchronized void close() throws IOException {
    closed = true;
    initialized = false;
    super.close();
  }

  private GameLogicGameplayRuleIntakeTerminalReadServiceGrpc
          .GameLogicGameplayRuleIntakeTerminalReadServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Game Logic intake terminal read client is not initialized and available");
    }
    return currentStub;
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException("Game Logic intake terminal read requires workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "Game Logic intake terminal read requires file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Game Logic intake terminal read requires file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Game Logic intake terminal read " + label + " must be a file-backed path", invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Game Logic intake terminal read " + label + " must be an existing readable file");
    }
  }
}
