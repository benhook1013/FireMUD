package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.CreateAccountRequest;
import net.firedevops.firemud.account.v1.CreateAccountResponse;
import net.firedevops.firemud.account.v1.DeleteAccountRequest;
import net.firedevops.firemud.account.v1.DeleteAccountResponse;
import net.firedevops.firemud.account.v1.ExportAccountRequest;
import net.firedevops.firemud.account.v1.ExportAccountResponse;
import net.firedevops.firemud.account.v1.GetProfileRequest;
import net.firedevops.firemud.account.v1.GetProfileResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.account.v1.PingRequest;
import net.firedevops.firemud.account.v1.PingResponse;
import net.firedevops.firemud.account.v1.UpdateProfileRequest;
import net.firedevops.firemud.account.v1.UpdateProfileResponse;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.PingService;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
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
        new StreamObserver<PingResponse>() {
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
    Mockito.when(
            accountService.authenticate(
                Mockito.eq(1L), Mockito.eq("demo"), Mockito.eq("bad"), Mockito.any()))
        .thenThrow(
            new AuthenticationException(
                AuthenticationErrorCodes.INVALID_CREDENTIALS, "Invalid credentials"));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<AuthenticateResponse> ref = new AtomicReference<>();
    service.authenticate(
        AuthenticateRequest.newBuilder()
            .setTenantId("1")
            .setUsername("demo")
            .setPassword("bad")
            .setOtp("")
            .build(),
        new StreamObserver<AuthenticateResponse>() {
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
    assertEquals(AuthenticationErrorCodes.INVALID_CREDENTIALS, ref.get().getError().getCode());
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
            .setTenantId("7")
            .setUsername("demo")
            .setEmail("e@example.com")
            .setPassword("pass")
            .build(),
        new StreamObserver<CreateAccountResponse>() {
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

  @Test
  void createAccountReturnsAccountIdAndTenant() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.createAccount(Mockito.any()))
        .thenReturn(new AccountDto(1L, 7L, "demo", "e@example.com", "player", true));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<CreateAccountResponse> ref = new AtomicReference<>();
    service.createAccount(
        CreateAccountRequest.newBuilder()
            .setTenantId("7")
            .setUsername("demo")
            .setEmail("e@example.com")
            .setPassword("pass")
            .build(),
        new StreamObserver<CreateAccountResponse>() {
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
    assertEquals("1", ref.get().getAccountId());
    org.mockito.ArgumentCaptor<net.firedevops.firemud.accountservice.dto.CreateAccountRequest>
        captor =
            org.mockito.ArgumentCaptor.forClass(
                net.firedevops.firemud.accountservice.dto.CreateAccountRequest.class);
    Mockito.verify(accountService).createAccount(captor.capture());
    assertEquals(7L, captor.getValue().tenantId());
  }

  @Test
  void getProfileReturnsProfile() throws Exception {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.getProfile(1L, 2L))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.ProfileDto(1L, 1L, 2L, "demo", "bio"));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<GetProfileResponse> ref = new AtomicReference<>();
    service.getProfile(
        GetProfileRequest.newBuilder().setTenantId("1").setAccountId("2").build(),
        new StreamObserver<GetProfileResponse>() {
          @Override
          public void onNext(GetProfileResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals(
        "demo",
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readTree(ref.get().getProfileJson())
            .get("displayName")
            .asText());
  }

  @Test
  void getTenantMembershipForRuntimeReturnsResponse() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.getTenantMembershipForRuntime(2L, 1L, "req-1"))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.RuntimeMembershipDto(
                2L, 1L, true, 44L, "2026-03-30T00:00:00Z"));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<GetTenantMembershipForRuntimeResponse> ref = new AtomicReference<>();
    service.getTenantMembershipForRuntime(
        GetTenantMembershipForRuntimeRequest.newBuilder()
            .setAccountId("2")
            .setTenantId("1")
            .setRequestId("req-1")
            .build(),
        new StreamObserver<GetTenantMembershipForRuntimeResponse>() {
          @Override
          public void onNext(GetTenantMembershipForRuntimeResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("2", ref.get().getAccountId());
    assertTrue(ref.get().getGameplayAdmissionAllowed());
    assertEquals(44L, ref.get().getMembershipVersion());
  }

  @Test
  void getTenantEntitlementsForRuntimeReturnsResponse() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.getTenantEntitlementsForRuntime(1L, "req-2"))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.RuntimeEntitlementsDto(
                1L, true, 19L, 311L, "2026-03-30T00:00:00Z"));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<GetTenantEntitlementsForRuntimeResponse> ref = new AtomicReference<>();
    service.getTenantEntitlementsForRuntime(
        GetTenantEntitlementsForRuntimeRequest.newBuilder()
            .setTenantId("1")
            .setRequestId("req-2")
            .build(),
        new StreamObserver<GetTenantEntitlementsForRuntimeResponse>() {
          @Override
          public void onNext(GetTenantEntitlementsForRuntimeResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("1", ref.get().getTenantId());
    assertTrue(ref.get().getGameplayAvailable());
    assertEquals(19L, ref.get().getEntitlementVersion());
  }

  @Test
  void updateProfileErrorReturnsDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.updateProfile(Mockito.any()))
        .thenThrow(new IllegalArgumentException("bad"));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<UpdateProfileResponse> ref = new AtomicReference<>();
    service.updateProfile(
        UpdateProfileRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setProfileJson("{\"displayName\":\"demo\",\"bio\":\"bio\"}")
            .build(),
        new StreamObserver<UpdateProfileResponse>() {
          @Override
          public void onNext(UpdateProfileResponse value) {
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

  @Test
  void exportAccountErrorReturnsDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    Mockito.when(accountService.exportAccountData(1L, 2L))
        .thenThrow(new IllegalArgumentException("missing"));
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<ExportAccountResponse> ref = new AtomicReference<>();
    service.exportAccount(
        ExportAccountRequest.newBuilder().setTenantId("1").setAccountId("2").build(),
        new StreamObserver<ExportAccountResponse>() {
          @Override
          public void onNext(ExportAccountResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("NOT_FOUND", ref.get().getError().getCode());
  }

  @Test
  void deleteAccountSuccess() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<DeleteAccountResponse> ref = new AtomicReference<>();
    service.deleteAccount(
        DeleteAccountRequest.newBuilder().setTenantId("1").setAccountId("2").build(),
        new StreamObserver<DeleteAccountResponse>() {
          @Override
          public void onNext(DeleteAccountResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertTrue(ref.get().getSuccess());
  }

  @Test
  void linkExternalAccountSuccess() {
    PingService pingService = Mockito.mock(PingService.class);
    AccountService accountService = Mockito.mock(AccountService.class);
    AccountGrpcService service = new AccountGrpcService(pingService, accountService);

    AtomicReference<net.firedevops.firemud.account.v1.LinkExternalAccountResponse> ref =
        new AtomicReference<>();
    service.linkExternalAccount(
        net.firedevops.firemud.account.v1.LinkExternalAccountRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setProvider("google")
            .setExternalId("abc")
            .build(),
        new StreamObserver<net.firedevops.firemud.account.v1.LinkExternalAccountResponse>() {
          @Override
          public void onNext(net.firedevops.firemud.account.v1.LinkExternalAccountResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertTrue(ref.get().getSuccess());
  }
}
