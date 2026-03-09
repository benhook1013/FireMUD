package net.firedevops.firemud.accountservice.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.account.v1.AddCurrencyRequest;
import net.firedevops.firemud.account.v1.AddCurrencyResponse;
import net.firedevops.firemud.account.v1.GetBalanceRequest;
import net.firedevops.firemud.account.v1.GetBalanceResponse;
import net.firedevops.firemud.account.v1.SpendCurrencyRequest;
import net.firedevops.firemud.account.v1.SpendCurrencyResponse;
import net.firedevops.firemud.account.v1.VirtualCurrencyServiceGrpc;
import net.firedevops.firemud.accountservice.service.VirtualCurrencyService;
import org.lognet.springboot.grpc.GRpcService;

@GRpcService
public class VirtualCurrencyGrpcService
    extends VirtualCurrencyServiceGrpc.VirtualCurrencyServiceImplBase {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "CurrencyService is injected and not exposed")
  private final VirtualCurrencyService currencyService;

  public VirtualCurrencyGrpcService(VirtualCurrencyService currencyService) {
    this.currencyService = currencyService;
  }

  @Override
  @Timed(value = "currencyGrpc.getBalance")
  public void getBalance(
      GetBalanceRequest request, StreamObserver<GetBalanceResponse> responseObserver) {
    long balance =
        currencyService.getBalance(
            Long.valueOf(request.getTenantId()),
            Long.valueOf(request.getAccountId()),
            request.getCurrencyCode());
    GetBalanceResponse response = GetBalanceResponse.newBuilder().setBalance(balance).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "currencyGrpc.addCurrency")
  public void addCurrency(
      AddCurrencyRequest request, StreamObserver<AddCurrencyResponse> responseObserver) {
    try {
      long balance =
          currencyService.addCurrency(
              Long.valueOf(request.getTenantId()),
              Long.valueOf(request.getAccountId()),
              request.getCurrencyCode(),
              request.getAmount());
      AddCurrencyResponse response = AddCurrencyResponse.newBuilder().setBalance(balance).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      AddCurrencyResponse response =
          AddCurrencyResponse.newBuilder()
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
  @Timed(value = "currencyGrpc.spendCurrency")
  public void spendCurrency(
      SpendCurrencyRequest request, StreamObserver<SpendCurrencyResponse> responseObserver) {
    try {
      long balance =
          currencyService.spendCurrency(
              Long.valueOf(request.getTenantId()),
              Long.valueOf(request.getAccountId()),
              request.getCurrencyCode(),
              request.getAmount());
      SpendCurrencyResponse response =
          SpendCurrencyResponse.newBuilder().setBalance(balance).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      SpendCurrencyResponse response =
          SpendCurrencyResponse.newBuilder()
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
