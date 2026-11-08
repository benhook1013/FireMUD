package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationBuildGrpcCodec;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Evidence;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Snapshot;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.repository.GameSessionCurrentPrivatePlaytestRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshRuntimeAllocationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshTargetRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateWorldPreparationBuildReader;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateWorldPreparationSourceReader;
import net.firedevops.firemud.gamesession.security.PrivateWorldPreparationReadWorkloadGuard;
import net.firedevops.firemud.gamesession.v1.PrivateWorldPreparationReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationBuildResponse;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationRequest;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Unregistered World-purpose read of existing committed private source owners; no writes. */
public final class PrivateWorldPreparationReadGrpcService
    extends PrivateWorldPreparationReadServiceGrpc.PrivateWorldPreparationReadServiceImplBase {
  private final GameSessionPrivateWorldPreparationSourceReader source;
  private final TransactionOperations read;
  private final GameSessionPrivateWorldPreparationBuildReader builds;
  private final PrivateWorldPreparationReadWorkloadGuard guard;
  private final String namespace;
  private final Clock clock;

  public PrivateWorldPreparationReadGrpcService(
      GameSessionCurrentPrivatePlaytestRepository current,
      GameSessionPrivateFreshTargetRepository targets,
      GameSessionPrivateFreshRuntimeAllocationRepository runtimes,
      PlatformTransactionManager transactions,
      String namespace) {
    this(current, targets, runtimes, transaction(transactions), namespace, Clock.systemUTC());
  }

  PrivateWorldPreparationReadGrpcService(
      GameSessionCurrentPrivatePlaytestRepository current,
      GameSessionPrivateFreshTargetRepository targets,
      GameSessionPrivateFreshRuntimeAllocationRepository runtimes,
      TransactionOperations read,
      String namespace,
      Clock clock) {
    this(current, targets, runtimes, read, namespace, clock, null);
  }

  public PrivateWorldPreparationReadGrpcService(
      GameSessionCurrentPrivatePlaytestRepository current,
      GameSessionPrivateFreshTargetRepository targets,
      GameSessionPrivateFreshRuntimeAllocationRepository runtimes,
      PlatformTransactionManager transactions,
      String namespace,
      GameSessionPrivateWorldPreparationBuildReader builds) {
    this(
        current,
        targets,
        runtimes,
        transaction(transactions),
        namespace,
        Clock.systemUTC(),
        Objects.requireNonNull(builds));
  }

  PrivateWorldPreparationReadGrpcService(
      GameSessionCurrentPrivatePlaytestRepository current,
      GameSessionPrivateFreshTargetRepository targets,
      GameSessionPrivateFreshRuntimeAllocationRepository runtimes,
      TransactionOperations read,
      String namespace,
      Clock clock,
      GameSessionPrivateWorldPreparationBuildReader builds) {
    this.builds = builds;
    this.source = new GameSessionPrivateWorldPreparationSourceReader(current, targets, runtimes);
    this.read = Objects.requireNonNull(read);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Namespace required");
    this.namespace = namespace;
    this.clock = Objects.requireNonNull(clock);
    guard = new PrivateWorldPreparationReadWorkloadGuard(namespace);
  }

  @Override
  public void readPrivateWorldPreparation(
      ReadPrivateWorldPreparationRequest wire,
      StreamObserver<ReadPrivateWorldPreparationResponse> observer) {
    Objects.requireNonNull(observer);
    try {
      guard.requireWorldCaller();
    } catch (StatusRuntimeException denied) {
      fail(observer, denied.getStatus());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    }
    final PrivateWorldPreparationReadGrpcCodec.Request request;
    try {
      request = PrivateWorldPreparationReadGrpcCodec.parseRequest(wire);
      if (!namespace.equals(request.scope().targetNamespace())) {
        fail(observer, Status.PERMISSION_DENIED);
        return;
      }
    } catch (IllegalArgumentException malformed) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    }
    final Snapshot snapshot;
    try {
      snapshot = Objects.requireNonNull(read.execute(status -> source.read(request)));
      snapshot.requireCurrentAt(clock.instant());
    } catch (RuntimeException failure) {
      boolean connection = false;
      for (Throwable cause = failure; cause != null; cause = cause.getCause())
        if (cause instanceof SQLException sql
            && sql.getSQLState() != null
            && sql.getSQLState().startsWith("08")) connection = true;
      if (connection
          || failure instanceof org.springframework.transaction.CannotCreateTransactionException
          || failure instanceof org.springframework.dao.DataAccessResourceFailureException
          || failure instanceof org.springframework.dao.TransientDataAccessException)
        fail(observer, Status.UNAVAILABLE);
      else if (failure instanceof IllegalArgumentException
          || failure instanceof IllegalStateException) fail(observer, Status.FAILED_PRECONDITION);
      else fail(observer, Status.INTERNAL);
      return;
    }
    try {
      observer.onNext(PrivateWorldPreparationReadGrpcCodec.toResponse(request, snapshot));
      observer.onCompleted();
    } catch (RuntimeException invalid) {
      fail(observer, Status.INTERNAL);
    }
  }

  @Override
  public void readPrivateWorldPreparationBuild(
      ReadPrivateWorldPreparationRequest wire,
      StreamObserver<ReadPrivateWorldPreparationBuildResponse> observer) {
    Objects.requireNonNull(observer);
    try {
      guard.requireWorldCaller();
      if (builds == null
          || TransactionSynchronizationManager.isActualTransactionActive()
          || TransactionSynchronizationManager.isSynchronizationActive()) {
        fail(observer, Status.FAILED_PRECONDITION);
        return;
      }
      final PrivateWorldPreparationReadGrpcCodec.Request request;
      try {
        request = PrivateWorldPreparationReadGrpcCodec.parseRequest(wire);
      } catch (IllegalArgumentException malformed) {
        fail(observer, Status.INVALID_ARGUMENT);
        return;
      }
      if (!namespace.equals(request.scope().targetNamespace())) {
        fail(observer, Status.PERMISSION_DENIED);
        return;
      }
      var build = Objects.requireNonNull(read.execute(status -> builds.read(request)));
      build.source().requireCurrentAt(clock.instant());
      observer.onNext(
          PrivateWorldPreparationBuildGrpcCodec.toResponse(
              request,
              new PrivateWorldPreparationBuildGrpcCodec.Build(
                  new Evidence(request, build.source()), build.selectedWorld())));
      observer.onCompleted();
    } catch (StatusRuntimeException denied) {
      fail(observer, denied.getStatus());
    } catch (RuntimeException failure) {
      boolean unavailable = false;
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof SQLException sql
            && sql.getSQLState() != null
            && sql.getSQLState().startsWith("08")) unavailable = true;
      }
      unavailable |=
          failure instanceof org.springframework.transaction.CannotCreateTransactionException
              || failure instanceof org.springframework.dao.DataAccessResourceFailureException
              || failure instanceof org.springframework.dao.TransientDataAccessException;
      fail(observer, unavailable ? Status.UNAVAILABLE : Status.FAILED_PRECONDITION);
    }
  }

  private static TransactionTemplate transaction(PlatformTransactionManager transactions) {
    var read = new TransactionTemplate(Objects.requireNonNull(transactions));
    read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    read.setReadOnly(false); // PostgreSQL shared locks; the callback writes nothing.
    read.setTimeout(5);
    return read;
  }

  private static void fail(StreamObserver<?> observer, Status status) {
    observer.onError(
        Status.fromCode(status.getCode())
            .withDescription("Private World preparation source is unavailable")
            .asRuntimeException());
  }
}
