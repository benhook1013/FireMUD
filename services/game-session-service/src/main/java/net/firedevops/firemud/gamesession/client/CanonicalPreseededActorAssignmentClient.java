package net.firedevops.firemud.gamesession.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.Objects;
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
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorRequest;
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPreseededActorAssignmentServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;

/** Manually composed, non-admitting client for Entity's run-owned preseeded assignment action. */
public final class CanonicalPreseededActorAssignmentClient
    extends AbstractReloadingBlockingGrpcClient<
        CanonicalPreseededActorAssignmentServiceGrpc
            .CanonicalPreseededActorAssignmentServiceBlockingStub> {
  private static final int SCHEMA_VERSION = 1;
  private static final long CALL_DEADLINE_SECONDS = 20L;
  private static final int MAX_GAME_INSTANCE_ID_LENGTH = 255;
  private static final int MAX_DISPLAY_NAME_CODE_POINTS = 100;
  private static final int MAX_RELEASE_REF_LENGTH = 1024;
  private static final Pattern RAW_SHA256 = Pattern.compile("[0-9a-f]{64}");

  private final String namespace;

  public CanonicalPreseededActorAssignmentClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Preseeded actor assignment"),
        channels,
        CanonicalPreseededActorAssignmentClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Fixed Game Session workload namespace required");
    }
    this.namespace = namespace;
  }

  /** Initializes the manual channel outside any ambient SQL transaction or synchronization. */
  public void init() throws IOException {
    requireNoAmbientSql("Preseeded actor assignment client initialization requires no ambient SQL");
    initOnce("Preseeded actor assignment client is closed", "Entity assignment unavailable");
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

  /**
   * Assigns the caller's stable operation intent and returns only Entity's checked persisted
   * response. The result records assignment only; it does not establish admission or PLAY.
   */
  public AssignPreseededActorResponse assign(AssignPreseededActorRequest request) {
    requireNoAmbientSql("Preseeded actor assignment RPC requires no ambient SQL");
    requireRequest(request);
    var response =
        requireInitializedStub("Entity preseeded actor assignment client is unavailable")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .assignPreseededActor(request);
    requireResponse(request, response);
    return response;
  }

  private static void requireRequest(AssignPreseededActorRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.getSchemaVersion() != SCHEMA_VERSION
        || !request.getUnknownFields().asMap().isEmpty()
        || !request.hasExpectedTarget()
        || !request.hasCorePayload()) {
      throw new IllegalArgumentException("Closed complete schema-1 assignment request required");
    }
    var target = request.getExpectedTarget();
    var core = request.getCorePayload();
    if (!target.getUnknownFields().asMap().isEmpty()
        || !core.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Assignment request contains unknown fields");
    }

    requireCanonicalNonNilUuid(request.getAssignmentUuid(), "assignment_uuid");
    requireCanonicalNonNilUuid(request.getCanonicalAccountUuid(), "canonical_account_uuid");
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
    if (core.getActorKind()
        != CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER) {
      throw new IllegalArgumentException("actor_kind must be PLAYER");
    }
    requireDisplayName(core.getDisplayName());
    requireDigest(request.getMutationIntentDigest(), "mutation_intent_digest");
  }

  private static void requireResponse(
      AssignPreseededActorRequest request, AssignPreseededActorResponse response) {
    if (response == null
        || response.getSchemaVersion() != SCHEMA_VERSION
        || !response.getUnknownFields().asMap().isEmpty()
        || !request.getAssignmentUuid().equals(response.getAssignmentUuid())
        || !request.getMutationIntentDigest().equals(response.getIntentDigest())) {
      throw invalidResponse("Entity response changed the schema, operation, or intent digest");
    }
    requireCanonicalResponseUuid(response.getAssignmentUuid(), "assignment_uuid");
    requireResponseDigest(response.getIntentDigest());
    switch (response.getOutcome()) {
      case PRESEEDED_ACTOR_ASSIGNMENT_OUTCOME_ASSIGNED -> {
        if (!response.hasCharacterUuid()) {
          throw invalidResponse("ASSIGNED response must include character_uuid");
        }
        requireCanonicalResponseUuid(response.getCharacterUuid(), "character_uuid");
      }
      case PRESEEDED_ACTOR_ASSIGNMENT_OUTCOME_IDEMPOTENCY_CONFLICT -> {
        if (response.hasCharacterUuid()) {
          throw invalidResponse("IDEMPOTENCY_CONFLICT response cannot include character_uuid");
        }
      }
      default -> throw invalidResponse("Entity returned an unsupported assignment outcome");
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

  private static void requireResponseDigest(String value) {
    if (value == null || !RAW_SHA256.matcher(value).matches()) {
      throw invalidResponse("intent_digest is not raw lowercase SHA-256 hex");
    }
  }

  private static void requireCanonicalNonNilUuid(String value, String field) {
    try {
      DraftAuthorizationFenceBinding.canonicalUuid(value);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID", invalid);
    }
  }

  private static void requireCanonicalResponseUuid(String value, String field) {
    try {
      requireCanonicalNonNilUuid(value, field);
    } catch (IllegalArgumentException invalid) {
      throw invalidResponse(field + " is not a canonical non-nil UUID", invalid);
    }
  }

  private static IllegalStateException invalidResponse(String message) {
    return new IllegalStateException(
        "Entity preseeded actor assignment response is invalid: " + message);
  }

  private static IllegalStateException invalidResponse(String message, Throwable cause) {
    return new IllegalStateException(
        "Entity preseeded actor assignment response is invalid: " + message, cause);
  }
}
