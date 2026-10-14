package unit.net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;
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
    for (var failure :
        new FailureCase[] {
          new FailureCase(
              new SecurityException("controlled peer refusal"), Status.Code.PERMISSION_DENIED),
          new FailureCase(
              new IllegalArgumentException("owner selector mismatch"),
              Status.Code.FAILED_PRECONDITION),
          new FailureCase(
              new RunOwnedFixtureOwnerReadAuthorization.RequestDecodingException(
                  new IllegalArgumentException("malformed wire request")),
              Status.Code.INVALID_ARGUMENT),
          new FailureCase(
              new DataAccessResourceFailureException("storage unavailable"),
              Status.Code.UNAVAILABLE),
          new FailureCase(
              new TransientDataAccessResourceException("transient storage failure"),
              Status.Code.UNAVAILABLE),
          new FailureCase(
              new CannotCreateTransactionException("transaction unavailable"),
              Status.Code.UNAVAILABLE),
          new FailureCase(
              new DataIntegrityViolationException(
                  "connection failed", new SQLException("connection lost", "08006")),
              Status.Code.UNAVAILABLE),
          new FailureCase(
              new DataIntegrityViolationException(
                  "constraint rejected", new SQLException("duplicate key", "23505")),
              Status.Code.INTERNAL)
        }) {
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
          .isEqualTo(failure.expectedCode());
      assertThat(Status.fromThrowable(versionFailure.get()).getCode())
          .isEqualTo(failure.expectedCode());
      assertThat(Status.fromThrowable(sourceFailure.get()).getDescription())
          .doesNotContain("connection lost", "duplicate key", "storage unavailable");
      verifyNoInteractions(repository, transactions);
    }
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
