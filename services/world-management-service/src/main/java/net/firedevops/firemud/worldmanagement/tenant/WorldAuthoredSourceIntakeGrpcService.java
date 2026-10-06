package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.ReadRequest;
import net.firedevops.firemud.worldmanagement.v1.IntakeAuthoredWorldSourceRequest;
import net.firedevops.firemud.worldmanagement.v1.IntakeAuthoredWorldSourceResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldAuthoredSourceIntakeServiceGrpc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.grpc.server.service.GrpcService;

/**
 * Opt-in gRPC adapter for authenticated authored-source intake and exact receipt readback. World
 * binds the trusted namespace locally, and each method independently requires the exact same-
 * namespace Game Design mTLS peer.
 */
@GrpcService
@ConditionalOnProperty(
    prefix = "firemud.authored-world-source",
    name = "enabled",
    havingValue = "true")
public final class WorldAuthoredSourceIntakeGrpcService
    extends WorldAuthoredSourceIntakeServiceGrpc.WorldAuthoredSourceIntakeServiceImplBase {
  private final WorldAuthoredSourceIntakeService intakeService;
  private final String trustedNamespace;

  public WorldAuthoredSourceIntakeGrpcService(
      WorldAuthoredSourceIntakeService intakeService,
      @Value("${firemud.grpc.workload-namespace:}") String trustedNamespace) {
    this.intakeService = Objects.requireNonNull(intakeService, "intakeService");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public void intakeAuthoredWorldSource(
      IntakeAuthoredWorldSourceRequest request,
      StreamObserver<IntakeAuthoredWorldSourceResponse> responseObserver) {
    if (!requireAuthenticatedGameDesignPeer(responseObserver)) {
      return;
    }
    IntakeRequest binding;
    try {
      binding = WorldAuthoredSourceIntakeGrpcCodec.fromIntakeRequest(request);
    } catch (IllegalArgumentException exception) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical World intake request is required")
              .asRuntimeException());
      return;
    }
    if (!trustedNamespace.equals(binding.targetNamespace())) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription(
                  "World intake target namespace must match the authenticated workload")
              .asRuntimeException());
      return;
    }

    WorldAuthoredSourceIntakeReceipt receipt;
    try {
      receipt =
          intakeService.intake(
              binding.schemaVersion(),
              binding.targetNamespace(),
              binding.intakeRequestId(),
              binding.canonicalTenantId(),
              binding.worldSlug(),
              binding.sourceOperationId(),
              binding.expectedSourceEvidenceDigest());
    } catch (RuntimeException exception) {
      respondWithOwnerFailure(responseObserver, exception);
      return;
    }

    IntakeAuthoredWorldSourceResponse response;
    try {
      response =
          WorldAuthoredSourceIntakeGrpcCodec.toIntakeResponse(binding, toCommittedReceipt(receipt));
    } catch (IllegalArgumentException exception) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Committed World intake receipt is inconsistent")
              .asRuntimeException());
      return;
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void readAuthoredWorldSourceIntake(
      ReadAuthoredWorldSourceIntakeRequest request,
      StreamObserver<ReadAuthoredWorldSourceIntakeResponse> responseObserver) {
    if (!requireAuthenticatedGameDesignPeer(responseObserver)) {
      return;
    }
    ReadRequest readRequest;
    try {
      readRequest = WorldAuthoredSourceIntakeGrpcCodec.fromReadRequest(request);
    } catch (IllegalArgumentException exception) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical World intake read request is required")
              .asRuntimeException());
      return;
    }
    if (!trustedNamespace.equals(readRequest.binding().targetNamespace())) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription(
                  "World intake target namespace must match the authenticated workload")
              .asRuntimeException());
      return;
    }

    Optional<WorldAuthoredSourceIntakeReceipt> stored;
    try {
      IntakeRequest binding = readRequest.binding();
      stored =
          intakeService.readCommittedReceipt(
              binding.schemaVersion(),
              binding.targetNamespace(),
              readRequest.requestId(),
              binding.intakeRequestId(),
              binding.canonicalTenantId(),
              binding.worldSlug(),
              binding.sourceOperationId(),
              binding.expectedSourceEvidenceDigest());
    } catch (RuntimeException exception) {
      respondWithOwnerFailure(responseObserver, exception);
      return;
    }
    if (stored.isEmpty()) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription("No committed World intake matches the exact request")
              .asRuntimeException());
      return;
    }

    ReadAuthoredWorldSourceIntakeResponse response;
    try {
      response =
          WorldAuthoredSourceIntakeGrpcCodec.toReadResponse(
              readRequest, toCommittedReceipt(stored.orElseThrow()));
    } catch (IllegalArgumentException exception) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Committed World intake receipt is inconsistent")
              .asRuntimeException());
      return;
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private static CommittedReceipt toCommittedReceipt(WorldAuthoredSourceIntakeReceipt receipt) {
    if (receipt == null) {
      throw new IllegalArgumentException("World owner returned no committed intake receipt");
    }
    return new CommittedReceipt(
        receipt.schemaVersion(),
        receipt.targetNamespace(),
        receipt.intakeRequestId(),
        receipt.operationId(),
        receipt.canonicalTenantId(),
        receipt.worldSlug(),
        receipt.sourceOperationId(),
        receipt.sourceEvidenceDigest(),
        receipt.requestDigest(),
        receipt.receiptDigest());
  }

  private boolean requireAuthenticatedGameDesignPeer(StreamObserver<?> responseObserver) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer != null
        && peer.isService("game-design-service")
        && peer.isInNamespace(trustedNamespace)) {
      return true;
    }
    responseObserver.onError(
        Status.PERMISSION_DENIED
            .withDescription("Verified same-namespace Game Design workload is required")
            .asRuntimeException());
    return false;
  }

  private static void respondWithOwnerFailure(
      StreamObserver<?> responseObserver, RuntimeException exception) {
    if (exception instanceof StatusRuntimeException statusException) {
      responseObserver.onError(statusException);
    } else if (exception instanceof SecurityException) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Verified same-namespace Game Design workload is required")
              .asRuntimeException());
    } else if (exception
            instanceof WorldAuthoredSourceIntakeRepository.RegistrationConflictException
        || exception
            instanceof WorldAuthoredSourceIntakeRepository.InvalidIntakeEvidenceException) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("World authored-source intake conflicts with committed evidence")
              .asRuntimeException());
    } else if (exception instanceof IllegalArgumentException) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical World intake binding is required")
              .asRuntimeException());
    } else if (exception instanceof DataAccessResourceFailureException
        || exception instanceof TransientDataAccessException) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("World intake is temporarily unavailable")
              .asRuntimeException());
    } else if (exception instanceof DataAccessException) {
      Status.Code code =
          hasConnectionFailureSqlState(exception) ? Status.Code.UNAVAILABLE : Status.Code.INTERNAL;
      responseObserver.onError(
          Status.fromCode(code)
              .withDescription("World intake storage could not complete the request")
              .asRuntimeException());
    } else if (exception instanceof IllegalStateException) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("World intake is unavailable in the current transaction state")
              .asRuntimeException());
    } else {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("World authored-source intake failed")
              .asRuntimeException());
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
