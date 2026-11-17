package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionHoldRequest;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionHoldState;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionTerminalProof;
import net.firedevops.firemud.worldmanagement.client.GameSessionFixtureInitialAdmissionOwnerClient;
import net.firedevops.firemud.worldmanagement.data.RunOwnedCanonicalWorldLaunchEvidence;
import net.firedevops.firemud.worldmanagement.data.RunOwnedCanonicalWorldLaunchEvidenceProvider;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit fixture-only owner composition. No Spring registration, scheduler, Human authority,
 * generic StartSession, Account JOIN or player admission is enabled by this type.
 */
public final class WorldFixtureInitialAdmissionHoldOwner {
  private final WorldFixtureInitialAdmissionHoldRepository repository;
  private final RunOwnedCanonicalWorldLaunchEvidenceProvider sources;
  private final WorldCanonicalInstanceFixtureActivationOwner activation;
  private final GameSessionFixtureInitialAdmissionOwnerClient gameSession;

  public WorldFixtureInitialAdmissionHoldOwner(
      WorldFixtureInitialAdmissionHoldRepository repository,
      RunOwnedCanonicalWorldLaunchEvidenceProvider sources,
      WorldCanonicalInstanceFixtureActivationOwner activation,
      GameSessionFixtureInitialAdmissionOwnerClient gameSession) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.sources = Objects.requireNonNull(sources, "sources");
    this.activation = Objects.requireNonNull(activation, "activation");
    this.gameSession = Objects.requireNonNull(gameSession, "gameSession");
  }

  /** A transport read identity cannot reuse the original fixture operation identity. */
  public void requireReadCorrelation(
      WorldFixtureInitialAdmissionHoldRequest request, java.util.UUID readRequestId) {
    requireBoundary(request);
    var receipt = requireConfiguredBinding(request);
    requireFixedGameSessionPeer(receipt);
    if (Objects.requireNonNull(readRequestId, "readRequestId")
        .toString()
        .equals(receipt.fixtureOperationId())) {
      throw new IllegalArgumentException("Fixture operation is not a fresh read correlation");
    }
  }

  /** Fresh fixed-source reads finish before World takes its actual lifecycle exclusion lock. */
  public HoldIdentity acquire(WorldFixtureInitialAdmissionHoldRequest request) {
    requireBoundary(request);
    RunOwnedCanonicalWorldLaunchEvidence evidence = sources.requireAuthenticatedEvidence();
    evidence.requireFixedGameSessionPeer();
    requireEvidenceTarget(request, evidence);
    PreparedWorldInstanceRequest original = originalPreparation(evidence);
    activation
        .read(request.activation(), evidence, original)
        .orElseThrow(
            () -> new IllegalStateException("Exact fixture ACTIVE receipt is unavailable"));
    return repository.acquire(request);
  }

  /** Historical identity only, not a renewed ACTIVE or STARTING claim. */
  public Optional<HoldIdentity> readIdentity(WorldFixtureInitialAdmissionHoldRequest request) {
    requireBoundary(request);
    var receipt = requireConfiguredBinding(request);
    requireFixedGameSessionPeer(receipt);
    return repository.readIdentity(request);
  }

  /** Same-snapshot actual hold/lifecycle. A later lifecycle is not relabeled original ACTIVE. */
  public Optional<WorldFixtureInitialAdmissionHoldState> readState(
      WorldFixtureInitialAdmissionHoldRequest request, HoldIdentity identity) {
    requireBoundary(request);
    requireIdentity(request, identity);
    var receipt = requireConfiguredBinding(request);
    requireFixedGameSessionPeer(receipt);
    return repository.readStateEvidence(request, identity);
  }

  /**
   * Recovery uses the retained request plus an independently authenticated current owner read. No
   * incoming caller assertion can supply a terminal, and PENDING never releases exclusion.
   */
  public WorldFixtureInitialAdmissionTerminalProof settleFromAuthenticatedOwnerRead(
      WorldFixtureInitialAdmissionHoldRequest request, HoldIdentity identity) {
    requireBoundary(request);
    requireIdentity(request, identity);
    var receipt = requireConfiguredBinding(request);
    HoldIdentity stored =
        repository
            .readIdentity(request)
            .orElseThrow(() -> new IllegalStateException("Fixture hold identity is not retained"));
    if (!stored.equals(identity)) {
      throw new IllegalStateException("Fixture settlement changed the complete retained identity");
    }
    WorldFixtureInitialAdmissionTerminalProof proof =
        Objects.requireNonNull(
            gameSession.readFixture(request, identity, receipt.gameSessionLeafSha256()),
            "Fixture owner returned no terminal observation");
    if (!request.equals(proof.request())
        || !identity.holdId().equals(proof.holdId())
        || !identity.holdFence().equals(proof.holdFence())) {
      throw new IllegalStateException(
          "Authenticated fixture terminal changed its original binding");
    }
    repository.settle(identity, proof);
    return proof;
  }

  /**
   * Bounded explicit recovery, available to an existing reconciler only when separately composed.
   */
  public void reconcilePending(int limit) {
    requireNoAmbientTransaction();
    for (var request : repository.pendingFixtureRequests(limit)) {
      try {
        repository
            .readIdentity(request)
            .ifPresent(identity -> settleFromAuthenticatedOwnerRead(request, identity));
      } catch (RuntimeException unavailable) {
        // An unavailable, invalid or mismatched owner leaves its exact durable hold fenced.
        // Continue other independent retained holds; no timeout or failure fabricates an abort.
      }
    }
  }

  private RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt requireConfiguredBinding(
      WorldFixtureInitialAdmissionHoldRequest request) {
    var receipt = sources.requireConfiguredFixtureReceipt();
    var source = receipt.sourceEvidence();
    if (!request.selector().targetNamespace().equals(source.targetNamespace())
        || !request.selector().canonicalTenantId().equals(source.canonicalTenantId())
        || !request.selector().worldSlug().equals(source.worldSlug())
        || !request
            .selector()
            .canonicalVersionId()
            .toString()
            .equals(receipt.launchReadEvidence().publishedReleaseBundle().getCanonicalVersionId())
        || !request
            .activation()
            .fixturePolicySealEvidenceDigest()
            .equals(receipt.fixturePolicySeal().evidenceDigest())) {
      throw new IllegalArgumentException("Fixture hold differs from its fixed original receipt");
    }
    return receipt;
  }

  private static void requireFixedGameSessionPeer(
      RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt receipt) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    GrpcPeerCertificateEvidence leaf = GrpcPeerCertificateEvidence.current();
    if (SessionContext.hasAuthenticatedCallerContext()
        || peer == null
        || leaf == null
        || !peer.isService("game-session-service")
        || !peer.isInNamespace(receipt.sourceEvidence().targetNamespace())
        || !peer.uri().equals(receipt.gameSessionUriSan())
        || !leaf.leafSha256().equals(receipt.gameSessionLeafSha256())) {
      throw new SecurityException("Fixture hold read requires its fixed Game Session peer");
    }
  }

  private static void requireEvidenceTarget(
      WorldFixtureInitialAdmissionHoldRequest request,
      RunOwnedCanonicalWorldLaunchEvidence evidence) {
    var target = evidence.gameSessionAssociation();
    var selector = request.selector();
    if (!selector.targetNamespace().equals(target.request().targetNamespace())
        || !selector.canonicalTenantId().equals(target.canonicalTenantId())
        || !selector.worldSlug().equals(evidence.sourceReceipt().sourceEvidence().worldSlug())
        || !selector.canonicalGameInstanceId().equals(target.gameInstanceUuid())
        || !selector.canonicalVersionId().equals(target.canonicalVersionId())
        || !selector.realmId().equals(target.canonicalRealmId())
        || !selector.playableStateNamespaceId().equals(target.playableStateNamespaceId())
        || !selector.playableStateScope().equals(evidence.capability().stateScope())
        || selector.expectedCatalogRevision() != target.catalogRevision()
        || !request
            .activation()
            .fixturePolicySealEvidenceDigest()
            .equals(evidence.fixtureProvenanceDigest())) {
      throw new IllegalArgumentException(
          "Fixture hold differs from authenticated owner allocation");
    }
  }

  private static PreparedWorldInstanceRequest originalPreparation(
      RunOwnedCanonicalWorldLaunchEvidence evidence) {
    var association = evidence.gameSessionAssociation();
    var descriptor = evidence.launchDescriptor();
    return new PreparedWorldInstanceRequest(
        association.gameSessionTenantId(),
        association.gameInstanceId(),
        evidence.capability().gameTemplateId(),
        evidence.capability().operationId(),
        descriptor.launchDescriptorId(),
        descriptor.versionId(),
        descriptor.scriptPatchVersionPresent() ? descriptor.scriptPatchVersion() : null,
        descriptor.runtimeFlagsJson(),
        descriptor.generationConfigRevision(),
        descriptor.releaseBundleId(),
        descriptor.publishedReleaseBundleRef(),
        descriptor.versionStateEpoch(),
        descriptor.remapSetIdPresent() ? descriptor.remapSetId() : null);
  }

  private static void requireIdentity(
      WorldFixtureInitialAdmissionHoldRequest request, HoldIdentity identity) {
    if (identity == null || !request.selector().equals(identity.request())) {
      throw new IllegalArgumentException("Fixture hold identity changed its request");
    }
  }

  private static void requireBoundary(WorldFixtureInitialAdmissionHoldRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoAmbientTransaction();
    if (request.selector().initialAdmissionOrigin()
        != WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER) {
      throw new IllegalArgumentException("Fixture first OPEN supports NO_PRIOR_POINTER only");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Fixture owner cannot enter from an ambient transaction");
    }
  }
}
