package net.firedevops.firemud.entitymanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSelectedAssignmentReadRequest;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSelectedAssignmentReference;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSelectedAssignmentService;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterService;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSnapshot;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSelectedAssignmentRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSelectedAssignmentResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget.Builder;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
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
    try {
      requireTrustedPeer();
      CanonicalGameplayRosterReadRequest parsed = parseRequest(request);
      CanonicalGameplayRosterSnapshot snapshot = rosterService.read(parsed);
      responseObserver.onNext(toResponse(snapshot));
    } catch (AdminAuthorizationException denied) {
      responseObserver.onNext(error("PERMISSION_DENIED", "Trusted Game Session caller required"));
    } catch (UnsupportedOperationException unsupported) {
      responseObserver.onNext(error("UNSUPPORTED_ENTRY_POLICY", "Entry policy is unsupported"));
    } catch (
        net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterOwnerEvidencePort
                .OwnerEvidenceUnavailableException
            unavailable) {
      responseObserver.onNext(error("OWNER_EVIDENCE_UNAVAILABLE", "Owner evidence is unavailable"));
    } catch (IllegalArgumentException malformed) {
      responseObserver.onNext(error("INVALID_ARGUMENT", malformed.getMessage()));
    } catch (IllegalStateException rejected) {
      responseObserver.onNext(error("FAILED_PRECONDITION", rejected.getMessage()));
    } catch (Exception failure) {
      responseObserver.onNext(
          CanonicalGameplayRosterResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, LOGGER, OPERATION, failure))
              .build());
    }
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "entityGrpc.canonicalGameplayRoster.readSelectedPreseededAssignment")
  public void readSelectedPreseededAssignment(
      CanonicalGameplayRosterSelectedAssignmentRequest request,
      StreamObserver<CanonicalGameplayRosterSelectedAssignmentResponse> responseObserver) {
    try {
      requireTrustedPeer();
      CanonicalGameplayRosterSelectedAssignmentReadRequest parsed =
          parseSelectedAssignmentRequest(request);
      CanonicalGameplayRosterSelectedAssignmentReference reference =
          selectedAssignmentService.read(parsed);
      responseObserver.onNext(toSelectedAssignmentResponse(reference));
    } catch (AdminAuthorizationException denied) {
      responseObserver.onNext(
          selectedAssignmentError("PERMISSION_DENIED", "Trusted Game Session caller required"));
    } catch (UnsupportedOperationException unsupported) {
      responseObserver.onNext(
          selectedAssignmentError("UNSUPPORTED_TARGET", "Target is unsupported"));
    } catch (
        net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterOwnerEvidencePort
                .OwnerEvidenceUnavailableException
            unavailable) {
      responseObserver.onNext(
          selectedAssignmentError("OWNER_EVIDENCE_UNAVAILABLE", "Owner evidence is unavailable"));
    } catch (IllegalArgumentException malformed) {
      responseObserver.onNext(selectedAssignmentError("INVALID_ARGUMENT", malformed.getMessage()));
    } catch (IllegalStateException rejected) {
      responseObserver.onNext(
          selectedAssignmentError("FAILED_PRECONDITION", rejected.getMessage()));
    } catch (Exception failure) {
      responseObserver.onNext(
          CanonicalGameplayRosterSelectedAssignmentResponse.newBuilder()
              .setError(
                  GrpcAppErrors.internal(
                      meterRegistry, LOGGER, SELECTED_ASSIGNMENT_OPERATION, failure))
              .build());
    }
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
    CanonicalGameplayRosterReadRequest parsedTarget =
        parseRequest(
            CanonicalGameplayRosterRequest.newBuilder()
                .setRequestUuid(request.getRequestUuid())
                .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
                .setExpectedTarget(request.getExpectedTarget())
                .build());
    return new CanonicalGameplayRosterSelectedAssignmentReadRequest(
        parsedTarget.requestUuid(),
        parsedTarget.canonicalAccountUuid(),
        parseCanonicalUuid(request.getSelectedCharacterUuid(), "selected_character_uuid"),
        parsedTarget.expectedTarget());
  }

  static CanonicalGameplayRosterReadRequest parseRequest(CanonicalGameplayRosterRequest request) {
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
    return new CanonicalGameplayRosterReadRequest(requestUuid, accountUuid, expectedTarget);
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
    if (CanonicalGameplayRosterPeerInterceptor.currentVerifiedPeer() == null
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw new AdminAuthorizationException("Trusted Game Session certificate peer is required");
    }
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

  private CanonicalGameplayRosterResponse error(String code, String message) {
    return CanonicalGameplayRosterResponse.newBuilder()
        .setError(GrpcAppErrors.error(meterRegistry, LOGGER, OPERATION, code, message))
        .build();
  }

  private CanonicalGameplayRosterSelectedAssignmentResponse selectedAssignmentError(
      String code, String message) {
    return CanonicalGameplayRosterSelectedAssignmentResponse.newBuilder()
        .setError(
            GrpcAppErrors.error(
                meterRegistry, LOGGER, SELECTED_ASSIGNMENT_OPERATION, code, message))
        .build();
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
        .build();
  }
}
