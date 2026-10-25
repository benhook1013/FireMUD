package net.firedevops.firemud.entitymanagement.charactercreate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.function.Supplier;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.entitymanagement.v1.AbortProtectedCharacterCreateRequest;
import net.firedevops.firemud.entitymanagement.v1.CommitProtectedCharacterCreateRequest;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import net.firedevops.firemud.entitymanagement.v1.ReadEntityCharacterCreateTerminalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Manually composed only; no annotation, bean, controller, feature flag or route registration. */
public final class EntityCharacterCreateGrpcService
    extends EntityCharacterCreateServiceGrpc.EntityCharacterCreateServiceImplBase {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(EntityCharacterCreateGrpcService.class);

  private final EntityCharacterCreateService owner;
  private final MeterRegistry meterRegistry;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry is an intentionally shared dependency-injected metrics sink")
  public EntityCharacterCreateGrpcService(
      EntityCharacterCreateService owner, MeterRegistry meterRegistry) {
    this.owner = Objects.requireNonNull(owner);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
  }

  @Override
  public void commitProtectedCharacterCreate(
      CommitProtectedCharacterCreateRequest request,
      StreamObserver<EntityCharacterCreateTerminal> observer) {
    deliver("CommitProtectedCharacterCreate", () -> owner.commit(request), observer);
  }

  @Override
  public void abortProtectedCharacterCreate(
      AbortProtectedCharacterCreateRequest request,
      StreamObserver<EntityCharacterCreateTerminal> observer) {
    deliver("AbortProtectedCharacterCreate", () -> owner.abort(request), observer);
  }

  @Override
  public void readEntityCharacterCreateTerminal(
      ReadEntityCharacterCreateTerminalRequest request,
      StreamObserver<EntityCharacterCreateTerminal> observer) {
    deliver("ReadEntityCharacterCreateTerminal", () -> owner.read(request), observer);
  }

  private void deliver(
      String operation,
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
    } catch (IllegalArgumentException invalidEvidence) {
      observer.onError(
          Status.DATA_LOSS
              .withDescription("Entity creation owner evidence failed validation")
              .withCause(invalidEvidence)
              .asRuntimeException());
    } catch (RuntimeException unexpected) {
      var internal = GrpcAppErrors.internal(meterRegistry, LOGGER, operation, unexpected);
      observer.onError(
          Status.INTERNAL
              .withDescription(internal.getMessage())
              .withCause(unexpected)
              .asRuntimeException());
    }
  }
}
