package net.firedevops.firemud.worldmanagement.tenant;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInitialPlayerLocation;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.v1.PlaceCanonicalInitialPlayerLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.PlaceCanonicalInitialPlayerLocationResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalCurrentPlayerLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalCurrentPlayerLocationResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalPlayerLocationServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Standalone protected canonical placement/location adapter; intentionally not runtime-registered.
 */
public final class WorldCanonicalPlayerLocationGrpcService
    extends WorldCanonicalPlayerLocationServiceGrpc.WorldCanonicalPlayerLocationServiceImplBase {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final WorldCanonicalInitialPlayerLocationService placementService;
  private final WorldCanonicalCurrentPlayerLocationService currentLocationService;
  private final String trustedNamespace;

  public WorldCanonicalPlayerLocationGrpcService(
      WorldCanonicalInitialPlayerLocationService placementService,
      WorldCanonicalCurrentPlayerLocationService currentLocationService,
      String trustedNamespace) {
    this.placementService = Objects.requireNonNull(placementService, "placementService");
    this.currentLocationService =
        Objects.requireNonNull(currentLocationService, "currentLocationService");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public void placeCanonicalInitialPlayerLocation(
      PlaceCanonicalInitialPlayerLocationRequest request,
      StreamObserver<PlaceCanonicalInitialPlayerLocationResponse> responseObserver) {
    if (!requireAuthenticatedGameSessionPeer(responseObserver)) return;
    if (hasAmbientTransaction()) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World placement requires an independent owner operation");
      return;
    }

    WorldCanonicalInitialPlayerLocation.Request placementRequest;
    try {
      placementRequest = parsePlacementRequest(request);
    } catch (IllegalArgumentException malformed) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "A complete canonical World placement request is required");
      return;
    }
    if (!trustedNamespace.equals(
        placementRequest.activeLifecycleEvidence().request().targetNamespace())) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Canonical World placement namespace must match the authenticated peer");
      return;
    }

    WorldCanonicalInitialPlayerLocation.Result result;
    try {
      result = placementService.place(placementRequest);
    } catch (WorldCanonicalInitialPlayerLocationService.PlacementDeniedException denied) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Canonical World placement authority was denied");
      return;
    } catch (IllegalArgumentException failure) {
      if (hasPrefix(failure, "IDEMPOTENCY_CONFLICT:")) {
        fail(
            responseObserver,
            Status.ALREADY_EXISTS,
            "Canonical World placement operation conflicts with an existing request");
      } else {
        fail(responseObserver, Status.INTERNAL, "Canonical World placement failed");
      }
      return;
    } catch (IllegalStateException inconsistent) {
      if (hasPrefix(inconsistent, "INITIAL_PLAYER_LOCATION_DENIED:")) {
        fail(
            responseObserver,
            Status.FAILED_PRECONDITION,
            "Canonical World placement evidence is inconsistent");
      } else {
        fail(responseObserver, Status.INTERNAL, "Canonical World placement failed");
      }
      return;
    } catch (TransientDataAccessException unavailable) {
      fail(
          responseObserver,
          Status.UNAVAILABLE,
          "Canonical World placement storage is temporarily unavailable");
      return;
    } catch (DataAccessException permanentStorageFailure) {
      fail(responseObserver, Status.INTERNAL, "Canonical World placement storage failed");
      return;
    } catch (org.springframework.dao.DataAccessException permanentSpringStorageFailure) {
      fail(responseObserver, Status.INTERNAL, "Canonical World placement storage failed");
      return;
    } catch (RuntimeException failure) {
      fail(responseObserver, Status.INTERNAL, "Canonical World placement failed");
      return;
    }

    try {
      requireExactPlacementResult(placementRequest, result);
      if (result.outcome() == WorldCanonicalInitialPlayerLocation.Outcome.CONFLICT) {
        fail(
            responseObserver,
            Status.ALREADY_EXISTS,
            "Canonical World placement conflicts with an existing character location");
        return;
      }
      if (result.outcome() != WorldCanonicalInitialPlayerLocation.Outcome.APPLIED) {
        throw new InvalidOwnerEvidenceException();
      }
      byte[] immutableResult = result.canonicalBytes();
      if (immutableResult.length == 0) throw new InvalidOwnerEvidenceException();
      responseObserver.onNext(
          PlaceCanonicalInitialPlayerLocationResponse.newBuilder()
              .setOperationId(placementRequest.operationId().toString())
              .setCanonicalResultBytes(ByteString.copyFrom(immutableResult))
              .build());
      responseObserver.onCompleted();
    } catch (RuntimeException inconsistent) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World placement result does not match the requested operation");
    }
  }

  @Override
  public void readCanonicalCurrentPlayerLocation(
      ReadCanonicalCurrentPlayerLocationRequest request,
      StreamObserver<ReadCanonicalCurrentPlayerLocationResponse> responseObserver) {
    if (!requireAuthenticatedGameSessionPeer(responseObserver)) return;
    if (hasAmbientTransaction()) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World location read requires an independent owner operation");
      return;
    }

    ParsedCurrentLocationRequest parsed;
    try {
      parsed = parseCurrentLocationRequest(request);
    } catch (IllegalArgumentException malformed) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "A complete canonical World location read request is required");
      return;
    }
    if (!trustedNamespace.equals(
        parsed.placementRequest().activeLifecycleEvidence().request().targetNamespace())) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Canonical World location namespace must match the authenticated peer");
      return;
    }

    java.util.Optional<WorldCanonicalCurrentPlayerLocationRepository.CurrentLocation> current;
    try {
      current = currentLocationService.read(parsed.placementRequest());
    } catch (WorldCanonicalInitialPlayerLocationService.PlacementDeniedException denied) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Canonical World current-location authority was denied");
      return;
    } catch (IllegalStateException inconsistent) {
      if (hasPrefix(inconsistent, "CURRENT_PLAYER_LOCATION_DENIED:")) {
        fail(
            responseObserver,
            Status.FAILED_PRECONDITION,
            "Canonical World current-location evidence is inconsistent");
      } else {
        fail(responseObserver, Status.INTERNAL, "Canonical World location read failed");
      }
      return;
    } catch (TransientDataAccessException unavailable) {
      fail(
          responseObserver,
          Status.UNAVAILABLE,
          "Canonical World location storage is temporarily unavailable");
      return;
    } catch (DataAccessException permanentStorageFailure) {
      fail(responseObserver, Status.INTERNAL, "Canonical World location storage failed");
      return;
    } catch (org.springframework.dao.DataAccessException permanentSpringStorageFailure) {
      fail(responseObserver, Status.INTERNAL, "Canonical World location storage failed");
      return;
    } catch (RuntimeException failure) {
      fail(responseObserver, Status.INTERNAL, "Canonical World location read failed");
      return;
    }

    if (current == null) {
      fail(
          responseObserver,
          Status.INTERNAL,
          "Canonical World location owner returned no read result");
      return;
    }
    if (current.isEmpty()) {
      fail(responseObserver, Status.NOT_FOUND, "Canonical World current placement was not found");
      return;
    }

    try {
      WorldCanonicalCurrentPlayerLocationRepository.CurrentLocation location =
          current.orElseThrow();
      ValidatedCurrentLocation validated =
          validateCurrentLocation(parsed, location, trustedNamespace);
      responseObserver.onNext(
          ReadCanonicalCurrentPlayerLocationResponse.newBuilder()
              .setReadRequestId(parsed.readRequestId().toString())
              .setPlacementOperationId(parsed.placementRequest().operationId().toString())
              .setPlacementRequestDigest(parsed.placementRequest().requestDigest())
              .setCurrentLifecycleEvidenceBytes(
                  ByteString.copyFrom(validated.currentLifecycleEvidenceBytes()))
              .setImmutablePlacementResultBytes(
                  ByteString.copyFrom(validated.placementResultBytes()))
              .setOriginalPlacementLifecycleEvidenceBytes(
                  ByteString.copyFrom(validated.originalPlacementLifecycleEvidenceBytes()))
              .setRegionInstanceId(location.canonicalRegionInstanceId().toString())
              .setOperationalRegionId(location.operationalRegionId().toString())
              .build());
      responseObserver.onCompleted();
    } catch (RuntimeException inconsistent) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World current-location evidence does not match the requested placement");
    }
  }

  private static WorldCanonicalInitialPlayerLocation.Request parsePlacementRequest(
      PlaceCanonicalInitialPlayerLocationRequest request) {
    Objects.requireNonNull(request, "request");
    if (!request.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Unknown canonical World placement request fields");
    }
    byte[] canonicalRequestBytes = request.getCanonicalRequestBytes().toByteArray();
    byte[] lifecycleEvidenceBytes = request.getOriginalLifecycleEvidenceBytes().toByteArray();
    if (canonicalRequestBytes.length == 0 || lifecycleEvidenceBytes.length == 0) {
      throw new IllegalArgumentException("Complete canonical World placement evidence is required");
    }
    return WorldCanonicalInitialPlayerLocation.Request.fromStored(
        canonicalRequestBytes, lifecycleEvidenceBytes);
  }

  private static ParsedCurrentLocationRequest parseCurrentLocationRequest(
      ReadCanonicalCurrentPlayerLocationRequest request) {
    Objects.requireNonNull(request, "request");
    if (!request.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Unknown canonical World location read request fields");
    }
    UUID readRequestId = parseCanonicalUuid(request.getReadRequestId());
    byte[] canonicalRequestBytes = request.getCanonicalPlacementRequestBytes().toByteArray();
    byte[] lifecycleEvidenceBytes = request.getOriginalLifecycleEvidenceBytes().toByteArray();
    if (canonicalRequestBytes.length == 0 || lifecycleEvidenceBytes.length == 0) {
      throw new IllegalArgumentException("Complete canonical World location evidence is required");
    }
    WorldCanonicalInitialPlayerLocation.Request placementRequest =
        WorldCanonicalInitialPlayerLocation.Request.fromStored(
            canonicalRequestBytes, lifecycleEvidenceBytes);
    if (readRequestId.equals(placementRequest.operationId())
        || !readRequestId.equals(
            placementRequest.activeLifecycleEvidence().request().readRequestId())) {
      throw new IllegalArgumentException(
          "Read correlation must be fresh and match the complete current lifecycle evidence");
    }
    return new ParsedCurrentLocationRequest(readRequestId, placementRequest);
  }

  private static UUID parseCanonicalUuid(String value) {
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Read request ID must be a canonical UUID", invalid);
    }
    if (parsed.equals(NIL_UUID) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Read request ID must be a canonical non-nil UUID");
    }
    return parsed;
  }

  private static void requireExactPlacementResult(
      WorldCanonicalInitialPlayerLocation.Request request,
      WorldCanonicalInitialPlayerLocation.Result result) {
    if (result == null
        || result.request() == null
        || !request.operationId().equals(result.request().operationId())
        || !request.requestDigest().equals(result.requestDigest())
        || !Arrays.equals(
            request.canonicalRequestBytes(), result.request().canonicalRequestBytes())) {
      throw new InvalidOwnerEvidenceException();
    }
  }

  private static ValidatedCurrentLocation validateCurrentLocation(
      ParsedCurrentLocationRequest parsed,
      WorldCanonicalCurrentPlayerLocationRepository.CurrentLocation location,
      String trustedNamespace) {
    WorldCanonicalInitialPlayerLocation.Request requested = parsed.placementRequest();
    WorldCanonicalInitialPlayerLocation.Request binding = location.binding();
    if (binding == null
        || !requested.operationId().equals(binding.operationId())
        || !requested.requestDigest().equals(binding.requestDigest())
        || !Arrays.equals(requested.canonicalRequestBytes(), binding.canonicalRequestBytes())) {
      throw new InvalidOwnerEvidenceException();
    }

    WorldCanonicalInstanceLifecycleEvidence currentEvidence =
        Objects.requireNonNull(location.currentLifecycleEvidence(), "currentLifecycleEvidence");
    byte[] currentEvidenceBytes = currentEvidence.canonicalBytes();
    WorldCanonicalInstanceLifecycleEvidence parsedCurrentEvidence =
        WorldCanonicalInstanceLifecycleEvidence.fromStored(currentEvidenceBytes);
    if (!"ACTIVE".equals(parsedCurrentEvidence.lifecycleStatus())
        || !trustedNamespace.equals(parsedCurrentEvidence.request().targetNamespace())
        || !parsed.readRequestId().equals(parsedCurrentEvidence.request().readRequestId())
        || !Arrays.equals(
            requested.normalizedLifecycleEvidenceBytes(),
            WorldCanonicalInitialPlayerLocation.normalizedLifecycleEvidenceBytes(
                currentEvidenceBytes))) {
      throw new InvalidOwnerEvidenceException();
    }

    WorldCanonicalInitialPlayerLocation.Result placementResult = location.placementResult();
    requireExactPlacementResult(requested, placementResult);
    if (placementResult.outcome() != WorldCanonicalInitialPlayerLocation.Outcome.APPLIED
        || !placementResult.startLocation().equals(parsedCurrentEvidence.startLocation())
        || !Objects.equals(
            placementResult.runtimeRoomInstanceId(), parsedCurrentEvidence.runtimeRoomInstanceId())
        || !location.startLocation().equals(parsedCurrentEvidence.startLocation())
        || location.runtimeRoomInstanceId() != parsedCurrentEvidence.runtimeRoomInstanceId()) {
      throw new InvalidOwnerEvidenceException();
    }

    byte[] originalLifecycleEvidenceBytes = location.originalLifecycleEvidenceBytes();
    if (originalLifecycleEvidenceBytes.length == 0) throw new InvalidOwnerEvidenceException();
    WorldCanonicalInstanceLifecycleEvidence originalEvidence =
        WorldCanonicalInstanceLifecycleEvidence.fromStored(originalLifecycleEvidenceBytes);
    if (!trustedNamespace.equals(originalEvidence.request().targetNamespace())
        || parsed.readRequestId().equals(requested.operationId())
        || parsed.readRequestId().equals(originalEvidence.request().readRequestId())
        || !Arrays.equals(
            requested.normalizedLifecycleEvidenceBytes(),
            WorldCanonicalInitialPlayerLocation.normalizedLifecycleEvidenceBytes(
                originalLifecycleEvidenceBytes))) {
      throw new InvalidOwnerEvidenceException();
    }

    UUID canonicalRegionInstanceId = location.canonicalRegionInstanceId();
    UUID operationalRegionId = location.operationalRegionId();
    if (canonicalRegionInstanceId == null
        || operationalRegionId == null
        || NIL_UUID.equals(canonicalRegionInstanceId)
        || NIL_UUID.equals(operationalRegionId)
        || canonicalRegionInstanceId.equals(operationalRegionId)) {
      throw new InvalidOwnerEvidenceException();
    }
    byte[] immutablePlacementResultBytes = placementResult.canonicalBytes();
    if (immutablePlacementResultBytes.length == 0) throw new InvalidOwnerEvidenceException();
    return new ValidatedCurrentLocation(
        currentEvidenceBytes, immutablePlacementResultBytes, originalLifecycleEvidenceBytes);
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

  private static boolean hasAmbientTransaction() {
    return TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive();
  }

  private static boolean hasPrefix(RuntimeException failure, String prefix) {
    return failure.getMessage() != null && failure.getMessage().startsWith(prefix);
  }

  private static void fail(StreamObserver<?> responseObserver, Status status, String description) {
    responseObserver.onError(status.withDescription(description).asRuntimeException());
  }

  private record ParsedCurrentLocationRequest(
      UUID readRequestId, WorldCanonicalInitialPlayerLocation.Request placementRequest) {}

  private record ValidatedCurrentLocation(
      byte[] currentLifecycleEvidenceBytes,
      byte[] placementResultBytes,
      byte[] originalPlacementLifecycleEvidenceBytes) {}

  private static final class InvalidOwnerEvidenceException extends IllegalStateException {}
}
