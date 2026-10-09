package net.firedevops.firemud.accountservice.service.impl;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.account.v1.AuthorityEvidenceBundleReference;
import net.firedevops.firemud.account.v1.IssueHumanOperatorAuthorizationReferenceRequest;
import net.firedevops.firemud.account.v1.IssueHumanOperatorAuthorizationReferenceResponse;
import net.firedevops.firemud.account.v1.ReadRedeemedOperationProjectionRequest;
import net.firedevops.firemud.account.v1.ReadRedeemedOperationProjectionResponse;
import net.firedevops.firemud.account.v1.RecoverOperatorAuthorizationReferenceRequest;
import net.firedevops.firemud.account.v1.RecoverOperatorAuthorizationReferenceResponse;
import net.firedevops.firemud.account.v1.RedeemOperatorAuthorizationRequest;
import net.firedevops.firemud.account.v1.RedeemOperatorAuthorizationResponse;
import net.firedevops.firemud.account.v1.StartSessionOperatorAuthorizationServiceGrpc;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.RedemptionResult;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorityBundle.BundleReference;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService.AuthorizationResponse;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService.IssueRequest;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService.RecoverRequest;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService.RedeemRequest;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService.RedeemedOperationProjection;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import org.jooq.exception.DataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.CannotCreateTransactionException;

