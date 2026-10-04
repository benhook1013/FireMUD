package net.firedevops.firemud.accountservice.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ResolveRuntimeAccountIdentityRequest;
import net.firedevops.firemud.account.v1.ResolveRuntimeAccountIdentityResponse;
import net.firedevops.firemud.account.v1.RuntimeAccountIdentityServiceGrpc;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.jooq.exception.DataAccessException;
import org.jooq.exception.TooManyRowsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.grpc.server.service.GrpcService;

/** Authenticated read of Account's exact retained UUID-to-row provenance. */
@GrpcService
public class RuntimeAccountIdentityGrpcService
    extends RuntimeAccountIdentityServiceGrpc.RuntimeAccountIdentityServiceImplBase {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final AccountRepository accountRepository;
  private final String workloadNamespace;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "AccountRepository is an injected internal owner collaborator, not exposed state.")
  public RuntimeAccountIdentityGrpcService(
      AccountRepository accountRepository,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.accountRepository = accountRepository;
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void resolveRuntimeAccountIdentity(
      ResolveRuntimeAccountIdentityRequest request,
      StreamObserver<ResolveRuntimeAccountIdentityResponse> responseObserver) {
    if (!isAllowedRuntimePeer()) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription(
                  "Verified Game Session or Entity Management workload identity is required")
              .asRuntimeException());
      return;
    }

    UUID canonicalAccountId = parseCanonicalNonNilUuid(request.getCanonicalAccountId());
    UUID requestId = parseCanonicalNonNilUuid(request.getRequestId());
    if (canonicalAccountId == null
        || requestId == null
        || !request.getUnknownFields().asMap().isEmpty()) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription(
                  "Canonical nonnil IDs and the exact metadata request schema are required")
              .asRuntimeException());
      return;
    }

    Optional<Account> resolved;
    try {
      resolved = accountRepository.findByAccountUuid(canonicalAccountId);
    } catch (IllegalStateException | TooManyRowsException ex) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Account UUID provenance is ambiguous or invalid")
              .asRuntimeException());
      return;
    } catch (DataAccessResourceFailureException ex) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Account identity is temporarily unavailable")
              .asRuntimeException());
      return;
    } catch (DataAccessException ex) {
      Status.Code code =
          hasConnectionFailureSqlState(ex) ? Status.Code.UNAVAILABLE : Status.Code.INTERNAL;
      responseObserver.onError(
          Status.fromCode(code)
              .withDescription(
                  code == Status.Code.UNAVAILABLE
                      ? "Account identity is temporarily unavailable"
                      : "Account identity could not be read")
              .asRuntimeException());
      return;
    } catch (RuntimeException ex) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Account identity could not be read")
              .asRuntimeException());
      return;
    }

    if (resolved.isEmpty()) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription("No Account identity for exact canonical account ID")
              .asRuntimeException());
      return;
    }

    Account account = resolved.orElseThrow();
    if (!hasExactPersistedIdentity(account, canonicalAccountId)) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription(
                  "Account UUID provenance does not match its exact persisted source row")
              .asRuntimeException());
      return;
    }

    responseObserver.onNext(
        ResolveRuntimeAccountIdentityResponse.newBuilder()
            .setSchemaVersion(1)
            .setTargetNamespace(workloadNamespace)
            .setRequestId(requestId.toString())
            .setCanonicalAccountId(canonicalAccountId.toString())
            .setSourceAccountRowId(account.getId())
            .setAccountUuidProvenance(account.getAccountUuidProvenance().name())
            .setSourceNumericRowId(account.getAccountUuidSourceNumericId())
            .build());
    responseObserver.onCompleted();
  }

  private boolean isAllowedRuntimePeer() {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      return false;
    }
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null || !peer.namespace().equals(workloadNamespace)) {
      return false;
    }
    return peer.uri()
            .equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service")
        || peer.uri()
            .equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/entity-management-service");
  }

  private static boolean hasExactPersistedIdentity(Account account, UUID canonicalAccountId) {
    if (account == null
        || account.getId() == null
        || account.getId() <= 0L
        || account.getAccountUuid() == null
        || NIL_UUID.equals(account.getAccountUuid())
        || !canonicalAccountId.equals(account.getAccountUuid())
        || account.getAccountUuidProvenance() == null
        || account.getAccountUuidSourceNumericId() == null
        || !account.getId().equals(account.getAccountUuidSourceNumericId())) {
      return false;
    }
    for (AccountIdentityProvenance provenance : AccountIdentityProvenance.values()) {
      if (provenance == account.getAccountUuidProvenance()) {
        return true;
      }
    }
    return false;
  }

  private static UUID parseCanonicalNonNilUuid(String value) {
    if (value == null) {
      return null;
    }
    try {
      UUID parsed = UUID.fromString(value);
      return !NIL_UUID.equals(parsed) && parsed.toString().equals(value) ? parsed : null;
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
}
