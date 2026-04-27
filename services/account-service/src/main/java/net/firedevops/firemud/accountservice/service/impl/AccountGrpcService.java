package net.firedevops.firemud.accountservice.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.CreateAccountRequest;
import net.firedevops.firemud.account.v1.CreateAccountResponse;
import net.firedevops.firemud.account.v1.DeleteAccountRequest;
import net.firedevops.firemud.account.v1.DeleteAccountResponse;
import net.firedevops.firemud.account.v1.EnsurePublicProductionPlayerMembershipRequest;
import net.firedevops.firemud.account.v1.EnsurePublicProductionPlayerMembershipResponse;
import net.firedevops.firemud.account.v1.ExportAccountRequest;
import net.firedevops.firemud.account.v1.ExportAccountResponse;
import net.firedevops.firemud.account.v1.ExportTenantDataRequest;
import net.firedevops.firemud.account.v1.ExportTenantDataResponse;
import net.firedevops.firemud.account.v1.GetProfileRequest;
import net.firedevops.firemud.account.v1.GetProfileResponse;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.account.v1.PingRequest;
import net.firedevops.firemud.account.v1.PingResponse;
import net.firedevops.firemud.account.v1.UpdateProfileRequest;
import net.firedevops.firemud.account.v1.UpdateProfileResponse;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.PasswordResetRequest;
import net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.PingService;
import net.firedevops.firemud.accountservice.service.exception.AccountLifecycleException;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.grpc.server.service.GrpcService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@GrpcService
public class AccountGrpcService extends AccountServiceGrpc.AccountServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(AccountGrpcService.class);
  private final PingService pingService;
  private final AccountService accountService;
  private final MeterRegistry meterRegistry;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Services are injected and remain internal")
  public AccountGrpcService(PingService pingService, AccountService accountService) {
    this(pingService, accountService, null);
  }

  @Autowired
  public AccountGrpcService(
      PingService pingService, AccountService accountService, MeterRegistry meterRegistry) {
    this.pingService = pingService;
    this.accountService = accountService;
    this.meterRegistry = meterRegistry;
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
              Long.valueOf(request.getTenantId()),
              request.getUsername(),
              request.getEmail(),
              request.getPassword());
      var account = accountService.createAccount(dto);
      CreateAccountResponse response =
          CreateAccountResponse.newBuilder().setAccountId(account.id().toString()).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          CreateAccountResponse.newBuilder()
              .setError(appError("CreateAccount", "INVALID_ARGUMENT", ex.getMessage()))
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
              .setError(appError("Authenticate", ex.getCode(), ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      AuthenticateResponse response =
          AuthenticateResponse.newBuilder()
              .setError(appError("Authenticate", "UNAUTHENTICATED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.getTenantMembershipForRuntime")
  public void getTenantMembershipForRuntime(
      GetTenantMembershipForRuntimeRequest request,
      StreamObserver<GetTenantMembershipForRuntimeResponse> responseObserver) {
    try {
      var dto =
          accountService.getTenantMembershipForRuntime(
              Long.valueOf(request.getAccountId()),
              Long.valueOf(request.getTenantId()),
              request.getRequestId());
      GetTenantMembershipForRuntimeResponse response =
          GetTenantMembershipForRuntimeResponse.newBuilder()
              .setAccountId(String.valueOf(dto.accountId()))
              .setTenantId(String.valueOf(dto.tenantId()))
              .setGameplayAdmissionAllowed(dto.gameplayAdmissionAllowed())
              .setMembershipVersion(dto.membershipVersion())
              .setEvaluatedAt(dto.evaluatedAt())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetTenantMembershipForRuntimeResponse response =
          GetTenantMembershipForRuntimeResponse.newBuilder()
              .setError(appError("GetTenantMembershipForRuntime", "NOT_FOUND", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.getRealmAccessGrantForRuntime")
  public void getRealmAccessGrantForRuntime(
      GetRealmAccessGrantForRuntimeRequest request,
      StreamObserver<GetRealmAccessGrantForRuntimeResponse> responseObserver) {
    try {
      var dto =
          accountService.getRealmAccessGrantForRuntime(
              Long.valueOf(request.getAccountId()),
              Long.valueOf(request.getTenantId()),
              request.getWorldSlug(),
              request.getRealmSlug(),
              request.getRequestId());
      GetRealmAccessGrantForRuntimeResponse response =
          GetRealmAccessGrantForRuntimeResponse.newBuilder()
              .setAccountId(String.valueOf(dto.accountId()))
              .setTenantId(String.valueOf(dto.tenantId()))
              .setWorldSlug(dto.worldSlug())
              .setRealmSlug(dto.realmSlug())
              .setGranted(dto.granted())
              .setGrantVersion(dto.grantVersion())
              .setEvaluatedAt(dto.evaluatedAt())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetRealmAccessGrantForRuntimeResponse response =
          GetRealmAccessGrantForRuntimeResponse.newBuilder()
              .setError(appError("GetRealmAccessGrantForRuntime", "NOT_FOUND", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.ensurePublicProductionPlayerMembership")
  public void ensurePublicProductionPlayerMembership(
      EnsurePublicProductionPlayerMembershipRequest request,
      StreamObserver<EnsurePublicProductionPlayerMembershipResponse> responseObserver) {
    try {
      var dto =
          accountService.ensurePublicProductionPlayerMembership(
              Long.valueOf(request.getAccountId()),
              Long.valueOf(request.getTenantId()),
              request.getRealmSlug(),
              request.getRequestId());
      EnsurePublicProductionPlayerMembershipResponse response =
          EnsurePublicProductionPlayerMembershipResponse.newBuilder()
              .setAccountId(String.valueOf(dto.accountId()))
              .setTenantId(String.valueOf(dto.tenantId()))
              .setRealmSlug(dto.realmSlug())
              .setGameplayAdmissionAllowed(true)
              .setMembershipVersion(dto.membershipVersion())
              .setCreated(dto.created())
              .setEvaluatedAt(dto.evaluatedAt())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthenticationException ex) {
      EnsurePublicProductionPlayerMembershipResponse response =
          EnsurePublicProductionPlayerMembershipResponse.newBuilder()
              .setError(
                  appError("EnsurePublicProductionPlayerMembership", ex.getCode(), ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      EnsurePublicProductionPlayerMembershipResponse response =
          EnsurePublicProductionPlayerMembershipResponse.newBuilder()
              .setError(
                  appError("EnsurePublicProductionPlayerMembership", "NOT_FOUND", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.getTenantEntitlementsForRuntime")
  public void getTenantEntitlementsForRuntime(
      GetTenantEntitlementsForRuntimeRequest request,
      StreamObserver<GetTenantEntitlementsForRuntimeResponse> responseObserver) {
    try {
      var dto =
          accountService.getTenantEntitlementsForRuntime(
              Long.valueOf(request.getTenantId()), request.getRequestId());
      GetTenantEntitlementsForRuntimeResponse response =
          GetTenantEntitlementsForRuntimeResponse.newBuilder()
              .setTenantId(String.valueOf(dto.tenantId()))
              .setGameplayAvailable(dto.gameplayAvailable())
              .setEntitlementVersion(dto.entitlementVersion())
              .setTenantBillingSequence(dto.tenantBillingSequence())
              .setEvaluatedAt(dto.evaluatedAt())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetTenantEntitlementsForRuntimeResponse response =
          GetTenantEntitlementsForRuntimeResponse.newBuilder()
              .setError(appError("GetTenantEntitlementsForRuntime", "NOT_FOUND", ex.getMessage()))
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
              .setProfileJson(JsonMapper.builder().build().writeValueAsString(dto))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetProfileResponse response =
          GetProfileResponse.newBuilder()
              .setError(appError("GetProfile", "NOT_FOUND", ex.getMessage()))
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
      String presenceVisibilityPolicy = node.path("presenceVisibilityPolicy").asText(null);
      accountService.updateProfile(
          new net.firedevops.firemud.accountservice.dto.UpdateProfileRequest(
              Long.valueOf(request.getTenantId()),
              Long.valueOf(request.getAccountId()),
              displayName,
              bio,
              ProfilePresenceVisibilityPolicy.valueOf(
                  presenceVisibilityPolicy == null
                      ? ProfilePresenceVisibilityPolicy.FRIENDS_ONLY.name()
                      : presenceVisibilityPolicy)));
      UpdateProfileResponse response = UpdateProfileResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      UpdateProfileResponse response =
          UpdateProfileResponse.newBuilder()
              .setSuccess(false)
              .setError(appError("UpdateProfile", "INVALID_ARGUMENT", ex.getMessage()))
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
      var data = accountService.exportAccountData(Long.valueOf(request.getAccountId()));
      ExportAccountResponse response =
          ExportAccountResponse.newBuilder()
              .setAccountJson(JsonMapper.builder().build().writeValueAsString(data.account()))
              .setProfilesJson(JsonMapper.builder().build().writeValueAsString(data.profiles()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      ExportAccountResponse response =
          ExportAccountResponse.newBuilder()
              .setError(appError("ExportAccount", "NOT_FOUND", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.exportTenantData")
  public void exportTenantData(
      ExportTenantDataRequest request, StreamObserver<ExportTenantDataResponse> responseObserver) {
    try {
      var data =
          accountService.exportTenantData(
              Long.valueOf(request.getTenantId()), Long.valueOf(request.getAccountId()));
      ExportTenantDataResponse response =
          ExportTenantDataResponse.newBuilder()
              .setTenantId(String.valueOf(data.tenantId()))
              .setAccountJson(JsonMapper.builder().build().writeValueAsString(data.account()))
              .setProfileJson(
                  data.profile() != null
                      ? JsonMapper.builder().build().writeValueAsString(data.profile())
                      : "")
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      ExportTenantDataResponse response =
          ExportTenantDataResponse.newBuilder()
              .setError(appError("ExportTenantData", "NOT_FOUND", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.deleteAccount")
  public void deleteAccount(
      DeleteAccountRequest request, StreamObserver<DeleteAccountResponse> responseObserver) {
    try {
      AdminRoleGuard.requireAdminRole();
      accountService.deleteAccount(Long.valueOf(request.getAccountId()));
      DeleteAccountResponse response = DeleteAccountResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      DeleteAccountResponse response =
          DeleteAccountResponse.newBuilder()
              .setSuccess(false)
              .setError(appError("DeleteAccount", "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AccountLifecycleException ex) {
      DeleteAccountResponse response =
          DeleteAccountResponse.newBuilder()
              .setSuccess(false)
              .setError(appError("DeleteAccount", ex.getCode(), ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      DeleteAccountResponse response =
          DeleteAccountResponse.newBuilder()
              .setSuccess(false)
              .setError(appError("DeleteAccount", "NOT_FOUND", ex.getMessage()))
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
      accountService.requestPasswordReset(new PasswordResetRequest(request.getEmail()));
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
              .setError(appError("RequestPasswordReset", "INVALID_ARGUMENT", ex.getMessage()))
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
          new CompletePasswordResetRequest(request.getToken(), request.getNewPassword()));
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
              .setError(appError("CompletePasswordReset", "INVALID_ARGUMENT", ex.getMessage()))
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
              .setError(appError("LinkExternalAccount", "INVALID_ARGUMENT", ex.getMessage()))
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
      accountService.requestEmailVerification(Long.valueOf(request.getAccountId()));
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
              .setError(appError("RequestEmailVerification", "INVALID_ARGUMENT", ex.getMessage()))
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
          new net.firedevops.firemud.accountservice.dto.VerifyEmailRequest(request.getToken()));
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
              .setError(appError("VerifyEmail", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  private net.firedevops.firemud.shared.v1.ErrorDetail appError(
      String operation, String code, String message) {
    return meterRegistry == null
        ? net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
            .setCode(code)
            .setMessage(message)
            .build()
        : GrpcAppErrors.error(meterRegistry, logger, operation, code, message);
  }
}
