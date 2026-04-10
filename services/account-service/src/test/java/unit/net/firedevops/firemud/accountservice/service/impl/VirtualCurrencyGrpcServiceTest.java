package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.GetBalanceRequest;
import net.firedevops.firemud.account.v1.GetBalanceResponse;
import net.firedevops.firemud.accountservice.service.VirtualCurrencyService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VirtualCurrencyGrpcServiceTest {
  @Test
  void getBalanceReturnsResponse() {
    VirtualCurrencyService currencyService = Mockito.mock(VirtualCurrencyService.class);
    Mockito.when(currencyService.getBalance(1L, 2L, "GOLD")).thenReturn(123L);
    VirtualCurrencyGrpcService service =
        new VirtualCurrencyGrpcService(currencyService, new SimpleMeterRegistry());

    AtomicReference<GetBalanceResponse> ref = new AtomicReference<>();
    service.getBalance(
        GetBalanceRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setCurrencyCode("GOLD")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(GetBalanceResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals(123L, ref.get().getBalance());
  }

  @Test
  void getBalanceRuntimeFailureReturnsInternalErrorDetail() {
    VirtualCurrencyService currencyService = Mockito.mock(VirtualCurrencyService.class);
    Mockito.when(currencyService.getBalance(1L, 2L, "GOLD"))
        .thenThrow(new IllegalStateException("boom"));
    VirtualCurrencyGrpcService service =
        new VirtualCurrencyGrpcService(currencyService, new SimpleMeterRegistry());

    AtomicReference<GetBalanceResponse> ref = new AtomicReference<>();
    service.getBalance(
        GetBalanceRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setCurrencyCode("GOLD")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(GetBalanceResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INTERNAL", ref.get().getError().getCode());
  }
}