/** Disabled-by-default, same-namespace mTLS receiver for human StartSession authorization. */
@GrpcService
@ConditionalOnProperty(
    prefix = "firemud.account.start-session-operator-authorization",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
public final class StartSessionOperatorAuthorizationGrpcService
    extends StartSessionOperatorAuthorizationServiceGrpc
        .StartSessionOperatorAuthorizationServiceImplBase {
  private static final int MAX_TUPLE_BYTES = 8 * 1024;
  private static final int MAX_CONTROL_UI_JWT_BYTES = 16 * 1024;
  private static final int MAX_AUTHORIZATION_REFERENCE_BYTES = 128;
  private static final int MAX_AUTHORITY_EVIDENCE_BUNDLE_BYTES = 128 * 1024;
  private static final int MAX_FINGERPRINT_BYTES = 137;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String AUTHORIZATION_REFERENCE = "[A-Za-z0-9_-]{43}";
  private static final String AUTHORIZATION_REFERENCE_FINGERPRINT =
      "arfp/v1/[A-Za-z0-9_-]{1,64}/[0-9a-f]{64}";
  private static final String MUTATION_DIGEST = "[0-9a-f]{64}";

  private final AccountStartSessionOperatorAuthorizationService authorizationService;
  private final String workloadNamespace;

  public StartSessionOperatorAuthorizationGrpcService(
      AccountStartSessionOperatorAuthorizationService authorizationService,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.authorizationService = Objects.requireNonNull(authorizationService);
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void issueHumanOperatorAuthorizationReference(
      IssueHumanOperatorAuthorizationReferenceRequest request,
      StreamObserver<IssueHumanOperatorAuthorizationReferenceResponse> responseObserver) {
    if (!isAllowedPeer("logging-admin-service")) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Verified same-namespace Logging/Admin workload identity is required");
      return;
    }

    final IssueRequest parsed;
    try {
      parsed = parseIssueRequest(request);
    } catch (IllegalArgumentException malformed) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "Canonical StartSession issue input is required");
      return;
    }

    invoke(
        responseObserver,
        () -> {
          AuthorizationResponse authorization = authorizationService.issue(parsed);
          return toIssueResponse(authorization, verifiedLoggingPeerUri());
        });
  }

  @Override
  public void recoverOperatorAuthorizationReference(
      RecoverOperatorAuthorizationReferenceRequest request,
      StreamObserver<RecoverOperatorAuthorizationReferenceResponse> responseObserver) {
    if (!isAllowedPeer("logging-admin-service")) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Verified same-namespace Logging/Admin workload identity is required");
      return;
    }

    final RecoverRequest parsed;
    try {
      parsed = parseRecoverRequest(request);
    } catch (IllegalArgumentException malformed) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "Canonical StartSession recovery input is required");
      return;
    }

    invoke(
        responseObserver,
        () -> {
          AuthorizationResponse authorization = authorizationService.recover(parsed);
          return toRecoverResponse(authorization, verifiedLoggingPeerUri());
        });
  }

  @Override
  public void redeemOperatorAuthorization(
      RedeemOperatorAuthorizationRequest request,
      StreamObserver<RedeemOperatorAuthorizationResponse> responseObserver) {
    if (!isAllowedPeer("game-session-service")) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Verified same-namespace Game Session workload identity is required");
      return;
    }

    final RedeemRequest parsed;
    try {
      parsed = parseRedeemRequest(request);
    } catch (IllegalArgumentException malformed) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "Canonical StartSession redemption input is required");
      return;
    }

    invoke(responseObserver, () -> toRedeemResponse(authorizationService.redeem(parsed)));
  }

  @Override
  public void readRedeemedOperationProjection(
      ReadRedeemedOperationProjectionRequest request,
      StreamObserver<ReadRedeemedOperationProjectionResponse> responseObserver) {
    if (!isAllowedPeer("game-design-service") && !isAllowedPeer("game-session-service")) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Verified same-namespace Game Design or Game Session workload identity is required");
      return;
    }

    final AccountStartSessionOperatorAuthorizationService.ReadRedeemedOperationProjectionRequest
        parsed;
    try {
      parsed = parseReadRedeemedOperationProjectionRequest(request);
    } catch (IllegalArgumentException malformed) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "Canonical StartSession operation projection input is required");
      return;
    }

    invoke(
        responseObserver,
        () ->
            toReadRedeemedOperationProjectionResponse(
                authorizationService.readRedeemedOperationProjection(parsed), parsed));
  }

  private IssueRequest parseIssueRequest(IssueHumanOperatorAuthorizationReferenceRequest request) {
    rejectUnknownFields(request.getUnknownFields().asMap().isEmpty());
    byte[] tupleBytes = boundedTuple(request.getCanonicalPreAuthorizationTupleBytes());
    decodeCanonicalTuple(tupleBytes);
    String token = requireControlUiJwt(request.getControlUiJwt());
    UUID reservationOwnerId =
        canonicalNonNilUuid(request.getReservationOwnerId(), "reservationOwnerId");
    long reservationFence =
        positiveFence(request.getReservationClaimFence(), "reservationClaimFence");
    UUID claimOwnerId =
        canonicalNonNilUuid(request.getCurrentClaimOwnerId(), "currentClaimOwnerId");
    long claimFence = positiveFence(request.getCurrentClaimFence(), "currentClaimFence");
    return new IssueRequest(
        token, tupleBytes, reservationOwnerId, reservationFence, claimOwnerId, claimFence);
  }

  private RecoverRequest parseRecoverRequest(RecoverOperatorAuthorizationReferenceRequest request) {
    rejectUnknownFields(request.getUnknownFields().asMap().isEmpty());
    byte[] tupleBytes = boundedTuple(request.getCanonicalPreAuthorizationTupleBytes());
    decodeCanonicalTuple(tupleBytes);
    UUID reservationOwnerId =
        canonicalNonNilUuid(request.getReservationOwnerId(), "reservationOwnerId");
    long reservationFence =
        positiveFence(request.getReservationClaimFence(), "reservationClaimFence");
    UUID claimOwnerId =
        canonicalNonNilUuid(request.getCurrentClaimOwnerId(), "currentClaimOwnerId");
    long claimFence = positiveFence(request.getCurrentClaimFence(), "currentClaimFence");
    return new RecoverRequest(
        tupleBytes, reservationOwnerId, reservationFence, claimOwnerId, claimFence);
  }

  private RedeemRequest parseRedeemRequest(RedeemOperatorAuthorizationRequest request) {
    rejectUnknownFields(request.getUnknownFields().asMap().isEmpty());
    byte[] tupleBytes = boundedTuple(request.getCanonicalPreAuthorizationTupleBytes());
    decodeCanonicalTuple(tupleBytes);
    String authorizationReference =
        requireAuthorizationReference(request.getOperatorAuthorizationReference());
    String fingerprint = requireFingerprint(request.getAuthorizationReferenceFingerprint());
    UUID reservationOwnerId =
        canonicalNonNilUuid(request.getReservationOwnerId(), "reservationOwnerId");
    long reservationFence =
        positiveFence(request.getReservationClaimFence(), "reservationClaimFence");
    UUID ownerAttemptId = canonicalNonNilUuid(request.getOwnerAttemptId(), "ownerAttemptId");
    long ownerFence = positiveFence(request.getOwnerFence(), "ownerFence");
    return new RedeemRequest(
        tupleBytes,
        authorizationReference,
        fingerprint,
        reservationOwnerId,
        reservationFence,
        ownerAttemptId,
        ownerFence);
  }

  private AccountStartSessionOperatorAuthorizationService.ReadRedeemedOperationProjectionRequest
      parseReadRedeemedOperationProjectionRequest(ReadRedeemedOperationProjectionRequest request) {
    rejectUnknownFields(request.getUnknownFields().asMap().isEmpty());
    byte[] tupleBytes = boundedTuple(request.getCanonicalPreAuthorizationTupleBytes());
    decodeCanonicalTuple(tupleBytes);
    String fingerprint = requireFingerprint(request.getAuthorizationReferenceFingerprint());
    UUID reservationOwnerId =
        canonicalNonNilUuid(request.getReservationOwnerId(), "reservationOwnerId");
    long reservationFence =
        positiveFence(request.getReservationClaimFence(), "reservationClaimFence");
    UUID ownerAttemptId = canonicalNonNilUuid(request.getOwnerAttemptId(), "ownerAttemptId");
    long ownerFence = positiveFence(request.getOwnerFence(), "ownerFence");
    return new AccountStartSessionOperatorAuthorizationService
        .ReadRedeemedOperationProjectionRequest(
        tupleBytes, fingerprint, reservationOwnerId, reservationFence, ownerAttemptId, ownerFence);
  }

  private boolean isAllowedPeer(String serviceAccount) {
    if (SessionContext.hasAuthenticatedCallerContext()
        || !GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      return false;
    }
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    return peer != null
        && workloadNamespace.equals(peer.namespace())
        && peer.uri().equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/" + serviceAccount);
  }

  /** Returns only the exact peer identity authenticated on this successful RPC's TLS context. */
  private String verifiedLoggingPeerUri() {
    if (SessionContext.hasAuthenticatedCallerContext()
        || !GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalStateException("verified Logging/Admin peer is unavailable");
    }
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null
        || !workloadNamespace.equals(peer.namespace())
        || !"logging-admin-service".equals(peer.service())
        || !peer.uri()
            .equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/logging-admin-service")) {
      throw new IllegalStateException("verified Logging/Admin peer is unavailable");
    }
    return peer.uri();
  }

  private static byte[] boundedTuple(ByteString tuple) {
    if (tuple.isEmpty() || tuple.size() > MAX_TUPLE_BYTES) {
      throw new IllegalArgumentException("bounded canonical tuple bytes are required");
    }
    return tuple.toByteArray();
  }

  private static StartSessionPreAuthorizationReservationTuple decodeCanonicalTuple(
      byte[] exactBytes) {
    final String json;
    try {
      json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(exactBytes))
              .toString();
    } catch (CharacterCodingException malformedUtf8) {
      throw new IllegalArgumentException("tuple is not strict UTF-8", malformedUtf8);
    }
    StartSessionPreAuthorizationReservationTuple tuple =
        StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(json);
    if (!Arrays.equals(exactBytes, tuple.canonicalJson().getBytes(StandardCharsets.UTF_8))) {
      throw new IllegalArgumentException("tuple bytes are not the exact canonical encoding");
    }
    return tuple;
  }

  private static String requireControlUiJwt(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > MAX_CONTROL_UI_JWT_BYTES
        || value.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
      throw new IllegalArgumentException("bounded compact control-ui JWT is required");
    }
    return value;
  }

  private static UUID canonicalNonNilUuid(String value, String fieldName) {
    if (value == null || value.length() > 36) {
      throw new IllegalArgumentException(fieldName + " must be a canonical nonnil UUID");
    }
    try {
      UUID parsed = UUID.fromString(value);
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException(fieldName + " must be a canonical nonnil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(fieldName + " must be a canonical nonnil UUID", malformed);
    }
  }

  private static long positiveFence(long value, String fieldName) {
    // A protobuf uint64 above Long.MAX_VALUE is exposed as a negative Java long and is rejected.
    if (value <= 0L) {
      throw new IllegalArgumentException(fieldName + " must be a positive supported uint64");
    }
    return value;
  }

  private static String requireAuthorizationReference(String value) {
    if (value == null
        || value.getBytes(StandardCharsets.UTF_8).length > MAX_AUTHORIZATION_REFERENCE_BYTES
        || !value.matches(AUTHORIZATION_REFERENCE)) {
      throw new IllegalArgumentException("canonical authorization reference is required");
    }
    return value;
  }

  private static String requireFingerprint(String value) {
    if (value == null
        || value.getBytes(StandardCharsets.UTF_8).length > MAX_FINGERPRINT_BYTES
        || !value.matches(AUTHORIZATION_REFERENCE_FINGERPRINT)) {
      throw new IllegalArgumentException("canonical authorization fingerprint is required");
    }
    return value;
  }

  private static void rejectUnknownFields(boolean requestHasNoUnknownFields) {
    if (!requestHasNoUnknownFields) {
      throw new IllegalArgumentException("unknown request fields are not supported");
    }
  }

  private static IssueHumanOperatorAuthorizationReferenceResponse toIssueResponse(
      AuthorizationResponse response, String authenticatedLoggingWorkloadIdentity) {
    requireResponse(response);
    return IssueHumanOperatorAuthorizationReferenceResponse.newBuilder()
        .setOperatorAuthorizationReference(response.operatorAuthorizationReference())
        .setAuthorizationReferenceFingerprint(response.authorizationReferenceFingerprint())
        .setExpiresAt(timestamp(response.expiresAt()))
        .setAuthorityEvidenceBundle(ByteString.copyFrom(response.authorityEvidenceBundle()))
        .setBundleReference(toWireBundleReference(response.bundleReference()))
        .setAuthenticatedLoggingWorkloadIdentity(authenticatedLoggingWorkloadIdentity)
        .build();
  }

  private static RecoverOperatorAuthorizationReferenceResponse toRecoverResponse(
      AuthorizationResponse response, String authenticatedLoggingWorkloadIdentity) {
    requireResponse(response);
    return RecoverOperatorAuthorizationReferenceResponse.newBuilder()
        .setOperatorAuthorizationReference(response.operatorAuthorizationReference())
        .setAuthorizationReferenceFingerprint(response.authorizationReferenceFingerprint())
        .setExpiresAt(timestamp(response.expiresAt()))
        .setAuthorityEvidenceBundle(ByteString.copyFrom(response.authorityEvidenceBundle()))
        .setBundleReference(toWireBundleReference(response.bundleReference()))
        .setAuthenticatedLoggingWorkloadIdentity(authenticatedLoggingWorkloadIdentity)
        .build();
  }

  private static void requireResponse(AuthorizationResponse response) {
    if (response == null
        || response.operatorAuthorizationReference() == null
        || response.operatorAuthorizationReference().getBytes(StandardCharsets.UTF_8).length
            > MAX_AUTHORIZATION_REFERENCE_BYTES
        || !response.operatorAuthorizationReference().matches(AUTHORIZATION_REFERENCE)
        || response.authorizationReferenceFingerprint() == null
        || response.authorizationReferenceFingerprint().getBytes(StandardCharsets.UTF_8).length
            > MAX_FINGERPRINT_BYTES
        || !response
            .authorizationReferenceFingerprint()
            .matches(AUTHORIZATION_REFERENCE_FINGERPRINT)
        || response.expiresAt() == null
        || !response.expiresAt().isAfter(Instant.EPOCH)
        || response.authorityEvidenceBundle() == null
        || response.authorityEvidenceBundle().length == 0
        || response.authorityEvidenceBundle().length > MAX_AUTHORITY_EVIDENCE_BUNDLE_BYTES
        || response.bundleReference() == null) {
      throw new IllegalStateException(
          "Account produced an invalid StartSession authorization result");
    }
  }

  private static AuthorityEvidenceBundleReference toWireBundleReference(BundleReference reference) {
    if (reference == null) {
      throw new IllegalStateException("Account bundle reference is missing");
    }
    return AuthorityEvidenceBundleReference.newBuilder()
        .setBundleVersion(reference.bundleVersion())
        .setSourceVersion(reference.sourceVersion())
        .setSourceFence(reference.sourceFence())
        .setLinearization(reference.linearization())
        .build();
  }

  private static RedeemOperatorAuthorizationResponse toRedeemResponse(RedemptionResult result) {
    if (result == null
        || result.authorizationReferenceFingerprint() == null
        || result.authorizationReferenceFingerprint().getBytes(StandardCharsets.UTF_8).length
            > MAX_FINGERPRINT_BYTES
        || !result.authorizationReferenceFingerprint().matches(AUTHORIZATION_REFERENCE_FINGERPRINT)
        || result.authorityEvidenceBundle() == null
        || result.authorityEvidenceBundle().length == 0
        || result.authorityEvidenceBundle().length > MAX_AUTHORITY_EVIDENCE_BUNDLE_BYTES
        || result.issuanceOperationId() == null
        || NIL_UUID.equals(result.issuanceOperationId())
        || result.issuanceFence() <= 0L) {
      throw new IllegalStateException("Account produced an invalid StartSession redemption result");
    }
    return RedeemOperatorAuthorizationResponse.newBuilder()
        .setAuthorizationReferenceFingerprint(result.authorizationReferenceFingerprint())
        .setAuthorityEvidenceBundle(ByteString.copyFrom(result.authorityEvidenceBundle()))
        .setIssuanceOperationId(result.issuanceOperationId().toString())
        .setIssuanceFence(result.issuanceFence())
        .setReplay(result.replay())
        .build();
  }

  private ReadRedeemedOperationProjectionResponse toReadRedeemedOperationProjectionResponse(
      RedeemedOperationProjection projection,
      AccountStartSessionOperatorAuthorizationService.ReadRedeemedOperationProjectionRequest
          request) {
    if (projection == null) {
      throw new IllegalStateException("Account produced an invalid redeemed operation projection");
    }
    byte[] requestTupleBytes = request.canonicalPreAuthorizationTuple();
    byte[] projectionTupleBytes = projection.canonicalPreAuthorizationTuple();
    StartSessionPreAuthorizationReservationTuple tuple = decodeCanonicalTuple(requestTupleBytes);
    String expectedRedeemerUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
    if (!StartSessionPreAuthorizationReservationTuple.requireCanonicalControlPlaneRequestId(
                projection.controlPlaneRequestId())
            .equals(tuple.controlPlaneRequestId())
        || !Arrays.equals(projectionTupleBytes, requestTupleBytes)
        || projection.mutationDigest() == null
        || !projection.mutationDigest().matches(MUTATION_DIGEST)
        || !tuple.mutationDigest().equals(projection.mutationDigest())
        || !request
            .authorizationReferenceFingerprint()
            .equals(projection.authorizationReferenceFingerprint())
        || !request.reservationOwnerId().equals(projection.reservationOwnerId())
        || request.reservationClaimFence() != projection.reservationClaimFence()
        || !request.ownerAttemptId().equals(projection.ownerAttemptId())
        || request.ownerFence() != projection.ownerFence()
        || !GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        || !expectedRedeemerUri.equals(projection.redeemerWorkloadUri())
        || projection.referenceExpiresAt() == null
        || projection.redeemedAt() == null
        || !projection.referenceExpiresAt().isAfter(projection.redeemedAt())
        || projection.issuanceOperationId() == null
        || NIL_UUID.equals(projection.issuanceOperationId())
        || projection.issuanceFence() <= 0L
        || projection.bundleReference() == null
        || projection.authorityEvidenceBundle() == null
        || projection.authorityEvidenceBundle().length == 0
        || projection.authorityEvidenceBundle().length > MAX_AUTHORITY_EVIDENCE_BUNDLE_BYTES) {
      throw new IllegalStateException("Account produced an invalid redeemed operation projection");
    }
    return ReadRedeemedOperationProjectionResponse.newBuilder()
        .setControlPlaneRequestId(projection.controlPlaneRequestId())
        .setCanonicalPreAuthorizationTupleBytes(ByteString.copyFrom(projectionTupleBytes))
        .setMutationDigest(projection.mutationDigest())
        .setAuthorizationReferenceFingerprint(projection.authorizationReferenceFingerprint())
        .setReservationOwnerId(projection.reservationOwnerId().toString())
        .setReservationClaimFence(projection.reservationClaimFence())
        .setAuthenticatedRedeemerWorkloadIdentity(projection.redeemerWorkloadUri())
        .setOwnerAttemptId(projection.ownerAttemptId().toString())
        .setOwnerFence(projection.ownerFence())
        .setReferenceExpiresAt(timestamp(projection.referenceExpiresAt()))
        .setRedeemedAt(timestamp(projection.redeemedAt()))
        .setIssuanceOperationId(projection.issuanceOperationId().toString())
        .setIssuanceFence(projection.issuanceFence())
        .setBundleReference(toWireBundleReference(projection.bundleReference()))
        .setAuthorityEvidenceBundle(ByteString.copyFrom(projection.authorityEvidenceBundle()))
        .build();
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  private static <T> void invoke(StreamObserver<T> observer, Supplier<T> operation) {
    try {
      observer.onNext(operation.get());
      observer.onCompleted();
    } catch (AccountStartSessionOperatorAuthorizationService.NotFoundException missing) {
      fail(observer, Status.NOT_FOUND, "No original StartSession authorization exists");
    } catch (CannotCreateTransactionException unavailable) {
      fail(
          observer,
          Status.UNAVAILABLE,
          "Account operator authorization is temporarily unavailable");
    } catch (DataAccessException failure) {
      Status status = hasConnectionFailureSqlState(failure) ? Status.UNAVAILABLE : Status.INTERNAL;
      fail(
          observer,
          status,
          status.getCode() == Status.Code.UNAVAILABLE
              ? "Account operator authorization is temporarily unavailable"
              : "Account operator authorization could not be completed");
    } catch (StatusRuntimeException upstream) {
      Status mapped = mapUpstreamStatus(Status.fromThrowable(upstream).getCode());
      fail(observer, mapped, descriptionFor(mapped));
    } catch (IllegalArgumentException malformed) {
      fail(observer, Status.INVALID_ARGUMENT, "StartSession authorization input is invalid");
    } catch (IllegalStateException rejected) {
      fail(
          observer,
          Status.FAILED_PRECONDITION,
          "Current Account authorization evidence is unavailable");
    } catch (RuntimeException failure) {
      fail(observer, Status.INTERNAL, "Account operator authorization could not be completed");
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

  private static Status mapUpstreamStatus(Status.Code code) {
    return switch (code) {
      case INVALID_ARGUMENT -> Status.INVALID_ARGUMENT;
      case NOT_FOUND -> Status.NOT_FOUND;
      case PERMISSION_DENIED -> Status.PERMISSION_DENIED;
      case FAILED_PRECONDITION, ABORTED -> Status.FAILED_PRECONDITION;
      case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED -> Status.UNAVAILABLE;
      default -> Status.INTERNAL;
    };
  }

  private static String descriptionFor(Status status) {
    return switch (status.getCode()) {
      case INVALID_ARGUMENT -> "StartSession authorization input is invalid";
      case NOT_FOUND -> "No original StartSession authorization exists";
      case PERMISSION_DENIED -> "Account rejected the authenticated StartSession caller";
      case FAILED_PRECONDITION -> "Current Account authorization evidence is unavailable";
      case UNAVAILABLE -> "Account operator authorization is temporarily unavailable";
      default -> "Account operator authorization could not be completed";
    };
  }

  private static <T> void fail(StreamObserver<T> observer, Status status, String description) {
    observer.onError(status.withDescription(description).asRuntimeException());
  }
}
