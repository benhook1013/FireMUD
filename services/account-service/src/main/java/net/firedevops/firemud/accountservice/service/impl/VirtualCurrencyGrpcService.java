package net.firedevops.firemud.accountservice.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.account.v1.AddCurrencyRequest;
import net.firedevops.firemud.account.v1.AddCurrencyResponse;
import net.firedevops.firemud.account.v1.GetBalanceRequest;
import net.firedevops.firemud.account.v1.GetBalanceResponse;
import net.firedevops.firemud.account.v1.SpendCurrencyRequest;
import net.firedevops.firemud.account.v1.SpendCurrencyResponse;
import net.firedevops.firemud.account.v1.VirtualCurrencyServiceGrpc;
import net.firedevops.firemud.accountservice.service.VirtualCurrencyService;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class VirtualCurrencyGrpcService
    extends VirtualCurrencyServiceGrpc.VirtualCurrencyServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(VirtualCurrencyGrpcService.class);

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "CurrencyService is injected and not exposed")
  private final VirtualCurrencyService currencyService;

  private final MeterRegistry meterRegistry;

  public VirtualCurrencyGrpcService(VirtualCurrencyService currencyService) {
    this(currencyService, null);
  }

  @Autowired
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected service and registry remain internal collaborators.")
  public VirtualCurrencyGrpcService(
      VirtualCurrencyService currencyService, MeterRegistry meterRegistry) {
    this.currencyService = currencyService;
    this.meterRegistry = meterRegistry;
  }

  @Override
  @Timed(value = "currencyGrpc.getBalance")
  public void getBalance(
      GetBalanceRequest request, StreamObserver<GetBalanceResponse> responseObserver) {
    try {
      long balance =
          currencyService.getBalance(
              Long.valueOf(request.getTenantId()),
              Long.valueOf(request.getAccountId()),
              request.getCurrencyCode());
      GetBalanceResponse response = GetBalanceResponse.newBuilder().setBalance(balance).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetBalanceResponse response =
          GetBalanceResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "GetBalance", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      GetBalanceResponse response =
          GetBalanceResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "GetBalance", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
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
                  GrpcAppErrors.error(
                      meterRegistry, logger, "AddCurrency", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      AddCurrencyResponse response =
          AddCurrencyResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "AddCurrency", ex))
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
                  GrpcAppErrors.error(
                      meterRegistry, logger, "SpendCurrency", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      SpendCurrencyResponse response =
          SpendCurrencyResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "SpendCurrency", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }
}
