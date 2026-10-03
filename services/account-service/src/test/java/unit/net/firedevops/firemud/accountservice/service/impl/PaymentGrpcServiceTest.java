package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.CreateDonationRequest;
import net.firedevops.firemud.account.v1.CreateDonationResponse;
import net.firedevops.firemud.account.v1.CreatePaymentIntentRequest;
import net.firedevops.firemud.account.v1.CreatePaymentIntentResponse;
import net.firedevops.firemud.account.v1.CreateSubscriptionRequest;
import net.firedevops.firemud.account.v1.CreateSubscriptionResponse;
import net.firedevops.firemud.account.v1.RefundPaymentRequest;
import net.firedevops.firemud.account.v1.RefundPaymentResponse;
import org.junit.jupiter.api.Test;

class PaymentGrpcServiceTest {
  @Test
  void genericPaymentsRemainClosedForRepeatedRequests() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    PaymentGrpcService service = new PaymentGrpcService(meterRegistry);
    CreatePaymentIntentRequest intent =
        CreatePaymentIntentRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setAmountCents(500)
            .build();
    CreateDonationRequest donation =
        CreateDonationRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setAmountCents(100)
            .build();
    RefundPaymentRequest refund =
        RefundPaymentRequest.newBuilder().setTenantId("1").setPaymentId("9").build();
    List<CreatePaymentIntentResponse> intents = new ArrayList<>();
    List<CreateDonationResponse> donations = new ArrayList<>();
    List<RefundPaymentResponse> refunds = new ArrayList<>();

    for (int attempt = 0; attempt < 2; attempt++) {
      service.createPaymentIntent(intent, collecting(intents));
      service.createDonation(donation, collecting(donations));
      service.refundPayment(refund, collecting(refunds));
    }

