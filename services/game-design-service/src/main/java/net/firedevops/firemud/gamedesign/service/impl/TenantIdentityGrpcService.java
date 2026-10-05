package net.firedevops.firemud.gamedesign.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameSessionTenantAssociationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameSessionTenantAssociationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.service.impl.TenantAssociationMigrationService.ApprovedAssociation;
import net.firedevops.firemud.gamedesign.v1.GameSessionTenantAssociationManifestEvidence;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameSessionTenantAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameSessionTenantAssociationResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameTenantIdentityRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameTenantIdentityResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.jooq.exception.TooManyRowsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.grpc.server.service.GrpcService;

/** Authenticated read of Game Design's own retained tenant identity provenance. */
@GrpcService
public class TenantIdentityGrpcService
    extends TenantIdentityServiceGrpc.TenantIdentityServiceImplBase {
  private final GameRepository gameRepository;
  private final TenantAssociationMigrationService associationService;
  private final GameTenantCreationRepository creationRepository;
  private final GameAuthoredWorldSourceRepository authoredWorldRepository;
  private final GameSessionTenantAssociationRepository gameSessionAssociationRepository;
  private final String workloadNamespace;

  public TenantIdentityGrpcService(
      GameRepository gameRepository,
      TenantAssociationMigrationService associationService,
      GameTenantCreationRepository creationRepository,
      GameAuthoredWorldSourceRepository authoredWorldRepository,
      GameSessionTenantAssociationRepository gameSessionAssociationRepository,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.gameRepository = gameRepository;
    this.associationService = associationService;
    this.creationRepository = creationRepository;
    this.authoredWorldRepository = authoredWorldRepository;
    this.gameSessionAssociationRepository = gameSessionAssociationRepository;
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void resolveLegacyGameTenantIdentity(
      ResolveLegacyGameTenantIdentityRequest request,
      StreamObserver<ResolveLegacyGameTenantIdentityResponse> responseObserver) {
    if (!isAccountPeer()) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Verified Account workload identity is required")
              .asRuntimeException());
      return;
    }

    String sourceKey = request.getLegacyGameTenantId();
    if (sourceKey.isBlank() || sourceKey.length() > 36) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Exact legacy Game Design tenant key is required")
              .asRuntimeException());
      return;
    }

    Optional<GameTenantIdentity> resolved;
    try {
      resolved = gameRepository.findTenantIdentityByLegacyTenantId(sourceKey);
    } catch (IllegalStateException | TooManyRowsException ex) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Game Design tenant identity is ambiguous or provenance is invalid")
              .asRuntimeException());
      return;
    }
    if (resolved.isEmpty()) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription("No Game Design tenant identity for exact source key")
              .asRuntimeException());
      return;
    }
    GameTenantIdentity identity = resolved.orElseThrow();
    if (identity.canonicalTenantId() == null
        || identity.sourceGameId() == null
        || identity.sourceGameId() <= 0
        || identity.provenanceKind() == null
        || !sourceKey.equals(identity.sourceLegacyTenantId())) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Game Design tenant identity provenance is incomplete")
              .asRuntimeException());
      return;
    }

    responseObserver.onNext(
        ResolveLegacyGameTenantIdentityResponse.newBuilder()
            .setCanonicalTenantId(identity.canonicalTenantId().toString())
            .setSourceLegacyGameTenantId(identity.sourceLegacyTenantId())
            .setSourceGameRowId(identity.sourceGameId())
            .setProvenanceKind(identity.provenanceKind().name())
            .build());
    responseObserver.onCompleted();
  }

  @Override
  public void resolveLegacyAccountTenantAssociation(
      ResolveLegacyAccountTenantAssociationRequest request,
      StreamObserver<ResolveLegacyAccountTenantAssociationResponse> responseObserver) {
    if (!isAccountTenantMigratorPeer()) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Verified Account tenant migrator identity is required")
              .asRuntimeException());
      return;
    }
    if (request.getLegacyAccountTenantId() <= 0) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Positive exact legacy Account tenant key is required")
              .asRuntimeException());
      return;
    }
    Optional<ApprovedAssociation> resolved;
    try {
      resolved = associationService.findByLegacyAccountTenantId(request.getLegacyAccountTenantId());
    } catch (IllegalStateException ex) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Approved tenant association provenance is invalid")
              .asRuntimeException());
      return;
    }
    if (resolved.isEmpty()) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription("No approved association for exact Account legacy tenant key")
              .asRuntimeException());
      return;
    }
    ApprovedAssociation association = resolved.orElseThrow();
    if (!workloadNamespace.equals(association.targetNamespace())
        || association.legacyAccountTenantId() != request.getLegacyAccountTenantId()
        || association.canonicalTenantId() == null
        || association.sourceGameRowId() <= 0
        || association.operationId() == null
        || association.accountEvidenceDigest() == null
        || !association.accountEvidenceDigest().matches("sha256:[0-9a-f]{64}")
        || association.manifestDigest() == null
        || !association.manifestDigest().matches("sha256:[0-9a-f]{64}")
        || !validSignature(association.signature())
        || association.entryCount() <= 0
        || association.schemaVersion() != 1) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Approved tenant association readback is incomplete")
              .asRuntimeException());
      return;
    }
    responseObserver.onNext(
        ResolveLegacyAccountTenantAssociationResponse.newBuilder()
            .setLegacyAccountTenantId(association.legacyAccountTenantId())
            .setSourceLegacyGameTenantId(association.legacyGameTenantId())
            .setCanonicalTenantId(association.canonicalTenantId().toString())
            .setSourceGameRowId(association.sourceGameRowId())
            .setAccountEvidenceDigest(association.accountEvidenceDigest())
            .setOperationId(association.operationId().toString())
            .setManifestDigest(association.manifestDigest())
            .setTargetNamespace(association.targetNamespace())
            .setSignerKeyId(association.signerKeyId())
            .setApprovedBy(association.approvedBy())
            .setApprovalReference(association.approvalReference())
            .setSignedAt(association.signedAt())
            .setOperationEntryCount(association.entryCount())
            .setManifestSignature(association.signature())
            .setManifestSchemaVersion(association.schemaVersion())
            .build());
    responseObserver.onCompleted();
  }

  @Override
  public void resolveFreshTenantCreation(
      ResolveFreshTenantCreationRequest request,
      StreamObserver<ResolveFreshTenantCreationResponse> responseObserver) {
    if (!isAccountPeer()) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Verified Account workload identity is required")
              .asRuntimeException());
      return;
    }

    String expectedRequestDigest = request.getExpectedRequestDigest();
    if (!isSha256Digest(expectedRequestDigest)) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical expected request digest is required")
              .asRuntimeException());
      return;
    }
    UUID creationRequestId = parseCanonicalNonNilUuid(request.getCreationRequestId());
    if (creationRequestId == null) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical nonnil creation request ID is required")
              .asRuntimeException());
      return;
    }

    Optional<FreshTenantCreationEvidence> resolved;
    try {
      resolved = creationRepository.read(creationRequestId, workloadNamespace);
    } catch (GameTenantCreationRepository.InvalidCreationEvidenceException ex) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Fresh tenant creation evidence is incomplete or inconsistent")
              .asRuntimeException());
      return;
    } catch (TooManyRowsException ex) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Fresh tenant creation evidence is ambiguous")
              .asRuntimeException());
      return;
    } catch (DataAccessResourceFailureException ex) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Fresh tenant creation evidence is temporarily unavailable")
              .asRuntimeException());
      return;
    } catch (DataAccessException ex) {
      Status.Code code =
          hasConnectionFailureSqlState(ex) ? Status.Code.UNAVAILABLE : Status.Code.INTERNAL;
      responseObserver.onError(
          Status.fromCode(code)
              .withDescription(
                  code == Status.Code.UNAVAILABLE
                      ? "Fresh tenant creation evidence is temporarily unavailable"
                      : "Fresh tenant creation evidence could not be read")
              .asRuntimeException());
      return;
    } catch (RuntimeException ex) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Fresh tenant creation evidence could not be read")
              .asRuntimeException());
      return;
    }
    if (resolved.isEmpty()) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription("No fresh tenant creation for exact request ID")
              .asRuntimeException());
      return;
    }

    FreshTenantCreationEvidence receipt = resolved.orElseThrow();
    boolean exactReceipt =
        receipt.schemaVersion() == 1
            && workloadNamespace.equals(receipt.targetNamespace())
            && creationRequestId.equals(receipt.creationRequestId())
            && isCanonicalNonNilUuid(receipt.operationId())
            && expectedRequestDigest.equals(receipt.requestDigest())
            && isSha256Digest(receipt.requestDigest())
            && isCanonicalNonNilUuid(receipt.canonicalTenantId())
            && receipt.sourceGameRowId() > 0
            && receipt.sourceGameTenantKey() != null
            && !receipt.sourceGameTenantKey().isBlank()
            && "NEW_GAME_ROW".equals(receipt.provenanceKind())
            && isSha256Digest(receipt.evidenceDigest());
    if (exactReceipt) {
      try {
        String expectedEvidenceDigest =
            GameTenantCreationDigest.evidenceDigest(
                receipt.targetNamespace(),
                receipt.creationRequestId(),
                receipt.operationId(),
                receipt.requestDigest(),
                receipt.canonicalTenantId(),
                receipt.sourceGameRowId(),
                receipt.sourceGameTenantKey(),
                receipt.provenanceKind());
        exactReceipt = expectedEvidenceDigest.equals(receipt.evidenceDigest());
      } catch (IllegalArgumentException ex) {
        exactReceipt = false;
      }
    }
    if (!exactReceipt) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Fresh tenant creation readback does not match the exact request")
              .asRuntimeException());
      return;
    }

    responseObserver.onNext(
        ResolveFreshTenantCreationResponse.newBuilder()
            .setSchemaVersion(receipt.schemaVersion())
            .setTargetNamespace(receipt.targetNamespace())
            .setCreationRequestId(receipt.creationRequestId().toString())
            .setOperationId(receipt.operationId().toString())
            .setRequestDigest(receipt.requestDigest())
            .setCanonicalTenantId(receipt.canonicalTenantId().toString())
            .setSourceGameRowId(receipt.sourceGameRowId())
            .setSourceGameTenantKey(receipt.sourceGameTenantKey())
            .setProvenanceKind(receipt.provenanceKind())
            .setEvidenceDigest(receipt.evidenceDigest())
            .build());
    responseObserver.onCompleted();
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
    } catch (DataAccessResourceFailureException ex) {
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
  public void resolveAuthoredWorldSource(
      ResolveAuthoredWorldSourceRequest request,
      StreamObserver<ResolveAuthoredWorldSourceResponse> responseObserver) {
    if (!isAuthoredWorldSourceReaderPeer()) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription(
                  "Verified same-namespace Game Session or World Management identity is required")
              .asRuntimeException());
      return;
    }
    UUID requestId = parseCanonicalNonNilUuid(request.getRequestId());
    UUID operationId = parseCanonicalNonNilUuid(request.getOperationId());
    UUID tenantId = parseCanonicalNonNilUuid(request.getCanonicalTenantId());
    try {
      if (requestId == null
          || operationId == null
          || tenantId == null
          || !request.getUnknownFields().asMap().isEmpty()) {
        throw new IllegalArgumentException("Canonical exact source request is required");
      }
      AuthoredWorldSourceDigest.validateReadSelector(
          workloadNamespace, tenantId, request.getWorldSlug());
    } catch (IllegalArgumentException ex) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical exact source request is required")
              .asRuntimeException());
      return;
    }

    Optional<AuthoredWorldSourceEvidence> resolved;
    try {
      resolved =
          authoredWorldRepository.read(
              operationId, tenantId, request.getWorldSlug(), workloadNamespace);
    } catch (IllegalArgumentException | IllegalStateException | TooManyRowsException ex) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Authored-world source evidence is inconsistent")
              .asRuntimeException());
      return;
    } catch (DataAccessResourceFailureException ex) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Authored-world source is temporarily unavailable")
              .asRuntimeException());
      return;
    } catch (DataAccessException ex) {
      Status.Code code =
          hasConnectionFailureSqlState(ex) ? Status.Code.UNAVAILABLE : Status.Code.INTERNAL;
      responseObserver.onError(
          Status.fromCode(code)
              .withDescription("Authored-world source could not be read")
              .asRuntimeException());
      return;
    }
    if (resolved.isEmpty()) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription("No authored-world source for the exact operation and scope")
              .asRuntimeException());
      return;
    }
    AuthoredWorldSourceEvidence evidence = resolved.orElseThrow();
    if (!workloadNamespace.equals(evidence.targetNamespace())
        || !operationId.equals(evidence.operationId())
        || !tenantId.equals(evidence.canonicalTenantId())
        || !request.getWorldSlug().equals(evidence.worldSlug())) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Authored-world source readback does not match the request")
              .asRuntimeException());
      return;
    }
    responseObserver.onNext(
        ResolveAuthoredWorldSourceResponse.newBuilder()
            .setSchemaVersion(evidence.schemaVersion())
            .setTargetNamespace(evidence.targetNamespace())
            .setRequestId(requestId.toString())
            .setRegistrationRequestId(evidence.registrationRequestId().toString())
            .setOperationId(evidence.operationId().toString())
            .setRequestDigest(evidence.requestDigest())
            .setCanonicalTenantId(evidence.canonicalTenantId().toString())
            .setTenantSlug(evidence.tenantSlug())
            .setWorldSlug(evidence.worldSlug())
            .setWorldDisplayName(evidence.worldDisplayName())
            .setSourceGameRowId(evidence.sourceGameRowId())
            .setSourceGameTenantKey(evidence.sourceGameTenantKey())
            .setProvenanceKind(evidence.provenanceKind())
            .setEvidenceDigest(evidence.evidenceDigest())
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
    } catch (DataAccessResourceFailureException ex) {
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
              .withDescription("Retained Game Session association could not be read")
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
                    .setLegacyGameSessionTenantId(evidence.legacyGameSessionTenantId())
                    .setCanonicalTenantId(evidence.canonicalTenantId().toString())
                    .setSourceGameRowId(evidence.sourceGameRowId())
                    .setSourceGameTenantKey(evidence.sourceGameTenantKey())
                    .setProvenanceKind(evidence.provenanceKind())
                    .setGameSessionEvidenceDigest(evidence.gameSessionEvidenceDigest()))
            .setManifestDigest(receipt.manifestDigest())
            .setEd25519Signature(receipt.ed25519Signature())
            .build());
    responseObserver.onCompleted();
  }

  private boolean isAccountPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    return peer != null
        && workloadNamespace != null
        && !workloadNamespace.isBlank()
        && peer.uri().equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service");
  }

  private boolean isAccountTenantMigratorPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    return peer != null
        && workloadNamespace != null
        && !workloadNamespace.isBlank()
        && peer.uri()
            .equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/account-tenant-migrator");
  }

  private boolean isGameSessionPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    return peer != null
        && GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        && peer.uri()
            .equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service");
  }

  private boolean isAuthoredWorldSourceReaderPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    String expectedPrefix = "spiffe://firemud/ns/" + workloadNamespace + "/sa/";
    return peer != null
        && GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        && (peer.uri().equals(expectedPrefix + "game-session-service")
            || peer.uri().equals(expectedPrefix + "world-management-service"));
  }

  private static UUID parseCanonicalNonNilUuid(String value) {
    if (value == null) {
      return null;
    }
    try {
      UUID parsed = UUID.fromString(value);
      return isCanonicalNonNilUuid(parsed) && parsed.toString().equals(value) ? parsed : null;
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static boolean isCanonicalNonNilUuid(UUID value) {
    return value != null && !new UUID(0L, 0L).equals(value);
  }

  private static boolean isSha256Digest(String value) {
    return value != null && value.matches("sha256:[0-9a-f]{64}");
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
      byte[] decoded = Base64.getDecoder().decode(value);
      return decoded.length == 64 && Base64.getEncoder().encodeToString(decoded).equals(value);
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }
}
