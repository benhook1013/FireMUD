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
import net.firedevops.firemud.accountservice.service.PaymentService;
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
  private final PaymentService paymentService;
  private final MeterRegistry meterRegistry;

  public PaymentGrpcService(PaymentService paymentService) {
    this(paymentService, null);
  }

  @Autowired
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected service and registry remain internal collaborators.")
  public PaymentGrpcService(PaymentService paymentService, MeterRegistry meterRegistry) {
    this.paymentService = paymentService;
    this.meterRegistry = meterRegistry;
  }

  @Override
  @Timed(value = "paymentGrpc.createPaymentIntent")
  public void createPaymentIntent(
      CreatePaymentIntentRequest request,
      StreamObserver<CreatePaymentIntentResponse> responseObserver) {
    responseObserver.onNext(
        CreatePaymentIntentResponse.newBuilder()
            .setError(unavailable("CreatePaymentIntent"))
            .build());
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
      var error =
          meterRegistry == null
              ? net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                  .setCode("INVALID_ARGUMENT")
                  .setMessage(ex.getMessage())
                  .build()
              : GrpcAppErrors.error(
                  meterRegistry, logger, "CreateSubscription", "INVALID_ARGUMENT", ex.getMessage());
      responseObserver.onNext(CreateSubscriptionResponse.newBuilder().setError(error).build());
      responseObserver.onCompleted();
      return;
    }

    String message = "Subscription creation is unavailable";
    var error =
        meterRegistry == null
            ? net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                .setCode("FAILED_PRECONDITION")
                .setMessage(message)
                .build()
            : GrpcAppErrors.error(
                meterRegistry, logger, "CreateSubscription", "FAILED_PRECONDITION", message);
    responseObserver.onNext(CreateSubscriptionResponse.newBuilder().setError(error).build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "paymentGrpc.createDonation")
  public void createDonation(
      CreateDonationRequest request, StreamObserver<CreateDonationResponse> responseObserver) {
    responseObserver.onNext(
        CreateDonationResponse.newBuilder().setError(unavailable("CreateDonation")).build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "paymentGrpc.refundPayment")
  public void refundPayment(
      RefundPaymentRequest request, StreamObserver<RefundPaymentResponse> responseObserver) {
    responseObserver.onNext(
        RefundPaymentResponse.newBuilder()
            .setSuccess(false)
            .setError(unavailable("RefundPayment"))
            .build());
    responseObserver.onCompleted();
  }

  private ErrorDetail unavailable(String operation) {
    String message = operation + " is unavailable";
    return meterRegistry == null
        ? ErrorDetail.newBuilder().setCode("FAILED_PRECONDITION").setMessage(message).build()
        : GrpcAppErrors.error(meterRegistry, logger, operation, "FAILED_PRECONDITION", message);
  }
}
