package net.firedevops.firemud.gamesession.charactercreate;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadClient;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PlayerCreationDescriptor;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.world.CanonicalPlayerCreatedRosterEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterSourceIdentity;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationRequest;
import net.firedevops.firemud.gamesession.client.GameDesignPublishedRealmPolicyClient;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository.CurrentOpenSource;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredActorCoreRetentionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredActorCoreRetentionRepository.Candidate;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.v1.CharacterCreatePublicCatalog;
import net.firedevops.firemud.gamesession.v1.CharacterCreatePublicPointer;
import net.firedevops.firemud.gamesession.v1.ReadCharacterCreatePublicSourceRequest;
import net.firedevops.firemud.gamesession.v1.ReadCharacterCreatePublicSourceResponse;
import net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceRequest;
import net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceResponse;
import net.firedevops.firemud.gamesession.v1.ResolveCharacterCreatePublicTargetRequest;
import net.firedevops.firemud.gamesession.v1.ResolveCharacterCreatePublicTargetResponse;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unregistered source composition for Account creation intent and Entity PLAYER_CREATED roster
 * input. Reads existing original evidence; never captures a source, renews a launch claim, creates
 * an action or acquires a protection.
 */
public final class GameSessionCharacterCreatePublicSourceService {
  public static final int MAX_REQUEST_BYTES = 16 * 1024;
  public static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
  public static final int MAX_TARGET_RESPONSE_BYTES = 64 * 1024;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final String namespace;
  private final CanonicalPlayerRouteReadService routes;
  private final GameSessionCanonicalRealmCatalogRepository catalogs;
  private final CanonicalInitialAdmissionRepository admissions;
  private final CanonicalGameInstanceLaunchAssociationRepository launches;
  private final GameSessionAuthoredActorCoreRetentionRepository retainedSources;
  private final AuthoredInitialActorCoreReadClient gameDesign;
  private final WorldCanonicalInstanceLifecycleClient world;
  private final GameDesignPublishedRealmPolicyClient publishedPolicies;

