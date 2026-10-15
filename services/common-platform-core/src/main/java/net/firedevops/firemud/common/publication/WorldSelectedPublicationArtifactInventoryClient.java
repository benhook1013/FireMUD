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
import net.firedevops.firemud.worldmanagement.v1.WorldSelectedDraftPublicationFreezeServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** File-backed mTLS client for one exact immutable selected-publication inventory read. */
public final class WorldSelectedPublicationArtifactInventoryClient
    extends AbstractReloadingBlockingGrpcClient<
        WorldSelectedDraftPublicationFreezeServiceGrpc
            .WorldSelectedDraftPublicationFreezeServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public WorldSelectedPublicationArtifactInventoryClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        FileBackedMtls.require(tlsProperties, "World selected-publication inventory read"),
        channelFactory,
        WorldSelectedPublicationArtifactInventoryClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("World selected-publication inventory client is closed");
    }
    if (initialized) return;
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("World selected-publication inventory stub unavailable");
    }
    initialized = true;
  }

  /** Returns only the canonical public inventory bound to the exact acknowledged freeze. */
  public WorldSelectedPublicationArtifactInventoryEvidence read(
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "World selected-publication inventory read requires no ambient owner transaction");
    }
    Objects.requireNonNull(freezeEvidence, "freezeEvidence");
    if (!workloadNamespace.equals(freezeEvidence.request().targetNamespace())) {
      throw new IllegalArgumentException(
          "World selected-publication inventory read must use the configured namespace");
    }
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .readSelectedPublicationArtifactInventory(
                WorldSelectedPublicationArtifactInventoryGrpcCodec.toRequest(freezeEvidence));
    try {
      return WorldSelectedPublicationArtifactInventoryGrpcCodec.fromResponse(
          freezeEvidence, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "World returned invalid selected-publication inventory evidence", invalid);
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
  protected WorldSelectedDraftPublicationFreezeServiceGrpc
          .WorldSelectedDraftPublicationFreezeServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service";
    return WorldSelectedDraftPublicationFreezeServiceGrpc.newBlockingStub(channel)
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

  private WorldSelectedDraftPublicationFreezeServiceGrpc
          .WorldSelectedDraftPublicationFreezeServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "World selected-publication inventory client is not initialized and available");
    }
    return currentStub;
  }
}
