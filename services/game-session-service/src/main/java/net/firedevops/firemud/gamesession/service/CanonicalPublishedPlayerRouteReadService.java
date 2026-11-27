package net.firedevops.firemud.gamesession.service;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRunOwnedFixtureSourceEvidence;
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
 * set, or for the run-owned Compose fixture its catalog-retained sealed fixture policy. A result is
 * source evidence only and does not enable PLAY or actor admission.
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
    return requirePublished(readCurrent(expected, null));
  }

  /**
   * Reads an exact caller-selected tenant target and returns complete source evidence for Entity.
   * The Account UUID is echoed only; this method does not establish Account membership or PLAY
   * authority.
   */
  public CanonicalGameplayRosterOwnerReadEvidence readCurrent(
      CanonicalGameplayRosterOwnerReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    CanonicalPublishedPlayerRoute route = requirePublished(readCurrent(null, request));
    return new CanonicalGameplayRosterOwnerReadEvidence(
        request,
        route.route().admissionPointerSnapshotDigest(),
        ownerProof(route.route()),
        route.policySetEvidence());
  }

  /**
   * Reads the exact caller-selected target for the Account published-realm owner read: the full
   * Human published source, or for a run-owned fixture launch its retained sealed fixture policy.
   * Neither variant establishes Account membership or PLAY authority.
   */
  public AdmissionSource readCurrentAdmissionSource(
      CanonicalGameplayRosterOwnerReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    CurrentSource current = readCurrent(null, request);
    if (current.published() != null) {
      CanonicalPublishedPlayerRoute route = current.published();
      return new AdmissionSource(
          new CanonicalGameplayRosterOwnerReadEvidence(
              request,
              route.route().admissionPointerSnapshotDigest(),
              ownerProof(route.route()),
              route.policySetEvidence()),
          null);
    }
    var fixture = current.fixturePolicy();
    try {
      return new AdmissionSource(
          null,
          new CanonicalGameplayRunOwnedFixtureSourceEvidence(
              request,
              current.route().admissionPointerSnapshotDigest(),
              ownerProof(current.route()),
              fixture.requestBytes(),
              fixture.sealOperationId(),
              fixture.sealRequestDigest(),
              fixture.sealEvidenceDigest()));
    } catch (IllegalArgumentException invalid) {
      throw new InvalidAuthorityException(
          "Run-owned fixture source is not the exact current route", invalid);
    }
  }

  /** Exactly one Human published source or run-owned fixture source for one exact route. */
  public record AdmissionSource(
      CanonicalGameplayRosterOwnerReadEvidence published,
      CanonicalGameplayRunOwnedFixtureSourceEvidence runOwnedFixture) {
    public AdmissionSource {
      if ((published == null) == (runOwnedFixture == null)) {
        throw new IllegalArgumentException(
            "Exactly one published or run-owned fixture admission source is required");
      }
    }

    public boolean isRunOwnedFixture() {
      return runOwnedFixture != null;
    }
  }

  /** Exactly one Human published route or run-owned fixture route with its retained policy. */
  private record CurrentSource(
      CanonicalPlayableTarget route,
      CanonicalPublishedPlayerRoute published,
      CanonicalRealmCatalogSnapshot.FixturePolicy fixturePolicy) {
    private CurrentSource {
      Objects.requireNonNull(route, "route");
      if ((published == null) == (fixturePolicy == null)) {
        throw new IllegalArgumentException("Exactly one canonical route source is required");
      }
    }
  }

  private static CanonicalPublishedPlayerRoute requirePublished(CurrentSource current) {
    if (current.published() == null) {
      throw new InvalidAuthorityException(
          "Run-owned fixture route has no Game Design published policy source");
    }
    return current.published();
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

  private CurrentSource readCurrent(
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
    if (launchBefore.association().isRunOwnedFixture()) {
      return readCurrentFixture(expected, request, before, catalogBefore, launchBefore);
    }

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

    return new CurrentSource(
        after, new CanonicalPublishedPlayerRoute(after, complete, selected), null);
  }

  /**
   * Run-owned fixture variant: no Game Design publication exists, so the catalog-retained sealed
   * fixture policy (independently verified by the catalog owner on readback) replaces the policy
   * read, and the same complete route/catalog/launch reread brackets it.
   */
  private CurrentSource readCurrentFixture(
      CanonicalPlayableTarget expected,
      CanonicalGameplayRosterOwnerReadEvidence.Request request,
      CanonicalPlayableTarget before,
      CanonicalRealmCatalogSnapshot catalogBefore,
      CanonicalInitialAdmissionLaunchTarget launchBefore) {
    requireRunOwnedFixturePolicy(before, catalogBefore, launchBefore);

    CanonicalPlayableTarget after =
        expected == null ? readRoute(request.canonicalTenantUuid()) : readRoute(before);
    if (!before.equals(after)) {
      throw new StaleAuthorityException("Canonical player route changed during fixture read");
    }
    if (request != null) {
      requireExpectedRequest(request, after);
    }
    CanonicalRealmCatalogSnapshot catalogAfter = readCatalog(after);
    CanonicalInitialAdmissionLaunchTarget launchAfter = readLaunch(after);
    if (!catalogBefore.equals(catalogAfter)
        || !sameCurrentLaunchBinding(launchBefore, launchAfter)) {
      throw new StaleAuthorityException(
          "Canonical source or complete fixture launch binding changed during owner read");
    }
    requireCurrentRouteBinding(after, catalogAfter, launchAfter);
    requireRunOwnedFixturePolicy(after, catalogAfter, launchAfter);
    return new CurrentSource(after, null, catalogAfter.fixturePolicy());
  }

  private void requireRunOwnedFixturePolicy(
      CanonicalPlayableTarget route,
      CanonicalRealmCatalogSnapshot catalog,
      CanonicalInitialAdmissionLaunchTarget launch) {
    CanonicalGameInstanceLaunchAssociation association = launch.association();
    CanonicalRealmCatalogSnapshot.FixturePolicy fixture = catalog.fixturePolicy();
    if (!association.isRunOwnedFixture() || fixture == null) {
      throw new InvalidAuthorityException(
          "Run-owned fixture route requires its catalog-retained fixture policy");
    }
    final RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicyRequest parsed;
    final String requestDigest;
    final String evidenceDigest;
    try {
      byte[] requestBytes = fixture.requestBytes();
      parsed =
          RunOwnedInitialAdmissionFixtureWireCodec.parseFixturePolicyRequestBytes(requestBytes);
      requestDigest =
          RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestDigest(
              parsed.capability(),
              parsed.creationEvidence(),
              parsed.sourceEvidence(),
              parsed.launchReadEvidence());
      evidenceDigest =
          RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyEvidenceDigest(
              fixture.sealOperationId(), fixture.sealRequestDigest());
      if (!Arrays.equals(
          requestBytes,
          RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestBytes(
              parsed.capability(),
              parsed.creationEvidence(),
              parsed.sourceEvidence(),
              parsed.launchReadEvidence()))) {
        throw new InvalidAuthorityException("Retained fixture policy is not canonical");
      }
    } catch (IllegalArgumentException invalid) {
      throw new InvalidAuthorityException("Retained fixture policy is invalid", invalid);
    }
    var capability = parsed.capability();
    var bundle = parsed.launchReadEvidence().publishedReleaseBundle();
    var versionState = parsed.launchReadEvidence().versionState();
    var descriptor = association.descriptor();
    if (!capability.visible()
        || !capability.publicProductionRealm()
        || !"SHARED".equals(capability.stateScope())
        || !"PRESEEDED_ONLY".equals(capability.characterCreationPolicy())
        || !catalog.visible()
        || !catalog.publicProduction()
        || !"SHARED".equals(catalog.stateScope())
        || !capability.characterCreationPolicy().equals(catalog.characterCreationPolicy())
        || !capability.worldSlug().equals(route.worldSlug())
        || !capability.realmSlug().equals(route.realmSlug())
        || !parsed.sourceEvidence().equals(catalog.sourceIntakeReceipt().source())
        || !route.canonicalVersionId().toString().equals(bundle.getCanonicalVersionId())
        || !route.canonicalVersionId().equals(association.fixtureCanonicalVersionId())
        || !UUID.fromString(capability.operationId()).equals(fixture.sealOperationId())
        || !requestDigest.equals(fixture.sealRequestDigest())
        || !evidenceDigest.equals(fixture.sealEvidenceDigest())
        || !fixture.sealEvidenceDigest().equals(association.fixtureProvenanceDigest())
        || !descriptor.publishedReleaseBundleRef().equals(bundle.getPublishedReleaseBundleRef())
        || descriptor.versionId() != bundle.getVersionId()
        || descriptor.versionStateEpoch() != versionState.getVersionStateEpoch()) {
      throw new InvalidAuthorityException(
          "Retained fixture policy does not match the current PRESEEDED_ONLY fixture launch");
    }
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
    } catch (StatusRuntimeException failure) {
      if (isTransient(failure.getStatus())) {
        throw new GameDesignUnavailableException(
            "Game Design policy transport is unavailable", failure);
      }
      if (failure.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
        throw new StaleAuthorityException("Game Design policy owner read is stale");
      }
      throw new InvalidAuthorityException("Game Design policy owner read was rejected", failure);
    } catch (GameDesignPublishedRealmPolicyClient.UninitializedClientException unavailable) {
      throw new GameDesignUnavailableException(
          "Game Design policy transport is unavailable", unavailable);
    } catch (GameDesignPublishedRealmPolicyClient.InvalidResponseException invalid) {
      throw new InvalidAuthorityException("Game Design policy response is invalid", invalid);
    } catch (IllegalArgumentException rejected) {
      throw new InvalidAuthorityException("Game Design policy owner read was rejected", rejected);
    }
  }

  private static boolean isTransient(io.grpc.Status status) {
    return switch (status.getCode()) {
      case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, ABORTED -> true;
      default -> false;
    };
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
    if (association.isRunOwnedFixture()) {
      throw new InvalidAuthorityException("Run-owned fixture launch has no published policy");
    }
    var descriptor = association.descriptor();
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
    var descriptor = association.descriptor();
    if (association.isRunOwnedFixture() != catalog.isRunOwnedFixture()) {
      throw new InvalidAuthorityException(
          "Canonical catalog and launch disagree on run-owned fixture provenance");
    }
    if (association.isRunOwnedFixture()) {
      requireCurrentRouteSharedBinding(route, catalog, launch, association, source, descriptor);
      if (!route.canonicalVersionId().equals(association.fixtureCanonicalVersionId())) {
        throw new InvalidAuthorityException(
            "Canonical route and run-owned fixture launch disagree on content version");
      }
      return;
    }
    var release = association.launchBindingEvidence().releaseAttestation();
    requireCurrentRouteSharedBinding(route, catalog, launch, association, source, descriptor);
    if (!route.canonicalTenantId().equals(release.canonicalTenantId())
        || !route.canonicalVersionId().equals(release.canonicalVersionId())
        || !route.worldSlug().equals(release.worldSlug())
        || !targetNamespace.equals(release.targetNamespace())
        || !source.operationId().equals(release.authoredWorldSourceOperationId())
        || !source.evidenceDigest().equals(release.authoredWorldSourceEvidenceDigest())
        || descriptor.versionStateEpoch() != release.versionStateEpoch()
        || !descriptor.publishedReleaseBundleRef().equals(release.publishedReleaseBundleRef())) {
      throw new InvalidAuthorityException(
          "Canonical catalog, route, and complete launch binding disagree");
    }
  }

  /** Route, catalog, launch and descriptor binding shared by the Human and fixture variants. */
  private void requireCurrentRouteSharedBinding(
      CanonicalPlayableTarget route,
      CanonicalRealmCatalogSnapshot catalog,
      CanonicalInitialAdmissionLaunchTarget launch,
      CanonicalGameInstanceLaunchAssociation association,
      net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence source,
      net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence descriptor) {
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
        || !source.operationId().equals(descriptor.authoredWorldSourceOperationId())
        || !source.evidenceDigest().equals(descriptor.authoredWorldSourceEvidenceDigest())
        || descriptor.versionId() != route.runtimeVersionId()
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
        && Objects.equals(first.launchBindingEvidence(), second.launchBindingEvidence())
        && Objects.equals(first.fixtureDescriptor(), second.fixtureDescriptor())
        && Objects.equals(first.fixtureProvenanceDigest(), second.fixtureProvenanceDigest())
        && Objects.equals(first.fixtureCanonicalVersionId(), second.fixtureCanonicalVersionId())
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
