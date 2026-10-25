package net.firedevops.firemud.worldmanagement.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalInitialAdmissionOwnerGrpcCodec;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedGrpcMtlsPolicy;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOwnerProofReadServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit file-backed mTLS client for World reads of canonical Game Session owner outcomes. */
public final class GrpcGameSessionCanonicalInitialAdmissionOwnerClient
    extends AbstractReloadingBlockingGrpcClient<
        CanonicalInitialAdmissionOwnerProofReadServiceGrpc
            .CanonicalInitialAdmissionOwnerProofReadServiceBlockingStub>
    implements GameSessionCanonicalInitialAdmissionOwnerClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public GrpcGameSessionCanonicalInitialAdmissionOwnerClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        GrpcGameSessionCanonicalInitialAdmissionOwnerClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Opens its managed channel only when the owning World workflow explicitly starts it. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Canonical Game Session owner client is closed");
    }
    if (initialized) {
      return;
    }
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Canonical Game Session owner client has no gRPC stub");
    }
    initialized = true;
  }

  /**
   * Reads one exact authenticated owner observation. PENDING is returned as an observation and
   * never resolves or releases World's hold.
   */
  @Override
  public GameSessionCanonicalInitialAdmissionOwnerProof read(HoldIdentity identity) {
    requireNoAmbientOwnerTransaction();
    Objects.requireNonNull(identity, "identity");
    if (!workloadNamespace.equals(identity.request().targetNamespace())) {
      throw new IllegalArgumentException(
          "Canonical owner request must use the configured workload namespace");
    }

    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .getCanonicalInitialAdmissionOwnerProof(
                CanonicalInitialAdmissionOwnerGrpcCodec.toRequest(identity));
    try {
      return CanonicalInitialAdmissionOwnerGrpcCodec.requireResponse(identity, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Game Session returned invalid canonical initial-admission owner proof", invalid);
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
  protected CanonicalInitialAdmissionOwnerProofReadServiceGrpc
          .CanonicalInitialAdmissionOwnerProofReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String expectedPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
    return CanonicalInitialAdmissionOwnerProofReadServiceGrpc.newBlockingStub(channel)
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

  private CanonicalInitialAdmissionOwnerProofReadServiceGrpc
          .CanonicalInitialAdmissionOwnerProofReadServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Canonical Game Session owner client is not initialized and available");
    }
    return currentStub;
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    return FileBackedGrpcMtlsPolicy.require(
        tlsProperties,
        "Canonical Game Session owner proof reads",
        "Canonical Game Session owner reads require file-backed workload mTLS",
        "Canonical Game Session owner reads require workload mTLS");
  }

  private static void requireNoAmbientOwnerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Canonical Game Session owner proof reads require no ambient World transaction");
    }
  }
}
