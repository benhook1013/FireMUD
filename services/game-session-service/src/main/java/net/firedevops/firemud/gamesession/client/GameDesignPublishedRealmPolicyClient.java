package net.firedevops.firemud.gamesession.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.StartSessionPublishedRealmPolicyReadGrpcCodec;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamedesign.v1.GameDesignStartSessionPublishedRealmPolicyReadServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit file-backed mTLS client for the standalone immutable published-policy read. */
public final class GameDesignPublishedRealmPolicyClient
    extends AbstractReloadingBlockingGrpcClient<
        GameDesignStartSessionPublishedRealmPolicyReadServiceGrpc
            .GameDesignStartSessionPublishedRealmPolicyReadServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public GameDesignPublishedRealmPolicyClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        GameDesignPublishedRealmPolicyClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Game Design published-policy client is closed");
    }
    if (initialized) return;
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Game Design published-policy read stub unavailable");
    }
    initialized = true;
  }

  /** Returns only fully correlated Account, source, and immutable policy evidence. */
  public StartSessionPublishedRealmPolicyReadGrpcCodec.ReadResult read(
      StartSessionPublishedRealmPolicyReadGrpcCodec.ReadRequest request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Game Design published-policy read requires no ambient transaction synchronization");
    }
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Game Design published-policy read must use the configured workload namespace");
    }
    var wireRequest = StartSessionPublishedRealmPolicyReadGrpcCodec.toRequest(request);
    StartSessionPublishedRealmPolicyReadGrpcCodec.requireRequestSerializedSize(wireRequest);
    var response =
        requireStub()
            .withMaxInboundMessageSize(
                StartSessionPublishedRealmPolicyReadGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readStartSessionPublishedRealmPolicy(wireRequest);
    StartSessionPublishedRealmPolicyReadGrpcCodec.requireResponseSerializedSize(response);
    try {
      return StartSessionPublishedRealmPolicyReadGrpcCodec.fromResponse(
          StartSessionPublishedRealmPolicyReadGrpcCodec.fromRequest(wireRequest), response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Game Design returned invalid or mismatched published-policy evidence", invalid);
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
  protected GameDesignStartSessionPublishedRealmPolicyReadServiceGrpc
          .GameDesignStartSessionPublishedRealmPolicyReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-design-service";
    return GameDesignStartSessionPublishedRealmPolicyReadServiceGrpc.newBlockingStub(channel)
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

  private GameDesignStartSessionPublishedRealmPolicyReadServiceGrpc
          .GameDesignStartSessionPublishedRealmPolicyReadServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Game Design published-policy client is not initialized and available");
    }
    return currentStub;
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException(
          "Game Design published-policy read requires workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "Game Design published-policy read requires file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Game Design published-policy read requires file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Game Design published-policy read " + label + " must be a file-backed path", invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Game Design published-policy read " + label + " must be an existing readable file");
    }
  }
}
