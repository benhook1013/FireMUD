package net.firedevops.firemud.gamedesign.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Base64;
import java.util.Optional;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.service.impl.TenantAssociationMigrationService.ApprovedAssociation;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameTenantIdentityRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameTenantIdentityResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.server.service.GrpcService;

/** Authenticated read of Game Design's own retained tenant identity provenance. */
@GrpcService
public class TenantIdentityGrpcService
    extends TenantIdentityServiceGrpc.TenantIdentityServiceImplBase {
  private final GameRepository gameRepository;
  private final TenantAssociationMigrationService associationService;
  private final String workloadNamespace;

  public TenantIdentityGrpcService(
      GameRepository gameRepository,
      TenantAssociationMigrationService associationService,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.gameRepository = gameRepository;
    this.associationService = associationService;
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
    } catch (IllegalStateException ex) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Game Design tenant identity provenance is invalid")
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
    if (!isAccountPeer()) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Verified Account workload identity is required")
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

  private boolean isAccountPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    return peer != null
        && workloadNamespace != null
        && !workloadNamespace.isBlank()
        && peer.uri().equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service");
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
