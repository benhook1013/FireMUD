package net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.account.v1.ReadPlayerAdmissionEntitlementRequest;
import net.firedevops.firemud.account.v1.ReadPlayerAdmissionEntitlementResponse;
import net.firedevops.firemud.accountservice.service.AccountPlayerAdmissionEntitlementOwner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Direct method calls cannot install the receiver's TLS-derived private peer assertion. */
class AccountPlayerAdmissionEntitlementGrpcServiceTest {
  @Test
  @SuppressWarnings("unchecked")
  void authenticationPrecedesEvenNullMalformedAndOversizedCredentialParsing() {
    var owner = mock(AccountPlayerAdmissionEntitlementOwner.class);
    var service = new AccountPlayerAdmissionEntitlementGrpcService(owner, "prod");
    for (var request :
        new ReadPlayerAdmissionEntitlementRequest[] {
          null,
          ReadPlayerAdmissionEntitlementRequest.getDefaultInstance(),
          ReadPlayerAdmissionEntitlementRequest.newBuilder()
              .addInitialLoginJwt("private-token".repeat(4000))
              .build()
        }) {
      StreamObserver<ReadPlayerAdmissionEntitlementResponse> observer = mock(StreamObserver.class);

      service.readPlayerAdmissionEntitlement(request, observer);

      var failure = ArgumentCaptor.forClass(Throwable.class);
      verify(observer).onError(failure.capture());
      assertThat(Status.fromThrowable(failure.getValue()).getCode())
          .isEqualTo(Status.Code.UNAUTHENTICATED);
      assertThat(failure.getValue().getMessage()).doesNotContain("private-token");
      verify(observer, never()).onNext(any());
      verify(observer, never()).onCompleted();
    }
    verifyNoInteractions(owner);
  }
}
