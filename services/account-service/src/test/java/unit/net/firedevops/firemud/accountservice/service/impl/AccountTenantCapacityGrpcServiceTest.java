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
import java.util.stream.Stream;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionRequest;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionResponse;
import net.firedevops.firemud.accountservice.security.AccountTenantCapacityWorkloadGuard;
import net.firedevops.firemud.accountservice.service.AccountTenantCapacityOwner;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionTestFixture;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Failure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
  void retainedOwnerParseEnumAndArithmeticFailuresAreEntitlementUnavailable(
      RuntimeException cause) {
    var request = CapacityAdmissionTestFixture.playerRequest();
    try (var guards = authenticatedCaller()) {
      AccountTenantCapacityOwner owner = mock();
      when(owner.commit(request, CapacityAdmissionTestFixture.PEER)).thenThrow(cause);
      StreamObserver<CommitTenantCapacityAdmissionResponse> observer = mock();

      new AccountTenantCapacityGrpcService(owner, "prod")
          .commitTenantCapacityAdmission(
              TenantCapacityAdmissionGrpcCodec.toRequest(request), observer);

      assertFailure(observer, Failure.ENTITLEMENT_UNAVAILABLE);
      verify(owner).commit(request, CapacityAdmissionTestFixture.PEER);
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
