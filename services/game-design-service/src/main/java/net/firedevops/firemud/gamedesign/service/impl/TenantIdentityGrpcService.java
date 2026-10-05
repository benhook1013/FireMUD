package net.firedevops.firemud.gamedesign.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.jooq.exception.TooManyRowsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.grpc.server.service.GrpcService;

/** Read-only same-namespace Account handoff for exact, persisted fresh tenant evidence. */
@GrpcService
public class TenantIdentityGrpcService
    extends TenantIdentityServiceGrpc.TenantIdentityServiceImplBase {
  private final GameTenantCreationRepository creationRepository;
  private final String workloadNamespace;

  public TenantIdentityGrpcService(
      GameTenantCreationRepository creationRepository,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.creationRepository = creationRepository;
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void resolveFreshTenantCreation(
      ResolveFreshTenantCreationRequest request,
      StreamObserver<ResolveFreshTenantCreationResponse> responseObserver) {
    if (!isAccountPeer()) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Verified same-namespace Account workload identity is required")
              .asRuntimeException());
      return;
    }

    UUID creationRequestId = parseCanonicalNonNilUuid(request.getCreationRequestId());
    String expectedRequestDigest = request.getExpectedRequestDigest();
    if (creationRequestId == null
        || !GameTenantCreationDigest.isDigest(expectedRequestDigest)
        || !request.getUnknownFields().asMap().isEmpty()) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical exact fresh tenant creation request is required")
              .asRuntimeException());
      return;
    }

    Optional<FreshTenantCreationEvidence> resolved;
    try {
      resolved = creationRepository.read(creationRequestId, workloadNamespace);
    } catch (GameTenantCreationRepository.InvalidCreationEvidenceException
        | TooManyRowsException exception) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Fresh tenant creation evidence is incomplete or inconsistent")
              .asRuntimeException());
      return;
    } catch (DataAccessResourceFailureException | TransientDataAccessException exception) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Fresh tenant creation evidence is temporarily unavailable")
              .asRuntimeException());
      return;
    } catch (DataAccessException exception) {
      Status.Code code =
          hasConnectionFailureSqlState(exception) ? Status.Code.UNAVAILABLE : Status.Code.INTERNAL;
      responseObserver.onError(
          Status.fromCode(code)
              .withDescription(
                  code == Status.Code.UNAVAILABLE
                      ? "Fresh tenant creation evidence is temporarily unavailable"
                      : "Fresh tenant creation evidence could not be read")
              .asRuntimeException());
      return;
    } catch (RuntimeException exception) {
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
            && GameTenantCreationDigest.isDigest(receipt.requestDigest())
            && expectedRequestDigest.equals(receipt.requestDigest())
            && isCanonicalNonNilUuid(receipt.canonicalTenantId())
            && receipt.sourceGameRowId() > 0
            && receipt.sourceGameTenantKey() != null
            && !receipt.sourceGameTenantKey().isBlank()
            && "NEW_GAME_ROW".equals(receipt.provenanceKind())
            && GameTenantCreationDigest.isDigest(receipt.evidenceDigest());
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
      } catch (IllegalArgumentException exception) {
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

  private boolean isAccountPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    return peer != null
        && GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        && peer.uri().equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service");
  }

  private static UUID parseCanonicalNonNilUuid(String value) {
    if (value == null) {
      return null;
    }
    try {
      UUID parsed = UUID.fromString(value);
      return isCanonicalNonNilUuid(parsed) && parsed.toString().equals(value) ? parsed : null;
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static boolean isCanonicalNonNilUuid(UUID value) {
    return value != null && !new UUID(0L, 0L).equals(value);
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
}
