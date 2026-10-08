package net.firedevops.firemud.gamesession.service;

import io.grpc.StatusRuntimeException;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.common.world.PreseededActorAssignmentOwnerReadEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.gamesession.client.GameDesignPublishedRealmPolicyClient;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalPublishedPlayerRoute;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import org.jooq.exception.DataAccessException;

/**
 * Read-only composition of current Game Session routing and a fresh complete Game Design policy
 * set. A result is source evidence only and does not enable PLAY or actor admission.
 */
public final class CanonicalPublishedPlayerRouteReadService {
  private final String targetNamespace;
  private final CanonicalPlayerRouteReadService routeReader;
  private final GameSessionCanonicalRealmCatalogRepository catalogRepository;
  private final CanonicalGameInstanceLaunchAssociationRepository launchRepository;
  private final GameDesignPublishedRealmPolicyClient policyClient;

  public CanonicalPublishedPlayerRouteReadService(
      String targetNamespace,
      CanonicalPlayerRouteReadService routeReader,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      CanonicalGameInstanceLaunchAssociationRepository launchRepository,
      GameDesignPublishedRealmPolicyClient policyClient) {
    this.targetNamespace = targetNamespace;
    this.routeReader = routeReader;
    this.catalogRepository = catalogRepository;
    this.launchRepository = launchRepository;
    this.policyClient = policyClient;
  }

  /** Default-off source client: absent composition produces unavailable, never an open route. */
  public static CanonicalPublishedPlayerRouteReadService unavailable() {
    return new CanonicalPublishedPlayerRouteReadService(null, null, null, null, null);
  }

  /**
   * Reads fresh owner policy for an exact current route, checks the original source and launch
   * bindings, then rereads the full current route before returning the sealed evidence.
   */
  public CanonicalPublishedPlayerRoute readCurrent(CanonicalPlayableTarget expected) {
    Objects.requireNonNull(expected, "expected");
    return readCurrent(expected, null);
  }

  /**
   * Reads an exact caller-selected tenant target and returns complete source evidence for Entity.
   * The Account UUID is echoed only; this method does not establish Account membership or PLAY
   * authority.
   */
  public CanonicalGameplayRosterOwnerReadEvidence readCurrent(
      CanonicalGameplayRosterOwnerReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    CanonicalPublishedPlayerRoute route = readCurrent(null, request);
    return new CanonicalGameplayRosterOwnerReadEvidence(
        request,
        route.route().admissionPointerSnapshotDigest(),
        ownerProof(route.route()),
        route.policySetEvidence());
  }

  /**
   * Reads assignment source evidence from exact immutable selectors while deriving current pointer
   * and lifecycle counters from Game Session-owned route evidence. This is a staging-only source
   * read; the Account selectors are correlation data and do not establish membership or admission.
   */
  public PreseededActorAssignmentOwnerReadEvidence readCurrentForAssignment(
      PreseededActorAssignmentOwnerReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    requireConfigured();
    if (!targetNamespace.equals(request.targetNamespace())) {
      throw new InvalidAuthorityException("Selected route belongs to another owner namespace");
    }

    CanonicalPlayableTarget current = readRoute(request.canonicalTenantUuid());
    requireAssignmentSelectors(request, current);
    var sourceRequest =
        PreseededActorAssignmentOwnerReadEvidence.sourceRequest(request, ownerProof(current));
    CanonicalGameplayRosterOwnerReadEvidence sourceEvidence = readCurrent(sourceRequest);
    return new PreseededActorAssignmentOwnerReadEvidence(request, sourceEvidence);
  }

