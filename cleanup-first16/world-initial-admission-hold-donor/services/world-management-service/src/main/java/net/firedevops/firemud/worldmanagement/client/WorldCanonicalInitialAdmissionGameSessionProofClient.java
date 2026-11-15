package net.firedevops.firemud.worldmanagement.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.FileBackedGrpcMtlsPolicy;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.AuthorityKind;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidenceProvider.GameSessionOwnerProofClientPins;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOrigin;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOwnerProofOutcome;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOwnerProofReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Authenticated, method-specific Game Session readback for canonical first-OPEN holds. */
@Component
@ConditionalOnProperty(name = "FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED", havingValue = "true")
public final class WorldCanonicalInitialAdmissionGameSessionProofClient
    extends AbstractReloadingBlockingGrpcClient<
        CanonicalInitialAdmissionOwnerProofReadServiceGrpc
            .CanonicalInitialAdmissionOwnerProofReadServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private volatile boolean initialized;
  private volatile boolean closed;

  public WorldCanonicalInitialAdmissionGameSessionProofClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      GrpcChannelFactory channelFactory) {
    super(
        endpoints,
        FileBackedGrpcMtlsPolicy.require(
            tlsProperties,
            "Canonical initial-admission Game Session owner reads",
            "Canonical initial-admission owner reads require workload mTLS",
            "Canonical initial-admission owner reads require workload mTLS"),
        channelFactory,
        WorldCanonicalInitialAdmissionGameSessionProofClient.class);
  }

  @PostConstruct
  public synchronized void init() throws SSLException, IOException {
    if (closed) {
      throw new IllegalStateException("Canonical Game Session owner read client is closed");
    }
    if (initialized) return;
    initReloadingClient();
    if (stub() == null) {
      throw new IllegalStateException("Canonical Game Session owner read client has no gRPC stub");
    }
    initialized = true;
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
      buildStub(io.grpc.ManagedChannel channel) {
    return CanonicalInitialAdmissionOwnerProofReadServiceGrpc.newBlockingStub(channel)
        .withCompression("gzip");
  }

  public GameSessionCanonicalInitialAdmissionOwnerProof readOwnerProof(
      HoldIdentity identity, GameSessionOwnerProofClientPins pins) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(pins, "pins");
    Request requestIdentity = identity.request();
    pins.requireTargetNamespace(requestIdentity.targetNamespace());
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Game Session owner reads require no ambient transaction");
    }
    var builder =
        GetCanonicalInitialAdmissionOwnerProofRequest.newBuilder()
            .setTargetNamespace(requestIdentity.targetNamespace())
            .setInitialAdmissionRequestId(requestIdentity.initialAdmissionRequestId())
            .setInitialAdmissionRequestDigest(requestIdentity.initialAdmissionRequestDigest())
            .setCanonicalTenantId(requestIdentity.canonicalTenantId().toString())
            .setWorldSlug(requestIdentity.worldSlug())
            .setRealmId(requestIdentity.realmId().toString())
            .setPlayableStateNamespaceId(requestIdentity.playableStateNamespaceId().toString())
            .setPlayableStateScope(requestIdentity.playableStateScope())
            .setCanonicalGameInstanceId(requestIdentity.canonicalGameInstanceId().toString())
            .setCanonicalVersionId(requestIdentity.canonicalVersionId().toString())
            .setActiveLifecycleEpoch(requestIdentity.activeLifecycleEpoch())
            .setExpectedCatalogRevision(requestIdentity.expectedCatalogRevision())
            .setOrigin(toWireOrigin(requestIdentity.initialAdmissionOrigin()))
            .setHoldId(identity.holdId().toString())
            .setHoldFence(identity.holdFence().toString())
            .setHoldBindingDigest(identity.holdBindingDigest());
    if (requestIdentity.expectedPriorPointerVersion() != null) {
      builder.setExpectedPriorPointerVersion(requestIdentity.expectedPriorPointerVersion());
    }
    if (requestIdentity.authorityKind() == AuthorityKind.RUN_OWNED_FIXTURE) {
      builder
          .setAuthorityKind(requestIdentity.authorityKind().name())
          .setFixturePolicySealEvidenceDigest(requestIdentity.fixturePolicySealEvidenceDigest());
    }
    GetCanonicalInitialAdmissionOwnerProofResponse response =
        requireStub()
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .withCallCredentials(
                new GrpcServerPeerIdentityCallCredentials(
                    pins.gameSessionUriSan(), pins.gameSessionLeafSha256()))
            .withInterceptors(
                new GrpcServerPeerIdentityClientInterceptor(
                    pins.gameSessionUriSan(), pins.gameSessionLeafSha256()))
            .getCanonicalInitialAdmissionOwnerProof(builder.build());
    return decodeExact(identity, response);
  }

  @Override
  public synchronized void close() throws IOException {
    closed = true;
    initialized = false;
    super.close();
  }

  private CanonicalInitialAdmissionOwnerProofReadServiceGrpc
          .CanonicalInitialAdmissionOwnerProofReadServiceBlockingStub
      requireStub() {
    var currentStub = stub();
    if (closed || !initialized || currentStub == null) {
      throw new IllegalStateException(
          "Canonical Game Session owner read client is not initialized and available");
    }
    return currentStub;
  }

  static GameSessionCanonicalInitialAdmissionOwnerProof decodeExact(
      HoldIdentity expected, GetCanonicalInitialAdmissionOwnerProofResponse response) {
    if (response == null || !response.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalStateException("Game Session canonical owner proof is incomplete");
    }
    var request = expected.request();
    if (!response.getTargetNamespace().equals(request.targetNamespace())
        || !response.getInitialAdmissionRequestId().equals(request.initialAdmissionRequestId())
        || !response.getInitialAdmissionRequestDigest().equals(request.initialAdmissionRequestDigest())
        || !response.getCanonicalTenantId().equals(request.canonicalTenantId().toString())
        || !response.getWorldSlug().equals(request.worldSlug())
        || !response.getRealmId().equals(request.realmId().toString())
        || !response.getPlayableStateNamespaceId().equals(request.playableStateNamespaceId().toString())
        || !response.getPlayableStateScope().equals(request.playableStateScope())
        || !response.getCanonicalGameInstanceId().equals(request.canonicalGameInstanceId().toString())
        || !response.getCanonicalVersionId().equals(request.canonicalVersionId().toString())
        || response.getActiveLifecycleEpoch() != request.activeLifecycleEpoch()
        || response.getExpectedCatalogRevision() != request.expectedCatalogRevision()
        || fromWireOrigin(response.getOrigin()) != request.initialAdmissionOrigin()
        || !response.getHoldId().equals(expected.holdId().toString())
        || !response.getHoldFence().equals(expected.holdFence().toString())
        || !response.getHoldBindingDigest().equals(expected.holdBindingDigest())
        || response.hasExpectedPriorPointerVersion()
            != (request.expectedPriorPointerVersion() != null)
        || (request.expectedPriorPointerVersion() != null
            && response.getExpectedPriorPointerVersion() != request.expectedPriorPointerVersion())
        || !response.getAuthorityKind().equals(
            request.authorityKind()
                == net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.AuthorityKind.HUMAN
                ? ""
                : request.authorityKind().name())
        || !response.getFixturePolicySealEvidenceDigest().equals(
            Objects.toString(request.fixturePolicySealEvidenceDigest(), ""))) {
      throw new IllegalStateException("Game Session owner proof changed the complete hold identity");
    }
    var outcome =
        switch (response.getOutcome()) {
          case CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_PENDING ->
              GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING;
          case CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_COMMITTED ->
              GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED;
          case CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_ABORTED ->
              GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED;
          case CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_UNSPECIFIED, UNRECOGNIZED ->
              throw new IllegalStateException("Game Session owner proof outcome is unsupported");
        };
    Long pointerVersion =
        response.hasCommittedPointerVersion() ? response.getCommittedPointerVersion() : null;
    Long auditEventId = response.hasAuditEventId() ? response.getAuditEventId() : null;
    Instant terminalAt =
        response.hasTerminalAt()
            ? Instant.ofEpochSecond(
                response.getTerminalAt().getSeconds(), response.getTerminalAt().getNanos())
            : null;
    return new GameSessionCanonicalInitialAdmissionOwnerProof(
        expected,
        outcome,
        pointerVersion,
        auditEventId,
        response.getProofDigest().isBlank() ? null : response.getProofDigest(),
        response.getPositiveDurableAbort(),
        terminalAt);
  }

  private static CanonicalInitialAdmissionOrigin toWireOrigin(
      net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin origin) {
    return switch (origin) {
      case NO_PRIOR_POINTER ->
          CanonicalInitialAdmissionOrigin.CANONICAL_INITIAL_ADMISSION_ORIGIN_NO_PRIOR_POINTER;
      case EXPECT_CLOSED ->
          CanonicalInitialAdmissionOrigin.CANONICAL_INITIAL_ADMISSION_ORIGIN_EXPECT_CLOSED;
    };
  }

  private static net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin
      fromWireOrigin(CanonicalInitialAdmissionOrigin origin) {
    return switch (origin) {
      case CANONICAL_INITIAL_ADMISSION_ORIGIN_NO_PRIOR_POINTER ->
          net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER;
      case CANONICAL_INITIAL_ADMISSION_ORIGIN_EXPECT_CLOSED ->
          net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.EXPECT_CLOSED;
      case CANONICAL_INITIAL_ADMISSION_ORIGIN_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalStateException("Game Session owner proof origin is unsupported");
    };
  }
}
