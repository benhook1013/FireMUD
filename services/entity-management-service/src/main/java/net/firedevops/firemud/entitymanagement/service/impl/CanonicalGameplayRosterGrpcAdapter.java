package net.firedevops.firemud.entitymanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.security.CanonicalGameplayRosterPeerInterceptor;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterActor;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterExecutionContext;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterOwnerEvidencePort;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSelectedAssignmentReadRequest;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSelectedAssignmentReference;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSelectedAssignmentService;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterService;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSnapshot;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSnapshotReference;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSelectedAssignmentRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSelectedAssignmentResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget.Builder;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

/** Dedicated certificate-guarded non-admitting canonical roster gRPC boundary. */
@GrpcService
public final class CanonicalGameplayRosterGrpcAdapter
    extends CanonicalGameplayRosterServiceGrpc.CanonicalGameplayRosterServiceImplBase {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(CanonicalGameplayRosterGrpcAdapter.class);
  private static final String OPERATION = "CanonicalGameplayRoster.ListPreseededRoster";
  private static final String SELECTED_ASSIGNMENT_OPERATION =
      "CanonicalGameplayRoster.ReadSelectedPreseededAssignment";

  private final CanonicalGameplayRosterService rosterService;
  private final CanonicalGameplayRosterSelectedAssignmentService selectedAssignmentService;
  private final MeterRegistry meterRegistry;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected services and meter registry remain internal to the gRPC adapter.")
  public CanonicalGameplayRosterGrpcAdapter(
      CanonicalGameplayRosterService rosterService,
      CanonicalGameplayRosterSelectedAssignmentService selectedAssignmentService,
      MeterRegistry meterRegistry) {
    this.rosterService = rosterService;
    this.selectedAssignmentService = selectedAssignmentService;
    this.meterRegistry = meterRegistry;
  }

  @Override
  @Timed(value = "entityGrpc.canonicalGameplayRoster.listPreseeded")
  public void listPreseededRoster(
      CanonicalGameplayRosterRequest request,
      StreamObserver<CanonicalGameplayRosterResponse> responseObserver) {
    CanonicalGameplayRosterResponse response;
    try {
      requireTrustedPeer();
      CanonicalGameplayRosterReadRequest parsed = parseRequest(request);
      CanonicalGameplayRosterSnapshot snapshot = rosterService.read(parsed);
      response = toResponse(snapshot);
    } catch (Exception failure) {
      responseObserver.onError(toStatus(failure, OPERATION));
      return;
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "entityGrpc.canonicalGameplayRoster.readSelectedPreseededAssignment")
  public void readSelectedPreseededAssignment(
      CanonicalGameplayRosterSelectedAssignmentRequest request,
      StreamObserver<CanonicalGameplayRosterSelectedAssignmentResponse> responseObserver) {
    CanonicalGameplayRosterSelectedAssignmentResponse response;
    try {
      requireTrustedPeer();
      CanonicalGameplayRosterSelectedAssignmentReadRequest parsed =
          parseSelectedAssignmentRequest(request);
      CanonicalGameplayRosterSelectedAssignmentReference reference =
          selectedAssignmentService.read(parsed);
      response = toSelectedAssignmentResponse(reference);
    } catch (Exception failure) {
      responseObserver.onError(toStatus(failure, SELECTED_ASSIGNMENT_OPERATION));
      return;
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  static CanonicalGameplayRosterSelectedAssignmentReadRequest parseSelectedAssignmentRequest(
      CanonicalGameplayRosterSelectedAssignmentRequest request) {
    if (request == null || !request.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Request is absent or contains unknown fields");
    }
    if (!request.hasExpectedTarget()) {
      throw new IllegalArgumentException("Complete expected target is required");
    }
    if (!request.hasExpectedSnapshot()
        || !request.getExpectedSnapshot().getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Exact expected_snapshot is required");
    }
    UUID selectedCharacterUuid =
        parseCanonicalUuid(request.getSelectedCharacterUuid(), "selected_character_uuid");
    CanonicalGameplayRosterReadRequest parsedTarget =
        parseRequest(
            CanonicalGameplayRosterRequest.newBuilder()
                .setRequestUuid(request.getRequestUuid())
                .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
                .setExpectedTarget(request.getExpectedTarget())
                .setPlayerExecutionContext(request.getPlayerExecutionContext())
                .build(),
            true);
    return new CanonicalGameplayRosterSelectedAssignmentReadRequest(
        parsedTarget.requestUuid(),
        parsedTarget.canonicalAccountUuid(),
        selectedCharacterUuid,
        parsedTarget.expectedTarget(),
        parseSnapshotReference(request.getExpectedSnapshot()),
        parsedTarget.playerExecutionContext());
  }

  static CanonicalGameplayRosterReadRequest parseRequest(CanonicalGameplayRosterRequest request) {
    return parseRequest(request, false);
  }

  private static CanonicalGameplayRosterReadRequest parseRequest(
      CanonicalGameplayRosterRequest request, boolean selectedAssignment) {
    if (request == null || !request.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Request is absent or contains unknown fields");
    }
    if (!request.hasExpectedTarget()
        || !request.getExpectedTarget().getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Complete expected target is required");
    }
    var target = request.getExpectedTarget();
    UUID requestUuid = parseCanonicalUuid(request.getRequestUuid(), "request_uuid");
    UUID accountUuid =
        parseCanonicalUuid(request.getCanonicalAccountUuid(), "canonical_account_uuid");
    if (!request.hasPlayerExecutionContext()) {
      throw new IllegalArgumentException("player_execution_context is required");
    }
    CanonicalGameplayRosterExecutionContext executionContext =
        parseExecutionContext(request.getPlayerExecutionContext());
    if (!selectedAssignment && executionContext.characterUuid() != null) {
      throw new IllegalArgumentException("character_id must be unset for roster discovery");
    }
    PlayableStateScope scope = target.getPlayableStateScope();
    if (scope == PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED
        || scope == PlayableStateScope.UNRECOGNIZED) {
      throw new IllegalArgumentException("playable_state_scope is required");
    }
    CanonicalGameplayRosterEntryPolicy entryPolicy =
        switch (target.getEntryPolicy()) {
          case CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED ->
              CanonicalGameplayRosterEntryPolicy.PLAYER_CREATED;
          case CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY ->
              CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY;
          case CANONICAL_ROSTER_ENTRY_POLICY_AUTO_PROVISIONED ->
              CanonicalGameplayRosterEntryPolicy.AUTO_PROVISIONED;
          case CANONICAL_ROSTER_ENTRY_POLICY_UNSPECIFIED, UNRECOGNIZED ->
              CanonicalGameplayRosterEntryPolicy.UNSPECIFIED;
        };
    CanonicalGameplayRosterTarget expectedTarget =
        new CanonicalGameplayRosterTarget(
            parseCanonicalUuid(target.getTenantUuid(), "tenant_uuid"),
            parseCanonicalUuid(target.getRealmUuid(), "realm_uuid"),
            target.getWorldSlug(),
            target.getRealmSlug(),
            parseCanonicalUuid(target.getGameInstanceUuid(), "game_instance_uuid"),
            target.getCatalogRevision(),
            target.getPointerVersion(),
            target.getActiveWorldEpoch(),
            parseCanonicalUuid(target.getCanonicalVersionUuid(), "canonical_version_uuid"),
            target.getPublishedPolicyDigest(),
            target.getPublishedReleaseBundleRef(),
            target.getAdmissionPointerSnapshotDigest(),
            target.getPublishedOwnerProofDigest(),
            parseCanonicalUuid(
                target.getPlayableStateNamespaceUuid(), "playable_state_namespace_uuid"),
            scope,
            entryPolicy);
    executionContext.requireTargetBinding(requestUuid, accountUuid, expectedTarget);
    return new CanonicalGameplayRosterReadRequest(
        requestUuid, accountUuid, expectedTarget, executionContext);
  }

  private static CanonicalGameplayRosterExecutionContext parseExecutionContext(
      PlayerExecutionContext context) {
    if (context == null || !context.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(
          "player_execution_context is absent or contains unknown fields");
    }
    PlayableStateScope scope =
        switch (context.getPlayableStateScope()) {
          case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
          case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
          default ->
              throw new IllegalArgumentException(
                  "player_execution_context.playable_state_scope must be SHARED or ISOLATED");
        };
    UUID characterUuid =
        context.getCharacterId().isEmpty()
            ? null
            : parseCanonicalUuid(context.getCharacterId(), "player_execution_context.character_id");
    return new CanonicalGameplayRosterExecutionContext(
        parseCanonicalUuid(context.getAccountId(), "player_execution_context.account_id"),
        parseCanonicalUuid(context.getTenantId(), "player_execution_context.tenant_id"),
        parseCanonicalUuid(
            context.getPlayableStateNamespaceId(),
            "player_execution_context.playable_state_namespace_id"),
        parseCanonicalUuid(
            context.getGameInstanceId(), "player_execution_context.game_instance_id"),
        characterUuid,
        parseCanonicalUuid(context.getSessionId(), "player_execution_context.session_id"),
        parseCanonicalUuid(context.getRealmId(), "player_execution_context.realm_id"),
        parseCanonicalUuid(context.getRequestId(), "player_execution_context.request_id"),
        scope);
  }

  private static CanonicalGameplayRosterSnapshotReference parseSnapshotReference(
      net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSnapshotReference ref) {
    return new CanonicalGameplayRosterSnapshotReference(
        parseCanonicalUuid(ref.getSnapshotUuid(), "expected_snapshot.snapshot_uuid"),
        ref.getSnapshotDigest());
  }

  private static UUID parseCanonicalUuid(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(fieldName + " must be a canonical non-nil UUID", invalid);
    }
    if (parsed.equals(new UUID(0L, 0L)) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException(fieldName + " must be a canonical non-nil UUID");
    }
    return parsed;
  }

  private void requireTrustedPeer() {
    if (CanonicalGameplayRosterPeerInterceptor.currentVerifiedPeer() == null) {
      throw new UnauthenticatedPeerException();
    }
    if (SessionContext.hasAuthenticatedCallerContext()) {
      throw new AdminAuthorizationException("Trusted Game Session certificate peer is required");
    }
  }

  private RuntimeException toStatus(Exception failure, String operation) {
    if (failure instanceof UnauthenticatedPeerException) {
      return Status.UNAUTHENTICATED
          .withDescription("Authenticated Game Session peer required")
          .asRuntimeException();
    }
    if (failure instanceof AdminAuthorizationException) {
      return Status.PERMISSION_DENIED
          .withDescription("Trusted Game Session caller required")
          .asRuntimeException();
    }
    if (failure
            instanceof CanonicalGameplayRosterOwnerEvidencePort.OwnerEvidenceUnavailableException
        || failure instanceof DataAccessException) {
      return Status.UNAVAILABLE
          .withDescription("Owner evidence is unavailable")
          .asRuntimeException();
    }
    if (failure instanceof UnsupportedOperationException) {
      return Status.FAILED_PRECONDITION
          .withDescription("Target policy or scope is unsupported")
          .asRuntimeException();
    }
    if (failure instanceof IllegalArgumentException) {
      return Status.INVALID_ARGUMENT
          .withDescription("Request is malformed or inconsistent")
          .asRuntimeException();
    }
    if (failure instanceof IllegalStateException) {
      return Status.FAILED_PRECONDITION
          .withDescription("Request precondition is not satisfied")
          .asRuntimeException();
    }
    GrpcAppErrors.internal(meterRegistry, LOGGER, operation, failure);
    return Status.INTERNAL.withDescription("Internal error").asRuntimeException();
  }

  private CanonicalGameplayRosterResponse toResponse(CanonicalGameplayRosterSnapshot snapshot) {
    CanonicalGameplayRosterResponse.Builder response =
        CanonicalGameplayRosterResponse.newBuilder()
            .setCanonicalAccountUuid(snapshot.canonicalAccountUuid().toString())
            .setTarget(toProto(snapshot.target()))
            .setSnapshotUuid(snapshot.snapshotUuid().toString())
            .setSnapshotDigest(snapshot.snapshotDigest());
    for (CanonicalGameplayRosterActor actor : snapshot.actors()) {
      response.addActors(
          net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor.newBuilder()
              .setCharacterUuid(actor.characterUuid().toString())
              .setDisplayName(actor.displayName())
              .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER));
    }
    return response.build();
  }

  static net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget toProto(
      CanonicalGameplayRosterTarget target) {
    Builder proto =
        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget.newBuilder()
            .setTenantUuid(target.tenantUuid().toString())
            .setRealmUuid(target.realmUuid().toString())
            .setWorldSlug(target.worldSlug())
            .setRealmSlug(target.realmSlug())
            .setGameInstanceUuid(target.gameInstanceUuid().toString())
            .setCatalogRevision(target.catalogRevision())
            .setCanonicalVersionUuid(target.canonicalVersionUuid().toString())
            .setPointerVersion(target.pointerVersion())
            .setActiveWorldEpoch(target.activeWorldEpoch())
            .setPublishedPolicyDigest(target.publishedPolicyDigest())
            .setPublishedReleaseBundleRef(target.publishedReleaseBundleRef())
            .setAdmissionPointerSnapshotDigest(target.admissionPointerSnapshotDigest())
            .setPublishedOwnerProofDigest(target.publishedOwnerProofDigest())
            .setPlayableStateNamespaceUuid(target.playableStateNamespaceId().toString())
            .setPlayableStateScope(target.playableStateScope());
    switch (target.entryPolicy()) {
      case PRESEEDED_ONLY ->
          proto.setEntryPolicy(
              net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy
                  .CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY);
      case PLAYER_CREATED ->
          proto.setEntryPolicy(
              net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy
                  .CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED);
      case AUTO_PROVISIONED ->
          proto.setEntryPolicy(
              net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy
                  .CANONICAL_ROSTER_ENTRY_POLICY_AUTO_PROVISIONED);
      case UNSPECIFIED ->
          proto.setEntryPolicy(
              net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy
                  .CANONICAL_ROSTER_ENTRY_POLICY_UNSPECIFIED);
    }
    return proto.build();
  }

  static CanonicalGameplayRosterSelectedAssignmentResponse toSelectedAssignmentResponse(
      CanonicalGameplayRosterSelectedAssignmentReference reference) {
    return CanonicalGameplayRosterSelectedAssignmentResponse.newBuilder()
        .setRequestUuid(reference.requestUuid().toString())
        .setCanonicalAccountUuid(reference.canonicalAccountUuid().toString())
        .setSelectedCharacterUuid(reference.selectedCharacterUuid().toString())
        .setTarget(toProto(reference.target()))
        .setAssignmentUuid(reference.assignmentUuid().toString())
        .setIntentDigest(reference.intentDigest())
        .setSnapshot(
            net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSnapshotReference
                .newBuilder()
                .setSnapshotUuid(reference.snapshot().snapshotUuid().toString())
                .setSnapshotDigest(reference.snapshot().snapshotDigest())
                .build())
        .build();
  }

  private static final class UnauthenticatedPeerException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
