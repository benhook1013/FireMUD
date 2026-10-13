package unit.net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.IssueReadinessProbesRequest;
import net.firedevops.firemud.account.v1.IssueReadinessProbesResponse;
import net.firedevops.firemud.account.v1.ValidateReadinessProbeRequest;
import net.firedevops.firemud.account.v1.ValidateReadinessProbeResponse;
import net.firedevops.firemud.accountservice.service.impl.AccountJwtReadinessValidationGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessTransportOwner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.grpc.server.service.GrpcService;

class AccountJwtReadinessValidationGrpcServiceTest {
  @Test
  void serviceBindsOnlyTheDedicatedReadinessTlsInterceptorAndRemainsOptIn() {
    GrpcService grpc =
        AccountJwtReadinessValidationGrpcService.class.getAnnotation(GrpcService.class);
    assertThat(grpc).isNotNull();
    assertThat(grpc.interceptorNames()).containsExactly("accountJwtReadinessTlsInterceptor");
    assertThat(grpc.blendWithGlobalInterceptors()).isFalse();

    ConditionalOnProperty condition =
        AccountJwtReadinessValidationGrpcService.class.getAnnotation(ConditionalOnProperty.class);
    assertThat(condition).isNotNull();
    assertThat(condition.prefix()).isEqualTo("firemud.account.jwt-readiness.validation");
    assertThat(condition.name()).containsExactly("enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
    assertThat(condition.matchIfMissing()).isFalse();
  }

  @Test
  void bothMethodsDenyMissingTlsContextBeforeOwnerAccess() {
    AccountJwtReadinessTransportOwner owner = mock(AccountJwtReadinessTransportOwner.class);
    var service = new AccountJwtReadinessValidationGrpcService(owner);
    RecordingObserver<IssueReadinessProbesResponse> issue = new RecordingObserver<>();
    RecordingObserver<ValidateReadinessProbeResponse> validate = new RecordingObserver<>();

    service.issueReadinessProbes(
        IssueReadinessProbesRequest.newBuilder().setSchemaVersion(1).build(), issue);
    service.validateReadinessProbe(
        ValidateReadinessProbeRequest.newBuilder().setSchemaVersion(1).build(), validate);

    assertDenied(issue);
    assertDenied(validate);
    verifyNoInteractions(owner);
  }

  private static void assertDenied(RecordingObserver<?> observer) {
    assertThat(observer.value.get()).isNull();
    assertThat(observer.completed).isFalse();
    assertThat(Status.fromThrowable(observer.failure.get()).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
  }

  private static final class RecordingObserver<T> implements StreamObserver<T> {
    private final AtomicReference<T> value = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private boolean completed;

    @Override
    public void onNext(T item) {
      value.set(item);
    }

    @Override
    public void onError(Throwable throwable) {
      failure.set(throwable);
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
