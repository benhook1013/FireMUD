package net.firedevops.firemud.worldmanagement.tenant;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.worldmanagement.v1.FinalizeCanonicalInitialAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.FinalizeCanonicalInitialAdmissionHoldResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInitialAdmissionHoldTerminalServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicitly gated transport adapter for terminalizing an exact canonical initial-admission hold.
 */
@GrpcService
@ConditionalOnProperty(
    prefix = "firemud.world.canonical-first-admission",
    name = "transport-enabled",
    havingValue = "true")
public final class WorldCanonicalInitialAdmissionHoldTerminalGrpcService
    extends WorldCanonicalInitialAdmissionHoldTerminalServiceGrpc
        .WorldCanonicalInitialAdmissionHoldTerminalServiceImplBase {
  private final WorldCanonicalInitialAdmissionHoldFinalizationService finalizationService;
  private final String trustedNamespace;

  public WorldCanonicalInitialAdmissionHoldTerminalGrpcService(
      WorldCanonicalInitialAdmissionHoldFinalizationService finalizationService,
      @Value("${firemud.grpc.workload-namespace:}") String trustedNamespace) {
    this.finalizationService = Objects.requireNonNull(finalizationService, "finalizationService");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public void finalizeCanonicalInitialAdmissionHold(
      FinalizeCanonicalInitialAdmissionHoldRequest request,
      StreamObserver<FinalizeCanonicalInitialAdmissionHoldResponse> responseObserver) {
    if (!requireAuthenticatedGameSessionPeer(responseObserver)) return;
    if (!requireIndependentOwnerOperation(responseObserver)) return;

    HoldIdentity identity;
    GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome;
    try {
      ParsedRequest parsed = parseRequest(request);
      identity = parsed.identity();
      outcome = parsed.outcome();
    } catch (IllegalArgumentException malformed) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "A complete canonical hold identity and terminal outcome are required");
      return;
    }
    if (!trustedNamespace.equals(identity.request().targetNamespace())) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Canonical World hold namespace must match the authenticated peer");
      return;
    }

    GameSessionCanonicalInitialAdmissionOwnerProof proof;
    try {
      // Only the caller's exact hold selection and terminal outcome cross this boundary. Owner
      // proof is independently obtained and held by the World finalization service.
      proof = finalizationService.finalizeHold(identity, outcome);
    } catch (WorldCanonicalInitialAdmissionHoldFinalizationService.FinalizationDeniedException
        | WorldCanonicalInitialAdmissionHoldFinalizationRepository.FinalizationDeniedException
            denied) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Authenticated Game Session terminal owner proof is unavailable or does not match");
      return;
    } catch (TransientDataAccessException unavailable) {
      fail(
          responseObserver,
          Status.UNAVAILABLE,
          "Canonical World hold storage is temporarily unavailable");
      return;
    } catch (DataAccessException storageFailure) {
      fail(responseObserver, Status.INTERNAL, "Canonical World hold storage failed");
      return;
    } catch (RuntimeException failure) {
      fail(responseObserver, Status.INTERNAL, "Canonical World hold finalization failed");
      return;
    }

    byte[] proofBytes;
    try {
      proofBytes = requireExactProof(identity, outcome, proof);
    } catch (RuntimeException inconsistent) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World hold owner returned a substituted or incomplete terminal proof");
      return;
    }
    responseObserver.onNext(
        FinalizeCanonicalInitialAdmissionHoldResponse.newBuilder()
            .setOwnerProofBytes(ByteString.copyFrom(proofBytes))
            .build());
    responseObserver.onCompleted();
  }

  private static ParsedRequest parseRequest(FinalizeCanonicalInitialAdmissionHoldRequest request) {
    if (request == null || !request.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Closed terminal request is required");
    }
    byte[] bytes = request.getHoldIdentityBytes().toByteArray();
    HoldIdentity identity = WorldCanonicalInitialAdmissionHold.HoldIdentity.fromStored(bytes);
    if (!Arrays.equals(bytes, identity.canonicalBytes())) {
      throw new IllegalArgumentException("Hold identity bytes are not canonical");
    }
    GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome =
        switch (request.getExpectedOutcome()) {
          case COMMITTED -> GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED;
          case ABORTED -> GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED;
          case EXPECTED_OUTCOME_UNSPECIFIED, UNRECOGNIZED ->
              throw new IllegalArgumentException("A terminal expected outcome is required");
        };
    return new ParsedRequest(identity, outcome);
  }

  private static byte[] requireExactProof(
      HoldIdentity expectedIdentity,
      GameSessionCanonicalInitialAdmissionOwnerProof.Outcome expectedOutcome,
      GameSessionCanonicalInitialAdmissionOwnerProof actual) {
    if (actual == null
        || !expectedIdentity.equals(actual.holdIdentity())
        || expectedOutcome != actual.outcome()
        || !Arrays.equals(
            expectedIdentity.canonicalBytes(), actual.holdIdentity().canonicalBytes())) {
      throw new IllegalStateException("Game Session terminal proof differs from the request");
    }
    if (expectedOutcome == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED) {
      long expectedPointerVersion =
          switch (expectedIdentity.request().initialAdmissionOrigin()) {
            case NO_PRIOR_POINTER -> 1L;
            case EXPECT_CLOSED ->
                Math.addExact(expectedIdentity.request().expectedPriorPointerVersion(), 1L);
          };
      if (!Long.valueOf(expectedPointerVersion).equals(actual.committedPointerVersion())
          || actual.positiveDurableAbort()) {
        throw new IllegalStateException("Committed Game Session proof has noncanonical semantics");
      }
    } else if (!actual.positiveDurableAbort()
        || actual.committedPointerVersion() != null
        || actual.auditEventId() != null) {
      throw new IllegalStateException("Aborted Game Session proof lacks positive durable fencing");
    }
    byte[] bytes = GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(actual);
    var decoded = GameSessionCanonicalInitialAdmissionOwnerProofCodec.fromStored(bytes);
    if (!expectedIdentity.equals(decoded.holdIdentity()) || decoded.outcome() != expectedOutcome) {
      throw new IllegalStateException("Game Session terminal proof failed canonical round-trip");
    }
    return bytes;
  }

  private boolean requireAuthenticatedGameSessionPeer(StreamObserver<?> responseObserver) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer != null
        && peer.isService("game-session-service")
        && peer.isInNamespace(trustedNamespace)
        && !SessionContext.hasAuthenticatedCallerContext()) {
      return true;
    }
    fail(
        responseObserver,
        Status.PERMISSION_DENIED,
        "Only the verified same-namespace Game Session workload without end-user context is allowed");
    return false;
  }

  private static boolean requireIndependentOwnerOperation(StreamObserver<?> responseObserver) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        && !TransactionSynchronizationManager.isSynchronizationActive()) {
      return true;
    }
    fail(
        responseObserver,
        Status.FAILED_PRECONDITION,
        "Canonical World hold finalization requires an independent owner transaction");
    return false;
  }

  private static void fail(StreamObserver<?> responseObserver, Status status, String description) {
    responseObserver.onError(status.withDescription(description).asRuntimeException());
  }

  private record ParsedRequest(
      HoldIdentity identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome) {}
}
