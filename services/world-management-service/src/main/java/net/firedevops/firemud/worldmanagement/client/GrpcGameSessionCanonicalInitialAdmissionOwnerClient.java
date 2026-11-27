package net.firedevops.firemud.worldmanagement.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
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
import net.firedevops.firemud.gamesession.v1.ReadRunOwnedFixtureInitialAdmissionOwnerRequest;
import net.firedevops.firemud.gamesession.v1.RunOwnedFixtureInitialAdmissionOwnerReadServiceGrpc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit file-backed mTLS client for World reads of canonical Game Session owner outcomes. */
public final class GrpcGameSessionCanonicalInitialAdmissionOwnerClient
    extends AbstractReloadingBlockingGrpcClient<
        CanonicalInitialAdmissionOwnerProofReadServiceGrpc
            .CanonicalInitialAdmissionOwnerProofReadServiceBlockingStub>
    implements GameSessionCanonicalInitialAdmissionOwnerClient {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final int MAX_FIXTURE_WIRE_BYTES = 64 * 1024;
  private static final String FIXTURE_KIND = "RUN_OWNED_FIXTURE";

  private final String workloadNamespace;

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
  public void init() throws SSLException, IOException {
    initOnce(
        "Canonical Game Session owner client is closed",
        "Canonical Game Session owner client has no gRPC stub");
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
        requireInitializedStub(
                "Canonical Game Session owner client is not initialized and available")
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

  /** Reads the fixed fixture's owner outcome over its exact Game Session recipient leaf. */
  public GameSessionCanonicalInitialAdmissionOwnerProof readFixture(
      HoldIdentity identity, String expectedProvenance, String expectedLeafSha256) {
    requireNoAmbientOwnerTransaction();
    Objects.requireNonNull(identity, "identity");
    if (!workloadNamespace.equals(identity.request().targetNamespace())
        || expectedProvenance == null
        || !expectedProvenance.matches("sha256:[0-9a-f]{64}")
        || expectedLeafSha256 == null
        || !expectedLeafSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Fixed fixture owner binding required");
    }
    String correlation = UUID.randomUUID().toString();
    String peer = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
    var channel = requireInitializedStub("Canonical Game Session owner client is unavailable")
        .getChannel();
    var request = ReadRunOwnedFixtureInitialAdmissionOwnerRequest.newBuilder()
        .setReadRequestId(correlation)
        .setAuthorityKind(FIXTURE_KIND)
        .setFixturePolicySealEvidenceDigest(expectedProvenance)
        .setHold(CanonicalInitialAdmissionOwnerGrpcCodec.toRequest(identity))
        .build();
    var response = RunOwnedFixtureInitialAdmissionOwnerReadServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
        .withMaxInboundMessageSize(MAX_FIXTURE_WIRE_BYTES)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer, expectedLeafSha256))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer, expectedLeafSha256))
        .readRunOwnedFixtureInitialAdmissionOwner(request);
    if (!response.hasProof()
        || !response.getUnknownFields().asMap().isEmpty()
        || !correlation.equals(response.getReadRequestId())
        || !FIXTURE_KIND.equals(response.getAuthorityKind())
        || !expectedProvenance.equals(response.getFixturePolicySealEvidenceDigest())) {
      throw new IllegalArgumentException("Game Session fixture proof changed its binding");
    }
    var proof = CanonicalInitialAdmissionOwnerGrpcCodec.requireResponse(identity, response.getProof());
    if (proof.outcome() == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED) {
      throw new IllegalArgumentException("Fixture abort proof is unavailable");
    }
    return proof;
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
