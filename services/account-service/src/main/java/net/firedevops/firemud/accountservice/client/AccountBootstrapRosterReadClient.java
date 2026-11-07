package net.firedevops.firemud.accountservice.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterReadRequest;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterReadServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;

/**
 * Manually composed Entity persisted discovery read. No session, selection or admission is minted.
 */
public final class AccountBootstrapRosterReadClient
    extends AbstractReloadingBlockingGrpcClient<
        AccountBootstrapRosterReadServiceGrpc.AccountBootstrapRosterReadServiceBlockingStub> {
  public static final int MAX_REQUEST_BYTES = 4096;
  public static final int MAX_RESPONSE_BYTES = 64 * 1024;
  public static final int MAX_ACTORS = 100;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private final String namespace;

  public AccountBootstrapRosterReadClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Account bootstrap Entity discovery"),
        channels,
        AccountBootstrapRosterReadClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.namespace = namespace;
  }

  public void init() throws IOException {
    requireNoAmbientSql("Bootstrap Entity discovery initialization refuses ambient SQL");
    initOnce(
        "Bootstrap Entity discovery client is closed",
        "Bootstrap Entity discovery client is not initialized");
  }

  /**
   * Reads an exact independently resolved target; scope/caller authentication remains with Account.
   */
  public List<Actor> read(UUID requestId, UUID accountId, PublishedRealmEvidence published) {
    requireNoAmbientSql("Bootstrap Entity discovery refuses ambient SQL");
    // This stub has no GrpcClientAuth/customizer: local browser SessionContext is preserved,
    // and only the Account workload certificate authenticates this purpose-specific read.
    requireUuid(requestId);
    requireUuid(accountId);
    Objects.requireNonNull(published);
    if (!accountId.equals(published.selector().canonicalAccountUuid())
        || !namespace.equals(published.selector().targetNamespace())) {
      throw new IllegalArgumentException("Bootstrap Entity discovery caller/namespace mismatch");
    }
    AccountBootstrapRosterReadRequest request =
        AccountBootstrapRosterReadRequest.newBuilder()
            .setRequestUuid(requestId.toString())
            .setCanonicalAccountUuid(accountId.toString())
            .setTargetNamespace(namespace)
            .setExpectedTarget(expectedTarget(published))
            .build();
    if (request.getSerializedSize() > MAX_REQUEST_BYTES) {
      throw new IllegalArgumentException("Bootstrap Entity request exceeds its bound");
    }
    var response =
        requireInitializedStub("Bootstrap Entity discovery client is not initialized")
            .withDeadlineAfter(5L, TimeUnit.SECONDS)
            .withMaxInboundMessageSize(MAX_RESPONSE_BYTES)
            .readAccountBootstrapRoster(request);
    if (!response.getUnknownFields().asMap().isEmpty()
        || response.getSerializedSize() > MAX_RESPONSE_BYTES
        || !response.hasRequest()
        || !request.equals(response.getRequest())
        || response.getActorsCount() > MAX_ACTORS) {
      throw new IllegalStateException(
          "Entity bootstrap discovery response is malformed or mismatched");
    }
    List<Actor> actors = new ArrayList<>(response.getActorsCount());
    String previous = null;
    for (var actor : response.getActorsList()) {
      UUID id = parseUuid(actor.getCharacterUuid());
      if (!actor.getUnknownFields().asMap().isEmpty()
          || !actor.hasLevel()
          || (previous != null && previous.compareTo(actor.getCharacterUuid()) >= 0)) {
        throw new IllegalStateException(
            "Entity bootstrap discovery actors are duplicated or unordered");
      }
      actors.add(new Actor(id, actor.getDisplayName(), actor.getLevel()));
      previous = actor.getCharacterUuid();
    }
    return List.copyOf(actors);
  }

  private CanonicalGameplayRosterTarget expectedTarget(PublishedRealmEvidence published) {
    var target = published.currentTarget();
    var source = published.sourceEvidence();
    var policy = source.selectedPolicyEvidence();
    var policySet = source.publishedPolicySetEvidence();
    policySet.requireValidDigest();
    policy.requireValidDigest(policySet);
    if (!namespace.equals(target.targetNamespace())
        || !"SHARED".equals(target.playableStateScope())
        || !"PRESEEDED_ONLY".equals(target.characterCreationPolicy())) {
      throw new IllegalArgumentException(
          "Bootstrap Entity discovery supports public SHARED PRESEEDED_ONLY targets");
    }
    return CanonicalGameplayRosterTarget.newBuilder()
        .setTenantUuid(target.canonicalTenantId().toString())
        .setRealmUuid(target.realmId().toString())
        .setWorldSlug(target.worldSlug())
        .setRealmSlug(target.realmSlug())
        .setGameInstanceUuid(target.canonicalGameInstanceId().toString())
        .setCatalogRevision(target.catalogRevision())
        .setPointerVersion(target.pointerVersion())
        .setActiveWorldEpoch(target.activeWorldEpoch())
        .setCanonicalVersionUuid(target.canonicalVersionId().toString())
        .setPublishedPolicyDigest(rawDigest(policy.policyDigest()))
        .setPublishedReleaseBundleRef(policySet.publishedReleaseBundleRef())
        .setAdmissionPointerSnapshotDigest(target.admissionPointerSnapshotDigest())
        .setPublishedOwnerProofDigest(rawDigest(target.ownerProofDigest()))
        .setPlayableStateNamespaceUuid(target.playableStateNamespaceId().toString())
        .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
        .setEntryPolicy(
            CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
        .build();
  }

  private static String rawDigest(String prefixed) {
    if (prefixed == null || !prefixed.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Canonical published source digest is malformed");
    }
    return prefixed.substring(7);
  }

  private static UUID parseUuid(String value) {
    UUID id = UUID.fromString(value);
    requireUuid(id);
    if (!id.toString().equals(value))
      throw new IllegalStateException("Canonical actor UUID required");
    return id;
  }

  private static void requireUuid(UUID value) {
    if (value == null || NIL_UUID.equals(value))
      throw new IllegalArgumentException("Canonical non-nil UUID required");
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getEntityManagementService();
  }

  @Override
  protected String defaultTarget() {
    return "entity-management-service:6565";
  }

  @Override
  protected AccountBootstrapRosterReadServiceGrpc.AccountBootstrapRosterReadServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/entity-management-service";
    return AccountBootstrapRosterReadServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer));
  }

  public record Actor(UUID characterId, String displayName, int level) {
    public Actor {
      requireUuid(characterId);
      if (level < 0
          || displayName == null
          || displayName.isBlank()
          || !displayName.equals(displayName.trim())
          || displayName.codePointCount(0, displayName.length()) > 100
          || displayName.codePoints().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("Canonical actor display name required");
      }
    }
  }
}
