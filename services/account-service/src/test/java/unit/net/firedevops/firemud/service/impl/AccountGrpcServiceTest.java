package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.CreateAccountRequest;
import net.firedevops.firemud.account.v1.CreateAccountResponse;
import net.firedevops.firemud.account.v1.PingRequest;
import net.firedevops.firemud.account.v1.PingResponse;
import net.firedevops.firemud.service.AccountService;
import net.firedevops.firemud.service.PingService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AccountGrpcServiceTest {
  @Test
  void pingReturnsPong() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenReturn("pong");
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<PingResponse> ref = new AtomicReference<>();
    service.ping(
        PingRequest.getDefaultInstance(),
        new StreamObserver<>() {
          @Override
          public void onNext(PingResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("pong", ref.get().getMessage());
  }

  @Test
  void authenticateFailureReturnsErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.authenticate(1L, "demo", "bad"))
        .thenThrow(new IllegalArgumentException("invalid"));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<AuthenticateResponse> ref = new AtomicReference<>();
    service.authenticate(
        AuthenticateRequest.newBuilder()
            .setTenantId("1")
            .setUsername("demo")
            .setPassword("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(AuthenticateResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("UNAUTHENTICATED", ref.get().getError().getCode());
  }

  @Test
  void createAccountValidationErrorReturnsErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.createAccount(Mockito.any()))
        .thenThrow(new IllegalArgumentException("bad"));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<CreateAccountResponse> ref = new AtomicReference<>();
    service.createAccount(
        CreateAccountRequest.newBuilder()
            .setTenantId("1")
            .setUsername("demo")
            .setEmail("e@example.com")
            .setPassword("pass")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateAccountResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
  }
}
