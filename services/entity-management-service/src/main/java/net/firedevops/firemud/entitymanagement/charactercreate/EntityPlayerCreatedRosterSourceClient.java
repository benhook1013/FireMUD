package net.firedevops.firemud.entitymanagement.charactercreate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PlayerCreationDescriptor;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.validation.CanonicalEntitySourceValues;
import net.firedevops.firemud.gamesession.v1.CharacterCreatePublicSourceServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceRequest;
import net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceResponse;

/**
 * Unregistered Entity read of the complete published PLAYER_CREATED source. The authenticated
 * result binds an actual read from the configured Game Session owner only; it grants no Account,
 * roster assignment, protection, activation or admission authority.
 */
public final class EntityPlayerCreatedRosterSourceClient
    extends AbstractReloadingBlockingGrpcClient<
        CharacterCreatePublicSourceServiceGrpc.CharacterCreatePublicSourceServiceBlockingStub> {
  public static final int MAX_SOURCE_WIRE_BYTES = 32 * 1024 * 1024;
  private final String namespace;

  public EntityPlayerCreatedRosterSourceClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Entity PLAYER_CREATED roster source"),
        channels,
        EntityPlayerCreatedRosterSourceClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) throw invalid();
    this.namespace = namespace;
  }

  public void init() throws IOException {
    initOnce("Creation public source client is closed", "Creation public source is unavailable");
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
  protected CharacterCreatePublicSourceServiceGrpc.CharacterCreatePublicSourceServiceBlockingStub
      buildStub(io.grpc.ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/game-session-service";
    return CharacterCreatePublicSourceServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer))
        .withMaxInboundMessageSize(MAX_SOURCE_WIRE_BYTES);
  }

  public AuthenticatedSource read(ReadPlayerCreatedRosterSourceRequest request) {
    requireNoAmbientSql("Public creation source read requires no ambient Entity transaction");
    requireRequest(request);
    var response =
        requireInitializedStub("Creation public source is unavailable")
            .withDeadlineAfter(5L, TimeUnit.SECONDS)
            .readPlayerCreatedRosterSource(request);
    try {
      return validateOwnerResponse(request, response);
    } catch (RuntimeException invalidOwnerEvidence) {
      throw new InvalidOwnerEvidenceException(invalidOwnerEvidence);
    }
  }

  private AuthenticatedSource validateOwnerResponse(
      ReadPlayerCreatedRosterSourceRequest request,
      ReadPlayerCreatedRosterSourceResponse response) {
    if (response == null
        || !response.getUnknownFields().asMap().isEmpty()
        || response.getSchemaVersion() != 1
        || response.getSerializedSize() > MAX_SOURCE_WIRE_BYTES
        || !request.equals(response.getRequest())
        || !response.hasCatalog()
        || !response.hasPointer()
        || !response.hasAuthoredSource()
        || response.getWorldLifecycleEvidence().isEmpty()
        || response.getPublishedPolicySetEvidence().isEmpty()) throw invalid();
    var catalog = response.getCatalog();
    var pointer = response.getPointer();
    if (!catalog.getUnknownFields().asMap().isEmpty()
        || !pointer.getUnknownFields().asMap().isEmpty()
        || !catalog.getVisible()
        || !catalog.getPublicProduction()
        || !"PLAYER_CREATED".equals(catalog.getCharacterCreationPolicy())
        || !"SHARED".equals(catalog.getStateScope())
        || !request.getCanonicalTenantId().equals(catalog.getTenantId())
        || !request.getRealmId().equals(catalog.getRealmId())
        || !request.getWorldSlug().equals(catalog.getWorldSlug())
        || !request.getRealmSlug().equals(catalog.getRealmSlug())
        || !request.getPlayableStateNamespaceId().equals(catalog.getPlayableStateNamespaceId())
        || request.getCatalogRevision() != catalog.getCatalogRevision()
        || !request.getCanonicalTenantId().equals(pointer.getTenantId())
        || !request.getRealmId().equals(pointer.getRealmId())
        || !request.getWorldSlug().equals(pointer.getWorldSlug())
        || !request.getRealmSlug().equals(pointer.getRealmSlug())
        || !request.getCanonicalGameInstanceId().equals(pointer.getAdmissibleGameInstanceId())
        || !"OPEN".equals(pointer.getAdmissionState())
        || request.getCatalogRevision() != pointer.getCatalogRevision()
        || request.getPointerVersion() != pointer.getPointerVersion()
        || !request.getExpectedCanonicalVersionId().equals(response.getCanonicalVersionId())
        || response.getActiveLifecycleEpoch() != request.getActiveWorldEpoch()
        || response.getOriginalOwnerFence() <= 0) throw invalid();
    EntityCharacterCreateBindings.id(response.getCanonicalVersionId());
    opaque(response.getOriginalControlPlaneRequestId());
    EntityCharacterCreateBindings.id(response.getOriginalOwnerAttemptId());
    EntityCharacterCreateBindings.id(catalog.getCreationRequestId());
    if (!RealmEntryPolicy.isCanonicalSlug(catalog.getTenantSlug())
        || !catalog.getReceiptDigest().matches("sha256:[0-9a-f]{64}")
        || !pointer.getInitialAdmissionOwnerProofDigest().matches("sha256:[0-9a-f]{64}")
        || !pointer.getAdmissionPointerSnapshotDigest().matches("[0-9a-f]{64}")) throw invalid();
    opaque(pointer.getInitialAdmissionRequestId());
    EntityCharacterCreateBindings.instant(pointer.getUpdatedAt());
    var authored =
        AuthoredInitialActorCoreReadGrpcCodec.fromResponse(
            StartSessionTemplateAssociationReadGrpcCodec.fromRequest(
                response.getAuthoredSource().getRequest()),
            response.getAuthoredSource());
    var world =
        WorldCanonicalInstanceLifecycleEvidence.fromStored(
            response.getWorldLifecycleEvidence().toByteArray());
    var association = authored.associationRead().association();
    var lifecycleRequest = world.request();
    if (!namespace.equals(authored.request().targetNamespace())
        || !response
            .getOriginalOwnerAttemptId()
            .equals(authored.request().ownerAttemptId().toString())
        || response.getOriginalOwnerFence() != authored.request().ownerFence()
        || !response
            .getOriginalControlPlaneRequestId()
            .equals(authored.request().decodedTuple().controlPlaneRequestId())
        || !authored.descriptor().equals(world.launchBinding().descriptor())
        || !association
            .publishWorkflowId()
            .equals(world.launchBinding().releaseAttestation().publishWorkflowId())
        || !association
            .selectedCommitId()
            .toString()
            .equals(world.launchBinding().releaseAttestation().commitId())
        || !request.getCanonicalTenantId().equals(association.canonicalTenantId().toString())
        || !request.getWorldSlug().equals(association.worldSlug())
        || !response.getCanonicalVersionId().equals(association.canonicalVersionId().toString())
        || !namespace.equals(lifecycleRequest.targetNamespace())
        || !request.getCanonicalTenantId().equals(lifecycleRequest.canonicalTenantId().toString())
        || !request.getWorldSlug().equals(lifecycleRequest.worldSlug())
        || !request
            .getCanonicalGameInstanceId()
            .equals(lifecycleRequest.canonicalGameInstanceId().toString())
        || !request
            .getPlayableStateNamespaceId()
            .equals(lifecycleRequest.playableStateNamespaceId().toString())
        || !response
            .getCanonicalVersionId()
            .equals(lifecycleRequest.canonicalVersionId().toString())
        || !response
            .getOriginalControlPlaneRequestId()
            .equals(lifecycleRequest.controlPlaneRequestId())
        || !"SHARED".equals(lifecycleRequest.playableStateScope())
        || !lifecycleRequest.publicProduction()
        || !"ACTIVE".equals(world.lifecycleStatus())
        || response.getActiveLifecycleEpoch() != world.lifecycleEpoch()) throw invalid();
    var json = tools.jackson.databind.json.JsonMapper.builder().build();
    var selected =
        authored.projections().stream()
            .filter(
                projection ->
                    RealmEntryPolicy.parseCanonical(projection.policy().canonicalJson(), json)
                        .realmSlug()
                        .equals(request.getRealmSlug()))
            .toList();
    if (selected.size() != 1) throw invalid();
    var policy =
        RealmEntryPolicy.parseCanonical(selected.getFirst().policy().canonicalJson(), json);
    if (policy.schemaVersion() != RealmEntryPolicy.AUTHORED_INITIAL_ACTOR_CORE_SCHEMA_VERSION
        || policy.creationDescriptor() == null
        || policy.initialActorCore() == null
        || !policy
            .creationDescriptor()
            .canonicalJson()
            .equals(selected.getFirst().descriptor().canonicalJson())
        || !policy
            .initialActorCore()
            .canonicalJson()
            .equals(selected.getFirst().core().canonicalJson())
        || policy.entryPolicy() != RealmEntryPolicy.EntryPolicy.PLAYER_CREATED
        || !policy.visible()
        || !policy.publicProduction()
        || policy.stateScope() != RealmEntryPolicy.StateScope.SHARED
        || !policy.worldSlug().equals(catalog.getWorldSlug())
        || !policy.worldDisplayName().equals(catalog.getWorldDisplayName())
        || !policy.realmDisplayName().equals(catalog.getRealmDisplayName())) throw invalid();
    var projection = selected.getFirst();
    var set =
        PublishedRealmEntryPolicySetEvidence.fromStored(
                response.getPublishedPolicySetEvidence().toByteArray())
            .requireValidDigest();
    var operation = GameDesignPublicationOperationBinding.fromStored(set.operationBytes());
    var release = world.launchBinding().releaseAttestation();
    var bundle = authored.associationRead().releaseBundle();
    var expectedRelease =
        StartSessionTemplateAssociationReadEvidence.releaseContent(
            bundle, authored.associationRead().worldPublishedStartLocationEvidence());
    var terminal = GameDesignPublicationTerminalEvidence.fromStored(set.terminalEvidenceBytes());
    if (!namespace.equals(operation.world().request().targetNamespace())
        || !association.canonicalTenantId().equals(set.target().canonicalTenantId())
        || !association.canonicalVersionId().equals(set.target().canonicalVersionId())
        || !association.selectedCommitId().equals(set.sourceCommitId())
        || !projection
            .selectedCommitDigest()
            .equals(operation.account().input().selection().selectedCommit().digest())
        || !association.publishWorkflowId().equals(set.publishWorkflowId())
        || !release.publishWorkflowId().equals(set.publishWorkflowId())
        || !release.commitId().equals(set.sourceCommitId().toString())
        || !release.publishedReleaseBundleRef().equals(set.publishedReleaseBundleRef())
        || !bundle.getPublishedReleaseBundleRef().equals(set.publishedReleaseBundleRef())
        || !release.manifestHash().equals(set.manifestHash())
        || expectedRelease.manifestSchemaVersion() != release.manifestSchemaVersion()
        || !expectedRelease.artifactDigests().equals(release.artifactDigests())
        || !expectedRelease.requiredManifestAssetKeys().equals(release.requiredManifestAssetKeys())
        || !expectedRelease.commandDefinitions().equals(release.commandDefinitions())
        || !expectedRelease.generationConfigRevision().equals(release.generationConfigRevision())
        || !expectedRelease.participantDigests().stream()
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
            .equals(release.participantDigests())
        || !expectedRelease.contentDigest().equals(set.publishedReleaseBundleDigest())
        || !Arrays.equals(
            expectedRelease.canonicalBytes(), terminal.releaseContent().canonicalBytes())
        || !Arrays.equals(
            release.worldStartLocationEvidence().canonicalBytes(),
            operation.world().canonicalBytes())) throw invalid();
    var policies =
        set.policies().stream()
            .filter(
                candidate ->
                    candidate.policy().worldSlug().equals(request.getWorldSlug())
                        && candidate.policy().realmSlug().equals(request.getRealmSlug()))
            .toList();
    if (policies.size() != 1) throw invalid();
    var sealed = policies.getFirst().requireValidDigest(set);
    if (!sealed.policy().canonicalJson().equals(policy.canonicalJson())
        || !sealed.sourceCommitId().equals(projection.policy().commitId())
        || !sealed.sourceRevisionId().equals(projection.policy().revisionId())
        || !sealed.logicalRevisionId().equals(projection.policy().logicalRevisionId()))
      throw invalid();
    var target =
        new CanonicalGameplayRosterTarget(
            UUID.fromString(catalog.getTenantId()),
            UUID.fromString(catalog.getRealmId()),
            catalog.getWorldSlug(),
            catalog.getRealmSlug(),
            UUID.fromString(pointer.getAdmissibleGameInstanceId()),
            catalog.getCatalogRevision(),
            pointer.getPointerVersion(),
            world.lifecycleEpoch(),
            UUID.fromString(response.getCanonicalVersionId()),
            CanonicalEntitySourceValues.normalizeSha256ToRawHex(sealed.policyDigest()),
            set.publishedReleaseBundleRef(),
            pointer.getAdmissionPointerSnapshotDigest(),
            CanonicalEntitySourceValues.normalizeSha256ToRawHex(
                pointer.getInitialAdmissionOwnerProofDigest()),
            UUID.fromString(catalog.getPlayableStateNamespaceId()),
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            CanonicalGameplayRosterEntryPolicy.PLAYER_CREATED);
    return new AuthenticatedSource(
        response,
        authored,
        world,
        PlayerCreationDescriptor.parse(projection.descriptor().canonicalJson()),
        set,
        target);
  }

  private void requireRequest(ReadPlayerCreatedRosterSourceRequest request) {
    if (request == null
        || !request.getUnknownFields().asMap().isEmpty()
        || request.getSchemaVersion() != 1
        || request.getSerializedSize() > 16 * 1024
        || !namespace.equals(request.getTargetNamespace())
        || !"SHARED".equals(request.getPlayableStateScope())
        || !RealmEntryPolicy.isCanonicalSlug(request.getWorldSlug())
        || !RealmEntryPolicy.isCanonicalSlug(request.getRealmSlug())
        || request.getCatalogRevision() <= 0
        || request.getPointerVersion() <= 0
        || request.getActiveWorldEpoch() <= 0) throw invalid();
    for (String id :
        java.util.List.of(
            request.getReadRequestId(),
            request.getCanonicalTenantId(),
            request.getRealmId(),
            request.getPlayableStateNamespaceId(),
            request.getCanonicalGameInstanceId(),
            request.getExpectedCanonicalVersionId())) EntityCharacterCreateBindings.id(id);
  }

  private static void opaque(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 128
        || value.indexOf(0) >= 0
        || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw invalid();
  }

  private static RuntimeException invalid() {
    return EntityCharacterCreateBindings.invalid();
  }

  /** Authenticated owner response failed its closed value checks; caller input is separate. */
  public static final class InvalidOwnerEvidenceException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private InvalidOwnerEvidenceException(RuntimeException cause) {
      super("Game Session PLAYER_CREATED roster source evidence is invalid", cause);
    }
  }

  /** Constructor custody establishes this transport read, never the other owners' authority. */
  public static final class AuthenticatedSource {
    private final ReadPlayerCreatedRosterSourceResponse response;
    private final AuthoredInitialActorCoreReadEvidence.ReadResult authored;
    private final WorldCanonicalInstanceLifecycleEvidence world;
    private final PlayerCreationDescriptor creationDescriptor;
    private final PublishedRealmEntryPolicySetEvidence publishedPolicySet;
    private final CanonicalGameplayRosterTarget target;

    private AuthenticatedSource(
        ReadPlayerCreatedRosterSourceResponse response,
        AuthoredInitialActorCoreReadEvidence.ReadResult authored,
        WorldCanonicalInstanceLifecycleEvidence world,
        PlayerCreationDescriptor creationDescriptor,
        PublishedRealmEntryPolicySetEvidence publishedPolicySet,
        CanonicalGameplayRosterTarget target) {
      this.response = response;
      this.authored = authored;
      this.world = world;
      this.creationDescriptor = creationDescriptor;
      this.publishedPolicySet = publishedPolicySet;
      this.target = target;
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Protobuf messages are immutable.")
    public ReadPlayerCreatedRosterSourceResponse response() {
      return response;
    }

    public AuthoredInitialActorCoreReadEvidence.ReadResult authored() {
      return authored;
    }

    public PublishedRealmEntryPolicySetEvidence publishedPolicySet() {
      return publishedPolicySet;
    }

    public CanonicalGameplayRosterTarget target() {
      return target;
    }

    public PlayerCreationDescriptor creationDescriptor() {
      return creationDescriptor;
    }

    public WorldCanonicalInstanceLifecycleEvidence world() {
      return world;
    }
  }
}
