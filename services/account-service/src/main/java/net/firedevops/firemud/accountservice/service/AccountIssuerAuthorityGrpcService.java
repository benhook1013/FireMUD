package net.firedevops.firemud.accountservice.service;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.sql.SQLTransientException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.CaptureIssuerProjectionForRuntimeRequest;
import net.firedevops.firemud.account.v1.CaptureIssuerProjectionForRuntimeResponse;
import net.firedevops.firemud.account.v1.IssuerAuthorityServiceGrpc;
import net.firedevops.firemud.account.v1.IssuerAuthoritySourceSnapshot;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeRequest;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeResponse;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository.Receipt;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthorityEventReadback;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import net.firedevops.firemud.common.account.authority.IssuerProjectionReconciliationRequestDigestV1;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.dao.TransientDataAccessException;

/**
 * Unwired transport candidate for Account-owned issuer source readback.
 *
 * <p>This implementation exposes no runtime bean. Its only successful response is derived from a
 * complete local producer readback and an exact certificate-derived Game Session peer identity.
 */
public final class AccountIssuerAuthorityGrpcService
    extends IssuerAuthorityServiceGrpc.IssuerAuthorityServiceImplBase {
  private static final String GAME_SESSION_SERVICE = "game-session-service";
  private static final String SOURCE_SCOPE_PREFIX = "issuer/";
  private static final String EVENT_STREAM_PREFIX = "account:auth-authority:v1:";
  private static final String EVENT_ID_PREFIX = "account-issuer-authority-event-v1:";
  private static final String READBACK_SCHEMA_VERSION = "account-auth-issuer-source-readback/v1";
  private static final String CAPTURE_SCHEMA_VERSION = "account-auth-issuer-projection-capture/v1";
  private static final String PROJECTION_KEY_PREFIX = "session:game:auth:issuer-generation:v1:";
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final AccountIssuerAuthorityEventProducer producer;
  private final AccountIssuerProjectionReconciliationService captureService;

  private final String workloadNamespace;
  private final String expectedGameSessionPeerUri;

  public AccountIssuerAuthorityGrpcService(
      AccountIssuerAuthorityEventProducer producer,
      AccountIssuerProjectionReconciliationService captureService,
      String workloadNamespace) {
    this.producer = Objects.requireNonNull(producer, "issuer authority producer is required");
    this.captureService =
        Objects.requireNonNull(
            captureService, "issuer projection reconciliation service is required");
    if (workloadNamespace == null || workloadNamespace.isBlank()) {
      throw new IllegalArgumentException("Account workload namespace is required");
    }
    GrpcPeerIdentity expectedPeer =
        GrpcPeerIdentity.parseUri(
                "spiffe://firemud/ns/" + workloadNamespace + "/sa/" + GAME_SESSION_SERVICE)
            .filter(identity -> workloadNamespace.equals(identity.namespace()))
            .filter(identity -> identity.isService(GAME_SESSION_SERVICE))
            .orElseThrow(
                () -> new IllegalArgumentException("Account workload namespace is invalid"));
    this.workloadNamespace = expectedPeer.namespace();
    this.expectedGameSessionPeerUri = expectedPeer.uri();
  }

  @Override
  public void captureIssuerProjectionForRuntime(
      CaptureIssuerProjectionForRuntimeRequest request,
      StreamObserver<CaptureIssuerProjectionForRuntimeResponse> responseObserver) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null || !expectedGameSessionPeerUri.equals(peer.uri())) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Verified Game Session workload identity is required")
              .asRuntimeException());
      return;
    }

    final CaptureSelection selection;
    try {
      selection = validateCaptureRequest(request);
    } catch (InvalidRequestException invalidRequest) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Issuer projection capture request is invalid")
              .asRuntimeException());
      return;
    }

    final CaptureIssuerProjectionForRuntimeResponse response;
    try {
      Receipt receipt =
          captureService.capture(selection.issuerId(), peer.uri(), selection.requestId());
      response = encodeCapture(selection, peer.uri(), receipt);
    } catch (RuntimeException captureFailure) {
      Status.Code code = captureFailureCode(captureFailure);
      String description =
          switch (code) {
            case INVALID_ARGUMENT -> "Issuer projection capture request is invalid";
            case ALREADY_EXISTS -> "Issuer projection capture request conflicts with its receipt";
            case PERMISSION_DENIED -> "Verified Game Session workload identity is required";
            case UNAVAILABLE -> "Issuer projection source is temporarily unavailable";
            default -> "Issuer projection receipt is unavailable or contradictory";
          };
      responseObserver.onError(
          Status.fromCode(code).withDescription(description).asRuntimeException());
      return;
    }

    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void readIssuerAuthorityForRuntime(
      ReadIssuerAuthorityForRuntimeRequest request,
      StreamObserver<ReadIssuerAuthorityForRuntimeResponse> responseObserver) {
    if (!hasExactGameSessionPeer()) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Verified Game Session workload identity is required")
              .asRuntimeException());
      return;
    }

    final RequestSelection selection;
    try {
      selection = validateRequest(request);
    } catch (InvalidRequestException invalidRequest) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Issuer authority read request is invalid")
              .asRuntimeException());
      return;
    }

    final ReadIssuerAuthorityForRuntimeResponse response;
    try {
      response = readAndEncode(selection);
    } catch (RuntimeException sourceFailure) {
      Status.Code code =
          isExactIssuerMismatch(sourceFailure)
              ? Status.Code.INVALID_ARGUMENT
              : sourceFailureCode(sourceFailure);
      String description =
          code == Status.Code.UNAVAILABLE
              ? "Issuer authority source is temporarily unavailable"
              : isExactIssuerMismatch(sourceFailure)
                  ? "Issuer authority read request is invalid"
                  : "Issuer authority source evidence is unavailable or contradictory";
      responseObserver.onError(
          Status.fromCode(code).withDescription(description).asRuntimeException());
      return;
    }

    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private boolean hasExactGameSessionPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    return peer != null && expectedGameSessionPeerUri.equals(peer.uri());
  }

  private RequestSelection validateRequest(ReadIssuerAuthorityForRuntimeRequest request) {
    if (request == null || !request.getUnknownFields().asMap().isEmpty()) {
      throw new InvalidRequestException();
    }
    if (request.getIssuerId().isBlank()) {
      throw new InvalidRequestException();
    }

    UUID requestId;
    try {
      requestId = UUID.fromString(request.getRequestId());
    } catch (IllegalArgumentException malformed) {
      throw new InvalidRequestException();
    }
    if (new UUID(0L, 0L).equals(requestId)
        || !requestId.toString().equals(request.getRequestId())) {
      throw new InvalidRequestException();
    }

    Optional<Long> requestedSequence = Optional.empty();
    if (request.hasRequestedOutboxSequence()) {
      String sequenceText = request.getRequestedOutboxSequence();
      if (!sequenceText.matches("[1-9][0-9]*")) {
        throw new InvalidRequestException();
      }
      try {
        requestedSequence = Optional.of(Long.parseLong(sequenceText));
      } catch (NumberFormatException outsideBigintRange) {
        throw new InvalidRequestException();
      }
    }

    return new RequestSelection(request.getIssuerId(), requestId.toString(), requestedSequence);
  }

  private ReadIssuerAuthorityForRuntimeResponse readAndEncode(RequestSelection selection) {
    IssuerAuthoritySnapshot currentSnapshot;
    IssuerGenerationAuthorityEvent requestedEvent = null;
    if (selection.requestedSequence().isPresent()) {
      IssuerAuthorityEventReadback readback =
          producer.readCommittedEvent(
              selection.issuerId(), selection.requestedSequence().orElseThrow());
      if (readback == null) {
        throw new IllegalStateException("Issuer authority historical readback is absent");
      }
      currentSnapshot = readback.currentSnapshot();
      requestedEvent = readback.requestedEvent();
    } else {
      currentSnapshot = producer.readCurrent(selection.issuerId());
    }

    validateSourceSnapshot(currentSnapshot, selection.issuerId());
    if (selection.requestedSequence().isPresent()) {
      validateHistoricalEvent(
          requestedEvent, currentSnapshot, selection.requestedSequence().orElseThrow());
    }

    IssuerAuthoritySourceSnapshot.Builder snapshotResponse =
        IssuerAuthoritySourceSnapshot.newBuilder()
            .setIssuerId(currentSnapshot.issuerId())
            .setSourceScope(SOURCE_SCOPE_PREFIX + currentSnapshot.issuerId())
            .setOutboxStreamKey(currentSnapshot.outboxStreamKey())
            .setIssuerAuthGeneration(Long.toString(currentSnapshot.issuerAuthGeneration()))
            .setSourceVersion(Long.toString(currentSnapshot.sourceVersion()))
            .setOutboxSequence(Long.toString(currentSnapshot.outboxSequence()));
    currentSnapshot
        .latestEvent()
        .ifPresent(event -> snapshotResponse.setLatestEventCanonicalJson(event.canonicalJson()));

    ReadIssuerAuthorityForRuntimeResponse.Builder response =
        ReadIssuerAuthorityForRuntimeResponse.newBuilder()
            .setSchemaVersion(READBACK_SCHEMA_VERSION)
            .setTargetNamespace(workloadNamespace)
            .setRequestId(selection.requestId())
            .setSourceSnapshot(snapshotResponse);
    if (requestedEvent != null) {
      response.setRequestedEventCanonicalJson(requestedEvent.canonicalJson());
    }
    return response.build();
  }

  private CaptureSelection validateCaptureRequest(
      CaptureIssuerProjectionForRuntimeRequest request) {
    if (request == null || !request.getUnknownFields().asMap().isEmpty()) {
      throw new InvalidRequestException();
    }
    if (request.getIssuerId().isBlank()) {
      throw new InvalidRequestException();
    }

    UUID requestId;
    try {
      requestId = UUID.fromString(request.getRequestId());
    } catch (IllegalArgumentException malformed) {
      throw new InvalidRequestException();
    }
    if (NIL_UUID.equals(requestId) || !requestId.toString().equals(request.getRequestId())) {
      throw new InvalidRequestException();
    }
    return new CaptureSelection(request.getIssuerId(), requestId);
  }

  private CaptureIssuerProjectionForRuntimeResponse encodeCapture(
      CaptureSelection selection, String callerIdentity, Receipt receipt) {
    if (receipt == null
        || receipt.operationId() == null
        || NIL_UUID.equals(receipt.operationId())
        || !selection.requestId().equals(receipt.requestId())
        || !selection.issuerId().equals(receipt.issuerId())
        || !callerIdentity.equals(receipt.callerWorkloadIdentity())
        || !(PROJECTION_KEY_PREFIX + selection.issuerId()).equals(receipt.projectionKey())
        || receipt.requestDigestVersion()
            != IssuerProjectionReconciliationRequestDigestV1.VERSION) {
      throw new IllegalStateException(
          "Issuer projection receipt bindings are incomplete or mismatched");
    }

    String expectedDigest =
        IssuerProjectionReconciliationRequestDigestV1.digest(
            selection.issuerId(),
            callerIdentity,
            PROJECTION_KEY_PREFIX + selection.issuerId(),
            selection.requestId());
    if (!expectedDigest.equals(receipt.requestDigest())) {
      throw new IllegalStateException("Issuer projection receipt digest is mismatched");
    }

    IssuerAuthoritySnapshot captured = receipt.capturedSource();
    validateSourceSnapshot(captured, selection.issuerId());
    return CaptureIssuerProjectionForRuntimeResponse.newBuilder()
        .setSchemaVersion(CAPTURE_SCHEMA_VERSION)
        .setTargetNamespace(workloadNamespace)
        .setOperationId(receipt.operationId().toString())
        .setRequestId(selection.requestId().toString())
        .setIssuerId(receipt.issuerId())
        .setCallerWorkloadIdentity(receipt.callerWorkloadIdentity())
        .setProjectionKey(receipt.projectionKey())
        .setRequestDigestVersion(receipt.requestDigestVersion())
        .setRequestDigest(receipt.requestDigest())
        .setCapturedSourceSnapshot(encodeSourceSnapshot(captured))
        .build();
  }

  private IssuerAuthoritySourceSnapshot encodeSourceSnapshot(IssuerAuthoritySnapshot source) {
    IssuerAuthoritySourceSnapshot.Builder snapshotResponse =
        IssuerAuthoritySourceSnapshot.newBuilder()
            .setIssuerId(source.issuerId())
            .setSourceScope(SOURCE_SCOPE_PREFIX + source.issuerId())
            .setOutboxStreamKey(source.outboxStreamKey())
            .setIssuerAuthGeneration(Long.toString(source.issuerAuthGeneration()))
            .setSourceVersion(Long.toString(source.sourceVersion()))
            .setOutboxSequence(Long.toString(source.outboxSequence()));
    source
        .latestEvent()
        .ifPresent(event -> snapshotResponse.setLatestEventCanonicalJson(event.canonicalJson()));
    return snapshotResponse.build();
  }

  private static void validateSourceSnapshot(IssuerAuthoritySnapshot snapshot, String issuerId) {
    if (snapshot == null
        || !issuerId.equals(snapshot.issuerId())
        || snapshot.issuerAuthGeneration() <= 0L
        || snapshot.sourceVersion() <= 0L
        || snapshot.outboxSequence() < 0L
        || !(EVENT_STREAM_PREFIX + SOURCE_SCOPE_PREFIX + issuerId)
            .equals(snapshot.outboxStreamKey())) {
      throw new IllegalStateException(
          "Issuer authority current snapshot is incomplete or mismatched");
    }

    Optional<IssuerGenerationAuthorityEvent> latestEvent = snapshot.latestEvent();
    if (latestEvent == null || (snapshot.outboxSequence() == 0L) != latestEvent.isEmpty()) {
      throw new IllegalStateException("Issuer authority current checkpoint is incomplete");
    }
    if (snapshot.outboxSequence() == 0L) {
      if (snapshot.issuerAuthGeneration() != 1L || snapshot.sourceVersion() != 1L) {
        throw new IllegalStateException("Issuer authority baseline is contradictory");
      }
      return;
    }

    IssuerGenerationAuthorityEvent latest = latestEvent.orElseThrow();
    verifyCompleteEvent(latest);
    if (!issuerId.equals(latest.issuerId())
        || !(SOURCE_SCOPE_PREFIX + issuerId).equals(latest.sourceScope())
        || !snapshot.outboxStreamKey().equals(latest.outboxStreamKey())
        || !Long.toString(snapshot.outboxSequence()).equals(latest.outboxSequence())
        || !Long.toString(snapshot.issuerAuthGeneration()).equals(latest.issuerAuthGeneration())
        || !Long.toString(snapshot.sourceVersion()).equals(latest.sourceVersion())) {
      throw new IllegalStateException("Issuer authority latest event differs from its checkpoint");
    }
  }

  private static void validateHistoricalEvent(
      IssuerGenerationAuthorityEvent event,
      IssuerAuthoritySnapshot currentSnapshot,
      long requestedSequence) {
    verifyCompleteEvent(event);
    String exactIssuer = currentSnapshot.issuerId();
    if (!exactIssuer.equals(event.issuerId())
        || !(SOURCE_SCOPE_PREFIX + exactIssuer).equals(event.sourceScope())
        || !currentSnapshot.outboxStreamKey().equals(event.outboxStreamKey())
        || !Long.toString(requestedSequence).equals(event.outboxSequence())
        || new BigInteger(event.outboxSequence())
                .compareTo(BigInteger.valueOf(currentSnapshot.outboxSequence()))
            > 0
        || new BigInteger(event.issuerAuthGeneration())
                .compareTo(BigInteger.valueOf(currentSnapshot.issuerAuthGeneration()))
            > 0
        || new BigInteger(event.sourceVersion())
                .compareTo(BigInteger.valueOf(currentSnapshot.sourceVersion()))
            > 0) {
      throw new IllegalStateException("Requested issuer event differs from its exact selector");
    }
    if (requestedSequence == currentSnapshot.outboxSequence()
        && (currentSnapshot.latestEvent().isEmpty()
            || !sameCanonicalEvent(event, currentSnapshot.latestEvent().orElseThrow()))) {
      throw new IllegalStateException("Requested latest issuer event differs from its checkpoint");
    }
  }

  private static void verifyCompleteEvent(IssuerGenerationAuthorityEvent event) {
    if (event == null) {
      throw new IllegalStateException("Issuer authority event is absent");
    }
    IssuerGenerationAuthorityEvent verified =
        IssuerGenerationAuthorityEventV1Codec.verify(event.canonicalJson());
    if (!sameCanonicalEvent(event, verified)
        || !event.eventId().equals(EVENT_ID_PREFIX + event.requestId())) {
      throw new IllegalStateException(
          "Issuer authority event is not complete canonical source data");
    }
    UUID eventRequestId;
    try {
      eventRequestId = UUID.fromString(event.requestId());
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Issuer authority event request identity is malformed", malformed);
    }
    if (new UUID(0L, 0L).equals(eventRequestId)
        || !eventRequestId.toString().equals(event.requestId())) {
      throw new IllegalStateException("Issuer authority event request identity is noncanonical");
    }
  }

  private static boolean sameCanonicalEvent(
      IssuerGenerationAuthorityEvent first, IssuerGenerationAuthorityEvent second) {
    return first != null
        && second != null
        && first.schemaVersion().equals(second.schemaVersion())
        && first.eventType().equals(second.eventType())
        && first.eventId().equals(second.eventId())
        && first.requestId().equals(second.requestId())
        && first.issuerId().equals(second.issuerId())
        && first.sourceScope().equals(second.sourceScope())
        && first.outboxStreamKey().equals(second.outboxStreamKey())
        && first.outboxSequence().equals(second.outboxSequence())
        && first.issuerAuthGeneration().equals(second.issuerAuthGeneration())
        && first.sourceVersion().equals(second.sourceVersion())
        && first.eventDigest().equals(second.eventDigest())
        && first.canonicalJson().equals(second.canonicalJson())
        && MessageDigest.isEqual(first.canonicalJsonUtf8(), second.canonicalJsonUtf8());
  }

  private static Status.Code sourceFailureCode(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof TransientDataAccessException || cause instanceof SQLTransientException) {
        return Status.Code.UNAVAILABLE;
      }
    }
    return Status.Code.FAILED_PRECONDITION;
  }

  private static Status.Code captureFailureCode(Throwable failure) {
    if (isExactIssuerMismatch(failure)) {
      return Status.Code.INVALID_ARGUMENT;
    }
    if (failure
        instanceof AccountIssuerProjectionReconciliationRepository.IdempotencyConflictException) {
      return Status.Code.ALREADY_EXISTS;
    }
    if (failure instanceof SecurityException) {
      return Status.Code.PERMISSION_DENIED;
    }
    return sourceFailureCode(failure);
  }

  private static boolean isExactIssuerMismatch(Throwable failure) {
    return failure instanceof AccountIssuerAuthorityEventProducer.IssuerMismatchException;
  }

  private record RequestSelection(
      String issuerId, String requestId, Optional<Long> requestedSequence) {
    private RequestSelection {
      Objects.requireNonNull(issuerId, "issuerId");
      Objects.requireNonNull(requestId, "requestId");
      Objects.requireNonNull(requestedSequence, "requestedSequence");
    }
  }

  private record CaptureSelection(String issuerId, UUID requestId) {
    private CaptureSelection {
      Objects.requireNonNull(issuerId, "issuerId");
      Objects.requireNonNull(requestId, "requestId");
    }
  }

  private static final class InvalidRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
