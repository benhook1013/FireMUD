package net.firedevops.firemud.common.gamesession;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Result;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedGrpcMtlsPolicy;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;

/** Explicit file-backed mTLS client for the exact Game Session launch-association owner read. */
public final class CanonicalGameInstanceLaunchAssociationClient
    extends AbstractReloadingBlockingGrpcClient<
        GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;

  private final String workloadNamespace;
  private volatile boolean initialized;
  private volatile boolean closed;

  public CanonicalGameInstanceLaunchAssociationClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory,
      String workloadNamespace) {
    super(
        endpoints,
        requireFileBackedMtls(tlsProperties),
        channelFactory,
        CanonicalGameInstanceLaunchAssociationClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /** Initializes only when an owning service explicitly starts this client. */
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Canonical launch association client is closed");
    }
    if (initialized) {
      return;
    }
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Canonical launch association client has no gRPC stub");
    }
    initialized = true;
  }

  /** Reads a single exact owner association; the response is not World lifecycle evidence. */
  public Result read(Request request) {
    Objects.requireNonNull(request, "request");
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw new IllegalArgumentException(
          "Launch association request must use the configured workload namespace");
    }
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .getCanonicalGameInstanceLaunchAssociation(
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toRequest(request));
    try {
      return CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Game Session returned invalid canonical launch association evidence", invalid);
    }
  }

  /**
   * Exact fixed-capability allocation read; caller supplies the independently verified run leaf.
   */
  public CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult readFixture(
      CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest request,
      String expectedLeafSha256) {
    Objects.requireNonNull(request);
    if (!workloadNamespace.equals(request.targetNamespace())
        || expectedLeafSha256 == null
        || !expectedLeafSha256.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Fixed fixture owner binding required");
    if (org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive()
        || org.springframework.transaction.support.TransactionSynchronizationManager
            .isSynchronizationActive()) {
      throw new IllegalStateException("Fixture owner reads require no ambient transaction");
    }
    String peer = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
    var response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .withCallCredentials(
                new GrpcServerPeerIdentityCallCredentials(peer, expectedLeafSha256))
            .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer, expectedLeafSha256))
            .getRunOwnedFixtureLaunchAssociation(
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toFixtureRequest(request));
    CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult result;
    try {
      result =
          CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromFixtureResponse(
              request, response);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "Game Session returned invalid run-owned fixture owner evidence", invalid);
    }
    if (!expectedLeafSha256.equals(
        result.fixturePolicyRequest().capability().gameSessionLeafSha256())) {
      throw new IllegalStateException(
          "Fixture owner TLS leaf differs from its committed capability");
    }
    return result;
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
    super.close();
  }

  private GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Canonical launch association client is not initialized and available");
    }
    return currentStub;
  }

  private static CommonGrpcClientProperties requireFileBackedMtls(
      CommonGrpcClientProperties tlsProperties) {
    return FileBackedGrpcMtlsPolicy.require(
        tlsProperties,
        "Canonical launch association reads",
        "Canonical launch association reads require workload mTLS",
        "Canonical launch association reads require workload mTLS");
  }
}
