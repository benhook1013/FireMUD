package net.firedevops.firemud.accountservice.service.impl;

import com.google.protobuf.Any;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
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
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class VirtualCurrencyGrpcService
    extends VirtualCurrencyServiceGrpc.VirtualCurrencyServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(VirtualCurrencyGrpcService.class);

  private final MeterRegistry meterRegistry;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected registry remains an internal collaborator.")
  public VirtualCurrencyGrpcService(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  @Override
  @Timed(value = "currencyGrpc.getBalance")
  public void getBalance(
      GetBalanceRequest request, StreamObserver<GetBalanceResponse> responseObserver) {
    responseObserver.onError(unavailable("GetBalance"));
  }

  @Override
  @Timed(value = "currencyGrpc.addCurrency")
  public void addCurrency(
      AddCurrencyRequest request, StreamObserver<AddCurrencyResponse> responseObserver) {
    responseObserver.onError(unavailable("AddCurrency"));
  }

  @Override
  @Timed(value = "currencyGrpc.spendCurrency")
  public void spendCurrency(
      SpendCurrencyRequest request, StreamObserver<SpendCurrencyResponse> responseObserver) {
    responseObserver.onError(unavailable("SpendCurrency"));
  }

  private RuntimeException unavailable(String operation) {
    String message = operation + " is unavailable";
    ErrorDetail detail =
        meterRegistry == null
            ? ErrorDetail.newBuilder().setCode("FAILED_PRECONDITION").setMessage(message).build()
            : GrpcAppErrors.error(meterRegistry, logger, operation, "FAILED_PRECONDITION", message);
    return StatusProto.toStatusRuntimeException(
        com.google.rpc.Status.newBuilder()
            .setCode(Status.Code.FAILED_PRECONDITION.value())
            .setMessage(message)
            .addDetails(Any.pack(detail))
            .build());
  }
}
