package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import net.firedevops.firemud.account.v1.AddCurrencyRequest;
import net.firedevops.firemud.account.v1.AddCurrencyResponse;
import net.firedevops.firemud.account.v1.GetBalanceRequest;
import net.firedevops.firemud.account.v1.GetBalanceResponse;
import net.firedevops.firemud.account.v1.SpendCurrencyRequest;
import net.firedevops.firemud.account.v1.SpendCurrencyResponse;
import net.firedevops.firemud.accountservice.service.VirtualCurrencyService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VirtualCurrencyGrpcServiceTest {
  @Test
  void getBalanceFailsClosedForRepeatedValidRequestsWithoutReadingBalance() {
    VirtualCurrencyService currencyService = Mockito.mock(VirtualCurrencyService.class);
    VirtualCurrencyGrpcService service =
        new VirtualCurrencyGrpcService(currencyService, new SimpleMeterRegistry());
    List<GetBalanceResponse> responses = new ArrayList<>();

    StreamObserver<GetBalanceResponse> observer = collecting(responses);
    GetBalanceRequest request =
        GetBalanceRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setCurrencyCode("GOLD")
            .build();
    service.getBalance(request, observer);
    service.getBalance(request, observer);

    assertUnavailable(responses, "GetBalance is unavailable");
    Mockito.verifyNoInteractions(currencyService);
  }

  @Test
  void addCurrencyFailsClosedForRepeatedValidRequestsWithoutAddingCurrency() {
    VirtualCurrencyService currencyService = Mockito.mock(VirtualCurrencyService.class);
    VirtualCurrencyGrpcService service =
        new VirtualCurrencyGrpcService(currencyService, new SimpleMeterRegistry());
    List<AddCurrencyResponse> responses = new ArrayList<>();

    StreamObserver<AddCurrencyResponse> observer = collecting(responses);
    AddCurrencyRequest request =
        AddCurrencyRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setCurrencyCode("GOLD")
            .setAmount(10)
            .build();
    service.addCurrency(request, observer);
    service.addCurrency(request, observer);

    assertUnavailable(responses, "AddCurrency is unavailable");
    Mockito.verifyNoInteractions(currencyService);
  }

  @Test
  void spendCurrencyFailsClosedForRepeatedValidRequestsWithoutSpendingCurrency() {
    VirtualCurrencyService currencyService = Mockito.mock(VirtualCurrencyService.class);
    VirtualCurrencyGrpcService service =
        new VirtualCurrencyGrpcService(currencyService, new SimpleMeterRegistry());
    List<SpendCurrencyResponse> responses = new ArrayList<>();

    StreamObserver<SpendCurrencyResponse> observer = collecting(responses);
    SpendCurrencyRequest request =
        SpendCurrencyRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setCurrencyCode("GOLD")
            .setAmount(5)
            .build();
    service.spendCurrency(request, observer);
    service.spendCurrency(request, observer);

    assertUnavailable(responses, "SpendCurrency is unavailable");
    Mockito.verifyNoInteractions(currencyService);
  }

  private static <T> StreamObserver<T> collecting(List<T> responses) {
    return new StreamObserver<>() {
      @Override
      public void onNext(T value) {
        responses.add(value);
      }

      @Override
      public void onError(Throwable t) {}

      @Override
      public void onCompleted() {}
    };
  }

  private static void assertUnavailable(List<?> responses, String message) {
    assertEquals(2, responses.size());
    for (Object response : responses) {
      assertNotNull(response);
      if (response instanceof GetBalanceResponse getBalanceResponse) {
        assertEquals("FAILED_PRECONDITION", getBalanceResponse.getError().getCode());
        assertEquals(message, getBalanceResponse.getError().getMessage());
      } else if (response instanceof AddCurrencyResponse addCurrencyResponse) {
        assertEquals("FAILED_PRECONDITION", addCurrencyResponse.getError().getCode());
        assertEquals(message, addCurrencyResponse.getError().getMessage());
      } else if (response instanceof SpendCurrencyResponse spendCurrencyResponse) {
        assertEquals("FAILED_PRECONDITION", spendCurrencyResponse.getError().getCode());
        assertEquals(message, spendCurrencyResponse.getError().getMessage());
      } else {
        throw new AssertionError("Unexpected response type: " + response.getClass());
      }
    }
  }
}
