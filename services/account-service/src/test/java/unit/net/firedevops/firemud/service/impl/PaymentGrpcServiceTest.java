package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.CreatePaymentIntentRequest;
import net.firedevops.firemud.account.v1.CreatePaymentIntentResponse;
import net.firedevops.firemud.account.v1.CreateSubscriptionRequest;
import net.firedevops.firemud.account.v1.CreateSubscriptionResponse;
import net.firedevops.firemud.dto.PaymentIntentDto;
import net.firedevops.firemud.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PaymentGrpcServiceTest {
  @Test
  void createPaymentIntentReturnsResponse() {
    PaymentService paymentService = Mockito.mock(PaymentService.class);
    Mockito.when(paymentService.createPaymentIntent(1L, 2L, 500L))
        .thenReturn(new PaymentIntentDto(10L, 1L, 2L, 500L, "USD"));
    PaymentGrpcService service = new PaymentGrpcService(paymentService);

    AtomicReference<CreatePaymentIntentResponse> ref = new AtomicReference<>();
    service.createPaymentIntent(
        CreatePaymentIntentRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setAmountCents(500)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreatePaymentIntentResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("10", ref.get().getIntentId());
  }

  @Test
  void createSubscriptionErrorReturnsErrorDetail() {
    PaymentService paymentService = Mockito.mock(PaymentService.class);
    Mockito.when(paymentService.createSubscription(1L, 2L, "plan"))
        .thenThrow(new IllegalArgumentException("bad"));
    PaymentGrpcService service = new PaymentGrpcService(paymentService);

    AtomicReference<CreateSubscriptionResponse> ref = new AtomicReference<>();
    service.createSubscription(
        CreateSubscriptionRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setPlanId("plan")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateSubscriptionResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
  }
}