    assertEquals(2, intents.size());
    assertEquals(2, donations.size());
    assertEquals(2, refunds.size());
    for (CreatePaymentIntentResponse response : intents) {
      assertEquals("FAILED_PRECONDITION", response.getError().getCode());
      assertEquals("", response.getIntentId());
      assertEquals("", response.getClientSecret());
    }
    for (CreateDonationResponse response : donations) {
      assertEquals("FAILED_PRECONDITION", response.getError().getCode());
      assertEquals("", response.getIntentId());
      assertEquals("", response.getClientSecret());
    }
    for (RefundPaymentResponse response : refunds) {
      assertEquals("FAILED_PRECONDITION", response.getError().getCode());
      assertFalse(response.getSuccess());
    }
    assertEquals(
        6.0, meterRegistry.counter("grpc.app_error", "code", "FAILED_PRECONDITION").count());
  }

  private static <T> StreamObserver<T> collecting(List<T> responses) {
    return new StreamObserver<>() {
      @Override
      public void onNext(T value) {
        responses.add(value);
      }

      @Override
      public void onError(Throwable error) {
        throw new AssertionError("Expected an application-level fail-closed response", error);
      }

      @Override
      public void onCompleted() {}
    };
  }

  @Test
  void createPaymentIntentRejectsValidRequestBeforeDispatchAndRecordsErrorMetric() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    PaymentGrpcService service = new PaymentGrpcService(meterRegistry);

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

    assertNotNull(ref.get());
    assertEquals("FAILED_PRECONDITION", ref.get().getError().getCode());
    assertEquals("Generic payment operations are unavailable", ref.get().getError().getMessage());
    assertEquals("", ref.get().getIntentId());
    assertEquals("", ref.get().getClientSecret());
    assertEquals(
        1.0, meterRegistry.counter("grpc.app_error", "code", "FAILED_PRECONDITION").count());
  }

  @Test
  void createSubscriptionReturnsFailedPreconditionWithoutCallingService() {
    PaymentGrpcService service = new PaymentGrpcService(new SimpleMeterRegistry());

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
    assertEquals("FAILED_PRECONDITION", ref.get().getError().getCode());
    assertEquals("Subscription creation is unavailable", ref.get().getError().getMessage());
    assertEquals("", ref.get().getSubscriptionId());
  }

  @Test
  void createDonationRejectsValidRequestBeforeDispatch() {
    PaymentGrpcService service = new PaymentGrpcService(new SimpleMeterRegistry());

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

    assertNotNull(ref.get());
    assertEquals("FAILED_PRECONDITION", ref.get().getError().getCode());
    assertEquals("Generic payment operations are unavailable", ref.get().getError().getMessage());
    assertEquals("", ref.get().getIntentId());
    assertEquals("", ref.get().getClientSecret());
  }

  @Test
  void refundPaymentRejectsValidRequestAndPreservesFailureResponseContract() {
    PaymentGrpcService service = new PaymentGrpcService(new SimpleMeterRegistry());

    AtomicReference<net.firedevops.firemud.account.v1.RefundPaymentResponse> ref =
        new AtomicReference<>();
    service.refundPayment(
        net.firedevops.firemud.account.v1.RefundPaymentRequest.newBuilder()
            .setTenantId("1")
            .setPaymentId("9")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(net.firedevops.firemud.account.v1.RefundPaymentResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertFalse(ref.get().getSuccess());
    assertEquals("FAILED_PRECONDITION", ref.get().getError().getCode());
  }

  @Test
  void createPaymentIntentRejectsMalformedRequestBeforeDispatch() {
    PaymentGrpcService service = new PaymentGrpcService(new SimpleMeterRegistry());

    AtomicReference<CreatePaymentIntentResponse> ref = new AtomicReference<>();
    service.createPaymentIntent(
        CreatePaymentIntentRequest.newBuilder()
            .setTenantId("0")
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

    assertNotNull(ref.get());
    assertEquals("FAILED_PRECONDITION", ref.get().getError().getCode());
    assertEquals("", ref.get().getIntentId());
    assertEquals("", ref.get().getClientSecret());
  }

  @Test
  void createSubscriptionRejectsZeroAccountIdBeforeCreate() {
    PaymentGrpcService service = new PaymentGrpcService(new SimpleMeterRegistry());

    AtomicReference<CreateSubscriptionResponse> ref = new AtomicReference<>();
    service.createSubscription(
        CreateSubscriptionRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("0")
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
    assertEquals("accountId must be positive", ref.get().getError().getMessage());
    assertEquals("", ref.get().getSubscriptionId());
  }

  @Test
  void createDonationRejectsMalformedRequestBeforeDispatch() {
    PaymentGrpcService service = new PaymentGrpcService(new SimpleMeterRegistry());

    AtomicReference<CreateDonationResponse> ref = new AtomicReference<>();
    service.createDonation(
        CreateDonationRequest.newBuilder()
            .setTenantId("0")
            .setAccountId("2")
            .setAmountCents(100)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateDonationResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("FAILED_PRECONDITION", ref.get().getError().getCode());
    assertEquals("", ref.get().getIntentId());
    assertEquals("", ref.get().getClientSecret());
  }

  @Test
  void refundPaymentRejectsMalformedRequestBeforeDispatch() {
    PaymentGrpcService service = new PaymentGrpcService(new SimpleMeterRegistry());

    AtomicReference<RefundPaymentResponse> ref = new AtomicReference<>();
    service.refundPayment(
        RefundPaymentRequest.newBuilder().setTenantId("1").setPaymentId("0").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(RefundPaymentResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertFalse(ref.get().getSuccess());
    assertEquals("FAILED_PRECONDITION", ref.get().getError().getCode());
  }

  @Test
  void disabledGenericPaymentMethodReturnsFailedPreconditionWithoutMeterRegistry() {
    PaymentGrpcService service = new PaymentGrpcService();

    AtomicReference<CreateDonationResponse> ref = new AtomicReference<>();
    service.createDonation(
        CreateDonationRequest.newBuilder().setTenantId("malformed").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateDonationResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("FAILED_PRECONDITION", ref.get().getError().getCode());
    assertEquals("", ref.get().getIntentId());
    assertEquals("", ref.get().getClientSecret());
  }
}
