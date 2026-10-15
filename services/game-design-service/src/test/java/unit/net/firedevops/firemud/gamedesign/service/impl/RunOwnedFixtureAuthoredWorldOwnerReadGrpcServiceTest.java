package unit.net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt;
import net.firedevops.firemud.gamedesign.data.RunOwnedFixtureOwnerReadAuthorization;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.service.impl.RunOwnedFixtureAuthoredWorldSourceReadGrpcService;
import net.firedevops.firemud.gamedesign.service.impl.RunOwnedFixtureAuthoredWorldVersionStateGrpcService;
import net.firedevops.firemud.gamedesign.v1.GetAuthoredWorldVersionStateRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class RunOwnedFixtureAuthoredWorldOwnerReadGrpcServiceTest {
  @Test
  void fixedServicesRemainUnregisteredByDefaultAndUseExistingServiceIdsWhenEnabled() {
    var authorization = mock(RunOwnedFixtureOwnerReadAuthorization.class);
    var repository = mock(GameAuthoredWorldSourceRepository.class);
    var transactions = mock(PlatformTransactionManager.class);
    // This registration test uses a controlled authorization bean, not producer authority.
    var runner =
        new ApplicationContextRunner()
            .withBean(RunOwnedFixtureOwnerReadAuthorization.class, () -> authorization)
            .withBean(GameAuthoredWorldSourceRepository.class, () -> repository)
            .withBean(PlatformTransactionManager.class, () -> transactions)
            .withUserConfiguration(
                RunOwnedFixtureAuthoredWorldSourceReadGrpcService.class,
                RunOwnedFixtureAuthoredWorldVersionStateGrpcService.class);
    runner.run(
        context -> {
          assertThat(context)
              .doesNotHaveBean(RunOwnedFixtureAuthoredWorldSourceReadGrpcService.class);
          assertThat(context)
              .doesNotHaveBean(RunOwnedFixtureAuthoredWorldVersionStateGrpcService.class);
        });
    runner
        .withPropertyValues("FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED=false")
        .run(
            context -> {
              assertThat(context)
                  .doesNotHaveBean(RunOwnedFixtureAuthoredWorldSourceReadGrpcService.class);
              assertThat(context)
                  .doesNotHaveBean(RunOwnedFixtureAuthoredWorldVersionStateGrpcService.class);
            });
    runner
        .withPropertyValues(
            "FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED=true",
            "firemud.grpc.workload-namespace=dev")
        .run(
            context -> {
              assertThat(
                      context
                          .getBean(RunOwnedFixtureAuthoredWorldSourceReadGrpcService.class)
                          .bindService()
                          .getServiceDescriptor()
                          .getName())
                  .isEqualTo("gamedesign.v1.AuthoredWorldSourceReadService");
              assertThat(
                      context
                          .getBean(RunOwnedFixtureAuthoredWorldVersionStateGrpcService.class)
                          .bindService()
                          .getServiceDescriptor()
                          .getName())
                  .isEqualTo("gamedesign.v1.AuthoredWorldVersionStateService");
            });
    verifyNoInteractions(repository, transactions, authorization);
  }

  @Test
  void authorizationDenialAndMissingPreexistingOwnerNeverReachKernelsOrPublishResponses() {
    for (RuntimeException refusal :
        new RuntimeException[] {
          new SecurityException("controlled peer refusal"),
          new IllegalStateException("preexisting policy owner missing")
        }) {
      var authorization = mock(RunOwnedFixtureOwnerReadAuthorization.class);
      var repository = mock(GameAuthoredWorldSourceRepository.class);
      var transactions = mock(PlatformTransactionManager.class);
      when(authorization.authorizeSource(any())).thenThrow(refusal);
      when(authorization.authorizeVersion(any())).thenThrow(refusal);
      var sourceFailure = new AtomicReference<Throwable>();
      var versionFailure = new AtomicReference<Throwable>();
      new RunOwnedFixtureAuthoredWorldSourceReadGrpcService(authorization, repository, "dev")
          .resolveAuthoredWorldSource(
              ResolveAuthoredWorldSourceRequest.getDefaultInstance(),
              refusingObserver(sourceFailure));
      new RunOwnedFixtureAuthoredWorldVersionStateGrpcService(
              authorization, transactions, repository, "dev")
          .getAuthoredWorldVersionState(
              GetAuthoredWorldVersionStateRequest.getDefaultInstance(),
              refusingObserver(versionFailure));
      var expected =
          refusal instanceof SecurityException
              ? Status.Code.PERMISSION_DENIED
              : Status.Code.FAILED_PRECONDITION;
      assertThat(Status.fromThrowable(sourceFailure.get()).getCode()).isEqualTo(expected);
      assertThat(Status.fromThrowable(versionFailure.get()).getCode()).isEqualTo(expected);
      verifyNoInteractions(repository, transactions);
    }
  }

  @Test
  void fixtureReadFailuresKeepRequestAuthorityAndStorageStatusesDistinct() {
    for (var failure : failureCases()) {
      var authorization = mock(RunOwnedFixtureOwnerReadAuthorization.class);
      var repository = mock(GameAuthoredWorldSourceRepository.class);
      var transactions = mock(PlatformTransactionManager.class);
      when(authorization.authorizeSource(any())).thenThrow(failure.exception());
      when(authorization.authorizeVersion(any())).thenThrow(failure.exception());
      var sourceFailure = new AtomicReference<Throwable>();
      var versionFailure = new AtomicReference<Throwable>();
      new RunOwnedFixtureAuthoredWorldSourceReadGrpcService(authorization, repository, "dev")
          .resolveAuthoredWorldSource(
              ResolveAuthoredWorldSourceRequest.getDefaultInstance(),
              refusingObserver(sourceFailure));
      new RunOwnedFixtureAuthoredWorldVersionStateGrpcService(
              authorization, transactions, repository, "dev")
          .getAuthoredWorldVersionState(
              GetAuthoredWorldVersionStateRequest.getDefaultInstance(),
              refusingObserver(versionFailure));
      assertThat(Status.fromThrowable(sourceFailure.get()).getCode())
          .as("source transaction/storage %s", failure.exception().getClass().getSimpleName())
          .isEqualTo(failure.expectedCode());
      assertThat(Status.fromThrowable(versionFailure.get()).getCode())
          .isEqualTo(failure.expectedCode());
      assertThat(Status.fromThrowable(sourceFailure.get()).getDescription())
          .doesNotContain("connection lost", "duplicate key", "storage unavailable");
      if (failure.expectedCode() == Status.Code.UNAVAILABLE
          || failure.expectedCode() == Status.Code.INTERNAL) {
        assertThat(Status.fromThrowable(versionFailure.get()).getDescription())
            .isEqualTo(
                failure.expectedCode() == Status.Code.UNAVAILABLE
                    ? "Game Design Version state is temporarily unavailable"
                    : "Game Design Version state could not be read");
      }
      verifyNoInteractions(repository, transactions);
    }
  }

  @Test
  void authorizedStorageFailuresReachRepositoriesAndTransactions() {
    var sourceRequest =
        ResolveAuthoredWorldSourceRequest.newBuilder()
            .setRequestId("11111111-1111-4111-8111-111111111111")
            .setOperationId("22222222-2222-4222-8222-222222222222")
            .setCanonicalTenantId("33333333-3333-4333-8333-333333333333")
            .setWorldSlug("demo-world")
            .build();
    var versionRequest =
        GetAuthoredWorldVersionStateRequest.newBuilder()
            .setSchemaVersion(1)
            .setTargetNamespace("dev")
            .setReadRequestId(sourceRequest.getRequestId())
            .setSourceOperationId(sourceRequest.getOperationId())
            .setCanonicalTenantId(sourceRequest.getCanonicalTenantId())
            .setWorldSlug(sourceRequest.getWorldSlug())
            .setExpectedSourceEvidenceDigest("sha256:" + "a".repeat(64))
            .setVersionId(1)
            .build();
    var peer =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/dev/sa/world-management-service")
                    .orElseThrow());
    for (var failure : failureCases()) {
      if (failure.expectedCode() != Status.Code.UNAVAILABLE
          && failure.expectedCode() != Status.Code.INTERNAL) continue;
      for (boolean transactionFailure : new boolean[] {false, true}) {
        var authorization = mock(RunOwnedFixtureOwnerReadAuthorization.class);
        var repository = mock(GameAuthoredWorldSourceRepository.class);
        var transactions = mock(PlatformTransactionManager.class);
        var receipt = mock(SourceReceipt.class);
        when(authorization.authorizeSource(sourceRequest)).thenReturn(receipt);
        when(authorization.authorizeVersion(versionRequest)).thenReturn(receipt);
        when(repository.read(any(), any(), any(), any())).thenThrow(failure.exception());
        if (transactionFailure) {
          when(transactions.getTransaction(any())).thenThrow(failure.exception());
        } else {
          when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
          when(repository.readVersionStateSnapshot(
                  any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong()))
              .thenThrow(failure.exception());
        }
        var sourceFailure = new AtomicReference<Throwable>();
        var versionFailure = new AtomicReference<Throwable>();
        peer.run(
            () -> {
              new RunOwnedFixtureAuthoredWorldSourceReadGrpcService(
                      authorization, repository, "dev")
                  .resolveAuthoredWorldSource(sourceRequest, refusingObserver(sourceFailure));
              new RunOwnedFixtureAuthoredWorldVersionStateGrpcService(
                      authorization, transactions, repository, "dev")
                  .getAuthoredWorldVersionState(versionRequest, refusingObserver(versionFailure));
            });
        assertThat(Status.fromThrowable(sourceFailure.get()).getCode())
            .as("authorized source storage %s", failure.exception().getClass().getSimpleName())
            .isEqualTo(failure.expectedCode());
        assertThat(Status.fromThrowable(versionFailure.get()).getCode())
            .as(
                "authorized Version %s failure %s",
                transactionFailure ? "transaction" : "repository",
                failure.exception().getClass().getSimpleName())
            .isEqualTo(failure.expectedCode());
        assertThat(Status.fromThrowable(sourceFailure.get()).getDescription())
            .doesNotContain(failure.exception().getMessage());
        assertThat(Status.fromThrowable(versionFailure.get()).getDescription())
            .doesNotContain(failure.exception().getMessage());
        verify(repository).read(any(), any(), any(), any());
        verify(transactions).getTransaction(any());
        if (!transactionFailure)
          verify(repository)
              .readVersionStateSnapshot(
                  any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
      }
    }
  }

  private static FailureCase[] failureCases() {
    return new FailureCase[] {
      new FailureCase(
          new SecurityException("controlled peer refusal"), Status.Code.PERMISSION_DENIED),
      new FailureCase(
          new IllegalArgumentException("owner selector mismatch"), Status.Code.FAILED_PRECONDITION),
      new FailureCase(
          new RunOwnedFixtureOwnerReadAuthorization.RequestDecodingException(
              new IllegalArgumentException("malformed wire request")),
          Status.Code.INVALID_ARGUMENT),
      new FailureCase(
          new DataAccessResourceFailureException("storage unavailable"), Status.Code.UNAVAILABLE),
      new FailureCase(
          new TransientDataAccessResourceException("transient storage failure"),
          Status.Code.UNAVAILABLE),
      new FailureCase(
          new CannotCreateTransactionException("transaction unavailable"), Status.Code.UNAVAILABLE),
      new FailureCase(
          new org.springframework.transaction.TransactionTimedOutException("transaction timed out"),
          Status.Code.UNAVAILABLE),
      new FailureCase(
          new org.springframework.transaction.IllegalTransactionStateException(
              "propagation mismatch"),
          Status.Code.INTERNAL),
      new FailureCase(
          new org.springframework.transaction.UnexpectedRollbackException("rollback only"),
          Status.Code.INTERNAL),
      new FailureCase(
          new org.springframework.transaction.NestedTransactionNotSupportedException(
              "nested unsupported"),
          Status.Code.INTERNAL),
      new FailureCase(
          new org.springframework.transaction.InvalidIsolationLevelException(
              "isolation unsupported"),
          Status.Code.INTERNAL),
      new FailureCase(
          new DataIntegrityViolationException(
              "connection failed", new SQLException("connection lost", "08006")),
          Status.Code.UNAVAILABLE),
      new FailureCase(
          new DataIntegrityViolationException(
              "constraint rejected", new SQLException("duplicate key", "23505")),
          Status.Code.INTERNAL)
    };
  }

  private record FailureCase(RuntimeException exception, Status.Code expectedCode) {}

  private static <T> StreamObserver<T> refusingObserver(AtomicReference<Throwable> failure) {
    return new StreamObserver<>() {
      @Override
      public void onNext(T value) {
        throw new AssertionError("Refused read emitted success");
      }

      @Override
      public void onError(Throwable error) {
        failure.set(error);
      }

      @Override
      public void onCompleted() {
        throw new AssertionError("Refused read completed");
      }
    };
  }
}
