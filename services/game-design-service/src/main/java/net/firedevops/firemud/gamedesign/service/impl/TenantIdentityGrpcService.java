package net.firedevops.firemud.gamedesign.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.GameSessionTenantAssociationEvidence;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameSessionTenantAssociationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.v1.GameSessionTenantAssociationManifestEvidence;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameSessionTenantAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameSessionTenantAssociationResponse;
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
  private final GameSessionTenantAssociationRepository gameSessionAssociationRepository;
  private final String workloadNamespace;

  public TenantIdentityGrpcService(
      GameRepository gameRepository,
      GameSessionTenantAssociationRepository gameSessionAssociationRepository,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.gameRepository = gameRepository;
    this.gameSessionAssociationRepository = gameSessionAssociationRepository;
    this.workloadNamespace = workloadNamespace;
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
    } catch (DataAccessException ex) {
      responseObserver.onError(
          Status.UNAVAILABLE
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
