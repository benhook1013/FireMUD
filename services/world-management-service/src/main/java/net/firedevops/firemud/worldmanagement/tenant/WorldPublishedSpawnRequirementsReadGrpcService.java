package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsGrpcCodec;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedSpawnRequirementsRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedSpawnRequirementsResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldPublishedSpawnRequirementsReadServiceGrpc;

/** Standalone transport adapter; intentionally not registered in production composition. */
final class WorldPublishedSpawnRequirementsReadGrpcService
    extends WorldPublishedSpawnRequirementsReadServiceGrpc
        .WorldPublishedSpawnRequirementsReadServiceImplBase {
  private static final Map<Status.Code, String> DESCRIPTIONS =
      Map.of(
          Status.Code.PERMISSION_DENIED,
          "Published World source read is not authorized",
          Status.Code.INVALID_ARGUMENT,
          "Published World source selector is invalid",
          Status.Code.NOT_FOUND,
          "No exact published World source matches the selector",
          Status.Code.FAILED_PRECONDITION,
          "Published World source evidence is incomplete or inconsistent",
          Status.Code.UNAVAILABLE,
          "Published World source is temporarily unavailable",
          Status.Code.INTERNAL,
          "Published World source read failed");

  private final WorldPublishedSpawnRequirementsReadOwner owner;

  WorldPublishedSpawnRequirementsReadGrpcService(WorldPublishedSpawnRequirementsReadOwner owner) {
    this.owner = Objects.requireNonNull(owner, "owner");
  }

  @Override
  public void readWorldPublishedSpawnRequirements(
      ReadWorldPublishedSpawnRequirementsRequest request,
      StreamObserver<ReadWorldPublishedSpawnRequirementsResponse> responseObserver) {
    final ReadWorldPublishedSpawnRequirementsResponse response;
    try {
      var evidence = owner.read(request);
      response = WorldPublishedSpawnRequirementsGrpcCodec.toResponse(evidence.request(), evidence);
    } catch (WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException rejected) {
      responseObserver.onError(status(rejected.code()));
      return;
    } catch (RuntimeException failure) {
      responseObserver.onError(status(Status.Code.INTERNAL));
      return;
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private static io.grpc.StatusRuntimeException status(Status.Code code) {
    return Status.fromCode(code)
        .withDescription(DESCRIPTIONS.getOrDefault(code, "Published World source read failed"))
        .asRuntimeException();
  }
}
