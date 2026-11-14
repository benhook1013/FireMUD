package net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.stream.Stream;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionRequest;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionResponse;
import net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.CapacityException;
import net.firedevops.firemud.accountservice.security.AccountTenantCapacityWorkloadGuard;
import net.firedevops.firemud.accountservice.service.AccountTenantCapacityOwner;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionTestFixture;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Failure;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Refusal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

/** Controlled handler classification only, not authenticated transport or owner-source proof. */
class AccountTenantCapacityGrpcServiceTest {
  @Test
  void malformedWireAndStructurallyInvalidRequestsRefuseBeforeOwner() {
    var valid =
        TenantCapacityAdmissionGrpcCodec.toRequest(CapacityAdmissionTestFixture.playerRequest());
    for (var wire :
        new CommitTenantCapacityAdmissionRequest[] {
          null,
          CommitTenantCapacityAdmissionRequest.getDefaultInstance(),
          valid.toBuilder().setCapacityDelta(0).build()
        }) {
      try (var guards = authenticatedCaller()) {
        AccountTenantCapacityOwner owner = mock();
        StreamObserver<CommitTenantCapacityAdmissionResponse> observer = mock();

        new AccountTenantCapacityGrpcService(owner, "prod")
            .commitTenantCapacityAdmission(wire, observer);

        assertFailure(observer, Failure.INVALID_ARGUMENT);
        verifyNoInteractions(owner);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("ownerEvidenceFailures")
  void unexpectedOwnerFailuresAreSanitizedInternal(RuntimeException cause) {
    var request = CapacityAdmissionTestFixture.playerRequest();
    try (var guards = authenticatedCaller()) {
      AccountTenantCapacityOwner owner = mock();
      when(owner.commit(request, CapacityAdmissionTestFixture.PEER)).thenThrow(cause);
      StreamObserver<CommitTenantCapacityAdmissionResponse> observer = mock();

      new AccountTenantCapacityGrpcService(owner, "prod")
          .commitTenantCapacityAdmission(
              TenantCapacityAdmissionGrpcCodec.toRequest(request), observer);

      var error = ArgumentCaptor.forClass(Throwable.class);
      verify(observer).onError(error.capture());
      Status status = Status.fromThrowable(error.getValue());
      assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
      assertThat(status.getDescription()).isNull();
      assertThat(status.getCause()).isNull();
      assertThat(error.getValue().getCause()).isNull();
      verify(observer, never()).onNext(any());
      verify(observer, never()).onCompleted();
      verify(owner).commit(request, CapacityAdmissionTestFixture.PEER);
    }
  }

  @Test
  void typedOwnerEvidenceUnavailableKeepsItsPublicFailure() {
    var request = CapacityAdmissionTestFixture.playerRequest();
    try (var guards = authenticatedCaller()) {
      AccountTenantCapacityOwner owner = mock();
      when(owner.commit(request, CapacityAdmissionTestFixture.PEER))
          .thenThrow(new CapacityException(Failure.ENTITLEMENT_UNAVAILABLE));
      StreamObserver<CommitTenantCapacityAdmissionResponse> observer = mock();
      new AccountTenantCapacityGrpcService(owner, "prod")
          .commitTenantCapacityAdmission(
              TenantCapacityAdmissionGrpcCodec.toRequest(request), observer);
      assertFailure(observer, Failure.ENTITLEMENT_UNAVAILABLE);
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Failure.class,
      names = {"TENANT_BILLING_BLOCKED", "CAPACITY_QUOTA_EXCEEDED"})
  void producedBillingAndQuotaDenialsReturnAnExactTypedResponse(Failure code) {
    var request = CapacityAdmissionTestFixture.playerRequest();
    try (var guards = authenticatedCaller()) {
      AccountTenantCapacityOwner owner = mock();
      when(owner.commit(request, CapacityAdmissionTestFixture.PEER))
          .thenThrow(new CapacityException(code));
      StreamObserver<CommitTenantCapacityAdmissionResponse> observer = mock();
      new AccountTenantCapacityGrpcService(owner, "prod")
          .commitTenantCapacityAdmission(
              TenantCapacityAdmissionGrpcCodec.toRequest(request), observer);
      var response = ArgumentCaptor.forClass(CommitTenantCapacityAdmissionResponse.class);
      verify(observer).onNext(response.capture());
      assertThat(TenantCapacityAdmissionGrpcCodec.readRefusalDetails(request, response.getValue()))
          .contains(new Refusal(code, null));
      verify(observer).onCompleted();
      verify(observer, never()).onError(any());
    }
  }

  @Test
  void retainedQuotaDenialCarriesTheExactAccountDecision() {
    var request = CapacityAdmissionTestFixture.playerRequest();
    var decisionId = UUID.randomUUID();
    try (var guards = authenticatedCaller()) {
      AccountTenantCapacityOwner owner = mock();
      when(owner.commit(request, CapacityAdmissionTestFixture.PEER))
          .thenThrow(new CapacityException(Failure.CAPACITY_QUOTA_EXCEEDED, decisionId));
      StreamObserver<CommitTenantCapacityAdmissionResponse> observer = mock();
      new AccountTenantCapacityGrpcService(owner, "prod")
          .commitTenantCapacityAdmission(
              TenantCapacityAdmissionGrpcCodec.toRequest(request), observer);
      var response = ArgumentCaptor.forClass(CommitTenantCapacityAdmissionResponse.class);
      verify(observer).onNext(response.capture());
      assertThat(TenantCapacityAdmissionGrpcCodec.readRefusalDetails(request, response.getValue()))
          .contains(new Refusal(Failure.CAPACITY_QUOTA_EXCEEDED, decisionId));
      verify(observer).onCompleted();
      verify(observer, never()).onError(any());
    }
  }

  private static Stream<RuntimeException> ownerEvidenceFailures() {
    return Stream.of(
        new NumberFormatException("Controlled retained billing parse failure"),
        new IllegalArgumentException("Controlled retained enum or evidence parse failure"),
        new ArithmeticException("Controlled retained arithmetic failure"));
  }

  private static MockedConstruction<AccountTenantCapacityWorkloadGuard> authenticatedCaller() {
    return mockConstruction(
        AccountTenantCapacityWorkloadGuard.class,
        (guard, context) ->
            when(guard.requireAuthenticatedCaller()).thenReturn(CapacityAdmissionTestFixture.PEER));
  }

  private static void assertFailure(
      StreamObserver<CommitTenantCapacityAdmissionResponse> observer, Failure expected) {
    var error = ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(error.capture());
    var expectedFailure = expected.toStatusException();
    assertThat(Status.fromThrowable(error.getValue()).getCode())
        .isEqualTo(expectedFailure.getStatus().getCode());
    assertThat(StatusProto.fromThrowable(error.getValue()))
        .isEqualTo(StatusProto.fromThrowable(expectedFailure));
    assertThat(error.getValue().getCause()).isNull();
    verify(observer, never()).onNext(any());
    verify(observer, never()).onCompleted();
  }
}
