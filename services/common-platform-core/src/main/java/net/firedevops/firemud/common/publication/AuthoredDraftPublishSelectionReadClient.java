package net.firedevops.firemud.common.publication;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedMtls;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamedesign.v1.GameDesignSelectedDraftPublicationReadServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit file-backed mTLS client for the exact immutable Game Design selection read. */
public final class AuthoredDraftPublishSelectionReadClient
    extends AbstractReloadingBlockingGrpcClient<
        GameDesignSelectedDraftPublicationReadServiceGrpc
            .GameDesignSelectedDraftPublicationReadServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public AuthoredDraftPublishSelectionReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        FileBackedMtls.require(tlsProperties, "Game Design selected Draft publication read"),
        channelFactory,
        AuthoredDraftPublishSelectionReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException(
          "Game Design selected Draft publication read client is closed");
    }
    if (initialized) return;
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException(
          "Game Design selected Draft publication read stub unavailable");
    }
    initialized = true;
  }

  /**
   * Returns SELECTED evidence only after exact Game Design server identity and response
   * verification.
   */
  public AuthoredDraftPublishSelectionReadEvidence read(
      AuthoredDraftPublishSelectionReadEvidence.Request request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Game Design selected Draft publication read requires no owner transaction");
    }
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Game Design selected Draft publication read must use the configured workload namespace");
    }
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readSelectedDraftPublication(
                AuthoredDraftPublishSelectionReadGrpcCodec.toRequest(request));
    try {
      return AuthoredDraftPublishSelectionReadGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Game Design returned invalid selected Draft publication evidence", invalid);
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
  protected GameDesignSelectedDraftPublicationReadServiceGrpc
          .GameDesignSelectedDraftPublicationReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-design-service";
    return GameDesignSelectedDraftPublicationReadServiceGrpc.newBlockingStub(channel)
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

  private GameDesignSelectedDraftPublicationReadServiceGrpc
          .GameDesignSelectedDraftPublicationReadServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Game Design selected Draft publication read client is not initialized and available");
    }
    return currentStub;
  }
}
