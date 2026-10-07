package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldFinalizationService.HeldOwnerProof;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialAdmissionHoldFinalizationService.OwnerProofVerifier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit mTLS client for the exact Game Session canonical initial-admission owner read. */
public final class GameSessionCanonicalInitialAdmissionOwnerClient
    extends AbstractReloadingBlockingGrpcClient<
        GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub>
    implements OwnerProofVerifier {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;
  private final AtomicLong lifecycleGeneration = new AtomicLong();

  public GameSessionCanonicalInitialAdmissionOwnerClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        Objects.requireNonNull(channelFactory, "channelFactory"),
        GameSessionCanonicalInitialAdmissionOwnerClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when an owning service explicitly starts this client. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Game Session owner-proof client is closed");
    }
    if (initialized) {
      return;
    }
    try {
      initReloadingClient();
      if (stub() == null) {
        throw new IllegalStateException("Game Session owner-proof client has no gRPC stub");
      }
      initialized = true;
    } catch (IOException | RuntimeException failure) {
      closed = true;
      lifecycleGeneration.incrementAndGet();
      throw failure;
    }
  }

  /** Performs an authenticated owner read and holds only its immutable local result. */
  @Override
  public HeldOwnerProof verifyAndHold(
      HoldIdentity expectedIdentity,
      GameSessionCanonicalInitialAdmissionOwnerProof.Outcome expectedOutcome) {
    Objects.requireNonNull(expectedIdentity, "expectedIdentity");
    Objects.requireNonNull(expectedOutcome, "expectedOutcome");
    requireNoAmbientTransaction();
    if (expectedOutcome == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING) {
      throw denied("PENDING is not a terminal owner-proof selection");
    }
    if (!workloadNamespace.equals(expectedIdentity.request().targetNamespace())) {
      throw denied("Canonical Game Session owner read must use the configured workload namespace");
    }

    GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub currentStub =
        requireStub();
    long requestGeneration = lifecycleGeneration.get();
    var response =
        currentStub
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .getCanonicalInitialAdmissionOwnerProof(
                GameSessionCanonicalInitialAdmissionOwnerGrpcCodec.toRequest(expectedIdentity));

    GameSessionCanonicalInitialAdmissionOwnerProof proof;
    try {
      proof =
          GameSessionCanonicalInitialAdmissionOwnerGrpcCodec.fromResponse(
              expectedIdentity, expectedOutcome, response);
    } catch (IllegalArgumentException invalid) {
      throw denied("Game Session returned invalid canonical initial-admission owner proof");
    }
    requireGeneration(requestGeneration);
    return new LocalHeldOwnerProof(proof, requestGeneration);
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
  protected GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub buildStub(
      ManagedChannel channel) {
    String expectedPeerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
    return GameSessionControlPlaneServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(expectedPeerUri))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(expectedPeerUri))
        .withCompression("gzip");
  }

  @Override
  public synchronized void close() throws IOException {
    closed = true;
    initialized = false;
    lifecycleGeneration.incrementAndGet();
    super.close();
  }

  private GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub
      requireStub() {
    GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub currentStub =
        stub();
    if (closed || !initialized || currentStub == null) {
      throw denied("Game Session owner-proof client is not initialized and available");
    }
    return currentStub;
  }

  private void requireGeneration(long expectedGeneration) {
    if (closed || !initialized || lifecycleGeneration.get() != expectedGeneration) {
      throw denied("Game Session owner-proof client closed during owner verification");
    }
  }

  private synchronized void requireHeld(long expectedGeneration, boolean handleOpen) {
    if (!handleOpen
        || closed
        || !initialized
        || lifecycleGeneration.get() != expectedGeneration) {
      throw denied("Authenticated Game Session owner proof is no longer held");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw denied("Game Session owner proof must be read outside an ambient World transaction");
    }
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    if (tlsProperties == null || tlsProperties.isPlaintext()) {
      throw new IllegalArgumentException(
          "Game Session canonical owner reads require workload mTLS");
    }
    requireReadableFile(tlsProperties.getCertChain(), "certificate chain");
    requireReadableFile(tlsProperties.getPrivateKey(), "private key");
    requireReadableFile(tlsProperties.getCaCert(), "CA certificate");
    return tlsProperties;
  }

  private static void requireReadableFile(String configuredPath, String label) {
    if (configuredPath == null || configuredPath.isBlank()) {
      throw new IllegalArgumentException(
          "Game Session canonical owner reads require file-backed certificate, key, and CA material");
    }
    String pathText = configuredPath.trim();
    if (pathText.startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Game Session canonical owner reads require file-backed certificate, key, and CA material");
    }
    Path path;
    try {
      path = Path.of(pathText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Game Session owner-proof " + label + " must be a readable file-backed path", invalid);
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(
          "Game Session owner-proof " + label + " must be an existing readable file");
    }
  }

  private static WorldCanonicalInitialAdmissionHoldFinalizationService.FinalizationDeniedException
      denied(String message) {
    return new WorldCanonicalInitialAdmissionHoldFinalizationService.FinalizationDeniedException(
        message);
  }

  private final class LocalHeldOwnerProof implements HeldOwnerProof {
    private final GameSessionCanonicalInitialAdmissionOwnerProof proof;
    private final long proofGeneration;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private LocalHeldOwnerProof(
        GameSessionCanonicalInitialAdmissionOwnerProof proof, long proofGeneration) {
      this.proof = proof;
      this.proofGeneration = proofGeneration;
    }

    @Override
    public GameSessionCanonicalInitialAdmissionOwnerProof proof() {
      requireHeld();
      return proof;
    }

    @Override
    public void requireHeld() {
      GameSessionCanonicalInitialAdmissionOwnerClient.this.requireHeld(proofGeneration, open.get());
    }

    @Override
    public void close() {
      open.set(false);
    }
  }
}
