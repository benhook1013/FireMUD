package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
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
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    VirtualCurrencyGrpcService service =
        new VirtualCurrencyGrpcService(currencyService, meterRegistry);
    List<GetBalanceResponse> responses = new ArrayList<>();
    CompletionCounter completions = new CompletionCounter();

    StreamObserver<GetBalanceResponse> observer = collecting(responses, completions);
    GetBalanceRequest request =
        GetBalanceRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("11111111-1111-4111-8111-111111111111")
            .setCurrencyCode("GOLD")
            .build();
    service.getBalance(request, observer);
    service.getBalance(request, observer);

    assertUnavailable(responses, completions, 2, "GetBalance is unavailable");
    assertAppErrorCount(meterRegistry, 2);
    Mockito.verifyNoInteractions(currencyService);
  }

  @Test
  void addCurrencyFailsClosedForRepeatedValidRequestsWithoutAddingCurrency() {
    VirtualCurrencyService currencyService = Mockito.mock(VirtualCurrencyService.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    VirtualCurrencyGrpcService service =
        new VirtualCurrencyGrpcService(currencyService, meterRegistry);
    List<AddCurrencyResponse> responses = new ArrayList<>();
    CompletionCounter completions = new CompletionCounter();

    StreamObserver<AddCurrencyResponse> observer = collecting(responses, completions);
    AddCurrencyRequest request =
        AddCurrencyRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("11111111-1111-4111-8111-111111111111")
            .setCurrencyCode("GOLD")
            .setAmount(10)
            .build();
    service.addCurrency(request, observer);
    service.addCurrency(request, observer);

    assertUnavailable(responses, completions, 2, "AddCurrency is unavailable");
    assertAppErrorCount(meterRegistry, 2);
    Mockito.verifyNoInteractions(currencyService);
  }

  @Test
  void spendCurrencyFailsClosedForRepeatedValidRequestsWithoutSpendingCurrency() {
    VirtualCurrencyService currencyService = Mockito.mock(VirtualCurrencyService.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    VirtualCurrencyGrpcService service =
        new VirtualCurrencyGrpcService(currencyService, meterRegistry);
    List<SpendCurrencyResponse> responses = new ArrayList<>();
    CompletionCounter completions = new CompletionCounter();

    StreamObserver<SpendCurrencyResponse> observer = collecting(responses, completions);
    SpendCurrencyRequest request =
        SpendCurrencyRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("11111111-1111-4111-8111-111111111111")
            .setCurrencyCode("GOLD")
            .setAmount(5)
            .build();
    service.spendCurrency(request, observer);
    service.spendCurrency(request, observer);

    assertUnavailable(responses, completions, 2, "SpendCurrency is unavailable");
    assertAppErrorCount(meterRegistry, 2);
    Mockito.verifyNoInteractions(currencyService);
  }

  @Test
  void singleArgumentConstructorFailsClosedWithoutOwnerAccess() {
    VirtualCurrencyService currencyService = Mockito.mock(VirtualCurrencyService.class);
    VirtualCurrencyGrpcService service = new VirtualCurrencyGrpcService(currencyService);
    List<GetBalanceResponse> balanceResponses = new ArrayList<>();
    List<AddCurrencyResponse> addResponses = new ArrayList<>();
    List<SpendCurrencyResponse> spendResponses = new ArrayList<>();
    CompletionCounter balanceCompletions = new CompletionCounter();
    CompletionCounter addCompletions = new CompletionCounter();
    CompletionCounter spendCompletions = new CompletionCounter();

    service.getBalance(
        GetBalanceRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("11111111-1111-4111-8111-111111111111")
            .setCurrencyCode("GOLD")
            .build(),
        collecting(balanceResponses, balanceCompletions));
    service.addCurrency(
        AddCurrencyRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("11111111-1111-4111-8111-111111111111")
            .setCurrencyCode("GOLD")
            .setAmount(10)
            .build(),
        collecting(addResponses, addCompletions));
    service.spendCurrency(
        SpendCurrencyRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("11111111-1111-4111-8111-111111111111")
            .setCurrencyCode("GOLD")
            .setAmount(5)
            .build(),
        collecting(spendResponses, spendCompletions));

    assertUnavailable(balanceResponses, balanceCompletions, 1, "GetBalance is unavailable");
    assertUnavailable(addResponses, addCompletions, 1, "AddCurrency is unavailable");
    assertUnavailable(spendResponses, spendCompletions, 1, "SpendCurrency is unavailable");
    Mockito.verifyNoInteractions(currencyService);
  }

  private static <T> StreamObserver<T> collecting(
      List<T> responses, CompletionCounter completions) {
    return new StreamObserver<>() {
      @Override
      public void onNext(T value) {
        responses.add(value);
      }

      @Override
      public void onError(Throwable error) {
        throw new AssertionError("Expected an application-level fail-closed response", error);
      }

      @Override
      public void onCompleted() {
        completions.count++;
      }
    };
  }

  private static void assertUnavailable(
      List<?> responses, CompletionCounter completions, int expectedCalls, String message) {
    assertEquals(expectedCalls, responses.size());
    assertEquals(expectedCalls, completions.count);
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

  private static void assertAppErrorCount(MeterRegistry meterRegistry, int expectedCount) {
    assertEquals(
        (double) expectedCount,
        meterRegistry.get("grpc.app_error").tag("code", "FAILED_PRECONDITION").counter().count());
    assertEquals(1, meterRegistry.getMeters().size());
  }

  private static final class CompletionCounter {
    private int count;
  }
}
