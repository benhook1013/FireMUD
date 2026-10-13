package net.firedevops.firemud.common.gamedesign;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamedesign.v1.GameDesignSelectedOwnerIntakeSourceServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit file-backed mTLS client for complete selected-owner source content. */
public final class GrpcSelectedOwnerIntakeSourceClient
    extends AbstractReloadingBlockingGrpcClient<
        GameDesignSelectedOwnerIntakeSourceServiceGrpc
            .GameDesignSelectedOwnerIntakeSourceServiceBlockingStub>
    implements SelectedOwnerIntakeSourceClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public GrpcSelectedOwnerIntakeSourceClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        GrpcSelectedOwnerIntakeSourceClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace))
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    this.workloadNamespace = workloadNamespace;
  }

  public synchronized void init() throws SSLException, IOException {
    if (closed) throw new IllegalStateException("Game Design source client is closed");
    if (initialized) return;
    initReloadingClient();
    if (stub() == null) throw new IllegalStateException("Game Design source stub unavailable");
    initialized = true;
  }

  /** Reads exact selected source content after authenticating the configured Game Design peer. */
  @Override
  public SelectedOwnerIntakeSourceContent read(
      SelectedOwnerIntakeSourceReadEvidence.Request request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException(
          "Game Design selected-owner source read must run outside SQL");
    Objects.requireNonNull(request, "selected-owner source-read request is required");
    if (!workloadNamespace.equals(request.targetNamespace()))
      throw new IllegalArgumentException(
          "Game Design selected-owner source read must use the configured workload namespace");
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readSelectedSource(SelectedOwnerIntakeSourceProtoCodec.toRequest(request));
    try {
      return SelectedOwnerIntakeSourceProtoCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Game Design returned invalid selected-owner source content", invalid);
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
  protected GameDesignSelectedOwnerIntakeSourceServiceGrpc
          .GameDesignSelectedOwnerIntakeSourceServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-design-service";
    return GameDesignSelectedOwnerIntakeSourceServiceGrpc.newBlockingStub(channel)
        .withMaxInboundMessageSize(SelectedOwnerIntakeSourceProtoCodec.MAX_WIRE_BYTES)
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

  private GameDesignSelectedOwnerIntakeSourceServiceGrpc
          .GameDesignSelectedOwnerIntakeSourceServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null)
      throw new IllegalStateException(
          "Game Design selected-owner source client is not initialized and available");
    return currentStub;
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext())
      throw new IllegalArgumentException(
          "Game Design selected-owner source requires workload mTLS");
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank())
      throw new IllegalArgumentException(
          "Game Design selected-owner source requires file-backed certificate, key, and CA material");
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:"))
      throw new IllegalArgumentException(
          "Game Design selected-owner source requires file-backed certificate, key, and CA material");
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Game Design selected-owner source " + label + " must be a file-backed path", invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path))
      throw new IllegalArgumentException(
          "Game Design selected-owner source " + label + " must be an existing readable file");
  }
}
