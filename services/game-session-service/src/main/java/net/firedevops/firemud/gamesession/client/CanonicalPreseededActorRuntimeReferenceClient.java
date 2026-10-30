package net.firedevops.firemud.gamesession.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPreseededActorAssignmentServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceResponse;

/** Passive exact-original-assignment reader. A reference grants neither placement nor admission. */
public final class CanonicalPreseededActorRuntimeReferenceClient
    extends AbstractReloadingBlockingGrpcClient<
        CanonicalPreseededActorAssignmentServiceGrpc
            .CanonicalPreseededActorAssignmentServiceBlockingStub> {
  private static final int SCHEMA_VERSION = 1;
  private static final int MAX_REQUEST_BYTES = 8 * 1024;
  private static final int MAX_RESPONSE_BYTES = 8 * 1024;
  private static final long CALL_DEADLINE_SECONDS = 20L;
  private static final int MAX_GAME_INSTANCE_ID_LENGTH = 255;
  private static final int MAX_DISPLAY_NAME_CODE_POINTS = 100;
  private static final int MAX_RELEASE_REF_LENGTH = 1024;
  private static final Pattern RAW_SHA256 = Pattern.compile("[0-9a-f]{64}");

  private final String namespace;

  public CanonicalPreseededActorRuntimeReferenceClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Preseeded actor runtime reference"),
        channels,
        CanonicalPreseededActorRuntimeReferenceClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Fixed Game Session workload namespace required");
    }
    this.namespace = namespace;
  }

  /** Initializes the manual channel outside any ambient SQL transaction or synchronization. */
  public void init() throws IOException {
    requireNoAmbientSql(
        "Preseeded actor runtime reference client initialization requires no ambient SQL");
    initOnce(
        "Preseeded actor runtime reference client is closed",
        "Entity runtime reference unavailable");
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
  protected CanonicalPreseededActorAssignmentServiceGrpc
          .CanonicalPreseededActorAssignmentServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/entity-management-service";
    return CanonicalPreseededActorAssignmentServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer))
        .withCompression("gzip");
  }

  /** Reads the actual retained projection; this call never allocates or renews authority. */
  public RuntimeActorReference read(ReadPreseededActorRuntimeReferenceRequest request) {
    requireNoAmbientSql("Runtime reference RPC requires no ambient SQL");
    requireRequest(request);
    var response =
        requireInitializedStub("Entity runtime reference client is unavailable")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .withMaxInboundMessageSize(MAX_RESPONSE_BYTES)
            .withMaxOutboundMessageSize(MAX_REQUEST_BYTES)
            .readPreseededActorRuntimeReference(request);
    requireResponse(request, response);
    return new RuntimeActorReference(namespace, response);
  }

  private static void requireRequest(ReadPreseededActorRuntimeReferenceRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.getSchemaVersion() != SCHEMA_VERSION
        || !request.getUnknownFields().asMap().isEmpty()
        || !request.hasExpectedTarget()
        || request.getSerializedSize() > MAX_REQUEST_BYTES) {
      throw new IllegalArgumentException("Closed complete schema-1 assignment request required");
    }
    var target = request.getExpectedTarget();
    if (!target.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Assignment request contains unknown fields");
    }

    requireCanonicalNonNilUuid(request.getAssignmentUuid(), "assignment_uuid");
    requireCanonicalNonNilUuid(request.getCanonicalAccountUuid(), "canonical_account_uuid");
    requireCanonicalNonNilUuid(request.getCharacterUuid(), "character_uuid");
    requireCanonicalNonNilUuid(target.getCanonicalTenantUuid(), "canonical_tenant_uuid");
    requireCanonicalNonNilUuid(target.getRealmUuid(), "realm_uuid");
    requireCanonicalNonNilUuid(target.getCanonicalVersionUuid(), "canonical_version_uuid");
    requireCanonicalNonNilUuid(
        target.getPlayableStateNamespaceUuid(), "playable_state_namespace_uuid");
    if (!RealmEntryPolicy.isCanonicalSlug(target.getWorldSlug())
        || !RealmEntryPolicy.isCanonicalSlug(target.getRealmSlug())) {
      throw new IllegalArgumentException("Assignment target requires canonical realm selectors");
    }
    requireBoundedText(target.getGameInstanceId(), "game_instance_id", MAX_GAME_INSTANCE_ID_LENGTH);
    if (target.getCatalogRevision() <= 0L) {
      throw new IllegalArgumentException("catalog_revision must be positive");
    }
    requireDigest(target.getFrozenPolicyDigest(), "frozen_policy_digest");
    requireBoundedText(
        target.getPublishedReleaseBundleRef(),
        "published_release_bundle_ref",
        MAX_RELEASE_REF_LENGTH);
    if (target.getPlayableStateScope() != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
        && target.getPlayableStateScope() != PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED) {
      throw new IllegalArgumentException("playable_state_scope must be SHARED or ISOLATED");
    }
    requireDigest(request.getIntentDigest(), "intent_digest");
  }

  private static void requireResponse(
      ReadPreseededActorRuntimeReferenceRequest request,
      ReadPreseededActorRuntimeReferenceResponse response) {
    if (response == null
        || response.getSerializedSize() > MAX_RESPONSE_BYTES
        || response.getSchemaVersion() != SCHEMA_VERSION
        || !response.getUnknownFields().asMap().isEmpty()
        || !response.hasExpectedTarget()
        || !response.hasCorePayload()
        || !request.getExpectedTarget().equals(response.getExpectedTarget())
        || !request.getAssignmentUuid().equals(response.getAssignmentUuid())
        || !request.getCanonicalAccountUuid().equals(response.getCanonicalAccountUuid())
        || !request.getCharacterUuid().equals(response.getCharacterUuid())
        || !request.getIntentDigest().equals(response.getIntentDigest())
        || response.getRuntimeEntityId() <= 0L
        || response.getProjectionGeneration() <= 0L
        || response.getProjectionFence() <= 0L) {
      throw invalidResponse(
          "Entity changed the exact original assignment, target, identity or projection");
    }
    var core = response.getCorePayload();
    if (!core.getUnknownFields().asMap().isEmpty()
        || core.getActorKind()
            != CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER) {
      throw invalidResponse("Entity returned an open or unsupported actor core");
    }
    try {
      requireDigest(response.getPublishedOwnerProofDigest(), "published_owner_proof_digest");
      requireDisplayName(core.getDisplayName());
    } catch (IllegalArgumentException malformed) {
      throw invalidResponse("Entity returned malformed assignment provenance", malformed);
    }
  }

  /** Constructible only after an authenticated, exact, closed Entity response passes validation. */
  public static final class RuntimeActorReference
      implements CanonicalGameplayRuntimeActorReference {
    private final String workloadNamespace;
    private final ReadPreseededActorRuntimeReferenceResponse receipt;
    private final CanonicalRuntimeEntityId runtimeEntityId;

    private RuntimeActorReference(
        String workloadNamespace, ReadPreseededActorRuntimeReferenceResponse receipt) {
      this.workloadNamespace = workloadNamespace;
      this.receipt = receipt;
      this.runtimeEntityId =
          new CanonicalRuntimeEntityId(
              UUID.fromString(receipt.getExpectedTarget().getCanonicalTenantUuid()),
              receipt.getExpectedTarget().getGameInstanceId(),
              receipt.getRuntimeEntityId());
    }

    public String workloadNamespace() {
      return workloadNamespace;
    }

    public CanonicalRuntimeEntityId runtimeEntityId() {
      return runtimeEntityId;
    }

    public long projectionGeneration() {
      return receipt.getProjectionGeneration();
    }

    public long projectionFence() {
      return receipt.getProjectionFence();
    }

    @Override
    public ActorOrigin origin() {
      return new AssignmentOrigin(assignmentUuid(), intentDigest());
    }

    @Override
    public boolean matchesWorldRequest(
        net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest request) {
      return request != null
          && request.getSchemaVersion() == 1
          && !request.hasPlayerCreatedOrigin()
          && receipt.getAssignmentUuid().equals(request.getAssignmentUuid())
          && receipt.getIntentDigest().equals(request.getAssignmentIntentDigest())
          && receipt.getCanonicalAccountUuid().equals(request.getCanonicalAccountUuid())
          && receipt.getCharacterUuid().equals(request.getCharacterUuid())
          && receipt.getExpectedTarget().equals(request.getExpectedTarget());
    }

    @Override
    public byte[] canonicalEvidenceBytes() {
      return receipt.toByteArray();
    }

    public UUID assignmentUuid() {
      return UUID.fromString(receipt.getAssignmentUuid());
    }

    public UUID canonicalAccountUuid() {
      return UUID.fromString(receipt.getCanonicalAccountUuid());
    }

    public UUID characterUuid() {
      return UUID.fromString(receipt.getCharacterUuid());
    }

    public String intentDigest() {
      return receipt.getIntentDigest();
    }

    public UUID playableStateNamespaceId() {
      return UUID.fromString(receipt.getExpectedTarget().getPlayableStateNamespaceUuid());
    }

    public net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget
        target() {
      return receipt.getExpectedTarget();
    }

    public String publishedOwnerProofDigest() {
      return receipt.getPublishedOwnerProofDigest();
    }

    public net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentCorePayload
        corePayload() {
      return receipt.getCorePayload();
    }
  }

  private static void requireDisplayName(String value) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.trim())
        || value.codePointCount(0, value.length()) > MAX_DISPLAY_NAME_CODE_POINTS
        || containsUnpairedSurrogate(value)
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("display_name must be bounded printable text");
    }
  }

  private static void requireBoundedText(String value, String field, int maxLength) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.trim())
        || value.length() > maxLength
        || containsUnpairedSurrogate(value)
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " is incomplete or outside its text bound");
    }
  }

  private static boolean containsUnpairedSurrogate(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          return true;
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        return true;
      }
    }
    return false;
  }

  private static void requireDigest(String value, String field) {
    if (value == null || !RAW_SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be raw lowercase SHA-256 hex");
    }
  }

  private static void requireCanonicalNonNilUuid(String value, String field) {
    try {
      DraftAuthorizationFenceBinding.canonicalUuid(value);
      if ("00000000-0000-0000-0000-000000000000".equals(value)) {
        throw new IllegalArgumentException("nil UUID is not allowed");
      }
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID", invalid);
    }
  }

  private static IllegalStateException invalidResponse(String message) {
    return new IllegalStateException(
        "Entity preseeded actor runtime reference response is invalid: " + message);
  }

  private static IllegalStateException invalidResponse(String message, Throwable cause) {
    return new IllegalStateException(
        "Entity preseeded actor runtime reference response is invalid: " + message, cause);
  }
}
