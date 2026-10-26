package net.firedevops.firemud.accountservice.service.session;

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
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.account.v1.AcquireStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.account.v1.AcquireStartSessionAdmissionProtectionResponse;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository.ImmutableBindingConflictException;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService.OriginalAuthorizationDeniedException;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AccountStartSessionAdmissionProtectionGrpcServiceTest {
  private static final String NAMESPACE = "world-runtime";

  private final AccountStartSessionAdmissionProtectionAcquisitionService owner =
      mock(AccountStartSessionAdmissionProtectionAcquisitionService.class);
  private final AccountStartSessionAdmissionProtectionGrpcService service =
      AccountStartSessionAdmissionProtectionManualFactory.createUnregisteredReceiver(
          owner, NAMESPACE);

  @AfterEach
  void clearCallerContext() {
    SessionContext.clear();
  }

  @Test
  void rejectsWrongPeerNamespaceAndEndUserBeforeRequestDecodeOrOwnerInvocation() {
    var malformed = AcquireStartSessionAdmissionProtectionRequest.getDefaultInstance();
    assertDenied(null, malformed, Status.Code.UNAUTHENTICATED);
    assertDenied(
        peer(NAMESPACE, "world-management-service"), malformed, Status.Code.PERMISSION_DENIED);
    assertDenied(peer("another", "game-session-service"), malformed, Status.Code.PERMISSION_DENIED);

    SessionContext.setContext("123", List.of(), Map.of());
    assertDenied(peer(NAMESPACE, "game-session-service"), malformed, Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsWrongCanonicalNamespaceAndMalformedEnvelopeBeforeOwner() {
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer(NAMESPACE, "game-session-service"))
        .run(
            () -> {
              var wrongNamespace =
                  AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(
                      AccountStartSessionAdmissionProtectionGrpcTestFixture.request("another"));
              var wrongNamespaceObserver = new Capture();
              service.acquireStartSessionAdmissionProtection(
                  wrongNamespace, wrongNamespaceObserver);
              assertThat(wrongNamespaceObserver.code()).isEqualTo(Status.Code.PERMISSION_DENIED);

              var malformed =
                  AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(
                          AccountStartSessionAdmissionProtectionGrpcTestFixture.request(NAMESPACE))
                      .toBuilder()
                      .addSchemaVersion(1)
                      .build();
              var malformedObserver = new Capture();
              service.acquireStartSessionAdmissionProtection(malformed, malformedObserver);
              assertThat(malformedObserver.code()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            });
    verifyNoInteractions(owner);
  }

  @Test
  void sanitizesOwnerFailureAfterTheExactCanonicalRequestPassesTheGuard() {
    AccountStartSessionAdmissionProtectionRequest request =
        AccountStartSessionAdmissionProtectionGrpcTestFixture.request(NAMESPACE);
    when(owner.acquire(any()))
        .thenThrow(
            Status.FAILED_PRECONDITION
                .withDescription("private Account detail")
                .asRuntimeException());
    var response = new Capture();

    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer(NAMESPACE, "game-session-service"))
        .run(
            () ->
                service.acquireStartSessionAdmissionProtection(
                    AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(request),
                    response));

    assertThat(response.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(response.errorDescription)
        .isEqualTo("Account admission-protection acquisition denied or unavailable")
        .doesNotContain("private Account detail");
    assertThat(response.value).isNull();
    assertThat(response.completed).isFalse();
    verify(owner).acquire(any());
  }

  @Test
  void mapsImmutableBindingConflictToFailedPrecondition() {
    assertExpectedOwnerFailureMapsToFailedPrecondition(
        new ImmutableBindingConflictException("private binding detail"));
  }

  @Test
  void mapsOriginalAuthorizationDenialToFailedPrecondition() {
    assertExpectedOwnerFailureMapsToFailedPrecondition(
        new OriginalAuthorizationDeniedException("private authorization detail"));
  }

  @Test
  void mapsLockNotAvailableSqlStateToUnavailable() {
    assertSqlFailureMapsTo(
        new DataAccessException(
            "private database detail", new SQLException("private SQL detail", "55P03")),
        Status.Code.UNAVAILABLE);
  }

  @Test
  void mapsUnexpectedSqlStateToInternal() {
    assertSqlFailureMapsTo(
        new DataAccessException(
            "private database detail", new SQLException("private SQL detail", "23505")),
        Status.Code.INTERNAL);
  }

  @Test
  void sanitizesUnexpectedOwnerIllegalStateAsInternal() {
    AccountStartSessionAdmissionProtectionRequest request =
        AccountStartSessionAdmissionProtectionGrpcTestFixture.request(NAMESPACE);
    when(owner.acquire(any())).thenThrow(new IllegalStateException("private owner detail"));
    var response = new Capture();

    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer(NAMESPACE, "game-session-service"))
        .run(
            () ->
                service.acquireStartSessionAdmissionProtection(
                    AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(request),
                    response));

    assertThat(response.code()).isEqualTo(Status.Code.INTERNAL);
    assertThat(response.errorDescription)
        .isEqualTo("Account admission-protection acquisition denied or unavailable")
        .doesNotContain("private owner detail");
    assertThat(response.value).isNull();
    assertThat(response.completed).isFalse();
    verify(owner).acquire(any());
  }

  @Test
  void manualFactoryCreatesTheUnregisteredReceiver() {
    assertThat(service).isInstanceOf(AccountStartSessionAdmissionProtectionGrpcService.class);
  }

  private void assertDenied(
      GrpcPeerIdentity peer,
      AcquireStartSessionAdmissionProtectionRequest request,
      Status.Code expected) {
    var observer = new Capture();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(() -> service.acquireStartSessionAdmissionProtection(request, observer));
    assertThat(observer.code()).isEqualTo(expected);
  }

  private void assertExpectedOwnerFailureMapsToFailedPrecondition(RuntimeException failure) {
    AccountStartSessionAdmissionProtectionRequest request =
        AccountStartSessionAdmissionProtectionGrpcTestFixture.request(NAMESPACE);
    when(owner.acquire(any())).thenThrow(failure);
    var response = new Capture();

    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer(NAMESPACE, "game-session-service"))
        .run(
            () ->
                service.acquireStartSessionAdmissionProtection(
                    AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(request),
                    response));

    assertThat(response.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(response.errorDescription)
        .isEqualTo("Account admission-protection acquisition denied or unavailable")
        .doesNotContain(failure.getMessage());
    assertThat(response.value).isNull();
    assertThat(response.completed).isFalse();
    verify(owner).acquire(any());
  }

  private void assertSqlFailureMapsTo(RuntimeException failure, Status.Code expected) {
    AccountStartSessionAdmissionProtectionRequest request =
        AccountStartSessionAdmissionProtectionGrpcTestFixture.request(NAMESPACE);
    when(owner.acquire(any())).thenThrow(failure);
    var response = new Capture();

    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer(NAMESPACE, "game-session-service"))
        .run(
            () ->
                service.acquireStartSessionAdmissionProtection(
                    AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(request),
                    response));

    assertThat(response.code()).isEqualTo(expected);
    assertThat(response.errorDescription)
        .isEqualTo("Account admission-protection acquisition denied or unavailable")
        .doesNotContain("private database detail", "private SQL detail");
    assertThat(response.value).isNull();
    assertThat(response.completed).isFalse();
    verify(owner).acquire(any());
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
        .orElseThrow();
  }

  private static final class Capture
      implements StreamObserver<AcquireStartSessionAdmissionProtectionResponse> {
    private AcquireStartSessionAdmissionProtectionResponse value;
    private Status.Code errorCode;
    private String errorDescription;
    private boolean completed;

    @Override
    public void onNext(AcquireStartSessionAdmissionProtectionResponse next) {
      value = next;
    }

    @Override
    public void onError(Throwable failure) {
      Status status = Status.fromThrowable(failure);
      errorCode = status.getCode();
      errorDescription = status.getDescription();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }

    private Status.Code code() {
      return errorCode;
    }
  }
}
