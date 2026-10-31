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
import net.firedevops.firemud.shared.v1.ErrorDetail;
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
    responseObserver.onNext(
        GetBalanceResponse.newBuilder().setError(unavailable("GetBalance")).build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "currencyGrpc.addCurrency")
  public void addCurrency(
      AddCurrencyRequest request, StreamObserver<AddCurrencyResponse> responseObserver) {
    responseObserver.onNext(
        AddCurrencyResponse.newBuilder().setError(unavailable("AddCurrency")).build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "currencyGrpc.spendCurrency")
  public void spendCurrency(
      SpendCurrencyRequest request, StreamObserver<SpendCurrencyResponse> responseObserver) {
    responseObserver.onNext(
        SpendCurrencyResponse.newBuilder().setError(unavailable("SpendCurrency")).build());
    responseObserver.onCompleted();
  }

  private ErrorDetail unavailable(String operation) {
    String message = operation + " is unavailable";
    return meterRegistry == null
        ? ErrorDetail.newBuilder().setCode("FAILED_PRECONDITION").setMessage(message).build()
        : GrpcAppErrors.error(meterRegistry, logger, operation, "FAILED_PRECONDITION", message);
  }
}
