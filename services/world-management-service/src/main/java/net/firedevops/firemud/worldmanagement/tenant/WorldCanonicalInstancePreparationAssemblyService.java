package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationClient;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Read-only assembly of exact persisted World preparation inputs and authenticated owner reads.
 *
 * <p>This component does not materialize, authorize, or claim World lifecycle state. In particular,
 * the Game Session status is preserved as transient read evidence; a later caller remains
 * responsible for its separate fresh-STARTING and held-authority checks.
 */
public final class WorldCanonicalInstancePreparationAssemblyService {
  private final String workloadNamespace;
  private final WorldCompleteLaunchBindingRepository launchBindings;
  private final WorldAuthoredSourceIntakeRepository sourceIntakes;
  private final WorldAuthoredVersionIdentityRepository versionIdentities;
  private final WorldCanonicalFrozenTopologyRepository frozenTopologies;
  private final CanonicalGameInstanceLaunchAssociationClient gameSessionClient;
  private final AuthoredWorldLaunchDescriptorClient gameDesignClient;

  public WorldCanonicalInstancePreparationAssemblyService(
      String workloadNamespace,
      WorldCompleteLaunchBindingRepository launchBindings,
      WorldAuthoredSourceIntakeRepository sourceIntakes,
      WorldAuthoredVersionIdentityRepository versionIdentities,
      WorldCanonicalFrozenTopologyRepository frozenTopologies,
      CanonicalGameInstanceLaunchAssociationClient gameSessionClient,
      AuthoredWorldLaunchDescriptorClient gameDesignClient) {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException(
          "World workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
    this.launchBindings = Objects.requireNonNull(launchBindings, "launchBindings");
    this.sourceIntakes = Objects.requireNonNull(sourceIntakes, "sourceIntakes");
    this.versionIdentities = Objects.requireNonNull(versionIdentities, "versionIdentities");
    this.frozenTopologies = Objects.requireNonNull(frozenTopologies, "frozenTopologies");
    this.gameSessionClient = Objects.requireNonNull(gameSessionClient, "gameSessionClient");
    this.gameDesignClient = Objects.requireNonNull(gameDesignClient, "gameDesignClient");
  }

  /**
   * Reconstructs an input using only the stable canonical selector and independently authenticated
   * immutable owner readbacks. It never calls a World claim, bind, accept, or capture operation.
   */
  public WorldCanonicalInstancePreparation.Input assemble(Selector selector) {
    Objects.requireNonNull(selector, "selector");
    requireNoAmbientTransaction();

    WorldCompleteLaunchBindingRepository.StoredBinding storedBinding =
        launchBindings
            .read(workloadNamespace, selector.canonicalTenantId(), selector.controlPlaneRequestId())
            .orElseThrow(
                () ->
                    new AssemblyRejectedException(
                        "No committed World complete launch binding matches the stable selector"));
    requireStoredSelector(storedBinding, selector);

    WorldAuthoredSourceIntakeReceipt sourceReceipt =
        sourceIntakes
            .read(workloadNamespace, storedBinding.intakeRequestId())
            .orElseThrow(
                () ->
                    new AssemblyRejectedException(
                        "World launch binding has no exact committed source intake"));
    WorldCompleteLaunchBindingReceipt launchBinding =
        launchBindings.toReceipt(storedBinding, sourceReceipt);

    var descriptor = launchBinding.descriptor();
    var release = launchBinding.evidence().releaseAttestation();
    if (!AuthoredWorldReleaseAttestationEvidence.requiresWorldStartLocationEvidence(
            release.schemaVersion())
        || release.worldStartLocationEvidence() == null) {
      throw new AssemblyRejectedException(
          "Canonical preparation requires the stored selected release selector and original frozen request");
    }

    // The UUID is the canonical selector. Never resolve the Game Design numeric Version ID here.
    WorldAuthoredVersionIdentityReceipt versionIdentity =
        versionIdentities
            .readByCanonicalVersion(
                workloadNamespace,
                selector.canonicalTenantId(),
                descriptor.worldSlug(),
                release.canonicalVersionId())
            .orElseThrow(
                () ->
                    new AssemblyRejectedException(
                        "World has no committed source-qualified Version identity for the release UUID"));
    if (!versionIdentity.sourceIntakeReceipt().equals(sourceReceipt)
        || !versionIdentity.canonicalVersionId().equals(release.canonicalVersionId())
        || !versionIdentity.worldSlug().equals(descriptor.worldSlug())) {
      throw new AssemblyRejectedException(
          "World Version identity differs from the exact committed launch source and release");
    }

    CaptureRequest captureRequest = captureRequest(release.worldStartLocationEvidence());
    WorldCanonicalFrozenTopology frozen =
        frozenTopologies
            .readCommitted(captureRequest)
            .orElseThrow(
                () ->
                    new AssemblyRejectedException(
                        "Published release selector has no exact committed frozen World topology"));
    WorldCanonicalInstanceTopologyPlan topologyPlan =
        WorldCanonicalInstanceTopologyPlan.create(frozen);
    WorldCanonicalInstancePreparation.requireExactReleaseGraph(release, topologyPlan);

    // Independently authenticate the complete immutable descriptor/release pair with Game Design.
    GetLaunchDescriptorRequest gameDesignReadRequest = gameDesignReadRequest(launchBinding);
    CompleteLaunchBindingEvidence gameDesignBinding =
        gameDesignClient.getComplete(gameDesignReadRequest);
    requireSameCompletePair(launchBinding.evidence(), gameDesignBinding);

    var associationRequest =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            newReadRequestId(
                descriptor,
                selector.canonicalGameInstanceId(),
                UUID.fromString(gameDesignReadRequest.getRequestId())),
            workloadNamespace,
            selector.canonicalTenantId(),
            descriptor.worldSlug(),
            selector.canonicalGameInstanceId(),
            descriptor.controlPlaneRequestId(),
            descriptor.launchDescriptorId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    CanonicalGameInstanceLaunchAssociationReadEvidence.Result association =
        gameSessionClient.read(associationRequest);
    requireGameSessionBinding(associationRequest, association, launchBinding.evidence());

    var evidence = associationToWorldEvidence(associationRequest, association);
    return new WorldCanonicalInstancePreparation.Input(
        new WorldCanonicalInstanceAssociation.GameSessionReadRequest(
            associationRequest.readRequestId(),
            associationRequest.targetNamespace(),
            associationRequest.canonicalTenantId(),
            associationRequest.worldSlug(),
            associationRequest.gameInstanceUuid(),
            associationRequest.controlPlaneRequestId(),
            associationRequest.launchDescriptorId(),
            associationRequest.expectedDescriptorRequestDigest(),
            associationRequest.expectedDescriptorResultDigest(),
            associationRequest.expectedReleaseAttestationEvidenceDigest()),
        evidence,
        launchBinding,
        versionIdentity,
        topologyPlan);
  }

  private static CaptureRequest captureRequest(WorldPublishedStartLocationEvidence selector) {
    var request = Objects.requireNonNull(selector, "World release selector").request();
    return new CaptureRequest(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.canonicalVersionId(),
        request.intakeRequestId(),
        request.publicationFence(),
        request.publicationRequestId(),
        request.requestDigest(),
        request.versionStateEpoch(),
        request.publishWorkflowId(),
        request.appliedCommitId(),
        request.contentDigest(),
        request.digestSchemaVersion(),
        request.worldAffectedTuples().stream()
            .map(
                tuple ->
                    new WorldAuthoredGraphSnapshot.OwnedAffectedTuple(
                        tuple.owner(),
                        tuple.aggregateType(),
                        tuple.aggregateId(),
                        tuple.scopeType(),
                        tuple.scopeId(),
                        tuple.expectedEpoch()))
            .toList());
  }

  private GetLaunchDescriptorRequest gameDesignReadRequest(
      WorldCompleteLaunchBindingReceipt launchBinding) {
    AuthoredWorldLaunchDescriptorEvidence descriptor = launchBinding.descriptor();
    UUID requestId = newReadRequestId(descriptor, null, null);
    return GetLaunchDescriptorRequest.newBuilder()
        .setRequestId(requestId.toString())
        .setCanonicalTenantId(descriptor.canonicalTenantId().toString())
        .setWorldSlug(descriptor.worldSlug())
        .setControlPlaneRequestId(descriptor.controlPlaneRequestId())
        .setExpectedRequestDigest(descriptor.requestDigest())
        .setExpectedResultDigest(descriptor.resultDigest())
        .build();
  }

  private static UUID newReadRequestId(
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      UUID canonicalGameInstanceId,
      UUID otherReadRequestId) {
    UUID candidate;
    do {
      candidate = UUID.randomUUID();
    } while (candidate.equals(descriptor.authoredWorldSourceOperationId())
        || candidate.toString().equals(descriptor.controlPlaneRequestId())
        || candidate.equals(canonicalGameInstanceId)
        || candidate.equals(otherReadRequestId));
    return candidate;
  }

  private void requireStoredSelector(
      WorldCompleteLaunchBindingRepository.StoredBinding stored, Selector selector) {
    if (!workloadNamespace.equals(stored.targetNamespace())
        || !selector.canonicalTenantId().equals(stored.canonicalTenantId())
        || !selector.controlPlaneRequestId().equals(stored.controlPlaneRequestId())) {
      throw new AssemblyRejectedException(
          "World complete launch binding differs from the stable tenant/request selector");
    }
  }

  static void requireSameCompletePair(
      CompleteLaunchBindingEvidence expected, CompleteLaunchBindingEvidence actual) {
    if (actual == null
        || !expected.equals(actual)
        || !expected.descriptor().equals(actual.descriptor())
        || !expected.releaseAttestation().equals(actual.releaseAttestation())
        || !expected.descriptor().requestDigest().equals(actual.descriptor().requestDigest())
        || !expected.descriptor().resultDigest().equals(actual.descriptor().resultDigest())
        || !expected
            .releaseAttestation()
            .evidenceDigest()
            .equals(actual.releaseAttestation().evidenceDigest())) {
      throw new AssemblyRejectedException(
          "Authenticated Game Design complete launch pair differs from the committed World pair");
    }
  }

  static void requireGameSessionBinding(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request request,
      CanonicalGameInstanceLaunchAssociationReadEvidence.Result result,
      CompleteLaunchBindingEvidence expected) {
    if (result == null
        || !request.equals(result.request())
        || result.playableStateScope() != RealmEntryPolicy.StateScope.SHARED
        || !result.publicProduction()
        || !expected.equals(result.launchBindingEvidence())
        || !expected.descriptor().equals(result.launchBindingEvidence().descriptor())
        || !expected
            .releaseAttestation()
            .equals(result.launchBindingEvidence().releaseAttestation())) {
      throw new AssemblyRejectedException(
          "Authenticated Game Session association differs from the exact World selector or release pair");
    }
  }

  static WorldCanonicalInstanceAssociation.GameSessionReadEvidence associationToWorldEvidence(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request request,
      CanonicalGameInstanceLaunchAssociationReadEvidence.Result result) {
    var pair = result.launchBindingEvidence();
    return new WorldCanonicalInstanceAssociation.GameSessionReadEvidence(
        request.readRequestId(),
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.worldSlug(),
        request.gameInstanceUuid(),
        request.controlPlaneRequestId(),
        request.launchDescriptorId(),
        request.expectedDescriptorRequestDigest(),
        request.expectedDescriptorResultDigest(),
        request.expectedReleaseAttestationEvidenceDigest(),
        result.playableStateNamespaceId(),
        result.playableStateScope().name(),
        result.publicProduction(),
        result.currentGameInstanceStatus().name(),
        result.currentRowVersion(),
        pair.descriptor(),
        pair.releaseAttestation());
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Canonical World preparation input assembly requires independent committed reads");
    }
  }

  /** The only caller-selected fields; every other World/owner selector is derived from storage. */
  public record Selector(
      UUID canonicalTenantId, UUID canonicalGameInstanceId, String controlPlaneRequestId) {
    public Selector {
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
      if (controlPlaneRequestId == null
          || controlPlaneRequestId.isBlank()
          || controlPlaneRequestId.length() > 128) {
        throw new IllegalArgumentException("controlPlaneRequestId must be non-blank and bounded");
      }
    }

    private static void requireNonNil(UUID value, String name) {
      Objects.requireNonNull(value, name);
      if (value.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException(name + " must be a non-nil UUID");
      }
    }
  }

  public static final class AssemblyRejectedException extends IllegalStateException {
    public AssemblyRejectedException(String message) {
      super(message);
    }
  }
}
