package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import net.firedevops.firemud.account.v1.CreateDonationRequest;
import net.firedevops.firemud.account.v1.CreateDonationResponse;
import net.firedevops.firemud.account.v1.CreatePaymentIntentRequest;
import net.firedevops.firemud.account.v1.CreatePaymentIntentResponse;
import net.firedevops.firemud.account.v1.CreateSubscriptionRequest;
import net.firedevops.firemud.account.v1.CreateSubscriptionResponse;
import net.firedevops.firemud.account.v1.RefundPaymentRequest;
import net.firedevops.firemud.account.v1.RefundPaymentResponse;
import net.firedevops.firemud.accountservice.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PaymentGrpcServiceTest {
  @Test
  void createPaymentIntentFailsClosedForRepeatedRequestsWithoutCallingService() {
    PaymentService paymentService = Mockito.mock(PaymentService.class);
    PaymentGrpcService service = new PaymentGrpcService(paymentService, new SimpleMeterRegistry());
    List<CreatePaymentIntentResponse> responses = new ArrayList<>();
    CreatePaymentIntentRequest request =
        CreatePaymentIntentRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setAmountCents(500)
            .build();

    service.createPaymentIntent(request, collecting(responses));
    service.createPaymentIntent(request, collecting(responses));

    assertEquals(2, responses.size());
    for (CreatePaymentIntentResponse response : responses) {
      assertNotNull(response);
      assertEquals("FAILED_PRECONDITION", response.getError().getCode());
      assertEquals("CreatePaymentIntent is unavailable", response.getError().getMessage());
    }
    Mockito.verifyNoInteractions(paymentService);
  }

  @Test
  void createSubscriptionReturnsFailedPreconditionWithoutCallingService() {
    PaymentService paymentService = Mockito.mock(PaymentService.class);
    PaymentGrpcService service = new PaymentGrpcService(paymentService, new SimpleMeterRegistry());

    CreateSubscriptionResponse response =
        invoke(
            service,
            CreateSubscriptionRequest.newBuilder()
                .setTenantId("1")
                .setAccountId("2")
                .setPlanId("plan")
                .build());

    assertNotNull(response);
    assertEquals("FAILED_PRECONDITION", response.getError().getCode());
    assertEquals("Subscription creation is unavailable", response.getError().getMessage());
    Mockito.verifyNoInteractions(paymentService);
  }

  @Test
  void createDonationFailsClosedForRepeatedRequestsWithoutCallingService() {
    PaymentService paymentService = Mockito.mock(PaymentService.class);
    PaymentGrpcService service = new PaymentGrpcService(paymentService, new SimpleMeterRegistry());
    List<CreateDonationResponse> responses = new ArrayList<>();
    CreateDonationRequest request =
        CreateDonationRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setAmountCents(100)
            .build();

    service.createDonation(request, collecting(responses));
    service.createDonation(request, collecting(responses));

    assertEquals(2, responses.size());
    for (CreateDonationResponse response : responses) {
      assertNotNull(response);
      assertEquals("FAILED_PRECONDITION", response.getError().getCode());
      assertEquals("CreateDonation is unavailable", response.getError().getMessage());
    }
    Mockito.verifyNoInteractions(paymentService);
  }

  @Test
  void refundPaymentFailsClosedForRepeatedRequestsWithoutCallingService() {
    PaymentService paymentService = Mockito.mock(PaymentService.class);
    PaymentGrpcService service = new PaymentGrpcService(paymentService, new SimpleMeterRegistry());
    List<RefundPaymentResponse> responses = new ArrayList<>();
    RefundPaymentRequest request =
        RefundPaymentRequest.newBuilder().setTenantId("1").setPaymentId("9").build();

    service.refundPayment(request, collecting(responses));
    service.refundPayment(request, collecting(responses));

    assertEquals(2, responses.size());
    for (RefundPaymentResponse response : responses) {
      assertNotNull(response);
      assertFalse(response.getSuccess());
      assertEquals("FAILED_PRECONDITION", response.getError().getCode());
      assertEquals("RefundPayment is unavailable", response.getError().getMessage());
    }
    Mockito.verifyNoInteractions(paymentService);
  }

  @Test
  void createSubscriptionRejectsZeroAccountIdBeforeCreate() {
    PaymentService paymentService = Mockito.mock(PaymentService.class);
    PaymentGrpcService service = new PaymentGrpcService(paymentService, new SimpleMeterRegistry());

    CreateSubscriptionResponse response =
        invoke(
            service,
            CreateSubscriptionRequest.newBuilder()
                .setTenantId("1")
                .setAccountId("0")
                .setPlanId("plan")
                .build());

    assertNotNull(response);
    assertEquals("INVALID_ARGUMENT", response.getError().getCode());
    assertEquals("accountId must be positive", response.getError().getMessage());
    Mockito.verifyNoInteractions(paymentService);
  }

  private static <T> StreamObserver<T> collecting(List<T> responses) {
    return new StreamObserver<>() {
      @Override
      public void onNext(T value) {
        responses.add(value);
      }

      @Override
      public void onError(Throwable t) {}

      @Override
      public void onCompleted() {}
    };
  }

  private static CreateSubscriptionResponse invoke(
      PaymentGrpcService service, CreateSubscriptionRequest request) {
    List<CreateSubscriptionResponse> responses = new ArrayList<>();
    service.createSubscription(request, collecting(responses));
    assertEquals(1, responses.size());
    return responses.getFirst();
  }
}
