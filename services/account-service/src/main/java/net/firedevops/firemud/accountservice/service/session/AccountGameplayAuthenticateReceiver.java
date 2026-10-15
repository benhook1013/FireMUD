package net.firedevops.firemud.accountservice.service.session;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GameplayLoginTransportClass;
import net.firedevops.firemud.accountservice.AccountUuidText;
import net.firedevops.firemud.accountservice.dto.CanonicalGameplayLoginRequest;
import net.firedevops.firemud.accountservice.dto.GameplayCredentialSourceContext;
import net.firedevops.firemud.accountservice.dto.InitialGameplayLoginResult;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.jooq.exception.DataAccessException;

/**
 * Unregistered canonical Authenticate adapter. Construction exposes no endpoint or Spring bean. The
 * certificate-derived Game Session peer attests the source context; this adapter neither
 * authenticates the public ingress nor grants gameplay admission.
 */
public final class AccountGameplayAuthenticateReceiver
    extends AccountServiceGrpc.AccountServiceImplBase {
  private final AccountGameplayCanonicalLoginOwner owner;
  private final AccountGameplayPrimaryCredentialVerifier verifier;
  private final String expectedWorkload;
  private final AccountInitialGameplayLoginDisposalOwner disposalOwner;

  public AccountGameplayAuthenticateReceiver(
      AccountGameplayCanonicalLoginOwner owner,
      AccountGameplayPrimaryCredentialVerifier verifier,
      String expectedNamespace) {
    this(owner, verifier, expectedNamespace, null);
  }

  public AccountGameplayAuthenticateReceiver(
      AccountGameplayCanonicalLoginOwner owner,
      AccountGameplayPrimaryCredentialVerifier verifier,
      String expectedNamespace,
      AccountInitialGameplayLoginDisposalOwner disposalOwner) {
    if (!GrpcPeerIdentity.isValidNamespace(expectedNamespace)) {
      throw new IllegalArgumentException("An exact Account workload namespace is required");
    }
    this.owner = Objects.requireNonNull(owner, "Canonical LOGIN owner is required");
    this.verifier = Objects.requireNonNull(verifier, "Primary credential authority is required");
    this.disposalOwner = disposalOwner;
    expectedWorkload = "spiffe://firemud/ns/" + expectedNamespace + "/sa/game-session-service";
  }

  @Override
  public void retireInitialGameplayLogin(
      net.firedevops.firemud.account.v1.RetireInitialGameplayLoginRequest request,
      StreamObserver<net.firedevops.firemud.account.v1.RetireInitialGameplayLoginResponse>
          observer) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null || !expectedWorkload.equals(peer.uri())) {
      fail(
          observer,
          Status.PERMISSION_DENIED,
          "PERMISSION_DENIED",
          "The exact original Game Session workload is required");
      return;
    }
    if (Context.current().getDeadline() != null && Context.current().getDeadline().isExpired()) {
      fail(
          observer,
          Status.DEADLINE_EXCEEDED,
          "DEADLINE_EXCEEDED",
          "Initial LOGIN disposal deadline has expired");
      return;
    }
    if (Context.current().isCancelled()) {
      fail(observer, Status.CANCELLED, "CANCELLED", "Initial LOGIN disposal call was cancelled");
      return;
    }
    net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalRequest canonical;
    try {
      if (request == null
          || !request.getUnknownFields().asMap().isEmpty()
          || (request.hasTokenWitness()
              && !request.getTokenWitness().getUnknownFields().asMap().isEmpty())) {
        throw new IllegalArgumentException("Closed original LOGIN disposal request required");
      }
      var witness = request.hasTokenWitness() ? request.getTokenWitness() : null;
      canonical =
          new net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalRequest(
              requireV4(request.getRequestId()),
              requireV4(request.getCallerContextId()),
              witness == null
                  ? null
                  : new net.firedevops.firemud.accountservice.dto
                      .InitialGameplayLoginDisposalRequest.TokenWitness(
                      requireV4(witness.getTokenJti()),
                      witness.getTokenSha256(),
                      witness.getTokenGeneration(),
                      witness.getIssuanceFence()));
    } catch (IllegalArgumentException invalid) {
      fail(
          observer,
          Status.INVALID_ARGUMENT,
          "INVALID_ARGUMENT",
          "Complete original LOGIN disposal identity required");
      return;
    }
    try {
      if (disposalOwner == null) {
        fail(
            observer,
            Status.UNAVAILABLE,
            AuthenticationErrorCodes.UNAVAILABLE,
            "Initial LOGIN disposal is unavailable");
        return;
      }
      var result = disposalOwner.retire(canonical, peer.uri());
      if (result == null
          || !canonical.requestId().equals(result.requestId())
          || !canonical.callerContextId().equals(result.callerContextId())) {
        fail(
            observer,
            Status.INTERNAL,
            "INTERNAL",
            "Initial LOGIN disposal owner returned an invalid result");
        return;
      }
      var disposition =
          switch (result.disposition()) {
            case PENDING ->
                net.firedevops.firemud.account.v1.InitialGameplayLoginDisposalDisposition
                    .INITIAL_GAMEPLAY_LOGIN_DISPOSAL_DISPOSITION_PENDING;
            case RETIRED ->
                net.firedevops.firemud.account.v1.InitialGameplayLoginDisposalDisposition
                    .INITIAL_GAMEPLAY_LOGIN_DISPOSAL_DISPOSITION_RETIRED;
            case NO_TOKEN ->
                net.firedevops.firemud.account.v1.InitialGameplayLoginDisposalDisposition
                    .INITIAL_GAMEPLAY_LOGIN_DISPOSAL_DISPOSITION_NO_TOKEN;
          };
      var proofKind =
          switch (result.proofKind()) {
            case NONE ->
                net.firedevops.firemud.account.v1.InitialGameplayLoginDisposalProofKind
                    .INITIAL_GAMEPLAY_LOGIN_DISPOSAL_PROOF_KIND_UNSPECIFIED;
            case PHYSICAL_TOKEN_DENIAL ->
                net.firedevops.firemud.account.v1.InitialGameplayLoginDisposalProofKind
                    .INITIAL_GAMEPLAY_LOGIN_DISPOSAL_PROOF_KIND_PHYSICAL_TOKEN_DENIAL;
            case NO_ISSUANCE_BINDING ->
                net.firedevops.firemud.account.v1.InitialGameplayLoginDisposalProofKind
                    .INITIAL_GAMEPLAY_LOGIN_DISPOSAL_PROOF_KIND_NO_ISSUANCE_BINDING;
          };
      observer.onNext(
          net.firedevops.firemud.account.v1.RetireInitialGameplayLoginResponse.newBuilder()
              .setRequestId(result.requestId().toString())
              .setCallerContextId(result.callerContextId().toString())
              .setDisposalOperationId(result.disposalOperationId().toString())
              .setDisposition(disposition)
              .setProofKind(proofKind)
              .build());
      observer.onCompleted();
    } catch (
        net.firedevops.firemud.accountservice.repository
                .AccountInitialGameplayLoginDisposalRepository.DisposalConflictException
            conflict) {
      fail(
          observer,
          Status.ALREADY_EXISTS,
          "IDEMPOTENCY_CONFLICT",
          "Original LOGIN disposal identity conflicts");
    } catch (RuntimeException unavailable) {
      fail(
          observer,
          Status.UNAVAILABLE,
          AuthenticationErrorCodes.UNAVAILABLE,
          "Initial LOGIN disposal is unavailable");
    }
  }

  @Override
  public void authenticate(
      AuthenticateRequest request, StreamObserver<AuthenticateResponse> responseObserver) {
    // No request field (especially the credential) is inspected before authenticating the peer.
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null || !expectedWorkload.equals(peer.uri())) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "PERMISSION_DENIED",
          "The exact Game Session workload is required");
      return;
    }
    if (Context.current().getDeadline() != null && Context.current().getDeadline().isExpired()) {
      fail(
          responseObserver,
          Status.DEADLINE_EXCEEDED,
          "DEADLINE_EXCEEDED",
          "Canonical LOGIN deadline has expired");
      return;
    }
    if (Context.current().isCancelled()) {
      fail(responseObserver, Status.CANCELLED, "CANCELLED", "Canonical LOGIN call was cancelled");
      return;
    }
    if (request == null
        || !request.hasSourceContext()
        || request.getSourceContext().getTransportClass()
            != GameplayLoginTransportClass.GAMEPLAY_LOGIN_TRANSPORT_CLASS_TRUSTED_TCP_PROXY) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "PERMISSION_DENIED",
          "Credential LOGIN requires the trusted TCP Proxy source");
      return;
    }

    CanonicalGameplayLoginRequest canonical;
    try {
      if (!request.getUnknownFields().asMap().isEmpty()
          || !request.getSourceContext().getUnknownFields().asMap().isEmpty()) {
        throw new IllegalArgumentException("Unknown LOGIN fields");
      }
      UUID requestId = requireV4(request.getRequestId());
      var source = request.getSourceContext();
      GameplayCredentialSourceContext context =
          new GameplayCredentialSourceContext(
              requireV4(source.getCallerContextId()),
              source.getCanonicalClientAddress(),
              "trusted_tcp_proxy");
      canonical =
          new CanonicalGameplayLoginRequest(
              requestId, request.getEmail(), request.getPassword(), context);
    } catch (IllegalArgumentException invalid) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "INVALID_ARGUMENT",
          "Canonical LOGIN request and source context are required");
      return;
    }

    AuthenticateResponse response;
    try {
      InitialGameplayLoginResult result = owner.authenticate(canonical, peer.uri(), verifier);
      if (result == null
          || !canonical.requestId().equals(result.requestId())
          || !canonical.sourceContext().contextId().equals(result.callerContextId())) {
        throw new IllegalStateException("Canonical LOGIN result binding is required");
      }
      response = project(result);
    } catch (AuthenticationException outcome) {
      if (outcome.getCode() == null) {
        fail(responseObserver, Status.INTERNAL, "INTERNAL", "Canonical LOGIN failed");
        return;
      }
      switch (outcome.getCode()) {
        case AuthenticationErrorCodes.INVALID_CREDENTIALS ->
            response = rejection(outcome.getCode(), "Invalid credentials");
        case AuthenticationErrorCodes.ACCOUNT_LOCKED ->
            response = rejection(outcome.getCode(), "Account is locked");
        case AuthenticationErrorCodes.RETRY_LATER ->
            response = rejection(outcome.getCode(), "Authentication must be retried later");
        case AuthenticationErrorCodes.UNAVAILABLE,
            AuthenticationErrorCodes.ABUSE_CONTROL_UNAVAILABLE -> {
          fail(
              responseObserver,
              Status.UNAVAILABLE,
              outcome.getCode(),
              "Authentication authority is unavailable");
          return;
        }
        default -> {
          fail(responseObserver, Status.INTERNAL, "INTERNAL", "Canonical LOGIN failed");
          return;
        }
      }
    } catch (AccountGameplayDelegationResponseEnvelopeRepository.IdempotencyConflictException
        | AccountGameplayDelegationIssuanceRepository.IdempotencyConflictException conflict) {
      response = rejection("IDEMPOTENCY_CONFLICT", "Original LOGIN request binding conflicts");
    } catch (
        AccountGameplayDelegationResponseEnvelopeRepository.ResponseRecoveryExpiredException
            expired) {
      response = rejection(expired.errorCode(), "Original LOGIN response has expired");
    } catch (AccountGameplayDelegationIssuanceRepository.StaleAuthorityException stale) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "FAILED_PRECONDITION",
          "Original LOGIN authority is no longer current");
      return;
    } catch (AccountGameplayDelegationIssuanceCommitService.IssuanceCommitUnavailableException
        | AccountGameplayDelegationResponseRecoveryOwner.OwnerUnavailableException
        | AccountGameplayDelegationIssuanceRepository.StorageUnavailableException
        | AccountGameplayDelegationIssuanceRepository.PendingCandidateUnavailableException
        | AccountGameplayDelegationResponseEnvelopeRepository.StorageUnavailableException
            unavailable) {
      fail(
          responseObserver,
          Status.UNAVAILABLE,
          AuthenticationErrorCodes.UNAVAILABLE,
          "Canonical LOGIN authority is unavailable");
      return;
    } catch (DataAccessException failure) {
      boolean connectionFailure = hasConnectionFailure(failure);
      fail(
          responseObserver,
          connectionFailure ? Status.UNAVAILABLE : Status.INTERNAL,
          connectionFailure ? AuthenticationErrorCodes.UNAVAILABLE : "INTERNAL",
          "Canonical LOGIN owner evidence could not be produced");
      return;
    } catch (RuntimeException failure) {
      fail(responseObserver, Status.INTERNAL, "INTERNAL", "Canonical LOGIN failed");
      return;
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private static UUID requireV4(String text) {
    UUID value = AccountUuidText.parseOrNull(text);
    if (value == null || value.version() != 4 || value.variant() != 2) {
      throw new IllegalArgumentException("Canonical v4 UUID required");
    }
    return value;
  }

  private static AuthenticateResponse project(InitialGameplayLoginResult result) {
    byte[] compact = result.compactJwtBytes();
    if (compact == null || compact.length == 0) {
      throw new IllegalStateException("Exact compact credential required");
    }
    try {
      for (byte value : compact) {
        if (value < 0) throw new IllegalStateException("Exact ASCII credential required");
      }
      return AuthenticateResponse.newBuilder()
          .setAuthToken(new String(compact, StandardCharsets.US_ASCII))
          .setAccountId(result.accountId().toString())
          .setRequestId(result.requestId().toString())
          .setCallerContextId(result.callerContextId().toString())
          .setTokenJti(result.tokenJti().toString())
          .setTokenSha256(result.tokenSha256())
          .setTokenProfile(result.profile())
          .setIssuedAtEpochSeconds(result.issuedAtEpochSecond())
          .setNotBeforeEpochSeconds(result.notBeforeEpochSecond())
          .setExpiresAtEpochSeconds(result.expiresAtEpochSecond())
          .setTokenGeneration(result.tokenGeneration())
          .setIssuanceFence(result.issuanceFence())
          .setAuthorityTupleCanonicalJson(ByteString.copyFrom(result.authorityTupleCanonicalJson()))
          .setOutboxCheckpointsCanonicalJson(
              ByteString.copyFrom(result.outboxCheckpointsCanonicalJson()))
          .setOutboxSourceEventEvidenceCanonicalJson(
              ByteString.copyFrom(result.outboxSourceEventEvidenceCanonicalJson()))
          .build();
    } finally {
      Arrays.fill(compact, (byte) 0);
    }
  }

  private static AuthenticateResponse rejection(String code, String message) {
    return AuthenticateResponse.newBuilder()
        .setError(ErrorDetail.newBuilder().setCode(code).setMessage(message))
        .build();
  }

  private static <T> void fail(
      StreamObserver<T> observer, Status status, String code, String message) {
    observer.onError(
        StatusProto.toStatusRuntimeException(
            com.google.rpc.Status.newBuilder()
                .setCode(status.getCode().value())
                .setMessage(message)
                .addDetails(
                    Any.pack(ErrorDetail.newBuilder().setCode(code).setMessage(message).build()))
                .build()));
  }

  private static boolean hasConnectionFailure(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql
          && sql.getSQLState() != null
          && sql.getSQLState().startsWith("08")) return true;
    }
    return false;
  }
}
