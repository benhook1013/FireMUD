package net.firedevops.firemud.entitymanagement.charactercreate;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.function.Supplier;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRuntimeReferenceServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorRuntimeReferenceResponse;
import org.jooq.exception.DataAccessException;

/** Manually composed RPC adapter; it is not registered by Entity component scanning. */
public final class EntityPlayerCreatedRuntimeReferenceGrpcService
    extends CanonicalPlayerCreatedRuntimeReferenceServiceGrpc
        .CanonicalPlayerCreatedRuntimeReferenceServiceImplBase {
  private final EntityPlayerCreatedRuntimeReferenceService owner;

  public EntityPlayerCreatedRuntimeReferenceGrpcService(
      EntityPlayerCreatedRuntimeReferenceService owner) {
    this.owner = Objects.requireNonNull(owner, "owner");
  }

  @Override
  public void readPlayerCreatedActorPlacementReceipt(
      PlayerCreatedActorRuntimeReferenceRequest request,
      StreamObserver<PlayerCreatedActorRuntimeReferenceResponse> observer) {
    deliver(() -> owner.readPlacementReceipt(request), observer);
  }

  @Override
  public void readPlayerCreatedActorRuntimeReference(
      PlayerCreatedActorRuntimeReferenceRequest request,
      StreamObserver<PlayerCreatedActorRuntimeReferenceResponse> observer) {
    deliver(() -> owner.readRuntimeReference(request), observer);
  }

  private static void deliver(
      Supplier<PlayerCreatedActorRuntimeReferenceResponse> read,
      StreamObserver<PlayerCreatedActorRuntimeReferenceResponse> observer) {
    PlayerCreatedActorRuntimeReferenceResponse response;
    try {
      response = read.get();
    } catch (StatusRuntimeException deniedOrUnavailable) {
      observer.onError(deniedOrUnavailable);
      return;
    } catch (DataAccessException | org.springframework.dao.DataAccessException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription(
                  "Entity runtime reference storage unavailable; no reference inferred")
              .withCause(unavailable)
              .asRuntimeException());
      return;
    } catch (IllegalArgumentException invalidEvidence) {
      observer.onError(
          Status.DATA_LOSS
              .withDescription("Original PLAYER_CREATED runtime evidence failed validation")
              .withCause(invalidEvidence)
              .asRuntimeException());
      return;
    } catch (RuntimeException unexpected) {
      observer.onError(
          Status.INTERNAL
              .withDescription("Entity runtime reference read failed")
              .withCause(unexpected)
              .asRuntimeException());
      return;
    }
    try {
      observer.onNext(response);
    } catch (RuntimeException deliveryFailure) {
      observer.onError(deliveryFailure);
      return;
    }
    observer.onCompleted();
  }
}
