package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
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

  @Test
  void getBalanceRejectsZeroTenantIdBeforeLookup() {
    VirtualCurrencyService currencyService = Mockito.mock(VirtualCurrencyService.class);
    VirtualCurrencyGrpcService service =
        new VirtualCurrencyGrpcService(currencyService, new SimpleMeterRegistry());

    AtomicReference<GetBalanceResponse> ref = new AtomicReference<>();
    service.getBalance(
        GetBalanceRequest.newBuilder()
            .setTenantId("0")
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
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("tenantId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(currencyService);
  }

  @Test
  void addCurrencyRejectsZeroAccountIdBeforeMutation() {
    VirtualCurrencyService currencyService = Mockito.mock(VirtualCurrencyService.class);
    VirtualCurrencyGrpcService service =
        new VirtualCurrencyGrpcService(currencyService, new SimpleMeterRegistry());

    AtomicReference<AddCurrencyResponse> ref = new AtomicReference<>();
    service.addCurrency(
        AddCurrencyRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("0")
            .setCurrencyCode("GOLD")
            .setAmount(10)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(AddCurrencyResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("accountId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(currencyService);
  }

  @Test
  void spendCurrencyRejectsZeroTenantIdBeforeSpend() {
    VirtualCurrencyService currencyService = Mockito.mock(VirtualCurrencyService.class);
    VirtualCurrencyGrpcService service =
        new VirtualCurrencyGrpcService(currencyService, new SimpleMeterRegistry());

    AtomicReference<SpendCurrencyResponse> ref = new AtomicReference<>();
    service.spendCurrency(
        SpendCurrencyRequest.newBuilder()
            .setTenantId("0")
            .setAccountId("2")
            .setCurrencyCode("GOLD")
            .setAmount(5)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(SpendCurrencyResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("tenantId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(currencyService);
  }
}
