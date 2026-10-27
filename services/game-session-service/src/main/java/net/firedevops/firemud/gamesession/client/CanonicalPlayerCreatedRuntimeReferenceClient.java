package net.firedevops.firemud.gamesession.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.entity.CanonicalPlayerCreatedRuntimeReferenceEvidence;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRuntimeReferenceServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceResponse;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;

/** Passive historical PLAYER_CREATED projection reader; this reference grants no admission. */
public final class CanonicalPlayerCreatedRuntimeReferenceClient
    extends AbstractReloadingBlockingGrpcClient<
        CanonicalPlayerCreatedRuntimeReferenceServiceGrpc
            .CanonicalPlayerCreatedRuntimeReferenceServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 20L;

  private final String namespace;

  public CanonicalPlayerCreatedRuntimeReferenceClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "PLAYER_CREATED runtime reference"),
        channels,
        CanonicalPlayerCreatedRuntimeReferenceClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Fixed Game Session workload namespace required");
    }
    this.namespace = namespace;
  }

  /** Initializes the reloadable file-backed mTLS channel outside ambient SQL state. */
  public void init() throws IOException {
    requireNoAmbientSql("PLAYER_CREATED runtime reference initialization requires no ambient SQL");
    initOnce(
        "PLAYER_CREATED runtime reference client is closed",
        "Entity PLAYER_CREATED runtime reference unavailable");
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
  protected CanonicalPlayerCreatedRuntimeReferenceServiceGrpc
          .CanonicalPlayerCreatedRuntimeReferenceServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/entity-management-service";
    return CanonicalPlayerCreatedRuntimeReferenceServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer))
        .withCompression("gzip");
  }

  /** Reads an existing Entity projection without allocating, repairing, or renewing it. */
  public RuntimeActorReference read(PlayerCreatedActorRuntimeReferenceRequest request) {
    requireNoAmbientSql("PLAYER_CREATED runtime reference RPC requires no ambient SQL");
    requireRequest(request, namespace);
    var response =
        requireInitializedStub("Entity PLAYER_CREATED runtime reference client is unavailable")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .withMaxInboundMessageSize(CanonicalPlayerCreatedRuntimeReferenceEvidence.MAX_RESPONSE_BYTES)
            .withMaxOutboundMessageSize(CanonicalPlayerCreatedRuntimeReferenceEvidence.MAX_REQUEST_BYTES)
            .readPlayerCreatedActorRuntimeReference(request);
    try {
      CanonicalPlayerCreatedRuntimeReferenceEvidence.requireResponse(request, response);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Entity PLAYER_CREATED runtime reference response is invalid", malformed);
    }
    return new RuntimeActorReference(namespace, response);
  }

  private static void requireRequest(
      PlayerCreatedActorRuntimeReferenceRequest request, String namespace) {
    Objects.requireNonNull(request, "request");
    CanonicalPlayerCreatedRuntimeReferenceEvidence.requireRequest(request);
    if (!namespace.equals(request.getOrigin().getTargetNamespace())) {
      throw new IllegalArgumentException(
          "PLAYER_CREATED origin namespace must equal the fixed Game Session namespace");
    }
  }

  /** Constructible only from the authenticated RPC's exact, closed Entity response. */
  public static final class RuntimeActorReference implements CanonicalGameplayRuntimeActorReference {
    private final String workloadNamespace;
    private final PlayerCreatedActorRuntimeReferenceResponse evidence;
    private final CanonicalRuntimeEntityId runtimeEntityId;

    private RuntimeActorReference(
        String workloadNamespace, PlayerCreatedActorRuntimeReferenceResponse evidence) {
      this.workloadNamespace = workloadNamespace;
      this.evidence = evidence;
      var selector = evidence.getSelector();
      this.runtimeEntityId =
          new CanonicalRuntimeEntityId(
              UUID.fromString(selector.getTenantUuid()),
              selector.getGameInstanceUuid(),
              evidence.getRuntimeEntityId());
    }

    @Override
    public String workloadNamespace() {
      return workloadNamespace;
    }

    @Override
    public CanonicalRuntimeEntityId runtimeEntityId() {
      return runtimeEntityId;
    }

    @Override
    public long projectionGeneration() {
      return evidence.getProjectionGeneration();
    }

    @Override
    public long projectionFence() {
      return evidence.getProjectionFence();
    }

    @Override
    public UUID canonicalAccountUuid() {
      return UUID.fromString(evidence.getSelector().getAccountUuid());
    }

    @Override
    public UUID characterUuid() {
      return UUID.fromString(evidence.getSelector().getCharacterUuid());
    }

    @Override
    public UUID playableStateNamespaceId() {
      return UUID.fromString(evidence.getSelector().getPlayableStateNamespaceUuid());
    }

    @Override
    public ActorOrigin origin() {
      return new CreationOrigin(evidence.getSelector().getOrigin());
    }

    @Override
    public boolean matchesWorldRequest(CanonicalInitialPlayerLocationRequest request) {
      if (request == null
          || request.getSchemaVersion() != 2
          || !request.getUnknownFields().asMap().isEmpty()
          || !request.hasExpectedTarget()
          || !request.hasPlayerCreatedOrigin()
          || !request.getAssignmentUuid().isEmpty()
          || !request.getAssignmentIntentDigest().isEmpty()
          || !request.getCanonicalAccountUuid().equals(evidence.getSelector().getAccountUuid())
          || !request.getCharacterUuid().equals(evidence.getSelector().getCharacterUuid())) {
        return false;
      }
      var actualOrigin = request.getPlayerCreatedOrigin();
      if (!actualOrigin.equals(evidence.getSelector().getOrigin())
          || !actualOrigin.getUnknownFields().asMap().isEmpty()) {
        return false;
      }
      var target = request.getExpectedTarget();
      var selector = evidence.getSelector();
      return target.getUnknownFields().asMap().isEmpty()
          && target.getPlayableStateScope() == PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
          && target.getCanonicalTenantUuid().equals(selector.getTenantUuid())
          && target.getPlayableStateNamespaceUuid().equals(selector.getPlayableStateNamespaceUuid())
          && target.getGameInstanceId().equals(selector.getGameInstanceUuid())
          && target.getCanonicalVersionUuid().equals(selector.getCanonicalVersionUuid())
          && target.getRealmUuid().equals(selector.getRealmUuid())
          && target.getWorldSlug().equals(selector.getWorldSlug())
          && target.getRealmSlug().equals(selector.getRealmSlug());
    }

    @Override
    public byte[] canonicalEvidenceBytes() {
      return evidence.toByteArray();
    }
  }
}
