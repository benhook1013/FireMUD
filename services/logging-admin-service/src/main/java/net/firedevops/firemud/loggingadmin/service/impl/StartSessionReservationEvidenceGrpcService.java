package net.firedevops.firemud.loggingadmin.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.CurrentClaimEvidence;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ReadPurpose;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceRequest;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceResponse;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidencePurpose;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidenceServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.grpc.server.service.GrpcService;

/** Read-only Account handoff for the exact current StartSession reservation claim. */
@GrpcService
public class StartSessionReservationEvidenceGrpcService
    extends StartSessionReservationEvidenceServiceGrpc
        .StartSessionReservationEvidenceServiceImplBase {
  private final StartSessionPreAuthorizationReservationService reservationService;
  private final String workloadNamespace;

  public StartSessionReservationEvidenceGrpcService(
      StartSessionPreAuthorizationReservationService reservationService,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.reservationService = reservationService;
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void readCurrentClaimEvidence(
      ReadCurrentClaimEvidenceRequest request,
      StreamObserver<ReadCurrentClaimEvidenceResponse> responseObserver) {
    if (SessionContext.hasAuthenticatedCallerContext() || !isAccountPeer()) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Verified same-namespace Account workload identity without caller context is required");
      return;
    }

    ParsedRequest parsed;
    try {
      parsed = parseRequest(request);
    } catch (IllegalArgumentException exception) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "Canonical StartSession reservation claim evidence is required");
      return;
    }

    Optional<CurrentClaimEvidence> current;
    try {
      current =
          reservationService.readCurrentClaimEvidence(
              parsed.controlPlaneRequestId(),
              parsed.tuple(),
              parsed.reservationOwnerId(),
              parsed.reservationClaimFence(),
              parsed.claimOwnerId(),
              parsed.claimFence(),
              parsed.purpose());
    } catch (IllegalArgumentException exception) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "StartSession reservation claim evidence is invalid");
      return;
    } catch (StartSessionPreAuthorizationReservationService.IdempotencyConflictException
        | StartSessionPreAuthorizationReservationService.StaleReservationClaimException exception) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "The exact active StartSession reservation claim is unavailable");
      return;
    } catch (DataAccessResourceFailureException | TransientDataAccessException exception) {
      fail(
          responseObserver,
          Status.UNAVAILABLE,
          "StartSession reservation evidence is temporarily unavailable");
      return;
    } catch (DataAccessException exception) {
      fail(
          responseObserver,
          Status.UNAVAILABLE,
          "StartSession reservation evidence is temporarily unavailable");
      return;
    } catch (RuntimeException exception) {
      fail(
          responseObserver, Status.INTERNAL, "StartSession reservation evidence could not be read");
      return;
    }

    if (current.isEmpty()) {
      fail(
          responseObserver,
          Status.NOT_FOUND,
          "No StartSession reservation exists for the exact request ID");
      return;
    }

    CurrentClaimEvidence evidence = current.orElseThrow();
    var snapshot = evidence.snapshot();
    ReadCurrentClaimEvidenceResponse response =
        ReadCurrentClaimEvidenceResponse.newBuilder()
            .setControlPlaneRequestId(snapshot.tuple().controlPlaneRequestId())
            .setPreAuthorizationTupleJson(
                com.google.protobuf.ByteString.copyFrom(
                    snapshot.tuple().canonicalJson().getBytes(StandardCharsets.UTF_8)))
            .setMutationDigest(snapshot.mutationDigest())
            .setReservationOwnerId(evidence.reservationOwnerId().toString())
            .setReservationClaimFence(snapshot.reservationClaimFence())
            .setClaimOwnerId(evidence.currentClaimOwnerId().toString())
            .setClaimFence(snapshot.claimFence())
            .setClaimExpiresAtEpochMillis(snapshot.claimExpiresAtEpochMillis())
            .setObservedAtEpochMillis(evidence.observedAtEpochMillis())
            .setPurpose(toWirePurpose(evidence.purpose()))
            .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private ParsedRequest parseRequest(ReadCurrentClaimEvidenceRequest request) {
    if (!request.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("unknown request fields are not supported");
    }
    String requestId =
        StartSessionPreAuthorizationReservationTuple.requireCanonicalControlPlaneRequestId(
            request.getControlPlaneRequestId());
    UUID reservationOwnerId = parseCanonicalNonNilUuid(request.getReservationOwnerId());
    UUID claimOwnerId = parseCanonicalNonNilUuid(request.getClaimOwnerId());
    long reservationClaimFence = request.getReservationClaimFence();
    long claimFence = request.getClaimFence();
    if (reservationOwnerId == null
        || claimOwnerId == null
        || reservationClaimFence <= 0L
        || claimFence <= 0L
        || request.getPreAuthorizationTupleJson().isEmpty()
        || request.getPreAuthorizationTupleJson().size()
            > StartSessionPreAuthorizationReservationTuple.MAX_CANONICAL_TUPLE_UTF8_BYTES) {
      throw new IllegalArgumentException("reservation claim identity is malformed");
    }

    byte[] tupleBytes = request.getPreAuthorizationTupleJson().toByteArray();
    String tupleJson = decodeStrictUtf8(tupleBytes);
    StartSessionPreAuthorizationReservationTuple tuple =
        StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(tupleJson);
    if (!Arrays.equals(tuple.canonicalJson().getBytes(StandardCharsets.UTF_8), tupleBytes)
        || !requestId.equals(tuple.controlPlaneRequestId())) {
      throw new IllegalArgumentException("request key does not match the exact canonical tuple");
    }

    ReadPurpose purpose =
        switch (request.getPurpose()) {
          case START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE -> ReadPurpose.ISSUE;
          case START_SESSION_RESERVATION_EVIDENCE_PURPOSE_RECOVER -> ReadPurpose.RECOVER;
          default -> throw new IllegalArgumentException("read purpose is required");
        };
    return new ParsedRequest(
        requestId,
        tuple,
        reservationOwnerId,
        reservationClaimFence,
        claimOwnerId,
        claimFence,
        purpose);
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
      return !parsed.equals(new UUID(0L, 0L)) && parsed.toString().equals(value) ? parsed : null;
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static String decodeStrictUtf8(byte[] value) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(value))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("tuple JSON is not valid UTF-8", exception);
    }
  }

  private static StartSessionReservationEvidencePurpose toWirePurpose(ReadPurpose purpose) {
    return switch (purpose) {
      case ISSUE ->
          StartSessionReservationEvidencePurpose.START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE;
      case RECOVER ->
          StartSessionReservationEvidencePurpose.START_SESSION_RESERVATION_EVIDENCE_PURPOSE_RECOVER;
    };
  }

  private static void fail(
      StreamObserver<ReadCurrentClaimEvidenceResponse> responseObserver,
      Status status,
      String description) {
    responseObserver.onError(status.withDescription(description).asRuntimeException());
  }

  private record ParsedRequest(
      String controlPlaneRequestId,
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID reservationOwnerId,
      long reservationClaimFence,
      UUID claimOwnerId,
      long claimFence,
      ReadPurpose purpose) {}
}
