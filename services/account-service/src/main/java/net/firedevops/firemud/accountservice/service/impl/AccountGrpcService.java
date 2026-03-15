package net.firedevops.firemud.accountservice.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
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
import net.firedevops.firemud.account.v1.PingRequest;
import net.firedevops.firemud.account.v1.PingResponse;
import net.firedevops.firemud.account.v1.UpdateProfileRequest;
import net.firedevops.firemud.account.v1.UpdateProfileResponse;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.PasswordResetRequest;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.PingService;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.security.RequireAdminRole;
import org.lognet.springboot.grpc.GRpcService;

@GRpcService
public class AccountGrpcService extends AccountServiceGrpc.AccountServiceImplBase {
  private final PingService pingService;
  private final AccountService accountService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Services are injected and remain internal")
  public AccountGrpcService(PingService pingService, AccountService accountService) {
    this.pingService = pingService;
    this.accountService = accountService;
  }

  @Override
  @Timed(value = "accountGrpc.ping")
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    String msg = pingService.ping();
    PingResponse response = PingResponse.newBuilder().setMessage(msg).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "accountGrpc.createAccount")
  public void createAccount(
      CreateAccountRequest request, StreamObserver<CreateAccountResponse> responseObserver) {
    try {
      net.firedevops.firemud.accountservice.dto.CreateAccountRequest dto =
          new net.firedevops.firemud.accountservice.dto.CreateAccountRequest(
              request.getUsername(), request.getEmail(), request.getPassword());
      var account = accountService.createAccount(dto);
      CreateAccountResponse response =
          CreateAccountResponse.newBuilder().setAccountId(account.id().toString()).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          CreateAccountResponse.newBuilder()
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.authenticate")
  public void authenticate(
      AuthenticateRequest request, StreamObserver<AuthenticateResponse> responseObserver) {
    try {
      net.firedevops.firemud.accountservice.dto.AuthenticationResult result =
          accountService.authenticate(
              Long.valueOf(request.getTenantId()),
              request.getUsername(),
              request.getPassword(),
              request.getOtp());
      AuthenticateResponse response =
          AuthenticateResponse.newBuilder()
              .setAuthToken(result.authToken())
              .setAccountId(String.valueOf(result.accountId()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthenticationException ex) {
      AuthenticateResponse response =
          AuthenticateResponse.newBuilder()
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode(ex.getCode())
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      AuthenticateResponse response =
          AuthenticateResponse.newBuilder()
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("UNAUTHENTICATED")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.getProfile")
  public void getProfile(
      GetProfileRequest request, StreamObserver<GetProfileResponse> responseObserver) {
    try {
      var dto =
          accountService.getProfile(
              Long.valueOf(request.getTenantId()), Long.valueOf(request.getAccountId()));
      GetProfileResponse response =
          GetProfileResponse.newBuilder()
              .setProfileJson(
                  com.fasterxml.jackson.databind.json.JsonMapper.builder()
                      .build()
                      .createObjectNode()
                      .put("displayName", dto.displayName())
                      .put("bio", dto.bio())
                      .toString())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetProfileResponse response =
          GetProfileResponse.newBuilder()
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("NOT_FOUND")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.updateProfile")
  public void updateProfile(
      UpdateProfileRequest request, StreamObserver<UpdateProfileResponse> responseObserver) {
    try {
      JsonNode node = JsonMapper.builder().build().readTree(request.getProfileJson());
      String displayName = node.path("displayName").asText(null);
      String bio = node.path("bio").asText(null);
      accountService.updateProfile(
          new net.firedevops.firemud.accountservice.dto.UpdateProfileRequest(
              Long.valueOf(request.getTenantId()),
              Long.valueOf(request.getAccountId()),
              displayName,
              bio));
      UpdateProfileResponse response = UpdateProfileResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      UpdateProfileResponse response =
          UpdateProfileResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.exportAccount")
  public void exportAccount(
      ExportAccountRequest request, StreamObserver<ExportAccountResponse> responseObserver) {
    try {
      var data =
          accountService.exportAccountData(
              Long.valueOf(request.getTenantId()), Long.valueOf(request.getAccountId()));
      ExportAccountResponse response =
          ExportAccountResponse.newBuilder()
              .setAccountJson(JsonMapper.builder().build().writeValueAsString(data.account()))
              .setProfileJson(
                  data.profile() != null
                      ? JsonMapper.builder().build().writeValueAsString(data.profile())
                      : "")
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      ExportAccountResponse response =
          ExportAccountResponse.newBuilder()
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("NOT_FOUND")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @RequireAdminRole
  @Timed(value = "accountGrpc.deleteAccount")
  public void deleteAccount(
      DeleteAccountRequest request, StreamObserver<DeleteAccountResponse> responseObserver) {
    try {
      accountService.deleteAccount(
          Long.valueOf(request.getTenantId()), Long.valueOf(request.getAccountId()));
      DeleteAccountResponse response = DeleteAccountResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      DeleteAccountResponse response =
          DeleteAccountResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("NOT_FOUND")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.requestPasswordReset")
  public void requestPasswordReset(
      net.firedevops.firemud.account.v1.RequestPasswordResetRequest request,
      StreamObserver<net.firedevops.firemud.account.v1.RequestPasswordResetResponse>
          responseObserver) {
    try {
      accountService.requestPasswordReset(
          new PasswordResetRequest(Long.valueOf(request.getTenantId()), request.getEmail()));
      var response =
          net.firedevops.firemud.account.v1.RequestPasswordResetResponse.newBuilder()
              .setSuccess(true)
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      var response =
          net.firedevops.firemud.account.v1.RequestPasswordResetResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.completePasswordReset")
  public void completePasswordReset(
      net.firedevops.firemud.account.v1.CompletePasswordResetRequest request,
      StreamObserver<net.firedevops.firemud.account.v1.CompletePasswordResetResponse>
          responseObserver) {
    try {
      accountService.completePasswordReset(
          new CompletePasswordResetRequest(
              Long.valueOf(request.getTenantId()), request.getToken(), request.getNewPassword()));
      var response =
          net.firedevops.firemud.account.v1.CompletePasswordResetResponse.newBuilder()
              .setSuccess(true)
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      var response =
          net.firedevops.firemud.account.v1.CompletePasswordResetResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.linkExternalAccount")
  public void linkExternalAccount(
      net.firedevops.firemud.account.v1.LinkExternalAccountRequest request,
      StreamObserver<net.firedevops.firemud.account.v1.LinkExternalAccountResponse>
          responseObserver) {
    try {
      accountService.linkExternalAccount(
          new net.firedevops.firemud.accountservice.dto.LinkExternalAccountRequest(
              Long.valueOf(request.getTenantId()),
              Long.valueOf(request.getAccountId()),
              request.getProvider(),
              request.getExternalId()));
      var response =
          net.firedevops.firemud.account.v1.LinkExternalAccountResponse.newBuilder()
              .setSuccess(true)
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      var response =
          net.firedevops.firemud.account.v1.LinkExternalAccountResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.requestEmailVerification")
  public void requestEmailVerification(
      net.firedevops.firemud.account.v1.RequestEmailVerificationRequest request,
      StreamObserver<net.firedevops.firemud.account.v1.RequestEmailVerificationResponse>
          responseObserver) {
    try {
      accountService.requestEmailVerification(
          Long.valueOf(request.getTenantId()), Long.valueOf(request.getAccountId()));
      var response =
          net.firedevops.firemud.account.v1.RequestEmailVerificationResponse.newBuilder()
              .setSuccess(true)
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      var response =
          net.firedevops.firemud.account.v1.RequestEmailVerificationResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.verifyEmail")
  public void verifyEmail(
      net.firedevops.firemud.account.v1.VerifyEmailRequest request,
      StreamObserver<net.firedevops.firemud.account.v1.VerifyEmailResponse> responseObserver) {
    try {
      accountService.verifyEmail(
          new net.firedevops.firemud.accountservice.dto.VerifyEmailRequest(
              Long.valueOf(request.getTenantId()), request.getToken()));
      var response =
          net.firedevops.firemud.account.v1.VerifyEmailResponse.newBuilder()
              .setSuccess(true)
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      var response =
          net.firedevops.firemud.account.v1.VerifyEmailResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }
}
