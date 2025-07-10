package net.firedevops.firemud.service.impl;

import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.account.v1.CreatePaymentIntentRequest;
import net.firedevops.firemud.account.v1.CreatePaymentIntentResponse;
import net.firedevops.firemud.account.v1.CreateSubscriptionRequest;
import net.firedevops.firemud.account.v1.CreateSubscriptionResponse;
import net.firedevops.firemud.account.v1.PaymentServiceGrpc;
import net.firedevops.firemud.dto.PaymentIntentDto;
import net.firedevops.firemud.dto.SubscriptionDto;
import net.firedevops.firemud.service.PaymentService;
import org.lognet.springboot.grpc.GRpcService;

@GRpcService
public class PaymentGrpcService extends PaymentServiceGrpc.PaymentServiceImplBase {
  private final PaymentService paymentService;

  public PaymentGrpcService(PaymentService paymentService) {
    this.paymentService = paymentService;
  }

  @Override
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
              .setClientSecret("test")
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      CreatePaymentIntentResponse response =
          CreatePaymentIntentResponse.newBuilder()
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
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
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }
}
