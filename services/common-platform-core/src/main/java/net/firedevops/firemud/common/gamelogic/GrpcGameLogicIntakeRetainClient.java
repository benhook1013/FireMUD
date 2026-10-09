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
import net.firedevops.firemud.gamelogic.v1.GameLogicGameplayRuleIntakeServiceGrpc;

/** File-backed mTLS client for the unregistered selected-source retain transport. */
public final class GrpcGameLogicIntakeRetainClient
    extends AbstractReloadingBlockingGrpcClient<
        GameLogicGameplayRuleIntakeServiceGrpc.GameLogicGameplayRuleIntakeServiceBlockingStub>
    implements GameLogicIntakeRetainClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public GrpcGameLogicIntakeRetainClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        GrpcGameLogicIntakeRetainClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public synchronized void init() throws SSLException, IOException {
    if (closed) throw new IllegalStateException("Game Logic intake retain client is closed");
    if (initialized) return;
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Game Logic intake retain stub unavailable");
    }
    initialized = true;
  }

  @Override
  public GameLogicIntakeRetainEvidence retain(GameLogicIntakeRetainEvidence.Request request) {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive()
        || org.springframework.transaction.support.TransactionSynchronizationManager
            .isSynchronizationActive()) {
      throw new IllegalStateException("Game Logic intake retain transport must run outside SQL");
    }
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Game Logic intake retain must use the configured workload namespace");
    }
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .retainGameplayRuleIntake(GameLogicIntakeRetainProtoCodec.toRequest(request));
    try {
      return GameLogicIntakeRetainProtoCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Game Logic returned invalid intake retain evidence", invalid);
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
  protected GameLogicGameplayRuleIntakeServiceGrpc.GameLogicGameplayRuleIntakeServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-logic-service";
    return GameLogicGameplayRuleIntakeServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withMaxInboundMessageSize(GameLogicIntakeRetainProtoCodec.MAX_WIRE_BYTES)
        .withCompression("gzip");
  }

  @Override
  public synchronized void close() throws IOException {
    closed = true;
    initialized = false;
    super.close();
  }

  private GameLogicGameplayRuleIntakeServiceGrpc.GameLogicGameplayRuleIntakeServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Game Logic intake retain client is not initialized and available");
    }
    return currentStub;
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException("Game Logic intake retain requires workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "Game Logic intake retain requires file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Game Logic intake retain requires file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Game Logic intake retain " + label + " must be a file-backed path", invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Game Logic intake retain " + label + " must be an existing readable file");
    }
  }
}
