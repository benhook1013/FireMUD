package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.firedevops.firemud.account.v1.AddCurrencyRequest;
import net.firedevops.firemud.account.v1.AddCurrencyResponse;
import net.firedevops.firemud.account.v1.GetBalanceRequest;
import net.firedevops.firemud.account.v1.GetBalanceResponse;
import net.firedevops.firemud.account.v1.SpendCurrencyRequest;
import net.firedevops.firemud.account.v1.SpendCurrencyResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;

class VirtualCurrencyGrpcServiceTest {
  private static final String ACCOUNT_ID = "11111111-1111-4111-8111-111111111111";

  @Test
  void getBalanceFailsClosedForRepeatedValidRequests() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try {
      VirtualCurrencyGrpcService service = new VirtualCurrencyGrpcService(registry);
      GetBalanceRequest request =
          GetBalanceRequest.newBuilder()
              .setTenantId("1")
              .setAccountId(ACCOUNT_ID)
              .setCurrencyCode("GOLD")
              .build();
      assertRepeatedRefusal(
          (StreamObserver<GetBalanceResponse> observer) -> service.getBalance(request, observer),
          "GetBalance is unavailable");
      assertAppErrorCount(registry, 2);
    } finally {
      registry.close();
    }
  }

  @Test
  void addCurrencyFailsClosedForRepeatedValidRequests() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try {
      VirtualCurrencyGrpcService service = new VirtualCurrencyGrpcService(registry);
      AddCurrencyRequest request =
          AddCurrencyRequest.newBuilder()
              .setTenantId("1")
              .setAccountId(ACCOUNT_ID)
              .setCurrencyCode("GOLD")
              .setAmount(10)
              .build();
      assertRepeatedRefusal(
          (StreamObserver<AddCurrencyResponse> observer) -> service.addCurrency(request, observer),
          "AddCurrency is unavailable");
      assertAppErrorCount(registry, 2);
    } finally {
      registry.close();
    }
  }

  @Test
  void spendCurrencyFailsClosedForRepeatedValidRequests() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try {
      VirtualCurrencyGrpcService service = new VirtualCurrencyGrpcService(registry);
      SpendCurrencyRequest request =
          SpendCurrencyRequest.newBuilder()
              .setTenantId("1")
              .setAccountId(ACCOUNT_ID)
              .setCurrencyCode("GOLD")
              .setAmount(5)
              .build();
      assertRepeatedRefusal(
          (StreamObserver<SpendCurrencyResponse> observer) ->
              service.spendCurrency(request, observer),
          "SpendCurrency is unavailable");
      assertAppErrorCount(registry, 2);
    } finally {
      registry.close();
    }
  }

  @Test
  void nullRegistryStillRefusesAllOperationsBeforeInspectingRequests() throws Exception {
    VirtualCurrencyGrpcService service = new VirtualCurrencyGrpcService(null);
    assertRepeatedRefusal(
        (StreamObserver<GetBalanceResponse> observer) -> service.getBalance(null, observer),
        "GetBalance is unavailable");
    assertRepeatedRefusal(
        (StreamObserver<AddCurrencyResponse> observer) -> service.addCurrency(null, observer),
        "AddCurrency is unavailable");
    assertRepeatedRefusal(
        (StreamObserver<SpendCurrencyResponse> observer) -> service.spendCurrency(null, observer),
        "SpendCurrency is unavailable");
  }

  private static <T> void assertRepeatedRefusal(Consumer<StreamObserver<T>> call, String message)
      throws Exception {
    for (int attempt = 0; attempt < 2; attempt++) {
      RecordingObserver<T> observer = new RecordingObserver<>();
      call.accept(observer);
      assertTrue(observer.responses.isEmpty());
      assertEquals(0, observer.completions);
      assertEquals(1, observer.errors.size());
      Throwable error = observer.errors.get(0);
      assertTrue(error instanceof StatusRuntimeException);
      assertEquals(Status.Code.FAILED_PRECONDITION, Status.fromThrowable(error).getCode());
      assertEquals(message, Status.fromThrowable(error).getDescription());
      com.google.rpc.Status status = StatusProto.fromThrowable(error);
      assertNotNull(status);
      assertEquals(Status.Code.FAILED_PRECONDITION.value(), status.getCode());
      assertEquals(message, status.getMessage());
      assertEquals(1, status.getDetailsCount());
      assertEquals(
          ErrorDetail.newBuilder().setCode("FAILED_PRECONDITION").setMessage(message).build(),
          status.getDetails(0).unpack(ErrorDetail.class));
    }
  }

  private static void assertAppErrorCount(MeterRegistry registry, int expectedCount) {
    assertEquals(
        (double) expectedCount,
        registry.get("grpc.app_error").tag("code", "FAILED_PRECONDITION").counter().count());
    assertEquals(1, registry.getMeters().size());
    assertEquals(
        List.of("code"),
        registry.getMeters().get(0).getId().getTags().stream().map(tag -> tag.getKey()).toList());
  }

  private static final class RecordingObserver<T> implements StreamObserver<T> {
    private final List<T> responses = new ArrayList<>();
    private final List<Throwable> errors = new ArrayList<>();
    private int completions;

    @Override
    public void onNext(T value) {
      responses.add(value);
    }

    @Override
    public void onError(Throwable error) {
      errors.add(error);
    }

    @Override
    public void onCompleted() {
      completions++;
    }
  }
}
