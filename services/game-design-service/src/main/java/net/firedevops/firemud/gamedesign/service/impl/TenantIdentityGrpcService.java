package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.GameSessionTenantAssociationEvidence;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameSessionTenantAssociationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.v1.GameSessionTenantAssociationManifestEvidence;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesRequest;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedRealmEntryPolicyKind;
import net.firedevops.firemud.gamedesign.v1.PublishedRealmEntryStateScope;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameSessionTenantAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameSessionTenantAssociationResponse;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyRequest;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.jooq.exception.TooManyRowsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.grpc.server.service.GrpcService;
import tools.jackson.databind.ObjectMapper;

/** Authenticated read of Game Design's own retained tenant identity provenance. */
@GrpcService
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "The injected Jackson 3 ObjectMapper is an immutable configured collaborator.")
public class TenantIdentityGrpcService
    extends TenantIdentityServiceGrpc.TenantIdentityServiceImplBase {
  private final GameRepository gameRepository;
  private final GameSessionTenantAssociationRepository gameSessionAssociationRepository;
  private final PublishedReleaseBundleService publishedReleaseBundleService;
  private final String workloadNamespace;
  private final ObjectMapper objectMapper;

  public TenantIdentityGrpcService(
      GameRepository gameRepository,
      GameSessionTenantAssociationRepository gameSessionAssociationRepository,
      PublishedReleaseBundleService publishedReleaseBundleService,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace,
      ObjectMapper objectMapper) {
    this.gameRepository = gameRepository;
    this.gameSessionAssociationRepository = gameSessionAssociationRepository;
    this.publishedReleaseBundleService = publishedReleaseBundleService;
    this.workloadNamespace = workloadNamespace;
    this.objectMapper = objectMapper;
  }

  @Override
  public void resolveRuntimeTenantIdentity(
      ResolveRuntimeTenantIdentityRequest request,
      StreamObserver<ResolveRuntimeTenantIdentityResponse> responseObserver) {
    if (!isGameSessionPeer()) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Verified Game Session workload identity is required")
              .asRuntimeException());
      return;
    }

    UUID canonicalTenantId = parseCanonicalNonNilUuid(request.getCanonicalTenantId());
    UUID requestId = parseCanonicalNonNilUuid(request.getRequestId());
    if (canonicalTenantId == null
        || requestId == null
        || !request.getUnknownFields().asMap().isEmpty()) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription(
                  "Canonical nonnil IDs and the exact metadata request schema are required")
              .asRuntimeException());
      return;
    }

    Optional<GameTenantIdentity> resolved;
    try {
      resolved = gameRepository.findRuntimeTenantIdentityByCanonicalTenantId(canonicalTenantId);
    } catch (IllegalStateException | TooManyRowsException ex) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Game Design tenant identity provenance is ambiguous or invalid")
              .asRuntimeException());
      return;
    } catch (DataAccessResourceFailureException | TransientDataAccessException ex) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Game Design tenant identity is temporarily unavailable")
              .asRuntimeException());
      return;
    } catch (DataAccessException ex) {
      Status.Code code =
          hasConnectionFailureSqlState(ex) ? Status.Code.UNAVAILABLE : Status.Code.INTERNAL;
      responseObserver.onError(
          Status.fromCode(code)
              .withDescription(
                  code == Status.Code.UNAVAILABLE
                      ? "Game Design tenant identity is temporarily unavailable"
                      : "Game Design tenant identity could not be read")
              .asRuntimeException());
      return;
    } catch (RuntimeException ex) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Game Design tenant identity could not be read")
              .asRuntimeException());
      return;
    }
    if (resolved.isEmpty()) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription("No Game Design tenant identity for exact canonical tenant ID")
              .asRuntimeException());
      return;
    }

    GameTenantIdentity identity = resolved.orElseThrow();
    RuntimeTenantIdentityEvidence evidence;
    try {
      if (!canonicalTenantId.equals(identity.canonicalTenantId())) {
        throw new IllegalArgumentException("Resolved tenant UUID does not match the request");
      }
      evidence =
          new RuntimeTenantIdentityEvidence(
              1,
              workloadNamespace,
              requestId,
              identity.canonicalTenantId(),
              identity.sourceGameId() == null ? 0L : identity.sourceGameId(),
              identity.sourceLegacyTenantId(),
              identity.provenanceKind() == null ? null : identity.provenanceKind().name());
    } catch (RuntimeException ex) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Game Design tenant identity readback is incomplete or inconsistent")
              .asRuntimeException());
      return;
    }

    responseObserver.onNext(
        ResolveRuntimeTenantIdentityResponse.newBuilder()
            .setSchemaVersion(evidence.schemaVersion())
            .setTargetNamespace(evidence.targetNamespace())
            .setRequestId(evidence.requestId().toString())
            .setCanonicalTenantId(evidence.canonicalTenantId().toString())
            .setSourceGameRowId(evidence.sourceGameRowId())
            .setSourceGameTenantKey(evidence.sourceGameTenantKey())
            .setProvenanceKind(evidence.provenanceKind())
            .build());
    responseObserver.onCompleted();
  }

  @Override
  public void resolveLegacyGameSessionTenantAssociation(
      ResolveLegacyGameSessionTenantAssociationRequest request,
      StreamObserver<ResolveLegacyGameSessionTenantAssociationResponse> responseObserver) {
    if (!isGameSessionPeer()) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Verified Game Session workload identity is required")
              .asRuntimeException());
      return;
    }
    UUID requestId = parseCanonicalNonNilUuid(request.getRequestId());
    UUID operationId = parseCanonicalNonNilUuid(request.getOperationId());
    UUID tenantId = parseCanonicalNonNilUuid(request.getCanonicalTenantId());
    long legacyTenantId;
    try {
      if (requestId == null
          || operationId == null
          || tenantId == null
          || !request.getUnknownFields().asMap().isEmpty()
          || !request.getLegacyGameSessionTenantId().matches("[1-9][0-9]*")) {
        throw new IllegalArgumentException("Exact association request is required");
      }
      legacyTenantId = Long.parseLong(request.getLegacyGameSessionTenantId());
    } catch (IllegalArgumentException ex) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical exact association request is required")
              .asRuntimeException());
      return;
    }
    Optional<GameSessionTenantAssociationRepository.AssociationReceipt> resolved;
    try {
      resolved =
          gameSessionAssociationRepository.read(
              operationId, tenantId, legacyTenantId, workloadNamespace);
    } catch (IllegalArgumentException | IllegalStateException | TooManyRowsException ex) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Retained Game Session association evidence is inconsistent")
              .asRuntimeException());
      return;
    } catch (DataAccessResourceFailureException | TransientDataAccessException ex) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Retained Game Session association could not be read")
              .asRuntimeException());
      return;
    } catch (DataAccessException ex) {
      Status.Code code =
          hasConnectionFailureSqlState(ex) ? Status.Code.UNAVAILABLE : Status.Code.INTERNAL;
      responseObserver.onError(
          Status.fromCode(code)
              .withDescription(
                  code == Status.Code.UNAVAILABLE
                      ? "Retained Game Session association could not be read"
                      : "Retained Game Session association evidence could not be read")
              .asRuntimeException());
      return;
    } catch (RuntimeException ex) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Retained Game Session association evidence could not be read")
              .asRuntimeException());
      return;
    }
    if (resolved.isEmpty()) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription("No approved retained Game Session association for the exact scope")
              .asRuntimeException());
      return;
    }
    var receipt = resolved.orElseThrow();
    GameSessionTenantAssociationEvidence evidence = receipt.manifest();
    if (!operationId.equals(evidence.operationId())
        || !tenantId.equals(evidence.canonicalTenantId())
        || !workloadNamespace.equals(evidence.targetNamespace())
        || !request.getLegacyGameSessionTenantId().equals(evidence.legacyGameSessionTenantId())
        || !evidence.manifestDigest().equals(receipt.manifestDigest())
        || !validSignature(receipt.ed25519Signature())) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Retained association readback does not match the exact request")
              .asRuntimeException());
      return;
    }
    responseObserver.onNext(
        ResolveLegacyGameSessionTenantAssociationResponse.newBuilder()
            .setRequestId(requestId.toString())
            .setManifest(
                GameSessionTenantAssociationManifestEvidence.newBuilder()
                    .setSchemaVersion(evidence.schemaVersion())
                    .setOperationId(evidence.operationId().toString())
                    .setTargetNamespace(evidence.targetNamespace())
                    .setSignerKeyId(evidence.signerKeyId())
                    .setApprovedBy(evidence.approvedBy())
                    .setApprovalReference(evidence.approvalReference())
                    .setSignedAt(evidence.signedAt())
                    .setSourceCapturedAt(evidence.sourceCapturedAt())
                    .setLegacyGameSessionTenantId(evidence.legacyGameSessionTenantId())
                    .setCanonicalTenantId(evidence.canonicalTenantId().toString())
                    .setSourceGameRowId(evidence.sourceGameRowId())
                    .setSourceGameTenantKey(evidence.sourceGameTenantKey())
                    .setProvenanceKind(evidence.provenanceKind())
                    .setGameSessionEvidenceDigest(evidence.gameSessionEvidenceDigest())
                    .setGameSessionProjectionDigest(evidence.gameSessionProjectionDigest()))
            .setManifestDigest(receipt.manifestDigest())
            .setEd25519Signature(receipt.ed25519Signature())
            .build());
    responseObserver.onCompleted();
  }

  @Override
  public void resolvePublishedRealmEntryPolicy(
      ResolvePublishedRealmEntryPolicyRequest request,
      StreamObserver<ResolvePublishedRealmEntryPolicyResponse> responseObserver) {
    if (!isGameSessionPeer()) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Verified same-namespace Game Session workload identity is required")
              .asRuntimeException());
      return;
    }

    UUID canonicalTenantId = parseCanonicalNonNilUuid(request.getCanonicalTenantId());
    if (canonicalTenantId == null
        || request.getVersionId() <= 0
        || !RealmEntryPolicy.isCanonicalSlug(request.getWorldSlug())
        || !RealmEntryPolicy.isCanonicalSlug(request.getRealmSlug())
        || !request.getUnknownFields().asMap().isEmpty()) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription(
                  "Canonical tenant UUID, positive version, exact slugs, and the v1 request schema are required")
              .asRuntimeException());
      return;
    }

    PublishedRealmEntryPolicyEvidence evidence;
    try {
      evidence =
          publishedReleaseBundleService.resolvePublishedRealmEntryPolicy(
              canonicalTenantId,
              request.getVersionId(),
              request.getWorldSlug(),
              request.getRealmSlug());
    } catch (PublishedRealmEntryPolicyNotFoundException ex) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription("Published realm-entry policy is unavailable for the exact scope")
              .asRuntimeException());
      return;
    } catch (IllegalArgumentException | IllegalStateException ex) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Published realm-entry policy evidence is inconsistent")
              .asRuntimeException());
      return;
    } catch (DataAccessResourceFailureException | TransientDataAccessException ex) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Published realm-entry policy is temporarily unavailable")
              .asRuntimeException());
      return;
    } catch (DataAccessException ex) {
      Status.Code code =
          hasConnectionFailureSqlState(ex) ? Status.Code.UNAVAILABLE : Status.Code.INTERNAL;
      responseObserver.onError(
          Status.fromCode(code)
              .withDescription(
                  code == Status.Code.UNAVAILABLE
                      ? "Published realm-entry policy is temporarily unavailable"
                      : "Published realm-entry policy could not be read")
              .asRuntimeException());
      return;
    } catch (RuntimeException ex) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Published realm-entry policy could not be read")
              .asRuntimeException());
      return;
    }

    if (evidence == null
        || !evidence.canonicalTenantId().equals(canonicalTenantId)
        || evidence.versionId() != request.getVersionId()
        || !evidence.policy().worldSlug().equals(request.getWorldSlug())
        || !evidence.policy().realmSlug().equals(request.getRealmSlug())) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Published realm-entry policy readback does not match the request")
              .asRuntimeException());
      return;
    }
    try {
      evidence.requireValidDigest(objectMapper);
    } catch (IllegalArgumentException ex) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription(
                  "Published realm-entry policy evidence failed canonical verification")
              .asRuntimeException());
      return;
    }
    responseObserver.onNext(toPolicyResponse(evidence));
    responseObserver.onCompleted();
  }

  @Override
  public void listPublishedRealmEntryPolicies(
      ListPublishedRealmEntryPoliciesRequest request,
      StreamObserver<ListPublishedRealmEntryPoliciesResponse> responseObserver) {
    if (!isGameSessionPeer()) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Verified same-namespace Game Session workload identity is required")
              .asRuntimeException());
      return;
    }

    UUID canonicalTenantId = parseCanonicalNonNilUuid(request.getCanonicalTenantId());
    if (canonicalTenantId == null
        || request.getVersionId() <= 0
        || !request.getUnknownFields().asMap().isEmpty()) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription(
                  "Canonical tenant UUID, positive version, and the v1 request schema are required")
              .asRuntimeException());
      return;
    }

    PublishedRealmEntryPolicySetEvidence evidence;
    try {
      evidence =
          publishedReleaseBundleService.listPublishedRealmEntryPolicies(
              canonicalTenantId, request.getVersionId());
    } catch (PublishedRealmEntryPolicyNotFoundException ex) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription(
                  "Complete published realm-entry policy set is unavailable for the exact scope")
              .asRuntimeException());
      return;
    } catch (IllegalArgumentException | IllegalStateException ex) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription(
                  "Complete published realm-entry policy set is inconsistent or unsupported")
              .asRuntimeException());
      return;
    } catch (DataAccessResourceFailureException | TransientDataAccessException ex) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Published realm-entry policy set is temporarily unavailable")
              .asRuntimeException());
      return;
    } catch (DataAccessException ex) {
      Status.Code code =
          hasConnectionFailureSqlState(ex) ? Status.Code.UNAVAILABLE : Status.Code.INTERNAL;
      responseObserver.onError(
          Status.fromCode(code)
              .withDescription(
                  code == Status.Code.UNAVAILABLE
                      ? "Published realm-entry policy set is temporarily unavailable"
                      : "Published realm-entry policy set could not be read")
              .asRuntimeException());
      return;
    } catch (RuntimeException ex) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Published realm-entry policy set could not be read")
              .asRuntimeException());
      return;
    }

    if (evidence == null
        || !evidence.canonicalTenantId().equals(canonicalTenantId)
        || evidence.versionId() != request.getVersionId()
        || evidence.policies().isEmpty()
        || evidence.policies().size() > PublishedRealmEntryPolicySetEvidence.MAX_POLICIES) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription(
                  "Complete published realm-entry policy set does not match the request")
              .asRuntimeException());
      return;
    }
    try {
      evidence.requireValidDigest(objectMapper);
    } catch (IllegalArgumentException ex) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription(
                  "Complete published realm-entry policy set failed canonical verification")
              .asRuntimeException());
      return;
    }

    ListPublishedRealmEntryPoliciesResponse.Builder response =
        ListPublishedRealmEntryPoliciesResponse.newBuilder()
            .setSchemaVersion(RealmEntryPolicy.SCHEMA_VERSION)
            .setTargetNamespace(workloadNamespace)
            .setCanonicalTenantId(evidence.canonicalTenantId().toString())
            .setVersionId(evidence.versionId())
            .setVersionNumber(evidence.versionNumber())
            .setReleaseBundleIdentity(evidence.releaseBundleIdentity())
            .setPublishWorkflowId(evidence.publishWorkflowId())
            .setManifestHash(evidence.manifestHash())
            .setPolicyCount(evidence.policies().size())
            .setPolicySetDigest(evidence.policySetDigest());
    evidence.policies().stream().map(this::toPolicyResponse).forEach(response::addPolicies);
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  private ResolvePublishedRealmEntryPolicyResponse toPolicyResponse(
      PublishedRealmEntryPolicyEvidence evidence) {
    RealmEntryPolicy policy = evidence.policy();
    return ResolvePublishedRealmEntryPolicyResponse.newBuilder()
        .setSchemaVersion(RealmEntryPolicy.SCHEMA_VERSION)
        .setTargetNamespace(workloadNamespace)
        .setCanonicalTenantId(evidence.canonicalTenantId().toString())
        .setVersionId(evidence.versionId())
        .setVersionNumber(evidence.versionNumber())
        .setPolicyId(evidence.policyId().toString())
        .setSourceRevisionId(evidence.sourceRevisionId())
        .setSourceGameRowId(evidence.sourceGameRowId())
        .setSourceGameTenantKey(evidence.sourceGameTenantKey())
        .setTenantIdentityProvenanceKind(evidence.tenantIdentityProvenanceKind())
        .setReleaseBundleIdentity(evidence.releaseBundleIdentity())
        .setPublishWorkflowId(evidence.publishWorkflowId())
        .setManifestHash(evidence.manifestHash())
        .setWorldSlug(policy.worldSlug())
        .setWorldDisplayName(policy.worldDisplayName())
        .setRealmSlug(policy.realmSlug())
        .setRealmDisplayName(policy.realmDisplayName())
        .setVisible(policy.visible())
        .setPublicProduction(policy.publicProduction())
        .setStateScope(
            switch (policy.stateScope()) {
              case SHARED -> PublishedRealmEntryStateScope.PUBLISHED_REALM_ENTRY_STATE_SCOPE_SHARED;
              case ISOLATED ->
                  PublishedRealmEntryStateScope.PUBLISHED_REALM_ENTRY_STATE_SCOPE_ISOLATED;
            })
        .setEntryPolicy(
            PublishedRealmEntryPolicyKind.PUBLISHED_REALM_ENTRY_POLICY_KIND_PRESEEDED_ONLY)
        .setPolicyJson(policy.canonicalJson())
        .setPolicyDigest(evidence.policyDigest())
        .build();
  }

  private boolean isGameSessionPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    return peer != null
        && GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        && peer.uri()
            .equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service");
  }

  private static UUID parseCanonicalNonNilUuid(String value) {
    if (value == null) {
      return null;
    }
    try {
      UUID parsed = UUID.fromString(value);
      return !new UUID(0L, 0L).equals(parsed) && parsed.toString().equals(value) ? parsed : null;
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static boolean hasConnectionFailureSqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException exception) {
        String sqlState = exception.getSQLState();
        if (sqlState != null && sqlState.startsWith("08")) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean validSignature(String value) {
    if (value == null) {
      return false;
    }
    try {
      byte[] signature = java.util.Base64.getDecoder().decode(value);
      return signature.length == 64
          && java.util.Base64.getEncoder().encodeToString(signature).equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }
}
