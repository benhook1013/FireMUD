package net.firedevops.firemud.accountservice.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.account.v1.CreateDonationRequest;
import net.firedevops.firemud.account.v1.CreateDonationResponse;
import net.firedevops.firemud.account.v1.CreatePaymentIntentRequest;
import net.firedevops.firemud.account.v1.CreatePaymentIntentResponse;
import net.firedevops.firemud.account.v1.CreateSubscriptionRequest;
import net.firedevops.firemud.account.v1.CreateSubscriptionResponse;
import net.firedevops.firemud.account.v1.PaymentServiceGrpc;
import net.firedevops.firemud.account.v1.RefundPaymentRequest;
import net.firedevops.firemud.account.v1.RefundPaymentResponse;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class PaymentGrpcService extends PaymentServiceGrpc.PaymentServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(PaymentGrpcService.class);
  private static final String GENERIC_PAYMENT_UNAVAILABLE_MESSAGE =
      "Generic payment operations are unavailable";
  private final MeterRegistry meterRegistry;

  @Autowired
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The injected registry is an internal metrics collaborator.")
  public PaymentGrpcService(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  PaymentGrpcService() {
    this(null);
  }

  @Override
  @Timed(value = "paymentGrpc.createPaymentIntent")
  public void createPaymentIntent(
      CreatePaymentIntentRequest request,
      StreamObserver<CreatePaymentIntentResponse> responseObserver) {
    // ADR 0143 defers generic payment intents; reject before validating or dispatching the request.
    CreatePaymentIntentResponse response =
        CreatePaymentIntentResponse.newBuilder()
            .setError(
                errorDetail(
                    "CreatePaymentIntent",
                    "FAILED_PRECONDITION",
                    GENERIC_PAYMENT_UNAVAILABLE_MESSAGE))
            .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "paymentGrpc.createSubscription")
  public void createSubscription(
      CreateSubscriptionRequest request,
      StreamObserver<CreateSubscriptionResponse> responseObserver) {
    try {
      RequestIdValidation.requirePositiveLong(request.getTenantId(), "tenantId");
      RequestIdValidation.requirePositiveLong(request.getAccountId(), "accountId");
    } catch (IllegalArgumentException ex) {
      ErrorDetail error = errorDetail("CreateSubscription", "INVALID_ARGUMENT", ex.getMessage());
      responseObserver.onNext(CreateSubscriptionResponse.newBuilder().setError(error).build());
      responseObserver.onCompleted();
      return;
    }

    String message = "Subscription creation is unavailable";
    ErrorDetail error = errorDetail("CreateSubscription", "FAILED_PRECONDITION", message);
    responseObserver.onNext(CreateSubscriptionResponse.newBuilder().setError(error).build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "paymentGrpc.createDonation")
  public void createDonation(
      CreateDonationRequest request, StreamObserver<CreateDonationResponse> responseObserver) {
    CreateDonationResponse response =
        CreateDonationResponse.newBuilder()
            .setError(
                errorDetail(
                    "CreateDonation", "FAILED_PRECONDITION", GENERIC_PAYMENT_UNAVAILABLE_MESSAGE))
            .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "paymentGrpc.refundPayment")
  public void refundPayment(
      RefundPaymentRequest request, StreamObserver<RefundPaymentResponse> responseObserver) {
    RefundPaymentResponse response =
        RefundPaymentResponse.newBuilder()
            .setSuccess(false)
            .setError(
                errorDetail(
                    "RefundPayment", "FAILED_PRECONDITION", GENERIC_PAYMENT_UNAVAILABLE_MESSAGE))
            .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private ErrorDetail errorDetail(String operation, String code, String message) {
    if (meterRegistry != null) {
      return GrpcAppErrors.error(meterRegistry, logger, operation, code, message);
    }

    String normalizedMessage = message == null || message.isBlank() ? code : message;
    logger.warn("{} returned app error {}: {}", operation, code, normalizedMessage);
    return ErrorDetail.newBuilder().setCode(code).setMessage(normalizedMessage).build();
  }
}
