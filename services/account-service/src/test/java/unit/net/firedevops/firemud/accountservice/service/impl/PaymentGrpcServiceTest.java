package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.CreateDonationRequest;
import net.firedevops.firemud.account.v1.CreateDonationResponse;
import net.firedevops.firemud.account.v1.CreatePaymentIntentRequest;
import net.firedevops.firemud.account.v1.CreatePaymentIntentResponse;
import net.firedevops.firemud.account.v1.CreateSubscriptionRequest;
import net.firedevops.firemud.account.v1.CreateSubscriptionResponse;
import net.firedevops.firemud.accountservice.dto.PaymentIntentDto;
import net.firedevops.firemud.accountservice.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PaymentGrpcServiceTest {
  @Test
  void createPaymentIntentReturnsResponse() {
    PaymentService paymentService = Mockito.mock(PaymentService.class);
    Mockito.when(paymentService.createPaymentIntent(1L, 2L, 500L))
        .thenReturn(new PaymentIntentDto(10L, 1L, 2L, 500L, 25L, 475L, "USD", "secret", false));
    PaymentGrpcService service = new PaymentGrpcService(paymentService);

    AtomicReference<CreatePaymentIntentResponse> ref = new AtomicReference<>();
    service.createPaymentIntent(
        CreatePaymentIntentRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setAmountCents(500)
            .build(),
        new StreamObserver<CreatePaymentIntentResponse>() {
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
    assertEquals("secret", ref.get().getClientSecret());
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
        new StreamObserver<CreateSubscriptionResponse>() {
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

  @Test
  void createDonationReturnsResponse() {
    PaymentService paymentService = Mockito.mock(PaymentService.class);
    Mockito.when(paymentService.createDonation(1L, 2L, 100L))
        .thenReturn(new PaymentIntentDto(11L, 1L, 2L, 100L, 5L, 95L, "USD", "donate", true));
    PaymentGrpcService service = new PaymentGrpcService(paymentService);

    AtomicReference<CreateDonationResponse> ref = new AtomicReference<>();
    service.createDonation(
        CreateDonationRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setAmountCents(100)
            .build(),
        new StreamObserver<CreateDonationResponse>() {
          @Override
          public void onNext(CreateDonationResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("11", ref.get().getIntentId());
    assertEquals("donate", ref.get().getClientSecret());
  }
}
