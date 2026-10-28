package net.firedevops.firemud.gamesession.client;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayActorOrigins;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRegionResponse;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationResponse;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationServiceGrpc;

/** Manually composed, non-admitting client for World-owned initial placement evidence. */
public final class CanonicalInitialPlayerLocationClient
    extends AbstractReloadingBlockingGrpcClient<
        CanonicalInitialPlayerLocationServiceGrpc
            .CanonicalInitialPlayerLocationServiceBlockingStub> {
  private static final int SCHEMA_VERSION = 1;
  private static final long CALL_DEADLINE_SECONDS = 20L;
  private static final int MAX_REGION_REQUEST_BYTES = 16 * 1024;
  private static final int MAX_REGION_RESPONSE_BYTES = 8 * 1024;
  private static final int MAX_LIFECYCLE_REQUEST_BYTES = 4 * 1024;
  private static final int MAX_REQUEST_ID_LENGTH = 128;
  private static final int MAX_RELEASE_REF_LENGTH = 128;
  private static final Pattern RAW_SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern PREFIXED_SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern MACHINE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final Pattern POSITIVE_PRIVATE_RUNTIME_ROOM_KEY = Pattern.compile("[1-9][0-9]*");

  private final String namespace;

  public CanonicalInitialPlayerLocationClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace) {
    super(
        endpoints,
        requireFileBackedMtls(tls, "Initial player location"),
        channels,
        CanonicalInitialPlayerLocationClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Fixed Game Session workload namespace required");
    }
    this.namespace = namespace;
  }

  /** Initializes the manual channel outside any ambient SQL transaction or synchronization. */
  public void init() throws IOException {
    requireNoAmbientSql("Initial player location client initialization requires no ambient SQL");
    initOnce("Initial player location client is closed", "World placement unavailable");
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getWorldManagementService();
  }

  @Override
  protected String defaultTarget() {
    return "world-management-service:6565";
  }

  @Override
  protected CanonicalInitialPlayerLocationServiceGrpc
          .CanonicalInitialPlayerLocationServiceBlockingStub
      buildStub(ManagedChannel channel) {
    String peer = "spiffe://firemud/ns/" + namespace + "/sa/world-management-service";
    return CanonicalInitialPlayerLocationServiceGrpc.newBlockingStub(channel)
        .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(peer))
        .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(peer))
        .withCompression("gzip");
  }

  /** Places one assigned actor once and returns retained placement evidence, not live occupancy. */
  public CanonicalInitialPlayerLocationResponse placeInitialPlayer(
      CanonicalInitialPlayerLocationRequest request) {
    return invoke(request, true);
  }

  /**
   * Reads only the retained result for this exact operation, not current occupancy or activation.
   */
  public CanonicalInitialPlayerLocationResponse readInitialPlayerLocation(
      CanonicalInitialPlayerLocationRequest request) {
    return invoke(request, false);
  }

  /** Reads the fresh current-room region consequence for an exact retained APPLIED placement. */
  public CanonicalInitialPlayerLocationRegionResponse readInitialPlayerLocationRegion(
      CanonicalInitialPlayerLocationRequest request) {
    requireNoAmbientSql("Initial player location region RPC requires no ambient SQL");
    requireRequest(request);
    if (request.getSerializedSize() > MAX_REGION_REQUEST_BYTES) {
      throw new IllegalArgumentException(
          "Initial player location region request exceeds its bound");
    }
    var stub =
        requireInitializedStub("World initial player location client is unavailable")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
            .withMaxInboundMessageSize(MAX_REGION_RESPONSE_BYTES)
            .withMaxOutboundMessageSize(MAX_REGION_REQUEST_BYTES);
    CanonicalInitialPlayerLocationRegionResponse response =
        stub.readInitialPlayerLocationRegion(request);
    requireRegionResponse(request, response);
    return response;
  }

  private CanonicalInitialPlayerLocationResponse invoke(
      CanonicalInitialPlayerLocationRequest request, boolean place) {
    requireNoAmbientSql("Initial player location RPC requires no ambient SQL");
    requireRequest(request);
    var stub =
        requireInitializedStub("World initial player location client is unavailable")
            .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
    var response =
        place ? stub.placeInitialPlayer(request) : stub.readInitialPlayerLocation(request);
    requireResponse(request, response);
    return response;
  }

  private static void requireRequest(CanonicalInitialPlayerLocationRequest request) {
    Objects.requireNonNull(request, "request");
    if ((request.getSchemaVersion() != 1 && request.getSchemaVersion() != 2)
        || !request.getUnknownFields().asMap().isEmpty()
        || !request.hasExpectedTarget()) {
      throw new IllegalArgumentException(
          "Closed complete initial player location request required");
    }
    var target = request.getExpectedTarget();
    if (!target.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Initial player location target contains unknown fields");
    }

    requireCanonicalNonNilUuid(request.getPlacementOperationUuid(), "placement_operation_uuid");
    requireCanonicalNonNilUuid(request.getCanonicalAccountUuid(), "canonical_account_uuid");
    requireCanonicalNonNilUuid(request.getCharacterUuid(), "character_uuid");
    CanonicalGameplayActorOrigins.fromWorldRequest(request);
    requireCanonicalNonNilUuid(target.getCanonicalTenantUuid(), "canonical_tenant_uuid");
    requireCanonicalNonNilUuid(target.getRealmUuid(), "realm_uuid");
    requireCanonicalNonNilUuid(target.getCanonicalVersionUuid(), "canonical_version_uuid");
    requireCanonicalNonNilUuid(
        target.getPlayableStateNamespaceUuid(), "playable_state_namespace_uuid");
    requireCanonicalNonNilUuid(
        request.getInitialAdmissionHoldUuid(), "initial_admission_hold_uuid");
    requireCanonicalNonNilUuid(
        request.getInitialAdmissionHoldFence(), "initial_admission_hold_fence");
    if (!RealmEntryPolicy.isCanonicalSlug(target.getWorldSlug())
        || !RealmEntryPolicy.isCanonicalSlug(target.getRealmSlug())) {
      throw new IllegalArgumentException(
          "Initial player location target requires canonical realm selectors");
    }
    requireCanonicalNonNilUuid(target.getGameInstanceId(), "game_instance_id");
    if (target.getCatalogRevision() <= 0L) {
      throw new IllegalArgumentException("catalog_revision must be positive");
    }
    requireDigest(target.getFrozenPolicyDigest(), "frozen_policy_digest");
    requireBoundedText(
        target.getPublishedReleaseBundleRef(),
        "published_release_bundle_ref",
        MAX_RELEASE_REF_LENGTH);
    if (target.getPlayableStateScope() != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED) {
      throw new IllegalArgumentException("Initial admission requires SHARED playable state");
    }
    requireBoundedText(
        request.getInitialAdmissionRequestId(),
        "initial_admission_request_id",
        MAX_REQUEST_ID_LENGTH);
    requireDigest(request.getInitialAdmissionRequestDigest(), "initial_admission_request_digest");
    requirePrefixedDigest(request.getTerminalProofDigest(), "terminal_proof_digest");
    if (request.getActiveLifecycleEpoch() <= 0L) {
      throw new IllegalArgumentException("active_lifecycle_epoch must be positive");
    }
    if (request.getCommittedPointerVersion() != 1L) {
      throw new IllegalArgumentException("committed_pointer_version must be exactly 1");
    }
    if (request.getAuditEventId() <= 0L) {
      throw new IllegalArgumentException("audit_event_id must be positive");
    }
  }

  private static void requireResponse(
      CanonicalInitialPlayerLocationRequest request,
      CanonicalInitialPlayerLocationResponse response) {
    if (response == null
        || response.getSchemaVersion() != request.getSchemaVersion()
        || !response.getUnknownFields().asMap().isEmpty()
        || !request.getPlacementOperationUuid().equals(response.getPlacementOperationUuid())
        || !request.getCanonicalAccountUuid().equals(response.getCanonicalAccountUuid())
        || !request.getCharacterUuid().equals(response.getCharacterUuid())
        || !request.getAssignmentUuid().equals(response.getAssignmentUuid())
        || !request.getAssignmentIntentDigest().equals(response.getAssignmentIntentDigest())
        || request.hasPlayerCreatedOrigin() != response.hasPlayerCreatedOrigin()
        || (request.hasPlayerCreatedOrigin()
            && !request.getPlayerCreatedOrigin().equals(response.getPlayerCreatedOrigin()))) {
      throw invalidResponse("World response changed the schema, operation, scope, or assignment");
    }
    requireResponseUuid(response.getPlacementOperationUuid(), "placement_operation_uuid");
    requireResponseUuid(response.getCanonicalAccountUuid(), "canonical_account_uuid");
    requireResponseUuid(response.getCharacterUuid(), "character_uuid");
    if (request.getSchemaVersion() == 1) {
      requireResponseUuid(response.getAssignmentUuid(), "assignment_uuid");
      requireResponseRawDigest(response.getAssignmentIntentDigest(), "assignment_intent_digest");
    }
    requireResponseDigest(response.getRequestDigest(), "request_digest");
    requireResponseDigest(response.getResultDigest(), "result_digest");

    switch (response.getOutcome()) {
      case CANONICAL_INITIAL_PLAYER_LOCATION_OUTCOME_APPLIED -> {
        if (!response.getConflictCode().isEmpty() || !response.hasStartLocation()) {
          throw invalidResponse("APPLIED response must carry only its complete start location");
        }
        var location = response.getStartLocation();
        if (!location.getUnknownFields().asMap().isEmpty()) {
          throw invalidResponse("start_location contains unknown fields");
        }
        requireResponseUuid(
            location.getCanonicalTenantUuid(), "start_location.canonical_tenant_uuid");
        requireResponseUuid(
            location.getCanonicalVersionUuid(), "start_location.canonical_version_uuid");
        requireResponseUuid(location.getRoomTemplateUuid(), "start_location.room_template_uuid");
        if (!request
                .getExpectedTarget()
                .getCanonicalTenantUuid()
                .equals(location.getCanonicalTenantUuid())
            || !request
                .getExpectedTarget()
                .getCanonicalVersionUuid()
                .equals(location.getCanonicalVersionUuid())) {
          throw invalidResponse("APPLIED start location changed its tenant or version scope");
        }
        requirePositivePrivateRuntimeRoomKey(response.getPrivateRuntimeRoomKey());
      }
      case CANONICAL_INITIAL_PLAYER_LOCATION_OUTCOME_CONFLICT -> {
        if (!MACHINE_CODE.matcher(response.getConflictCode()).matches()
            || response.hasStartLocation()
            || !response.getPrivateRuntimeRoomKey().isEmpty()) {
          throw invalidResponse("CONFLICT response must contain a conflict code and no location");
        }
      }
      default -> throw invalidResponse("World returned an unsupported placement outcome");
    }
  }

  private void requireRegionResponse(
      CanonicalInitialPlayerLocationRequest request,
      CanonicalInitialPlayerLocationRegionResponse response) {
    if (response == null
        || response.getSerializedSize() > MAX_REGION_RESPONSE_BYTES
        || response.getSchemaVersion() != SCHEMA_VERSION
        || !response.getUnknownFields().asMap().isEmpty()
        || !response.hasPlacementResult()
        || !response.hasExpectedTarget()
        || !response.hasLifecycle()
        || !request.getExpectedTarget().equals(response.getExpectedTarget())
        || !response.getExpectedTarget().getUnknownFields().asMap().isEmpty()) {
      throw invalidResponse("Region response is not a bounded, closed echo of the selected target");
    }
    CanonicalInitialPlayerLocationResponse placement = response.getPlacementResult();
    requireResponse(request, placement);
    if (placement.getOutcome()
        != net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationOutcome
            .CANONICAL_INITIAL_PLAYER_LOCATION_OUTCOME_APPLIED) {
      throw invalidResponse("Region resolution requires the exact APPLIED placement result");
    }
    requireResponseUuid(
        response.getCanonicalRegionInstanceUuid(), "canonical_region_instance_uuid");
    requireResponseUuid(response.getOperationalRegionUuid(), "operational_region_uuid");
    if (response.getCanonicalRegionInstanceUuid().equals(response.getOperationalRegionUuid())
        || response.getPrivateWorldInstanceKey() <= 0L
        || response.getObservedRuntimeRoomKey() <= 0L) {
      throw invalidResponse("Region response has an invalid World or runtime room identity");
    }
    requirePositivePrivateRuntimeRoomKey(placement.getPrivateRuntimeRoomKey());
    if (Long.parseLong(placement.getPrivateRuntimeRoomKey())
        != response.getObservedRuntimeRoomKey()) {
      throw invalidResponse("Region response changed the retained initial runtime room");
    }

    var lifecycle = response.getLifecycle();
    if (!lifecycle.getUnknownFields().asMap().isEmpty()
        || lifecycle.getCanonicalRequestBytes().isEmpty()
        || lifecycle.getCanonicalRequestBytes().size() > MAX_LIFECYCLE_REQUEST_BYTES
        || !"ACTIVE".equals(lifecycle.getLifecycleStatus())
        || lifecycle.getLifecycleEpoch() != request.getActiveLifecycleEpoch()
        || lifecycle.getRowVersion() < 0L) {
      throw invalidResponse("Region lifecycle consequence is incomplete or stale");
    }
    requireResponseUuid(lifecycle.getCaptureUuid(), "lifecycle.capture_uuid");
    if (lifecycle.getGraphSha256() == null
        || !RAW_SHA256.matcher(lifecycle.getGraphSha256()).matches()) {
      throw invalidResponse("lifecycle.graph_sha256 is not raw lowercase SHA-256");
    }
    requireResponseDigest(
        lifecycle.getPreparationInputDigest(), "lifecycle.preparation_input_digest");
    WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest;
    try {
      lifecycleRequest =
          WorldCanonicalInstanceLifecycleEvidence.Request.fromStored(
              lifecycle.getCanonicalRequestBytes().toByteArray());
    } catch (IllegalArgumentException invalid) {
      throw invalidResponse(
          "lifecycle.canonical_request_bytes is not a closed canonical request", invalid);
    }
    var target = request.getExpectedTarget();
    if (!lifecycleRequest.canonicalTenantId().toString().equals(target.getCanonicalTenantUuid())
        || !namespace.equals(lifecycleRequest.targetNamespace())
        || !lifecycleRequest.readRequestId().equals(lifecycleReadRequestId(request))
        || !lifecycleRequest.canonicalGameInstanceId().toString().equals(target.getGameInstanceId())
        || !lifecycleRequest
            .playableStateNamespaceId()
            .toString()
            .equals(target.getPlayableStateNamespaceUuid())
        || !lifecycleRequest.playableStateScope().equals("SHARED")
        || !lifecycleRequest.worldSlug().equals(target.getWorldSlug())
        || !lifecycleRequest
            .canonicalVersionId()
            .toString()
            .equals(target.getCanonicalVersionUuid())
        || !lifecycleRequest.publicProduction()) {
      throw invalidResponse("lifecycle request differs from the echoed canonical placement target");
    }
  }

  private static void requirePositivePrivateRuntimeRoomKey(String value) {
    if (value == null || !POSITIVE_PRIVATE_RUNTIME_ROOM_KEY.matcher(value).matches()) {
      throw invalidResponse("private_runtime_room_key must be a positive private room key");
    }
    try {
      if (Long.parseLong(value) <= 0L) {
        throw invalidResponse("private_runtime_room_key must be a positive private room key");
      }
    } catch (NumberFormatException invalid) {
      throw invalidResponse(
          "private_runtime_room_key exceeds the supported positive long range", invalid);
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

  private static void requirePrefixedDigest(String value, String field) {
    if (value == null || !PREFIXED_SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be prefixed lowercase SHA-256");
    }
  }

  private static void requireResponseDigest(String value, String field) {
    if (value == null || !PREFIXED_SHA256.matcher(value).matches()) {
      throw invalidResponse(field + " is not prefixed lowercase SHA-256");
    }
  }

  private static void requireResponseRawDigest(String value, String field) {
    if (value == null || !RAW_SHA256.matcher(value).matches()) {
      throw invalidResponse(field + " is not raw lowercase SHA-256");
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

  private static void requireResponseUuid(String value, String field) {
    try {
      requireCanonicalNonNilUuid(value, field);
    } catch (IllegalArgumentException invalid) {
      throw invalidResponse(field + " is not a canonical non-nil UUID", invalid);
    }
  }

  private static UUID lifecycleReadRequestId(CanonicalInitialPlayerLocationRequest request) {
    byte[] name =
        ("firemud-world-initial-location-lifecycle-read/v1/" + request.getPlacementOperationUuid())
            .getBytes(StandardCharsets.US_ASCII);
    return UUID.nameUUIDFromBytes(name);
  }

  private static IllegalStateException invalidResponse(String message) {
    return new IllegalStateException(
        "World initial player location response is invalid: " + message);
  }

  private static IllegalStateException invalidResponse(String message, Throwable cause) {
    return new IllegalStateException(
        "World initial player location response is invalid: " + message, cause);
  }
}
