package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.ReadState;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Snapshot;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadGrpcCodec.Request;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.repository.GameSessionCurrentPrivatePlaytestRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCurrentPrivatePlaytestRepository.Current;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository;
import net.firedevops.firemud.gamesession.security.CurrentPrivatePlaytestLifecycleReadWorkloadGuard;
import net.firedevops.firemud.gamesession.v1.CurrentPrivatePlaytestLifecycleReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadCurrentPrivatePlaytestLifecycleRequest;
import net.firedevops.firemud.gamesession.v1.ReadCurrentPrivatePlaytestLifecycleResponse;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Directly constructible, deliberately unregistered read of current private-preparation facts. */
public final class CurrentPrivatePlaytestLifecycleReadGrpcService
    extends CurrentPrivatePlaytestLifecycleReadServiceGrpc
        .CurrentPrivatePlaytestLifecycleReadServiceImplBase {
  private static final String UNAVAILABLE_DESCRIPTION =
      "Current private-playtest source is unavailable";
  private static final String INVALID_SOURCE_DESCRIPTION =
      "Current private-playtest source does not match its retained evidence";
  private static final String INTERNAL_DESCRIPTION =
      "Current private-playtest source read failed internally";
  private final GameSessionCurrentPrivatePlaytestRepository repository;
  private final TransactionOperations ownerReadTransaction;
  private final CurrentPrivatePlaytestLifecycleReadWorkloadGuard workloadGuard;
  private final String workloadNamespace;

  public CurrentPrivatePlaytestLifecycleReadGrpcService(
      GameSessionCurrentPrivatePlaytestRepository repository,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this(repository, configuredTransaction(transactionManager), workloadNamespace);
  }

  CurrentPrivatePlaytestLifecycleReadGrpcService(
      GameSessionCurrentPrivatePlaytestRepository repository,
      TransactionOperations ownerReadTransaction,
      String workloadNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.ownerReadTransaction =
        Objects.requireNonNull(ownerReadTransaction, "ownerReadTransaction");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    this.workloadGuard = new CurrentPrivatePlaytestLifecycleReadWorkloadGuard(workloadNamespace);
  }

  @Override
  public void readCurrentPrivatePlaytestLifecycle(
      ReadCurrentPrivatePlaytestLifecycleRequest request,
      StreamObserver<ReadCurrentPrivatePlaytestLifecycleResponse> observer) {
    Objects.requireNonNull(observer, "response observer required");
    try {
      // Authenticate the workload before inspecting request bytes or touching source owners.
      workloadGuard.requireAccountCaller();
    } catch (StatusRuntimeException denied) {
      fail(observer, denied.getStatus());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    }

    final Request selector;
    try {
      if (request == null
          || request.getSerializedSize()
              > CurrentPrivatePlaytestLifecycleReadGrpcCodec.MAX_REQUEST_WIRE_BYTES) {
        throw new IllegalArgumentException("Bounded private-playtest read selector required");
      }
      selector = CurrentPrivatePlaytestLifecycleReadGrpcCodec.parseRequest(request);
      if (!workloadNamespace.equals(selector.scope().targetNamespace())) {
        fail(observer, Status.PERMISSION_DENIED);
        return;
      }
    } catch (IllegalArgumentException malformed) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    } catch (RuntimeException unexpectedParserFailure) {
      fail(observer, Status.INTERNAL);
      return;
    }

    final Optional<Current> current;
    try {
      current =
          ownerReadTransaction.execute(
              status ->
                  repository.readCurrent(
                      selector.scope().targetNamespace(),
                      selector.scope().tenantId(),
                      selector.scope().worldSlug(),
                      selector.scope().realmSlug()));
      if (current == null) {
        throw new IllegalStateException("Current private-playtest owner returned no result");
      }
    } catch (CannotCreateTransactionException
        | DataAccessResourceFailureException
        | TransientDataAccessException unavailable) {
      fail(observer, Status.UNAVAILABLE);
      return;
    } catch (DataAccessException | org.springframework.dao.DataAccessException failure) {
      fail(observer, hasConnectionFailureSqlState(failure) ? Status.UNAVAILABLE : Status.INTERNAL);
      return;
    } catch (GameSessionCurrentPrivatePlaytestRepository.SourceUnavailableException
        | GameSessionInitialPlaytestLifecycleRepository.SourceUnavailableException
        | IllegalArgumentException invalidSource) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    } catch (RuntimeException unavailable) {
      fail(
          observer,
          hasConnectionFailureSqlState(unavailable) ? Status.UNAVAILABLE : Status.INTERNAL);
      return;
    }

    final ReadCurrentPrivatePlaytestLifecycleResponse response;
    try {
      Optional<Snapshot> snapshot =
          current.map(CurrentPrivatePlaytestLifecycleReadGrpcService::snapshot);
      response = CurrentPrivatePlaytestLifecycleReadGrpcCodec.toResponse(selector, snapshot);
    } catch (RuntimeException invalidOwnerResult) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  private static Snapshot snapshot(Current current) {
    var selection = current.selection();
    var preparation = current.selectedPrepSnapshot().preparation();
    var request = preparation.request();
    var source = preparation.source();
    var privatePolicy = source.targetPolicy();
    if (privatePolicy == null) {
      throw new IllegalArgumentException(
          "Current private preparation lacks its exact target policy");
    }
    return new Snapshot(
        new Scope(
            selection.scope().targetNamespace(),
            selection.scope().tenantId(),
            selection.scope().worldSlug(),
            selection.scope().realmSlug()),
        selection.request().requestId(),
        selection.headRevision(),
        selection.requestDigest(),
        selection.selectedAt(),
        request.requestId(),
        preparation.requestDigest(),
        preparation.playtestLifecycleId(),
        preparation.playableStateNamespaceId(),
        preparation.playtestStateGeneration(),
        request.forkExpiresAt(),
        preparation.preparationState(),
        ReadState.valueOf(current.selectedPrepSnapshot().state().name()),
        current.selectedPrepSnapshot().observedAt(),
        request.canonicalVersionId(),
        request.sourceCatalogRealmId(),
        request.sourceCatalogRequestId(),
        request.sourceCatalogRevision(),
        request.publishedReleaseBundleRef(),
        request.publishedReleaseBundleDigest(),
        privatePolicy.policyId(),
        privatePolicy.policyDigest(),
        privatePolicy.source().commitId(),
        privatePolicy.source().revisionId(),
        privatePolicy.source().logicalRevisionId(),
        source.policySetDigest());
  }

  private static TransactionOperations configuredTransaction(
      PlatformTransactionManager transactionManager) {
    Objects.requireNonNull(transactionManager, "transactionManager");
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(5);
    // The current source performs SELECT FOR SHARE and no mutation; PostgreSQL requires a
    // writable transaction for this coherent owner read.
    transaction.setReadOnly(false);
    return transaction;
  }

  private static boolean hasConnectionFailureSqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException exception) {
        String sqlState = exception.getSQLState();
        if (sqlState != null && sqlState.startsWith("08")) return true;
      }
    }
    return false;
  }

  private static void fail(StreamObserver<?> observer, Status status) {
    String description =
        switch (status.getCode()) {
          case FAILED_PRECONDITION -> INVALID_SOURCE_DESCRIPTION;
          case UNAVAILABLE -> UNAVAILABLE_DESCRIPTION;
          case INTERNAL -> INTERNAL_DESCRIPTION;
          case INVALID_ARGUMENT -> "Canonical current private-playtest selector required";
          case PERMISSION_DENIED -> "Exact same-namespace Account workload required";
          case UNAUTHENTICATED -> "Verified Account workload identity required";
          default -> INTERNAL_DESCRIPTION;
        };
    observer.onError(
        Status.fromCode(status.getCode()).withDescription(description).asRuntimeException());
  }
}