  public GameSessionCharacterCreatePublicSourceService(
      String namespace,
      CanonicalPlayerRouteReadService routes,
      GameSessionCanonicalRealmCatalogRepository catalogs,
      CanonicalInitialAdmissionRepository admissions,
      CanonicalGameInstanceLaunchAssociationRepository launches,
      GameSessionAuthoredActorCoreRetentionRepository retainedSources,
      AuthoredInitialActorCoreReadClient gameDesign,
      WorldCanonicalInstanceLifecycleClient world,
      GameDesignPublishedRealmPolicyClient publishedPolicies) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) throw invalid();
    this.namespace = namespace;
    this.routes = Objects.requireNonNull(routes);
    this.catalogs = Objects.requireNonNull(catalogs);
    this.admissions = Objects.requireNonNull(admissions);
    this.launches = Objects.requireNonNull(launches);
    this.retainedSources = Objects.requireNonNull(retainedSources);
    this.gameDesign = Objects.requireNonNull(gameDesign);
    this.world = Objects.requireNonNull(world);
    this.publishedPolicies = Objects.requireNonNull(publishedPolicies);
  }

  /** Resolves friendly selectors before Account has issued any canonical scope. */
  public ResolveCharacterCreatePublicTargetResponse resolve(
      ResolveCharacterCreatePublicTargetRequest request) {
    outsideSql();
    requireResolveRequest(request, namespace);
    var visible = routes.readVisiblePublicProductionTargets();
    if (visible == null || visible.stream().anyMatch(Objects::isNull)) throw missing();
    var selected =
        visible.stream()
            .filter(
                route ->
                    request.getWorldSlug().equals(route.worldSlug())
                        && request.getRealmSlug().equals(route.realmSlug()))
            .toList();
    if (selected.size() != 1) throw missing();
    CanonicalPlayableTarget discovery = selected.getFirst();
    requirePublicCreatedTarget(discovery);
    CanonicalPlayableTarget before = routes.readCurrentTarget(discovery.canonicalTenantId());
    if (!discovery.equals(before)) throw missing();
    requirePublicCreatedTarget(before);
    CanonicalRealmCatalogSnapshot catalog = catalog(before);
    CurrentOpenSource pointer = pointer(before);
    CanonicalPlayableTarget after = routes.readCurrentTarget(before.canonicalTenantId());
    if (!before.equals(after) || !catalog.equals(catalog(after)) || !pointer.equals(pointer(after)))
      throw missing();
    var response =
        ResolveCharacterCreatePublicTargetResponse.newBuilder()
            .setSchemaVersion(1)
            .setRequest(request)
            .setCatalog(catalogMessage(catalog))
            .setPointer(pointerMessage(pointer))
            .setCanonicalGameInstanceId(before.canonicalGameInstanceId().toString())
            .setCanonicalVersionId(before.canonicalVersionId().toString())
            .setActiveLifecycleEpoch(before.activeWorldEpoch())
            .build();
    if (response.getSerializedSize() > MAX_TARGET_RESPONSE_BYTES) throw missing();
    return response;
  }

  private void requirePublicCreatedTarget(CanonicalPlayableTarget route) {
    if (route == null
        || !namespace.equals(route.targetNamespace())
        || !"SHARED".equals(route.playableStateScope())
        || !"PLAYER_CREATED".equals(route.characterCreationPolicy())) throw missing();
  }

  static void requireResolveRequest(
      ResolveCharacterCreatePublicTargetRequest request, String namespace) {
    if (request.getSerializedSize() > MAX_REQUEST_BYTES
        || !request.getUnknownFields().asMap().isEmpty()
        || request.getSchemaVersion() != 1
        || !namespace.equals(request.getTargetNamespace())
        || !RealmEntryPolicy.isCanonicalSlug(request.getWorldSlug())
        || !RealmEntryPolicy.isCanonicalSlug(request.getRealmSlug())) throw invalid();
    GameSessionCharacterCreateBinding.id(request.getReadRequestId());
  }

  public ReadCharacterCreatePublicSourceResponse read(
      ReadCharacterCreatePublicSourceRequest request) {
    outsideSql();
    requireRequest(request, namespace);
    var facts =
        readSource(
            UUID.fromString(request.getReadRequestId()),
            UUID.fromString(request.getCanonicalTenantId()),
            route -> requireTarget(request, route),
            false);
    var response =
        ReadCharacterCreatePublicSourceResponse.newBuilder()
            .setSchemaVersion(1)
            .setRequest(request)
            .setCatalog(catalogMessage(facts.catalog()))
            .setPointer(pointerMessage(facts.pointer()))
            .setCanonicalVersionId(facts.route().canonicalVersionId().toString())
            .setActiveLifecycleEpoch(facts.lifecycle().lifecycleEpoch())
            .setOriginalControlPlaneRequestId(facts.launch().controlPlaneRequestId())
            .setOriginalOwnerAttemptId(facts.retained().ownerAttemptId().toString())
            .setOriginalOwnerFence(facts.retained().ownerFence())
            .setAuthoredSource(AuthoredInitialActorCoreReadGrpcCodec.toResponse(facts.authored()))
            .setWorldLifecycleEvidence(ByteString.copyFrom(facts.lifecycle().canonicalBytes()))
            .build();
    if (response.getSerializedSize() > MAX_RESPONSE_BYTES) throw missing();
    return response;
  }

  /** Exact current published PLAYER_CREATED input for Entity roster policy resolution only. */
  public ReadPlayerCreatedRosterSourceResponse readPlayerCreatedRosterSource(
      ReadPlayerCreatedRosterSourceRequest request) {
    return resolvePlayerCreatedRosterSource(request).response();
  }

  /** Owner-constructed source view, available only after complete current-source revalidation. */
  public ResolvedPlayerCreatedRosterSource resolvePlayerCreatedRosterSource(
      ReadPlayerCreatedRosterSourceRequest request) {
    outsideSql();
    requireRosterRequest(request, namespace);
    var facts =
        readSource(
            UUID.fromString(request.getReadRequestId()),
            UUID.fromString(request.getCanonicalTenantId()),
            route -> requireRosterTarget(request, route),
            true);
    var response =
        ReadPlayerCreatedRosterSourceResponse.newBuilder()
            .setSchemaVersion(1)
            .setRequest(request)
            .setCatalog(catalogMessage(facts.catalog()))
            .setPointer(pointerMessage(facts.pointer()))
            .setCanonicalVersionId(facts.route().canonicalVersionId().toString())
            .setActiveLifecycleEpoch(facts.lifecycle().lifecycleEpoch())
            .setOriginalControlPlaneRequestId(facts.launch().controlPlaneRequestId())
            .setOriginalOwnerAttemptId(facts.retained().ownerAttemptId().toString())
            .setOriginalOwnerFence(facts.retained().ownerFence())
            .setAuthoredSource(AuthoredInitialActorCoreReadGrpcCodec.toResponse(facts.authored()))
            .setWorldLifecycleEvidence(ByteString.copyFrom(facts.lifecycle().canonicalBytes()))
            .setPublishedPolicySetEvidence(ByteString.copyFrom(facts.policySet().canonicalBytes()))
            .build();
    if (response.getSerializedSize() > MAX_RESPONSE_BYTES) throw missing();
    var descriptor =
        PlayerCreationDescriptor.parse(facts.projection().descriptor().canonicalJson());
    var sourceIdentity =
        CanonicalPlayerCreatedRosterEvidence.requireSourceIdentity(
            CanonicalPlayerCreatedRosterSourceIdentity.newBuilder()
                .setSchemaVersion(1)
                .setDescriptorUuid(descriptor.descriptorId())
                .setDescriptorRevisionUuid(descriptor.descriptorRevisionId())
                .setDescriptorDigest(descriptor.digest())
                .setAuthoredBindingUuid(facts.projection().bindingId().toString())
                .setAuthoredBindingDigest(facts.projection().bindingDigest())
                .build());
    var route = facts.route();
    var target =
        CanonicalGameplayRosterTarget.newBuilder()
            .setTenantUuid(route.canonicalTenantId().toString())
            .setRealmUuid(route.realmId().toString())
            .setWorldSlug(route.worldSlug())
            .setRealmSlug(route.realmSlug())
            .setGameInstanceUuid(route.canonicalGameInstanceId().toString())
            .setCanonicalVersionUuid(route.canonicalVersionId().toString())
            .setPlayableStateNamespaceUuid(route.playableStateNamespaceId().toString())
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setEntryPolicy(
                CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED)
            .setCatalogRevision(route.catalogRevision())
            .setPointerVersion(route.pointerVersion())
            .setActiveWorldEpoch(route.activeWorldEpoch())
            .setPublishedPolicyDigest(rawSourceDigest(facts.selectedPolicy().policyDigest()))
            .setPublishedReleaseBundleRef(facts.policySet().publishedReleaseBundleRef())
            .setAdmissionPointerSnapshotDigest(
                facts.pointer().snapshot().admissionPointerSnapshotDigest())
            .setPublishedOwnerProofDigest(
                rawSourceDigest(facts.pointer().snapshot().ownerProof().proofDigest()))
            .build();
    return new ResolvedPlayerCreatedRosterSource(response, route, target, sourceIdentity);
  }

  private static String rawSourceDigest(String value) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) throw missing();
    return value.substring(7);
  }

  /**
   * Immutable source facts; no public factory can turn an arbitrary wire response into this view.
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The route and protobuf source values are immutable")
  public static final class ResolvedPlayerCreatedRosterSource {
    private final ReadPlayerCreatedRosterSourceResponse response;
    private final CanonicalPlayableTarget route;
    private final CanonicalGameplayRosterTarget target;
    private final CanonicalPlayerCreatedRosterSourceIdentity sourceIdentity;

    private ResolvedPlayerCreatedRosterSource(
        ReadPlayerCreatedRosterSourceResponse response,
        CanonicalPlayableTarget route,
        CanonicalGameplayRosterTarget target,
        CanonicalPlayerCreatedRosterSourceIdentity sourceIdentity) {
      this.response = response;
      this.route = route;
      this.target = target;
      this.sourceIdentity = sourceIdentity;
    }

    public ReadPlayerCreatedRosterSourceResponse response() {
      return response;
    }

    public CanonicalPlayableTarget route() {
      return route;
    }

    public CanonicalGameplayRosterTarget target() {
      return target;
    }

    public CanonicalPlayerCreatedRosterSourceIdentity sourceIdentity() {
      return sourceIdentity;
    }
  }

  private SourceFacts readSource(
      UUID readRequestId,
      UUID tenant,
      Consumer<CanonicalPlayableTarget> requireSelectedTarget,
      boolean includePublishedPolicies) {
    CanonicalPlayableTarget before = routes.readCurrentTarget(tenant);
    requireSelectedTarget.accept(before);
    CanonicalRealmCatalogSnapshot catalog = catalog(before);
    CurrentOpenSource pointer = pointer(before);
    CanonicalGameInstanceLaunchAssociation launch = launch(before);
    Candidate retained = retained(launch);
    var sourceRequest = sourceRequest(retained);
    var historicalRequest =
        new HistoricalOriginalStartSessionOwnerEvidence.Request(
            new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
                readRequestId,
                namespace,
                tenant,
                before.worldSlug(),
                before.canonicalGameInstanceId(),
                launch.controlPlaneRequestId(),
                launch.launchDescriptorId(),
                launch.launchBindingEvidence().descriptor().requestDigest(),
                launch.launchBindingEvidence().descriptor().resultDigest(),
                launch.launchBindingEvidence().releaseAttestation().evidenceDigest()),
            retained.ownerAttemptId(),
            retained.ownerFence());
    var historical =
        launches
            .readHistoricalOriginalStartSessionOwnerEvidence(historicalRequest)
            .orElseThrow(GameSessionCharacterCreatePublicSourceService::missing);
    requireOriginal(retained, launch, historical, sourceRequest);

    outsideSql();
    AuthoredInitialActorCoreReadEvidence.ReadResult authored;
    try {
      authored = gameDesign.read(sourceRequest);
    } catch (StatusRuntimeException dependency) {
      throw new SourceDependencyUnavailableException(dependency);
    }
    if (!Arrays.equals(authored.canonicalBytes(), retained.canonicalSourceResultBytes())
        || !authored.digest().equals(retained.sourceResultDigest())
        || !sourceRequest.associationRequest().equals(authored.request())
        || !launch.launchBindingEvidence().descriptor().equals(authored.descriptor()))
      throw missing();
    var projection = requireProjection(before, catalog, retained, authored);
    var descriptor = launch.launchBindingEvidence().descriptor();
    var lifecycleRequest =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            1,
            readRequestId,
            namespace,
            tenant,
            before.worldSlug(),
            before.canonicalGameInstanceId(),
            before.playableStateNamespaceId(),
            "SHARED",
            true,
            launch.controlPlaneRequestId(),
            before.canonicalVersionId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            launch.launchBindingEvidence().releaseAttestation().evidenceDigest());
    outsideSql();
    WorldCanonicalInstanceLifecycleEvidence lifecycle;
    try {
      lifecycle = world.read(lifecycleRequest);
    } catch (StatusRuntimeException dependency) {
      throw new SourceDependencyUnavailableException(dependency);
    }
    if (!lifecycleRequest.equals(lifecycle.request())
        || !"ACTIVE".equals(lifecycle.lifecycleStatus())
        || lifecycle.lifecycleEpoch() != before.activeWorldEpoch()
        || !launch.launchBindingEvidence().equals(lifecycle.launchBinding())) throw missing();

    var policies =
        includePublishedPolicies
            ? readPublishedPolicies(before, authored, lifecycle, projection)
            : null;

    // Re-read every complete local source after all remote calls; never select a newer target.
    CanonicalPlayableTarget after = routes.readCurrentTarget(tenant);
    requireSelectedTarget.accept(after);
    if (!before.equals(after)
        || !catalog.equals(catalog(after))
        || !pointer.equals(pointer(after))
        || !launch.equals(launch(after))) throw missing();
    Candidate confirmed = retained(launch);
    var confirmedHistorical =
        launches
            .readHistoricalOriginalStartSessionOwnerEvidence(historicalRequest)
            .orElseThrow(GameSessionCharacterCreatePublicSourceService::missing);
    requireOriginal(confirmed, launch, confirmedHistorical, sourceRequest);
    if (!Arrays.equals(confirmed.canonicalSourceResultBytes(), authored.canonicalBytes())
        || !confirmed.sourceResultDigest().equals(authored.digest())) throw missing();
    return new SourceFacts(
        before,
        catalog,
        pointer,
        launch,
        retained,
        authored,
        lifecycle,
        policies == null ? null : policies.set(),
        projection,
        policies == null ? null : policies.selected());
  }

  private record SourceFacts(
      CanonicalPlayableTarget route,
      CanonicalRealmCatalogSnapshot catalog,
      CurrentOpenSource pointer,
      CanonicalGameInstanceLaunchAssociation launch,
      Candidate retained,
      AuthoredInitialActorCoreReadEvidence.ReadResult authored,
      WorldCanonicalInstanceLifecycleEvidence lifecycle,
      PublishedRealmEntryPolicySetEvidence policySet,
      AuthoredInitialActorCoreProjection projection,
      PublishedRealmEntryPolicyEvidence selectedPolicy) {}

  private record PublishedPolicies(
      PublishedRealmEntryPolicySetEvidence set, PublishedRealmEntryPolicyEvidence selected) {}

  private PublishedPolicies readPublishedPolicies(
      CanonicalPlayableTarget route,
      AuthoredInitialActorCoreReadEvidence.ReadResult authored,
      WorldCanonicalInstanceLifecycleEvidence lifecycle,
      AuthoredInitialActorCoreProjection projection) {
    outsideSql();
    PublishedRealmEntryPolicySetEvidence set;
    try {
      set =
          publishedPolicies.listPublishedRealmEntryPolicies(
              route.canonicalTenantId(), route.canonicalVersionId());
    } catch (StatusRuntimeException
        | GameDesignPublishedRealmPolicyClient.UninitializedClientException dependency) {
      throw new SourceDependencyUnavailableException(dependency);
    }
    if (set == null) throw missing();
    set.requireValidDigest();
    var operation = GameDesignPublicationOperationBinding.fromStored(set.operationBytes());
    var terminal = GameDesignPublicationTerminalEvidence.fromStored(set.terminalEvidenceBytes());
    var association = authored.associationRead().association();
    var bundle = authored.associationRead().releaseBundle();
    var associationWorld = authored.associationRead().worldPublishedStartLocationEvidence();
    var worldRelease = lifecycle.launchBinding().releaseAttestation();
    var sourceRelease =
        net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence
            .releaseContent(bundle, associationWorld);
    if (!namespace.equals(operation.world().request().targetNamespace())
        || !route.canonicalTenantId().equals(set.target().canonicalTenantId())
        || !route.canonicalVersionId().equals(set.target().canonicalVersionId())
        || !set.sourceCommitId().equals(association.selectedCommitId())
        || !set.publishWorkflowId().equals(association.publishWorkflowId())
        || !set.publishWorkflowId().equals(worldRelease.publishWorkflowId())
        || !set.sourceCommitId().toString().equals(worldRelease.commitId())
        || !set.publishedReleaseBundleRef().equals(bundle.getPublishedReleaseBundleRef())
        || !set.publishedReleaseBundleRef().equals(worldRelease.publishedReleaseBundleRef())
        || !set.publishedReleaseBundleDigest().equals(sourceRelease.contentDigest())
        || !set.publishedReleaseBundleDigest().equals(terminal.publishedReleaseBundleDigest())
        || !Arrays.equals(
            sourceRelease.canonicalBytes(), terminal.releaseContent().canonicalBytes())
        || !set.manifestHash().equals(bundle.getManifestHash())
        || !set.manifestHash().equals(worldRelease.manifestHash())
        || sourceRelease.manifestSchemaVersion() != worldRelease.manifestSchemaVersion()
        || !sourceRelease.artifactDigests().equals(worldRelease.artifactDigests())
        || !sourceRelease
            .requiredManifestAssetKeys()
            .equals(worldRelease.requiredManifestAssetKeys())
        || !sourceRelease.commandDefinitions().equals(worldRelease.commandDefinitions())
        || !sourceRelease.generationConfigRevision().equals(worldRelease.generationConfigRevision())
        || !sourceRelease.participantDigests().stream()
            .map(
                value ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        value.participantKey(),
                        value.scopeValue(),
                        value.baseVersionId() != null,
                        value.baseVersionId(),
                        value.appliedCommitId(),
                        value.contentDigest(),
                        value.digestSchemaVersion(),
                        value.abilitySchemaDigest() != null,
                        value.abilitySchemaDigest()))
            .toList()
            .equals(worldRelease.participantDigests())
        || !Arrays.equals(
            associationWorld.canonicalBytes(),
            worldRelease.worldStartLocationEvidence().canonicalBytes())
        || !Arrays.equals(
            worldRelease.worldStartLocationEvidence().canonicalBytes(),
            operation.world().canonicalBytes())
        || set.versionNumber() != bundle.getVersionNumber()) throw missing();
    var matches =
        set.policies().stream()
            .filter(
                evidence ->
                    evidence.policy().worldSlug().equals(route.worldSlug())
                        && evidence.policy().realmSlug().equals(route.realmSlug()))
            .toList();
    if (matches.size() != 1) throw missing();
    var evidence = matches.getFirst().requireValidDigest(set);
    var policy = evidence.policy();
    if (policy.schemaVersion() != RealmEntryPolicy.AUTHORED_INITIAL_ACTOR_CORE_SCHEMA_VERSION
        || policy.entryPolicy() != RealmEntryPolicy.EntryPolicy.PLAYER_CREATED
        || !policy.visible()
        || !policy.publicProduction()
        || policy.stateScope() != RealmEntryPolicy.StateScope.SHARED
        || !policy.canonicalJson().equals(projection.policy().canonicalJson())
        || !evidence.sourceCommitId().equals(projection.policy().commitId())
        || !evidence.sourceRevisionId().equals(projection.policy().revisionId())
        || !evidence.logicalRevisionId().equals(projection.policy().logicalRevisionId())
        || !operation
            .account()
            .input()
            .selection()
            .selectedCommit()
            .digest()
            .equals(projection.selectedCommitDigest())) throw missing();
    return new PublishedPolicies(set, evidence);
  }

  static void requireRosterRequest(ReadPlayerCreatedRosterSourceRequest request, String namespace) {
    if (request.getSerializedSize() > MAX_REQUEST_BYTES
        || !request.getUnknownFields().asMap().isEmpty()
        || request.getSchemaVersion() != 1
        || !namespace.equals(request.getTargetNamespace())
        || !RealmEntryPolicy.isCanonicalSlug(request.getWorldSlug())
        || !RealmEntryPolicy.isCanonicalSlug(request.getRealmSlug())
        || !"SHARED".equals(request.getPlayableStateScope())
        || request.getCatalogRevision() <= 0
        || request.getPointerVersion() <= 0
        || request.getActiveWorldEpoch() <= 0) throw invalid();
    for (String id :
        new String[] {
          request.getReadRequestId(),
          request.getCanonicalTenantId(),
          request.getRealmId(),
          request.getPlayableStateNamespaceId(),
          request.getCanonicalGameInstanceId(),
          request.getExpectedCanonicalVersionId()
        }) GameSessionCharacterCreateBinding.id(id);
  }

  private static void requireRosterTarget(
      ReadPlayerCreatedRosterSourceRequest request, CanonicalPlayableTarget route) {
    if (route == null
        || !request.getTargetNamespace().equals(route.targetNamespace())
        || !request.getCanonicalTenantId().equals(route.canonicalTenantId().toString())
        || !request.getRealmId().equals(route.realmId().toString())
        || !request.getWorldSlug().equals(route.worldSlug())
        || !request.getRealmSlug().equals(route.realmSlug())
        || !request
            .getPlayableStateNamespaceId()
            .equals(route.playableStateNamespaceId().toString())
        || !request.getPlayableStateScope().equals(route.playableStateScope())
        || !request.getCanonicalGameInstanceId().equals(route.canonicalGameInstanceId().toString())
        || request.getCatalogRevision() != route.catalogRevision()
        || request.getPointerVersion() != route.pointerVersion()
        || !request.getExpectedCanonicalVersionId().equals(route.canonicalVersionId().toString())
        || request.getActiveWorldEpoch() != route.activeWorldEpoch()
        || !"PLAYER_CREATED".equals(route.characterCreationPolicy())) throw missing();
  }

  private CanonicalRealmCatalogSnapshot catalog(CanonicalPlayableTarget route) {
    var catalog =
        catalogs
            .readUniqueVisiblePublicProduction(namespace, route.canonicalTenantId())
            .orElseThrow(GameSessionCharacterCreatePublicSourceService::missing);
    if (!catalog.targetNamespace().equals(namespace)
        || !catalog.tenantId().equals(route.canonicalTenantId())
        || !catalog.tenantSlug().equals(route.tenantSlug())
        || !catalog.realmId().equals(route.realmId())
        || !catalog.worldSlug().equals(route.worldSlug())
        || !catalog.realmSlug().equals(route.realmSlug())
        || !catalog.realmDisplayName().equals(route.realmDisplayName())
        || !catalog
            .sourceIntakeReceipt()
            .source()
            .worldDisplayName()
            .equals(route.worldDisplayName())
        || !catalog.playableStateNamespaceId().equals(route.playableStateNamespaceId())
        || !"SHARED".equals(catalog.stateScope())
        || !catalog.visible()
        || !catalog.publicProduction()
        || !"PLAYER_CREATED".equals(catalog.characterCreationPolicy())
        || catalog.catalogRevision() != route.catalogRevision()) throw missing();
    return catalog;
  }

  private CurrentOpenSource pointer(CanonicalPlayableTarget route) {
    var pointer =
        admissions.readCurrentOpenSourceForRealm(
            namespace, route.canonicalTenantId(), route.realmId());
    if (!pointer.tenantId().equals(route.canonicalTenantId())
        || !pointer.realmId().equals(route.realmId())
        || !pointer.worldSlug().equals(route.worldSlug())
        || !pointer.realmSlug().equals(route.realmSlug())
        || !"OPEN".equals(pointer.admissionState())
        || !pointer.canonicalGameInstanceId().equals(route.canonicalGameInstanceId())
        || pointer.catalogRevision() != route.catalogRevision()
        || pointer.pointerVersion() != route.pointerVersion()
        || !pointer
            .snapshot()
            .admissionPointerSnapshotDigest()
            .equals(route.admissionPointerSnapshotDigest())
        || !pointer.snapshot().ownerProof().proofDigest().equals(route.ownerProofDigest())
        || pointer.snapshot().ownerProof().activeLifecycleEpoch() != route.activeWorldEpoch()
        || pointer.updatedAt() == null) throw missing();
    return pointer;
  }

  private CanonicalGameInstanceLaunchAssociation launch(CanonicalPlayableTarget route) {
    var launch =
        launches
            .readForInitialAdmission(
                namespace,
                route.canonicalTenantId(),
                route.realmId(),
                route.canonicalGameInstanceId())
            .orElseThrow(GameSessionCharacterCreatePublicSourceService::missing)
            .association();
    if (!namespace.equals(launch.targetNamespace())
        || !launch.canonicalTenantId().equals(route.canonicalTenantId())
        || !launch.worldSlug().equals(route.worldSlug())
        || !launch.gameInstanceUuid().equals(route.canonicalGameInstanceId())
        || !launch.playableStateNamespaceId().equals(route.playableStateNamespaceId())
        || !launch.publicProduction()
        || launch.playableStateScope() != RealmEntryPolicy.StateScope.SHARED
        || launch.currentGameInstanceStatus()
            != CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.RUNNING
        || !launch
            .launchBindingEvidence()
            .releaseAttestation()
            .canonicalVersionId()
            .equals(route.canonicalVersionId())) throw missing();
    return launch;
  }

  private Candidate retained(CanonicalGameInstanceLaunchAssociation launch) {
    return retainedSources
        .readByOriginalOperation(namespace, launch.controlPlaneRequestId())
        .orElseThrow(GameSessionCharacterCreatePublicSourceService::missing)
        .candidate();
  }

  private static AuthoredInitialActorCoreReadEvidence.ReadRequest sourceRequest(
      Candidate retained) {
    try {
      var encoded =
          ReadStartSessionTemplateAssociationRequest.parseFrom(retained.sourceReadRequestBytes());
      var decoded = StartSessionTemplateAssociationReadGrpcCodec.fromRequest(encoded);
      if (!Arrays.equals(
          StartSessionTemplateAssociationReadGrpcCodec.toRequest(decoded).toByteArray(),
          retained.sourceReadRequestBytes())) throw missing();
      return new AuthoredInitialActorCoreReadEvidence.ReadRequest(decoded);
    } catch (InvalidProtocolBufferException invalid) {
      throw missing();
    }
  }

  private static void requireOriginal(
      Candidate retained,
      CanonicalGameInstanceLaunchAssociation launch,
      HistoricalOriginalStartSessionOwnerEvidence.Result historical,
      AuthoredInitialActorCoreReadEvidence.ReadRequest request) {
    var original = historical.launchAssociation();
    var selection = historical.descriptorPin().associationRead();
    if (!retained.targetNamespace().equals(launch.targetNamespace())
        || !retained.canonicalTenantId().equals(launch.canonicalTenantId())
        || !retained.gameInstanceUuid().equals(launch.gameInstanceUuid())
        || !retained.controlPlaneRequestId().equals(launch.controlPlaneRequestId())
        || !retained.worldSlug().equals(launch.worldSlug())
        || !retained.ownerAttemptId().equals(historical.ownerAttemptId())
        || retained.ownerFence() != historical.ownerFence()
        || !Arrays.equals(
            retained.originalPostAuthorizationTuple(), historical.originalTuple().canonicalBytes())
        || !Arrays.equals(
            retained.accountRedemptionProjection(), historical.accountRedemptionProjection())
        || !request.associationRequest().equals(selection.request())
        || !original.targetNamespace().equals(launch.targetNamespace())
        || !original.canonicalTenantId().equals(launch.canonicalTenantId())
        || !original.gameInstanceUuid().equals(launch.gameInstanceUuid())
        || !original.worldSlug().equals(launch.worldSlug())
        || !original.controlPlaneRequestId().equals(launch.controlPlaneRequestId())
        || !original.launchDescriptorId().equals(launch.launchDescriptorId())
        || !original.playableStateNamespaceId().equals(launch.playableStateNamespaceId())
        || !original.publicProduction()
        || original.playableStateScope() != RealmEntryPolicy.StateScope.SHARED
        || !original.launchBindingEvidence().equals(launch.launchBindingEvidence())
        || !retained.canonicalVersionId().equals(selection.association().canonicalVersionId())
        || !retained.selectedCommitId().equals(selection.association().selectedCommitId())
        || !retained.publishWorkflowId().equals(selection.association().publishWorkflowId())
        || !retained.associationDigest().equals(selection.association().associationDigest())
        || !retained.worldSourceOperationId().equals(selection.association().sourceOperationId())
        || !retained
            .worldSourceEvidenceDigest()
            .equals(selection.association().sourceEvidenceDigest())
        || !retained.launchDescriptorId().equals(launch.launchDescriptorId())
        || !retained
            .descriptorRequestDigest()
            .equals(launch.launchBindingEvidence().descriptor().requestDigest())
        || !retained
            .descriptorResultDigest()
            .equals(launch.launchBindingEvidence().descriptor().resultDigest())
        || !retained
            .releaseAttestationEvidenceDigest()
            .equals(launch.launchBindingEvidence().releaseAttestation().evidenceDigest()))
      throw missing();
  }

  private static AuthoredInitialActorCoreProjection requireProjection(
      CanonicalPlayableTarget route,
      CanonicalRealmCatalogSnapshot catalog,
      Candidate retained,
      AuthoredInitialActorCoreReadEvidence.ReadResult source) {
    var selected =
        source.projections().stream()
            .filter(
                projection ->
                    RealmEntryPolicy.parseCanonical(projection.policy().canonicalJson(), JSON)
                        .realmSlug()
                        .equals(route.realmSlug()))
            .toList();
    if (selected.size() != 1) throw missing();
    var projection = selected.getFirst();
    var policy = RealmEntryPolicy.parseCanonical(projection.policy().canonicalJson(), JSON);
    if (policy.entryPolicy() != RealmEntryPolicy.EntryPolicy.PLAYER_CREATED
        || !policy.visible()
        || !policy.publicProduction()
        || policy.stateScope() != RealmEntryPolicy.StateScope.SHARED
        || !policy.worldSlug().equals(catalog.worldSlug())
        || !policy.realmSlug().equals(catalog.realmSlug())
        || !policy.worldDisplayName().equals(route.worldDisplayName())
        || !policy.realmDisplayName().equals(catalog.realmDisplayName())
        || !projection.canonicalTenantId().equals(route.canonicalTenantId())
        || !projection.canonicalVersionId().equals(route.canonicalVersionId())
        || !projection
            .worldSourceOperationId()
            .equals(catalog.sourceIntakeReceipt().source().operationId())
        || !projection
            .worldSourceEvidenceDigest()
            .equals(catalog.sourceIntakeReceipt().source().evidenceDigest())
        || !projection.selectedCommitId().equals(retained.selectedCommitId())
        || !projection.publicationWorkflowId().equals(retained.publishWorkflowId()))
      throw missing();
    return projection;
  }

  static void requireRequest(ReadCharacterCreatePublicSourceRequest request, String namespace) {
    if (request.getSerializedSize() > MAX_REQUEST_BYTES
        || !request.getUnknownFields().asMap().isEmpty()
        || request.getSchemaVersion() != 1
        || !namespace.equals(request.getTargetNamespace())
        || !"SHARED".equals(request.getPlayableStateScope())
        || !RealmEntryPolicy.isCanonicalSlug(request.getWorldSlug())
        || !RealmEntryPolicy.isCanonicalSlug(request.getRealmSlug())
        || request.getCatalogRevision() <= 0
        || request.getPointerVersion() <= 0) throw invalid();
    for (String id :
        new String[] {
          request.getReadRequestId(),
          request.getCanonicalTenantId(),
          request.getRealmId(),
          request.getPlayableStateNamespaceId(),
          request.getCanonicalGameInstanceId()
        }) {
      GameSessionCharacterCreateBinding.id(id);
    }
    try {
      Instant evaluated = Instant.parse(request.getEvaluatedAt());
      Instant expires = Instant.parse(request.getConnectScopeExpiresAt());
      if (!evaluated.toString().equals(request.getEvaluatedAt())
          || !expires.toString().equals(request.getConnectScopeExpiresAt())
          || !expires.isAfter(evaluated)) throw invalid();
    } catch (java.time.format.DateTimeParseException malformed) {
      throw invalid();
    }
  }

  private static void requireTarget(
      ReadCharacterCreatePublicSourceRequest request, CanonicalPlayableTarget route) {
    if (!request.getTargetNamespace().equals(route.targetNamespace())
        || !request.getCanonicalTenantId().equals(route.canonicalTenantId().toString())
        || !request.getRealmId().equals(route.realmId().toString())
        || !request.getWorldSlug().equals(route.worldSlug())
        || !request.getRealmSlug().equals(route.realmSlug())
        || !request
            .getPlayableStateNamespaceId()
            .equals(route.playableStateNamespaceId().toString())
        || !request.getPlayableStateScope().equals(route.playableStateScope())
        || !request.getCanonicalGameInstanceId().equals(route.canonicalGameInstanceId().toString())
        || request.getCatalogRevision() != route.catalogRevision()
        || request.getPointerVersion() != route.pointerVersion()
        || !"PLAYER_CREATED".equals(route.characterCreationPolicy())) throw missing();
  }

  private static CharacterCreatePublicCatalog catalogMessage(
      CanonicalRealmCatalogSnapshot catalog) {
    return CharacterCreatePublicCatalog.newBuilder()
        .setTenantId(catalog.tenantId().toString())
        .setTenantSlug(catalog.tenantSlug())
        .setWorldSlug(catalog.worldSlug())
        .setWorldDisplayName(catalog.sourceIntakeReceipt().source().worldDisplayName())
        .setRealmId(catalog.realmId().toString())
        .setRealmSlug(catalog.realmSlug())
        .setRealmDisplayName(catalog.realmDisplayName())
        .setVisible(catalog.visible())
        .setPublicProduction(catalog.publicProduction())
        .setStateScope(catalog.stateScope())
        .setPlayableStateNamespaceId(catalog.playableStateNamespaceId().toString())
        .setCharacterCreationPolicy(catalog.characterCreationPolicy())
        .setCatalogRevision(catalog.catalogRevision())
        .setCreationRequestId(catalog.creationRequestId().toString())
        .setReceiptDigest(catalog.receiptDigest())
        .build();
  }

  private static CharacterCreatePublicPointer pointerMessage(CurrentOpenSource pointer) {
    return CharacterCreatePublicPointer.newBuilder()
        .setTenantId(pointer.tenantId().toString())
        .setRealmId(pointer.realmId().toString())
        .setWorldSlug(pointer.worldSlug())
        .setRealmSlug(pointer.realmSlug())
        .setAdmissionState(pointer.admissionState())
        .setAdmissibleGameInstanceId(pointer.canonicalGameInstanceId().toString())
        .setPointerVersion(pointer.pointerVersion())
        .setCatalogRevision(pointer.catalogRevision())
        .setUpdatedAt(pointer.updatedAt().toString())
        .setInitialAdmissionRequestId(pointer.snapshot().ownerProof().initialAdmissionRequestId())
        .setInitialAdmissionOwnerProofDigest(pointer.snapshot().ownerProof().proofDigest())
        .setAdmissionPointerSnapshotDigest(pointer.snapshot().admissionPointerSnapshotDigest())
        .build();
  }

  static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Creation source reads must not join an ambient transaction");
  }

  /** An outbound source owner could not provide evidence; this is not a caller rejection. */
  public static final class SourceDependencyUnavailableException extends RuntimeException {
    private SourceDependencyUnavailableException(RuntimeException cause) {
      super("Creation source dependency unavailable", cause);
    }
  }

  static RuntimeException invalid() {
    return Status.INVALID_ARGUMENT
        .withDescription("Exact bounded public CHARACTER_CREATE selectors required")
        .asRuntimeException();
  }

  private static RuntimeException missing() {
    return Status.FAILED_PRECONDITION
        .withDescription("Exact original public CHARACTER_CREATE source is unavailable or changed")
        .asRuntimeException();
  }
}
