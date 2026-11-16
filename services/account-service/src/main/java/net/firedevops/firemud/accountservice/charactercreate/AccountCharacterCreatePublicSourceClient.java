package net.firedevops.firemud.accountservice.charactercreate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadGrpcCodec;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.v1.CharacterCreatePublicSourceServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadCharacterCreatePublicSourceRequest;
import net.firedevops.firemud.gamesession.v1.ReadCharacterCreatePublicSourceResponse;
import net.firedevops.firemud.gamesession.v1.ResolveCharacterCreatePublicTargetRequest;
import net.firedevops.firemud.gamesession.v1.ResolveCharacterCreatePublicTargetResponse;

/**
 * Unregistered Account transport for Game Session's independently composed public source. Its
 * result authenticates the configured Game Session owner; it is not Account bootstrap, issued
 * scope, membership, protection, or admission authority.
 */
public final class AccountCharacterCreatePublicSourceClient
    extends AbstractReloadingBlockingGrpcClient<
        CharacterCreatePublicSourceServiceGrpc.CharacterCreatePublicSourceServiceBlockingStub> {
  public static final int MAX_SOURCE_WIRE_BYTES = 32 * 1024 * 1024;
  public static final int MAX_TARGET_WIRE_BYTES = 64 * 1024;
  private final String namespace;

  public AccountCharacterCreatePublicSourceClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Account CHARACTER_CREATE public source"),
        channels,
        AccountCharacterCreatePublicSourceClient.class);
    this.namespace = AccountCharacterCreateDelegationBindings.namespace(namespace);
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

  /** Canonical target identities originate in Game Session; this issues no Account scope. */
  public AuthenticatedTarget resolve(String worldSlug, String realmSlug, UUID readRequestId) {
    requireNoAmbientSql("Public creation target read requires no ambient Account transaction");
    if (!RealmEntryPolicy.isCanonicalSlug(worldSlug)
        || !RealmEntryPolicy.isCanonicalSlug(realmSlug)
        || readRequestId == null) throw invalid();
    AccountCharacterCreateDelegationBindings.id(readRequestId.toString());
    var request =
        ResolveCharacterCreatePublicTargetRequest.newBuilder()
            .setSchemaVersion(1)
            .setReadRequestId(readRequestId.toString())
            .setTargetNamespace(namespace)
            .setWorldSlug(worldSlug)
            .setRealmSlug(realmSlug)
            .build();
    if (request.getSerializedSize() > 16 * 1024) throw invalid();
    var response =
        requireInitializedStub("Creation public source is unavailable")
            .withMaxInboundMessageSize(MAX_TARGET_WIRE_BYTES)
            .withDeadlineAfter(5L, TimeUnit.SECONDS)
            .resolveCharacterCreatePublicTarget(request);
    if (response == null
        || !response.getUnknownFields().asMap().isEmpty()
        || response.getSchemaVersion() != 1
        || response.getSerializedSize() > MAX_TARGET_WIRE_BYTES
        || !request.equals(response.getRequest())
        || !response.hasCatalog()
        || !response.hasPointer()
        || response.getActiveLifecycleEpoch() <= 0) throw invalid();
    var catalog = response.getCatalog();
    var pointer = response.getPointer();
    if (!catalog.getUnknownFields().asMap().isEmpty()
        || !pointer.getUnknownFields().asMap().isEmpty()
        || !catalog.getVisible()
        || !catalog.getPublicProduction()
        || !"SHARED".equals(catalog.getStateScope())
        || !"PLAYER_CREATED".equals(catalog.getCharacterCreationPolicy())
        || !worldSlug.equals(catalog.getWorldSlug())
        || !realmSlug.equals(catalog.getRealmSlug())
        || !RealmEntryPolicy.isCanonicalSlug(catalog.getTenantSlug())
        || catalog.getWorldDisplayName().isBlank()
        || catalog.getRealmDisplayName().isBlank()
        || catalog.getCatalogRevision() <= 0
        || !catalog.getTenantId().equals(pointer.getTenantId())
        || !catalog.getRealmId().equals(pointer.getRealmId())
        || !catalog.getWorldSlug().equals(pointer.getWorldSlug())
        || !catalog.getRealmSlug().equals(pointer.getRealmSlug())
        || catalog.getCatalogRevision() != pointer.getCatalogRevision()
        || !"OPEN".equals(pointer.getAdmissionState())
        || !response.getCanonicalGameInstanceId().equals(pointer.getAdmissibleGameInstanceId())
        || pointer.getPointerVersion() <= 0
        || !catalog.getReceiptDigest().matches("sha256:[0-9a-f]{64}")
        || pointer.getInitialAdmissionRequestId().isBlank()
        || !pointer.getInitialAdmissionOwnerProofDigest().matches("sha256:[0-9a-f]{64}")
        || !pointer.getAdmissionPointerSnapshotDigest().matches("[0-9a-f]{64}")) throw invalid();
    for (String id :
        java.util.List.of(
            catalog.getTenantId(),
            catalog.getRealmId(),
            catalog.getPlayableStateNamespaceId(),
            catalog.getCreationRequestId(),
            response.getCanonicalGameInstanceId(),
            response.getCanonicalVersionId())) AccountCharacterCreateDelegationBindings.id(id);
    AccountCharacterCreateDelegationBindings.instant(pointer.getUpdatedAt());
    for (String name :
        java.util.List.of(catalog.getWorldDisplayName(), catalog.getRealmDisplayName())) {
      if (!name.equals(name.strip())
          || name.codePointCount(0, name.length()) > RealmEntryPolicy.MAX_DISPLAY_NAME_CODE_POINTS
          || name.codePoints().anyMatch(Character::isISOControl)) throw invalid();
    }
    return new AuthenticatedTarget(response);
  }

  public AuthenticatedSource read(ReadCharacterCreatePublicSourceRequest request) {
    requireNoAmbientSql("Public creation source read requires no ambient Account transaction");
    requireRequest(request);
    var response =
        requireInitializedStub("Creation public source is unavailable")
            .withDeadlineAfter(5L, TimeUnit.SECONDS)
            .readCharacterCreatePublicSource(request);
    if (response == null
        || !response.getUnknownFields().asMap().isEmpty()
        || response.getSchemaVersion() != 1
        || response.getSerializedSize() > MAX_SOURCE_WIRE_BYTES
        || !request.equals(response.getRequest())
        || !response.hasCatalog()
        || !response.hasPointer()
        || !response.hasAuthoredSource()
        || response.getWorldLifecycleEvidence().isEmpty()) throw invalid();
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
        || response.getActiveLifecycleEpoch() <= 0
        || response.getOriginalOwnerFence() <= 0) throw invalid();
    AccountCharacterCreateDelegationBindings.id(response.getCanonicalVersionId());
    if (response.getOriginalControlPlaneRequestId().isBlank()
        || response.getOriginalControlPlaneRequestId().length() > 128) throw invalid();
    AccountCharacterCreateDelegationBindings.id(response.getOriginalOwnerAttemptId());
    AccountCharacterCreateDelegationBindings.instant(pointer.getUpdatedAt());
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
    if (policy.entryPolicy() != RealmEntryPolicy.EntryPolicy.PLAYER_CREATED
        || !policy.visible()
        || !policy.publicProduction()
        || policy.stateScope() != RealmEntryPolicy.StateScope.SHARED
        || !policy.worldSlug().equals(catalog.getWorldSlug())
        || !policy.worldDisplayName().equals(catalog.getWorldDisplayName())
        || !policy.realmDisplayName().equals(catalog.getRealmDisplayName())) throw invalid();
    return new AuthenticatedSource(response, authored, world);
  }

  private void requireRequest(ReadCharacterCreatePublicSourceRequest request) {
    if (request == null
        || !request.getUnknownFields().asMap().isEmpty()
        || request.getSchemaVersion() != 1
        || request.getSerializedSize() > 16 * 1024
        || !namespace.equals(request.getTargetNamespace())
        || !"SHARED".equals(request.getPlayableStateScope())
        || request.getWorldSlug().isBlank()
        || request.getRealmSlug().isBlank()
        || request.getCatalogRevision() <= 0
        || request.getPointerVersion() <= 0) throw invalid();
    for (String id :
        java.util.List.of(
            request.getReadRequestId(),
            request.getCanonicalTenantId(),
            request.getRealmId(),
            request.getPlayableStateNamespaceId(),
            request.getCanonicalGameInstanceId())) AccountCharacterCreateDelegationBindings.id(id);
    AccountCharacterCreateDelegationBindings.instant(request.getEvaluatedAt());
    AccountCharacterCreateDelegationBindings.instant(request.getConnectScopeExpiresAt());
    if (!java.time.Instant.parse(request.getEvaluatedAt())
        .isBefore(java.time.Instant.parse(request.getConnectScopeExpiresAt()))) throw invalid();
  }

  private static RuntimeException invalid() {
    return AccountCharacterCreateDelegationBindings.invalid();
  }

  /** Authenticates only the configured Game Session owner, never bootstrap or scope authority. */
  public static final class AuthenticatedTarget {
    private final ResolveCharacterCreatePublicTargetResponse response;

    private AuthenticatedTarget(ResolveCharacterCreatePublicTargetResponse response) {
      this.response = response;
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "Generated protobuf messages are immutable values and cannot expose mutable state.")
    public ResolveCharacterCreatePublicTargetResponse response() {
      return response;
    }
  }

  /** Constructor custody establishes this transport read, never the other owners' authority. */
  public static final class AuthenticatedSource {
    private final ReadCharacterCreatePublicSourceResponse response;
    private final AuthoredInitialActorCoreReadEvidence.ReadResult authored;
    private final WorldCanonicalInstanceLifecycleEvidence world;

    private AuthenticatedSource(
        ReadCharacterCreatePublicSourceResponse response,
        AuthoredInitialActorCoreReadEvidence.ReadResult authored,
        WorldCanonicalInstanceLifecycleEvidence world) {
      this.response = response;
      this.authored = authored;
      this.world = world;
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "Generated protobuf messages are immutable values and cannot expose mutable state.")
    public ReadCharacterCreatePublicSourceResponse response() {
      return response;
    }

    public AuthoredInitialActorCoreReadEvidence.ReadResult authored() {
      return authored;
    }

    public WorldCanonicalInstanceLifecycleEvidence world() {
      return world;
    }
  }
}