  private CanonicalPublishedPlayerRoute readCurrent(
      CanonicalPlayableTarget expected, CanonicalGameplayRosterOwnerReadEvidence.Request request) {
    if ((expected == null) == (request == null)) {
      throw new IllegalArgumentException("Exactly one canonical route selector is required");
    }
    requireConfigured();
    if (expected != null && !targetNamespace.equals(expected.targetNamespace())) {
      throw new InvalidAuthorityException("Selected route belongs to another owner namespace");
    }
    if (request != null && !targetNamespace.equals(request.targetNamespace())) {
      throw new InvalidAuthorityException("Selected route belongs to another owner namespace");
    }

    CanonicalPlayableTarget before =
        expected == null ? readRoute(request.canonicalTenantUuid()) : readRoute(expected);
    if (expected != null) {
      requireExpectedRoute(expected, before);
    } else {
      requireExpectedRequest(request, before);
    }
    CanonicalRealmCatalogSnapshot catalogBefore = readCatalog(before);
    CanonicalInitialAdmissionLaunchTarget launchBefore = readLaunch(before);
    requireCurrentRouteBinding(before, catalogBefore, launchBefore);

    PublishedRealmEntryPolicySetEvidence policySet =
        readPolicy(before.canonicalTenantId(), before.canonicalVersionId());
    PublishedRealmEntryPolicySetEvidence complete = verifySet(policySet);
    PublishedRealmEntryPolicyEvidence selected =
        requirePublishedPreseededPolicy(complete, before, catalogBefore, launchBefore);

    CanonicalPlayableTarget after =
        expected == null ? readRoute(request.canonicalTenantUuid()) : readRoute(before);
    if (!before.equals(after)) {
      throw new StaleAuthorityException("Canonical player route changed during policy read");
    }
    if (request != null) {
      requireExpectedRequest(request, after);
    }
    CanonicalRealmCatalogSnapshot catalogAfter = readCatalog(after);
    CanonicalInitialAdmissionLaunchTarget launchAfter = readLaunch(after);
    if (!catalogBefore.equals(catalogAfter)
        || !sameCurrentLaunchBinding(launchBefore, launchAfter)) {
      throw new StaleAuthorityException(
          "Canonical source or complete launch binding changed during policy read");
    }
    requireCurrentRouteBinding(after, catalogAfter, launchAfter);

    PublishedRealmEntryPolicySetEvidence confirmedPolicySet =
        verifySet(readPolicy(after.canonicalTenantId(), after.canonicalVersionId()));
    if (!complete.equals(confirmedPolicySet)) {
      throw new StaleAuthorityException(
          "Game Design policy source or capture changed during owner read");
    }

    return new CanonicalPublishedPlayerRoute(after, complete, selected);
  }

