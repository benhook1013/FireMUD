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
import net.firedevops.firemud.accountservice.dto.PaymentIntentDto;
import net.firedevops.firemud.accountservice.dto.SubscriptionDto;
import net.firedevops.firemud.accountservice.service.PaymentService;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
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
    try {
      PaymentIntentDto dto =
          paymentService.createPaymentIntent(
              Long.valueOf(request.getTenantId()),
              Long.valueOf(request.getAccountId()),
              request.getAmountCents());
      CreatePaymentIntentResponse response =
          CreatePaymentIntentResponse.newBuilder()
              .setIntentId(dto.id().toString())
              .setClientSecret(dto.clientSecret())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      CreatePaymentIntentResponse response =
          CreatePaymentIntentResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "CreatePaymentIntent",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      CreatePaymentIntentResponse response =
          CreatePaymentIntentResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "CreatePaymentIntent", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "paymentGrpc.createSubscription")
  public void createSubscription(
      CreateSubscriptionRequest request,
      StreamObserver<CreateSubscriptionResponse> responseObserver) {
    try {
      SubscriptionDto dto =
          paymentService.createSubscription(
              Long.valueOf(request.getTenantId()),
              Long.valueOf(request.getAccountId()),
              request.getPlanId());
      CreateSubscriptionResponse response =
          CreateSubscriptionResponse.newBuilder().setSubscriptionId(dto.id().toString()).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      CreateSubscriptionResponse response =
          CreateSubscriptionResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "CreateSubscription",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      CreateSubscriptionResponse response =
          CreateSubscriptionResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "CreateSubscription", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "paymentGrpc.createDonation")
  public void createDonation(
      CreateDonationRequest request, StreamObserver<CreateDonationResponse> responseObserver) {
    try {
      PaymentIntentDto dto =
          paymentService.createDonation(
              Long.valueOf(request.getTenantId()),
              Long.valueOf(request.getAccountId()),
              request.getAmountCents());
      CreateDonationResponse response =
          CreateDonationResponse.newBuilder()
              .setIntentId(dto.id().toString())
              .setClientSecret(dto.clientSecret())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      CreateDonationResponse response =
          CreateDonationResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "CreateDonation", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      CreateDonationResponse response =
          CreateDonationResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "CreateDonation", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "paymentGrpc.refundPayment")
  public void refundPayment(
      RefundPaymentRequest request, StreamObserver<RefundPaymentResponse> responseObserver) {
    try {
      paymentService.refundPayment(
          Long.valueOf(request.getTenantId()), Long.valueOf(request.getPaymentId()));
      RefundPaymentResponse response = RefundPaymentResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      RefundPaymentResponse response =
          RefundPaymentResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "RefundPayment", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      RefundPaymentResponse response =
          RefundPaymentResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "RefundPayment", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }
}
