package net.firedevops.firemud.gamedesign.service.impl;

import com.google.protobuf.Message;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Outcome;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeGrpcCodec;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeGrpcCodec.ReadRequest;
import net.firedevops.firemud.gamedesign.v1.CreateAuthorizedTenantRequest;
import net.firedevops.firemud.gamedesign.v1.CreateAuthorizedTenantResponse;
import net.firedevops.firemud.gamedesign.v1.OriginalTenantCreationOutcome;
import net.firedevops.firemud.gamedesign.v1.TenantCreationServiceGrpc.TenantCreationServiceImplBase;
import org.springframework.beans.factory.annotation.Value;

/** Unregistered exact-Account ingress for tenant creation; no runtime service activation. */
public final class AccountAuthorizedTenantCreationGrpcService
    extends TenantCreationServiceImplBase {
  private static final int SCHEMA_VERSION = 1;
  private static final int MAX_REQUEST_BYTES = 4096;
  private static final java.util.UUID NIL_UUID = new java.util.UUID(0L, 0L);

  private final AccountAuthorizedTenantCreationService owner;
  private final String workloadNamespace;

  public AccountAuthorizedTenantCreationGrpcService(
      AccountAuthorizedTenantCreationService owner,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.owner = java.util.Objects.requireNonNull(owner, "owner");
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void createAuthorizedTenant(
      CreateAuthorizedTenantRequest request,
      StreamObserver<CreateAuthorizedTenantResponse> responseObserver) {
    if (SessionContext.hasAuthenticatedCallerContext() || !isAccountPeer()) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription(
                  "Verified same-namespace Account workload identity without caller context is "
                      + "required")
              .asRuntimeException());
      return;
    }

    final AccountAuthorizedTenantCreationService.CandidateRequest candidate;
    try {
      candidate = decodeRequest(request);
    } catch (IllegalArgumentException malformed) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical exact Account tenant-creation request is required")
              .asRuntimeException());
      return;
    }

    final net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence receipt;
    try {
      receipt = owner.create(candidate);
    } catch (IllegalArgumentException malformed) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Account tenant-creation inputs do not match their exact binding")
              .asRuntimeException());
      return;
    } catch (IllegalStateException conflict) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Exact current Account tenant-creation authority is unavailable")
              .asRuntimeException());
      return;
    } catch (RuntimeException failure) {
      responseObserver.onError(storageFailureStatus(failure).asRuntimeException());
      return;
    }

    final CreateAuthorizedTenantResponse response;
    try {
      var outcome =
          new GameTenantCreationOriginalOutcomeEvidence(
              SCHEMA_VERSION, candidate.binding(), Outcome.CREATED, Optional.of(receipt));
      var fullReceipt =
          GameTenantCreationOriginalOutcomeGrpcCodec.toReadResponse(
                  new ReadRequest(candidate.createRequestId(), candidate.binding()), outcome)
              .getCreatedReceipt();
      response =
          CreateAuthorizedTenantResponse.newBuilder()
              .setSchemaVersion(SCHEMA_VERSION)
              .setCreateRequestId(candidate.createRequestId().toString())
              .setTargetNamespace(candidate.binding().targetNamespace())
              .setCreationRequestId(candidate.binding().creationRequestId().toString())
              .setAccountAuthorizationOperationId(
                  candidate.binding().accountAuthorizationOperationId().toString())
              .setInitiatingAccountId(candidate.binding().initiatingAccountId().toString())
              .setCreationRequestDigest(candidate.binding().creationRequestDigest())
              .setAccountAuthorizationDigest(candidate.binding().accountAuthorizationDigest())
              .setStatus(OriginalTenantCreationOutcome.ORIGINAL_TENANT_CREATION_OUTCOME_CREATED)
              .setCreatedReceipt(fullReceipt)
              .build();
      requireKnownAndWithinBudget(response);
    } catch (IllegalArgumentException malformed) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Committed Game Design creation receipt is inconsistent")
              .asRuntimeException());
      return;
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private AccountAuthorizedTenantCreationService.CandidateRequest decodeRequest(
      CreateAuthorizedTenantRequest request) {
    requireKnownAndWithinBudget(request);
    if (request.getSchemaVersion() != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported tenant-creation request schema");
    }
    UUID createRequestId =
        parseCanonicalNonNilUuid(request.getCreateRequestId(), "createRequestId");
    Binding binding =
        new Binding(
            request.getTargetNamespace(),
            parseCanonicalNonNilUuid(request.getCreationRequestId(), "creationRequestId"),
            parseCanonicalNonNilUuid(
                request.getAccountAuthorizationOperationId(), "accountAuthorizationOperationId"),
            parseCanonicalNonNilUuid(request.getInitiatingAccountId(), "initiatingAccountId"),
            request.getCreationRequestDigest(),
            request.getAccountAuthorizationDigest());
    if (!binding.targetNamespace().equals(workloadNamespace)) {
      throw new IllegalArgumentException("Creation namespace differs from Game Design config");
    }
    var candidate =
        new AccountAuthorizedTenantCreationService.CandidateRequest(
            createRequestId,
            binding,
            request.getSourceGameTenantKey(),
            request.getName(),
            request.hasDescription() ? request.getDescription() : null);
    if (request.getSerializedSize() > MAX_REQUEST_BYTES) {
      throw new IllegalArgumentException("Tenant-creation request exceeds its byte bound");
    }
    return candidate;
  }

  private boolean isAccountPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    return peer != null
        && GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        && peer.uri().equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service");
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required", malformed);
    }
    if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required");
    }
    return parsed;
  }

  private static void requireKnownAndWithinBudget(Message message) {
    if (message == null || message.getSerializedSize() > MAX_REQUEST_BYTES) {
      throw new IllegalArgumentException("Tenant-creation wire message exceeds its byte bound");
    }
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Unknown tenant-creation wire fields");
    }
  }

  private static Status storageFailureStatus(Throwable failure) {
    if (failure instanceof org.springframework.transaction.TransactionException
        || failure instanceof org.jooq.exception.DataAccessException) {
      return Status.UNAVAILABLE.withDescription(
          "Game Design tenant creation is temporarily unavailable");
    }
    if (failure instanceof org.springframework.dao.DataAccessException) {
      return Status.UNAVAILABLE.withDescription(
          "Game Design tenant creation is temporarily unavailable");
    }
    return Status.INTERNAL.withDescription("Game Design tenant creation could not be completed");
  }
}