  private GameSessionCanonicalInitialAdmissionOwnerProof ownerProof(CanonicalPlayableTarget route) {
    if (route.ownerProofOutcome() != CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED) {
      throw new InvalidAuthorityException("Current route lacks terminal COMMITTED owner proof");
    }
    WorldCanonicalInitialAdmissionHold.Request request =
        new WorldCanonicalInitialAdmissionHold.Request(
            route.targetNamespace(),
            route.canonicalTenantId(),
            route.worldSlug(),
            route.realmId(),
            route.playableStateNamespaceId(),
            route.playableStateScope(),
            route.canonicalGameInstanceId(),
            route.canonicalVersionId(),
            route.activeWorldEpoch(),
            route.initialAdmissionRequestId(),
            route.initialAdmissionRequestDigest(),
            route.initialAdmissionOriginKind()
                    == CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER
                ? WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER
                : WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.EXPECT_CLOSED,
            route.catalogRevision(),
            route.expectedPriorPointerVersion());
    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            request, route.holdId(), route.holdFence());
    if (!hold.holdBindingDigest().equals(route.holdBindingDigest())) {
      throw new InvalidAuthorityException(
          "Current route hold digest differs from the complete World-held request");
    }
    GameSessionCanonicalInitialAdmissionOwnerProof proof =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            hold,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            route.pointerVersion(),
            route.auditEventId(),
            route.ownerProofDigest(),
            false,
            route.ownerProofTerminalAt());
    return GameSessionCanonicalInitialAdmissionOwnerProofCodec.fromStored(
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(proof));
  }

  private static void requireExpectedRequest(
      CanonicalGameplayRosterOwnerReadEvidence.Request expected, CanonicalPlayableTarget current) {
    if (current == null
        || !expected.targetNamespace().equals(current.targetNamespace())
        || !expected.canonicalTenantUuid().equals(current.canonicalTenantId())
        || !expected.worldSlug().equals(current.worldSlug())
        || !expected.realmUuid().equals(current.realmId())
        || !expected.realmSlug().equals(current.realmSlug())
        || !expected.playableStateNamespaceUuid().equals(current.playableStateNamespaceId())
        || !expected.playableStateScope().equals(current.playableStateScope())
        || !expected.canonicalGameInstanceUuid().equals(current.canonicalGameInstanceId())
        || !expected.canonicalVersionUuid().equals(current.canonicalVersionId())
        || expected.expectedCatalogRevision() != current.catalogRevision()
        || expected.expectedPointerVersion() != current.pointerVersion()
        || expected.expectedActiveWorldEpoch() != current.activeWorldEpoch()) {
      throw new StaleAuthorityException(
          "Canonical current route differs from the exact requested selectors and counters");
    }
  }

  private static void requireAssignmentSelectors(
      PreseededActorAssignmentOwnerReadEvidence.Request expected, CanonicalPlayableTarget current) {
    if (current == null
        || !expected.targetNamespace().equals(current.targetNamespace())
        || !expected.canonicalTenantUuid().equals(current.canonicalTenantId())
        || !expected.worldSlug().equals(current.worldSlug())
        || !expected.realmUuid().equals(current.realmId())
        || !expected.realmSlug().equals(current.realmSlug())
        || !expected.playableStateNamespaceUuid().equals(current.playableStateNamespaceId())
        || !expected.playableStateScope().equals(current.playableStateScope())
        || !expected.canonicalGameInstanceUuid().equals(current.canonicalGameInstanceId())
        || !expected.canonicalVersionUuid().equals(current.canonicalVersionId())
        || expected.expectedCatalogRevision() != current.catalogRevision()) {
      throw new StaleAuthorityException(
          "Canonical current route differs from the exact actor-assignment selectors");
    }
  }

  private CanonicalPlayableTarget readRoute(CanonicalPlayableTarget expected) {
    try {
      return routeReader.readCurrentTarget(expected);
    } catch (CanonicalPlayerRouteReadService.ReadUnavailableException unavailable) {
      throw new StorageUnavailableException("Canonical route storage is unavailable", unavailable);
    } catch (CanonicalPlayerRouteReadService.InvalidAuthorityException invalid) {
      throw new InvalidAuthorityException("Canonical route authority is invalid", invalid);
    }
  }

  private CanonicalPlayableTarget readRoute(UUID canonicalTenantId) {
    try {
      return routeReader.readCurrentTarget(canonicalTenantId);
    } catch (CanonicalPlayerRouteReadService.ReadUnavailableException unavailable) {
      throw new StorageUnavailableException("Canonical route storage is unavailable", unavailable);
    } catch (CanonicalPlayerRouteReadService.InvalidAuthorityException invalid) {
      throw new InvalidAuthorityException("Canonical route authority is invalid", invalid);
    }
  }

  private CanonicalRealmCatalogSnapshot readCatalog(CanonicalPlayableTarget route) {
    try {
      return catalogRepository
          .readUniqueVisiblePublicProduction(targetNamespace, route.canonicalTenantId())
          .orElseThrow(
              () -> new StaleAuthorityException("Canonical public-production catalog is missing"));
    } catch (DataAccessException unavailable) {
      throw new StorageUnavailableException(
          "Canonical catalog storage is unavailable", unavailable);
    } catch (StaleAuthorityException stale) {
      throw stale;
    } catch (RuntimeException invalid) {
      throw new InvalidAuthorityException("Canonical catalog source evidence is invalid", invalid);
    }
  }

  private CanonicalInitialAdmissionLaunchTarget readLaunch(CanonicalPlayableTarget route) {
    try {
      return launchRepository
          .readForInitialAdmission(
              targetNamespace,
              route.canonicalTenantId(),
              route.realmId(),
              route.canonicalGameInstanceId())
          .orElseThrow(
              () -> new StaleAuthorityException("Current canonical launch binding is missing"));
    } catch (DataAccessException unavailable) {
      throw new StorageUnavailableException("Canonical launch storage is unavailable", unavailable);
    } catch (StaleAuthorityException stale) {
      throw stale;
    } catch (RuntimeException invalid) {
      throw new InvalidAuthorityException("Canonical launch binding is invalid", invalid);
    }
  }

  private PublishedRealmEntryPolicySetEvidence readPolicy(
      UUID canonicalTenantId, UUID canonicalVersionId) {
    try {
      return policyClient.listPublishedRealmEntryPolicies(canonicalTenantId, canonicalVersionId);
    } catch (StatusRuntimeException | IllegalStateException unavailable) {
      throw new GameDesignUnavailableException(
          "Game Design policy transport is unavailable", unavailable);
    } catch (IllegalArgumentException rejected) {
      throw new InvalidAuthorityException("Game Design policy owner read was rejected", rejected);
    }
  }

  private PublishedRealmEntryPolicySetEvidence verifySet(
      PublishedRealmEntryPolicySetEvidence policySet) {
    if (policySet == null) {
      throw new InvalidAuthorityException("Game Design returned no published policy set");
    }
    try {
      return PublishedRealmEntryPolicySetEvidence.fromStored(policySet.canonicalBytes());
    } catch (RuntimeException invalid) {
      throw new InvalidAuthorityException(
          "Game Design policy set is not complete closed evidence", invalid);
    }
  }

  private PublishedRealmEntryPolicyEvidence requirePublishedPreseededPolicy(
      PublishedRealmEntryPolicySetEvidence complete,
      CanonicalPlayableTarget route,
      CanonicalRealmCatalogSnapshot catalog,
      CanonicalInitialAdmissionLaunchTarget launch) {
    var source = catalog.sourceIntakeReceipt().source();
    var targetProof = complete.target();
    var operation = GameDesignPublicationOperationBinding.fromStored(complete.operationBytes());
    var terminal =
        GameDesignPublicationTerminalEvidence.fromStored(complete.terminalEvidenceBytes());
    var association = launch.association();
    var descriptor = association.launchBindingEvidence().descriptor();
    var release = association.launchBindingEvidence().releaseAttestation();
    if (!operation.account().input().selection().target().equals(targetProof)
        || !targetNamespace.equals(operation.world().request().targetNamespace())
        || !targetNamespace.equals(terminal.worldEvidence().request().targetNamespace())
        || !route.canonicalTenantId().equals(operation.world().request().canonicalTenantId())
        || !route.canonicalVersionId().equals(operation.world().request().canonicalVersionId())
        || !complete.target().canonicalTenantId().equals(route.canonicalTenantId())
        || !complete.target().canonicalVersionId().equals(route.canonicalVersionId())
        || !targetNamespace.equals(source.targetNamespace())
        || !route.canonicalTenantId().equals(source.canonicalTenantId())
        || targetProof.sourceGameRowId() != source.sourceGameRowId()
        || !targetProof.sourceGameTenantKey().equals(source.sourceGameTenantKey())
        || !targetProof.gameDesignVersionTenantKey().equals(source.sourceGameTenantKey())
        || !targetProof.sourceProvenanceKind().equals(source.provenanceKind())
        || !release.authoredWorldSourceOperationId().equals(source.operationId())
        || !release.authoredWorldSourceEvidenceDigest().equals(source.evidenceDigest())
        || !release.commitId().equals(complete.sourceCommitId().toString())
        || !release.publishedReleaseBundleRef().equals(complete.publishedReleaseBundleRef())
        || !release.publishWorkflowId().equals(complete.publishWorkflowId())
        || !release.manifestHash().equals(complete.manifestHash())
        || release.versionStateEpoch() != complete.publicationVersionStateEpoch()
        || !terminal.publishedReleaseBundleRef().equals(complete.publishedReleaseBundleRef())
        || !terminal.publishedReleaseBundleDigest().equals(complete.publishedReleaseBundleDigest())
        || !terminal.releaseContent().publishWorkflowId().equals(complete.publishWorkflowId())
        || !terminal.releaseContent().manifestHash().equals(complete.manifestHash())
        || terminal.publicationVersionStateEpoch() != complete.publicationVersionStateEpoch()
        || !descriptor.publishedReleaseBundleRef().equals(complete.publishedReleaseBundleRef())
        || descriptor.versionStateEpoch() != complete.publicationVersionStateEpoch()) {
      throw new InvalidAuthorityException(
          "Published policy source or release does not match the current complete launch binding");
    }

    var matches =
        complete.policies().stream()
            .filter(
                evidence ->
                    evidence.policy().worldSlug().equals(route.worldSlug())
                        && evidence.policy().realmSlug().equals(route.realmSlug()))
            .toList();
    if (matches.size() != 1) {
      throw new InvalidAuthorityException("Published policy set lacks the exact current selector");
    }
    PublishedRealmEntryPolicyEvidence selected = matches.getFirst();
    RealmEntryPolicy policy = selected.policy();
    if (!policy.visible()
        || !policy.publicProduction()
        || policy.stateScope() != RealmEntryPolicy.StateScope.SHARED
        || policy.entryPolicy() != RealmEntryPolicy.EntryPolicy.PRESEEDED_ONLY
        || !catalog.visible()
        || !catalog.publicProduction()
        || !"SHARED".equals(catalog.stateScope())) {
      throw new InvalidAuthorityException(
          "Published policy and current public-production route do not match PRESEEDED_ONLY");
    }
    return selected;
  }

  private void requireCurrentRouteBinding(
      CanonicalPlayableTarget route,
      CanonicalRealmCatalogSnapshot catalog,
      CanonicalInitialAdmissionLaunchTarget launch) {
    CanonicalGameInstanceLaunchAssociation association = launch.association();
    var source = catalog.sourceIntakeReceipt().source();
    var descriptor = association.launchBindingEvidence().descriptor();
    var release = association.launchBindingEvidence().releaseAttestation();
    if (!targetNamespace.equals(route.targetNamespace())
        || !targetNamespace.equals(catalog.targetNamespace())
        || !route.canonicalTenantId().equals(catalog.tenantId())
        || !route.worldSlug().equals(catalog.worldSlug())
        || !route.realmId().equals(catalog.realmId())
        || !route.realmSlug().equals(catalog.realmSlug())
        || !route.playableStateNamespaceId().equals(catalog.playableStateNamespaceId())
        || !route.playableStateScope().equals(catalog.stateScope())
        || !catalog.visible()
        || !catalog.publicProduction()
        || !"SHARED".equals(catalog.stateScope())
        || !route.canonicalTenantId().equals(association.canonicalTenantId())
        || !route.canonicalGameInstanceId().equals(association.gameInstanceUuid())
        || !route.worldSlug().equals(association.worldSlug())
        || !route.playableStateNamespaceId().equals(association.playableStateNamespaceId())
        || association.playableStateScope() != RealmEntryPolicy.StateScope.SHARED
        || !association.publicProduction()
        || association.currentGameInstanceStatus()
            != CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.RUNNING
        || !route.realmId().equals(launch.realmId())
        || route.gameInstanceId() != launch.gameInstanceId()
        || route.runtimeVersionId() != launch.runtimeVersionId()
        || !route.canonicalVersionId().equals(launch.canonicalVersionId())
        || !targetNamespace.equals(descriptor.targetNamespace())
        || !route.canonicalTenantId().equals(descriptor.canonicalTenantId())
        || !route.worldSlug().equals(descriptor.worldSlug())
        || !route.canonicalTenantId().equals(release.canonicalTenantId())
        || !route.canonicalVersionId().equals(release.canonicalVersionId())
        || !route.worldSlug().equals(release.worldSlug())
        || !targetNamespace.equals(release.targetNamespace())
        || !source.operationId().equals(descriptor.authoredWorldSourceOperationId())
        || !source.evidenceDigest().equals(descriptor.authoredWorldSourceEvidenceDigest())
        || !source.operationId().equals(release.authoredWorldSourceOperationId())
        || !source.evidenceDigest().equals(release.authoredWorldSourceEvidenceDigest())
        || descriptor.versionId() != route.runtimeVersionId()
        || descriptor.versionStateEpoch() != release.versionStateEpoch()
        || !descriptor.publishedReleaseBundleRef().equals(release.publishedReleaseBundleRef())
        || !association.controlPlaneRequestId().equals(descriptor.controlPlaneRequestId())
        || !association.launchDescriptorId().equals(descriptor.launchDescriptorId())) {
      throw new InvalidAuthorityException(
          "Canonical catalog, route, and complete launch binding disagree");
    }
  }

  private static void requireExpectedRoute(
      CanonicalPlayableTarget expected, CanonicalPlayableTarget current) {
    if (current == null || !expected.equals(current)) {
      throw new StaleAuthorityException(
          "Selected canonical player route changed before policy read");
    }
  }

  private static boolean sameCurrentLaunchBinding(
      CanonicalInitialAdmissionLaunchTarget left, CanonicalInitialAdmissionLaunchTarget right) {
    if (right == null
        || !left.realmId().equals(right.realmId())
        || left.gameInstanceId() != right.gameInstanceId()
        || left.runtimeVersionId() != right.runtimeVersionId()) {
      return false;
    }
    CanonicalGameInstanceLaunchAssociation first = left.association();
    CanonicalGameInstanceLaunchAssociation second = right.association();
    return first.targetNamespace().equals(second.targetNamespace())
        && first.gameSessionTenantId() == second.gameSessionTenantId()
        && first.tenantAssociationOperationId().equals(second.tenantAssociationOperationId())
        && first.canonicalTenantId().equals(second.canonicalTenantId())
        && first.gameInstanceUuid().equals(second.gameInstanceUuid())
        && first.worldSlug().equals(second.worldSlug())
        && first.playableStateNamespaceId().equals(second.playableStateNamespaceId())
        && first.playableStateScope() == second.playableStateScope()
        && first.publicProduction() == second.publicProduction()
        && first.controlPlaneRequestId().equals(second.controlPlaneRequestId())
        && first.launchDescriptorId().equals(second.launchDescriptorId())
        && first.capturedStartingRowVersion() == second.capturedStartingRowVersion()
        && first.currentRowVersion() == second.currentRowVersion()
        && first.launchBindingEvidence().equals(second.launchBindingEvidence())
        && first.currentGameInstanceStatus() == second.currentGameInstanceStatus();
  }

  private void requireConfigured() {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)
        || routeReader == null
        || catalogRepository == null
        || launchRepository == null
        || policyClient == null) {
      throw new StorageUnavailableException("Canonical published player-route reader is disabled");
    }
  }

  public static class InvalidAuthorityException extends RuntimeException {
    public InvalidAuthorityException(String message) {
      super(message);
    }

    public InvalidAuthorityException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static final class StaleAuthorityException extends InvalidAuthorityException {
    public StaleAuthorityException(String message) {
      super(message);
    }
  }

  public static final class StorageUnavailableException extends RuntimeException {
    public StorageUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }

    public StorageUnavailableException(String message) {
      super(message);
    }
  }

  public static final class GameDesignUnavailableException extends RuntimeException {
    public GameDesignUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
