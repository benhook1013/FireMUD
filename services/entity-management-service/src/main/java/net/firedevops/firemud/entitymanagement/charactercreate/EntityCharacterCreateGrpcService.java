package net.firedevops.firemud.entitymanagement.charactercreate;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.function.Supplier;
import net.firedevops.firemud.entitymanagement.v1.AbortProtectedCharacterCreateRequest;
import net.firedevops.firemud.entitymanagement.v1.CommitProtectedCharacterCreateRequest;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import net.firedevops.firemud.entitymanagement.v1.ReadEntityCharacterCreateTerminalRequest;

/** Manually composed only; no annotation, bean, controller, feature flag or route registration. */
public final class EntityCharacterCreateGrpcService
    extends EntityCharacterCreateServiceGrpc.EntityCharacterCreateServiceImplBase {
  private final EntityCharacterCreateService owner;

  public EntityCharacterCreateGrpcService(EntityCharacterCreateService owner) {
    this.owner = Objects.requireNonNull(owner);
  }

  @Override
  public void commitProtectedCharacterCreate(
      CommitProtectedCharacterCreateRequest request,
      StreamObserver<EntityCharacterCreateTerminal> observer) {
    deliver(() -> owner.commit(request), observer);
  }

  @Override
  public void abortProtectedCharacterCreate(
      AbortProtectedCharacterCreateRequest request,
      StreamObserver<EntityCharacterCreateTerminal> observer) {
    deliver(() -> owner.abort(request), observer);
  }

  @Override
  public void readEntityCharacterCreateTerminal(
      ReadEntityCharacterCreateTerminalRequest request,
      StreamObserver<EntityCharacterCreateTerminal> observer) {
    deliver(() -> owner.read(request), observer);
  }

  private static void deliver(
      Supplier<EntityCharacterCreateTerminal> action,
      StreamObserver<EntityCharacterCreateTerminal> observer) {
    try {
      observer.onNext(action.get());
      observer.onCompleted();
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
    } catch (org.jooq.exception.DataAccessException
        | org.springframework.dao.DataAccessException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Entity creation storage unavailable; no terminal inferred")
              .asRuntimeException());
    } catch (RuntimeException invalid) {
      observer.onError(
          Status.DATA_LOSS
              .withDescription("Entity creation owner evidence failed closed")
              .asRuntimeException());
    }
  }
}
