package net.firedevops.firemud.accountservice.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.CreateAccountRequest;
import net.firedevops.firemud.account.v1.CreateAccountResponse;
import net.firedevops.firemud.account.v1.DeleteAccountRequest;
import net.firedevops.firemud.account.v1.DeleteAccountResponse;
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
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeRequest;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipRequest;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse;
import net.firedevops.firemud.account.v1.PingRequest;
import net.firedevops.firemud.account.v1.PingResponse;
import net.firedevops.firemud.account.v1.RequestEmailLoginOtpRequest;
import net.firedevops.firemud.account.v1.RequestEmailLoginOtpResponse;
import net.firedevops.firemud.account.v1.UpdateProfileRequest;
import net.firedevops.firemud.account.v1.UpdateProfileResponse;
import net.firedevops.firemud.account.v1.VerifyEmailLoginOtpRequest;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.DirectTextCallerContext;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget;
import net.firedevops.firemud.accountservice.dto.JoinPublicProductionRequest;
import net.firedevops.firemud.accountservice.dto.PasswordResetRequest;
import net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.PingService;
import net.firedevops.firemud.accountservice.service.exception.AccountAlreadyExistsException;
import net.firedevops.firemud.accountservice.service.exception.AccountLifecycleException;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import net.firedevops.firemud.common.security.RequestIdValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.server.service.GrpcService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@GrpcService
public class AccountGrpcService extends AccountServiceGrpc.AccountServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(AccountGrpcService.class);
  private static final int MAX_ACCOUNT_IDS_PER_REQUEST = 100;
  private final PingService pingService;
  private final AccountService accountService;
  private final MeterRegistry meterRegistry;
  private final String workloadNamespace;

  public AccountGrpcService(PingService pingService, AccountService accountService) {
    this(pingService, accountService, null, null);
  }

  public AccountGrpcService(
      PingService pingService, AccountService accountService, MeterRegistry meterRegistry) {
    this(pingService, accountService, meterRegistry, null);
  }

  @Autowired
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected services and registry remain internal collaborators.")
  public AccountGrpcService(
      PingService pingService,
      AccountService accountService,
      MeterRegistry meterRegistry,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.pingService = pingService;
    this.accountService = accountService;
    this.meterRegistry = meterRegistry;
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  @Timed(value = "accountGrpc.issueDirectTextConnectScope")
  public void issueDirectTextConnectScope(
      IssueDirectTextConnectScopeRequest request,
      StreamObserver<IssueDirectTextConnectScopeResponse> responseObserver) {
    IssueDirectTextConnectScopeResponse.Builder response =
        IssueDirectTextConnectScopeResponse.newBuilder();
    try {
      requireGameSessionPeer();
      DirectTextCallerContext caller = directTextCaller(request.getPlayerContext());
      long tenantId = requirePositiveRequestId(request.getTenantId(), "tenantId");
      UUID realmId = requireCanonicalRealmId(request.getRealmId());
      long gameInstanceId = requirePositiveRequestId(request.getGameInstanceId(), "gameInstanceId");
      if (caller.tenantId() != tenantId
          || !caller.realmId().equals(realmId)
          || caller.gameInstanceId() != gameInstanceId
          || !caller.playableStateNamespaceId().equals(request.getPlayableStateNamespaceId())
          || !caller.playableStateScope().equals(request.getPlayableStateScope())) {
        throw new InvalidRequestException("Player context and selected realm disagree", null);
      }
      var scope =
          accountService.issueDirectTextConnectScope(
              caller,
              new DirectTextJoinTarget(
                  tenantId,
                  realmId,
                  requireText(request.getWorldSlug(), "worldSlug"),
                  requireText(request.getRealmSlug(), "realmSlug"),
                  requireText(request.getPlayableStateNamespaceId(), "playableStateNamespaceId"),
                  requireText(request.getPlayableStateScope(), "playableStateScope"),
                  gameInstanceId,
                  request.getCatalogRevision(),
                  request.getPointerVersion()));
      response
          .setConnectScopeId(scope.connectScopeId())
          .setConnectScopeExpiresAt(scope.connectScopeExpiresAt());
    } catch (AdminAuthorizationException ex) {
      response.setError(
          appError("IssueDirectTextConnectScope", "PERMISSION_DENIED", ex.getMessage()));
    } catch (AuthenticationException ex) {
      response.setError(appError("IssueDirectTextConnectScope", ex.getCode(), ex.getMessage()));
    } catch (InvalidRequestException | IllegalArgumentException ex) {
      response.setError(
          appError("IssueDirectTextConnectScope", "INVALID_ARGUMENT", ex.getMessage()));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "accountGrpc.joinPublicProductionMembership")
  public void joinPublicProductionMembership(
      JoinPublicProductionMembershipRequest request,
      StreamObserver<JoinPublicProductionMembershipResponse> responseObserver) {
    JoinPublicProductionMembershipResponse.Builder response =
        JoinPublicProductionMembershipResponse.newBuilder();
    try {
      requireGameSessionPeer();
      DirectTextCallerContext caller = directTextCaller(request.getPlayerContext());
      String requestId = requireText(request.getRequestId(), "requestId");
      if (!requestId.equals(caller.requestId())) {
        throw new InvalidRequestException("Player context and JOIN request ID disagree", null);
      }
      var result =
          accountService.joinPublicProductionFromGameSession(
              caller,
              new JoinPublicProductionRequest(
                  requireText(request.getConnectScopeId(), "connectScopeId"), requestId));
      response
          .setSuccess(result.success())
          .setOutcomeCode(result.outcomeCode())
          .setAccountId(Long.toString(result.accountId()))
          .setTenantId(Long.toString(result.tenantId()))
          .setMembershipId(Long.toString(result.membershipId()))
          .setMembershipVersion(result.membershipVersion())
          .setMembershipAuthorityGeneration(result.membershipAuthorityGeneration())
          .setReplayed(result.replayed());
    } catch (AdminAuthorizationException ex) {
      response.setError(
          appError("JoinPublicProductionMembership", "PERMISSION_DENIED", ex.getMessage()));
    } catch (AuthenticationException ex) {
      response.setError(appError("JoinPublicProductionMembership", ex.getCode(), ex.getMessage()));
    } catch (InvalidRequestException | IllegalArgumentException ex) {
      response.setError(
          appError("JoinPublicProductionMembership", "INVALID_ARGUMENT", ex.getMessage()));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  private void requireGameSessionPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null
        || workloadNamespace == null
        || workloadNamespace.isBlank()
        || !peer.uri()
            .equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service")) {
      throw new AdminAuthorizationException("Verified Game Session workload identity is required");
    }
  }

  private DirectTextCallerContext directTextCaller(
      net.firedevops.firemud.shared.v1.PlayerExecutionContext context) {
    return new DirectTextCallerContext(
        requirePositiveRequestId(context.getAccountId(), "accountId"),
        requirePositiveRequestId(context.getTenantId(), "tenantId"),
        requireCanonicalRealmId(context.getRealmId()),
        requireText(context.getPlayableStateNamespaceId(), "playableStateNamespaceId"),
        requireText(context.getPlayableStateScope(), "playableStateScope"),
        requirePositiveRequestId(context.getGameInstanceId(), "gameInstanceId"),
        requireText(context.getSessionId(), "sessionId"),
        context.getRequestId());
  }

  private UUID requireCanonicalRealmId(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("realmId is required");
    }
    try {
      UUID realmId = UUID.fromString(value);
      if (!realmId.toString().equals(value)) {
        throw new IllegalArgumentException("realmId must be a canonical UUID");
      }
      return realmId;
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("realmId must be a canonical UUID", ex);
    }
  }

  private String requireText(String value, String fieldName) {
    int maxLength = "connectScopeId".equals(fieldName) ? 2048 : 128;
    if (value == null || value.isBlank() || value.length() > maxLength) {
      throw new InvalidRequestException(fieldName + " is required and bounded", null);
    }
    return value;
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
    } catch (AccountAlreadyExistsException ex) {
      responseObserver.onNext(
          CreateAccountResponse.newBuilder()
              .setError(appError("CreateAccount", "ALREADY_EXISTS", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (InvalidRequestException ex) {
      responseObserver.onNext(
          CreateAccountResponse.newBuilder()
              .setError(appError("CreateAccount", "INVALID_ARGUMENT", ex.getMessage()))
              .build());
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
      requireGameSessionPeer();
      net.firedevops.firemud.accountservice.dto.AuthenticationResult result =
          accountService.authenticateForGameplay(request.getEmail(), request.getPassword());
      AuthenticateResponse response =
          AuthenticateResponse.newBuilder()
              .setAuthToken(result.authToken())
              .setAccountId(String.valueOf(result.accountId()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      AuthenticateResponse response =
          AuthenticateResponse.newBuilder()
              .setError(appError("Authenticate", "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (InvalidRequestException ex) {
      AuthenticateResponse response =
          AuthenticateResponse.newBuilder()
              .setError(appError("Authenticate", "INVALID_ARGUMENT", ex.getMessage()))
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
  @Timed(value = "accountGrpc.requestEmailLoginOtp")
  public void requestEmailLoginOtp(
      RequestEmailLoginOtpRequest request,
      StreamObserver<RequestEmailLoginOtpResponse> responseObserver) {
    try {
      requireGameSessionPeer();
      accountService.requestEmailLoginOtp(request.getEmail());
      responseObserver.onNext(RequestEmailLoginOtpResponse.newBuilder().setAccepted(true).build());
    } catch (AdminAuthorizationException ex) {
      responseObserver.onNext(
          RequestEmailLoginOtpResponse.newBuilder()
              .setError(appError("RequestEmailLoginOtp", "PERMISSION_DENIED", ex.getMessage()))
              .build());
    } catch (InvalidRequestException ex) {
      responseObserver.onNext(
          RequestEmailLoginOtpResponse.newBuilder()
              .setError(appError("RequestEmailLoginOtp", "INVALID_ARGUMENT", ex.getMessage()))
              .build());
    }
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "accountGrpc.verifyEmailLoginOtp")
  public void verifyEmailLoginOtp(
      VerifyEmailLoginOtpRequest request, StreamObserver<AuthenticateResponse> responseObserver) {
    try {
      requireGameSessionPeer();
      var result = accountService.verifyEmailLoginOtp(request.getEmail(), request.getCode());
      responseObserver.onNext(
          AuthenticateResponse.newBuilder()
              .setAuthToken(result.authToken())
              .setAccountId(String.valueOf(result.accountId()))
              .build());
    } catch (AdminAuthorizationException ex) {
      responseObserver.onNext(
          AuthenticateResponse.newBuilder()
              .setError(appError("VerifyEmailLoginOtp", "PERMISSION_DENIED", ex.getMessage()))
              .build());
    } catch (InvalidRequestException ex) {
      responseObserver.onNext(
          AuthenticateResponse.newBuilder()
              .setError(appError("VerifyEmailLoginOtp", "INVALID_ARGUMENT", ex.getMessage()))
              .build());
    } catch (AuthenticationException | IllegalArgumentException ex) {
      responseObserver.onNext(
          AuthenticateResponse.newBuilder()
              .setError(
                  appError(
                      "VerifyEmailLoginOtp",
                      AuthenticationErrorCodes.INVALID_CREDENTIALS,
                      "Invalid credentials"))
              .build());
    }
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "accountGrpc.getTenantMembershipForRuntime")
  public void getTenantMembershipForRuntime(
      GetTenantMembershipForRuntimeRequest request,
      StreamObserver<GetTenantMembershipForRuntimeResponse> responseObserver) {
    try {
      var dto =
          accountService.getTenantMembershipForRuntime(
              requirePositiveRequestId(request.getAccountId(), "accountId"),
              requirePositiveRequestId(request.getTenantId(), "tenantId"),
              request.getRequestId());
      GetTenantMembershipForRuntimeResponse response =
          GetTenantMembershipForRuntimeResponse.newBuilder()
              .setAccountId(String.valueOf(dto.accountId()))
              .setTenantId(String.valueOf(dto.tenantId()))
              .setMembershipExists(dto.membershipExists())
              .setGameplayAdmissionAllowed(dto.gameplayAdmissionAllowed())
              .setMembershipVersion(dto.membershipVersion())
              .setMembershipLifecycleState(dto.membershipLifecycleState())
              .setMembershipAuthorityGeneration(dto.membershipAuthorityGeneration())
              .setEvaluatedAt(dto.evaluatedAt())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (InvalidRequestException ex) {
      GetTenantMembershipForRuntimeResponse response =
          GetTenantMembershipForRuntimeResponse.newBuilder()
              .setError(
                  appError("GetTenantMembershipForRuntime", "INVALID_ARGUMENT", ex.getMessage()))
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
              requirePositiveRequestId(request.getAccountId(), "accountId"),
              requirePositiveRequestId(request.getTenantId(), "tenantId"),
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
    } catch (InvalidRequestException ex) {
      GetRealmAccessGrantForRuntimeResponse response =
          GetRealmAccessGrantForRuntimeResponse.newBuilder()
              .setError(
                  appError("GetRealmAccessGrantForRuntime", "INVALID_ARGUMENT", ex.getMessage()))
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
  @Timed(value = "accountGrpc.getTenantEntitlementsForRuntime")
  public void getTenantEntitlementsForRuntime(
      GetTenantEntitlementsForRuntimeRequest request,
      StreamObserver<GetTenantEntitlementsForRuntimeResponse> responseObserver) {
    try {
      var dto =
          accountService.getTenantEntitlementsForRuntime(
              requirePositiveRequestId(request.getTenantId(), "tenantId"), request.getRequestId());
      GetTenantEntitlementsForRuntimeResponse response =
          GetTenantEntitlementsForRuntimeResponse.newBuilder()
              .setTenantId(String.valueOf(dto.tenantId()))
              .setGameplayAvailable(dto.gameplayAvailable())
              .setAllowPublicJoin(dto.allowPublicJoin())
              .setEntitlementVersion(dto.entitlementVersion())
              .setTenantBillingSequence(dto.tenantBillingSequence())
              .setEvaluatedAt(dto.evaluatedAt())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (InvalidRequestException ex) {
      GetTenantEntitlementsForRuntimeResponse response =
          GetTenantEntitlementsForRuntimeResponse.newBuilder()
              .setError(
                  appError("GetTenantEntitlementsForRuntime", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthenticationException ex) {
      GetTenantEntitlementsForRuntimeResponse response =
          GetTenantEntitlementsForRuntimeResponse.newBuilder()
              .setError(appError("GetTenantEntitlementsForRuntime", ex.getCode(), ex.getMessage()))
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
              requirePositiveRequestId(request.getTenantId(), "tenantId"),
              requirePositiveRequestId(request.getAccountId(), "accountId"));
      GetProfileResponse response =
          GetProfileResponse.newBuilder()
              .setProfileJson(JsonMapper.builder().build().writeValueAsString(dto))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (InvalidRequestException ex) {
      GetProfileResponse response =
          GetProfileResponse.newBuilder()
              .setError(appError("GetProfile", "INVALID_ARGUMENT", ex.getMessage()))
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
  @Timed(value = "accountGrpc.listPresenceVisibilityPolicies")
  public void listPresenceVisibilityPolicies(
      net.firedevops.firemud.account.v1.ListPresenceVisibilityPoliciesRequest request,
      StreamObserver<net.firedevops.firemud.account.v1.ListPresenceVisibilityPoliciesResponse>
          responseObserver) {
    try {
      long tenantId = requirePositiveRequestId(request.getTenantId(), "tenantId");
      if (request.getAccountIdsCount() > MAX_ACCOUNT_IDS_PER_REQUEST) {
        throw new InvalidRequestException(
            "accountIds must contain at most " + MAX_ACCOUNT_IDS_PER_REQUEST + " entries", null);
      }
      java.util.List<Long> accountIds =
          request.getAccountIdsList().stream()
              .map(accountId -> requirePositiveRequestId(accountId, "accountId"))
              .distinct()
              .toList();
      var builder =
          net.firedevops.firemud.account.v1.ListPresenceVisibilityPoliciesResponse.newBuilder();
      accountService
          .listPresenceVisibilityPolicies(tenantId, accountIds)
          .forEach(
              (accountId, policy) ->
                  builder.addPolicies(
                      net.firedevops.firemud.account.v1.PresenceVisibilityPolicyEntry.newBuilder()
                          .setAccountId(Long.toString(accountId))
                          .setPolicy(policy.name())
                          .build()));
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
    } catch (InvalidRequestException ex) {
      responseObserver.onNext(
          net.firedevops.firemud.account.v1.ListPresenceVisibilityPoliciesResponse.newBuilder()
              .setError(
                  appError("ListPresenceVisibilityPolicies", "INVALID_ARGUMENT", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          net.firedevops.firemud.account.v1.ListPresenceVisibilityPoliciesResponse.newBuilder()
              .setError(appError("ListPresenceVisibilityPolicies", "NOT_FOUND", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (RuntimeException ex) {
      responseObserver.onNext(
          net.firedevops.firemud.account.v1.ListPresenceVisibilityPoliciesResponse.newBuilder()
              .setError(appError("ListPresenceVisibilityPolicies", "INTERNAL", ex.getMessage()))
              .build());
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
              requirePositiveRequestId(request.getTenantId(), "tenantId"),
              requirePositiveRequestId(request.getAccountId(), "accountId"),
              displayName,
              bio,
              ProfilePresenceVisibilityPolicy.valueOf(
                  presenceVisibilityPolicy == null
                      ? ProfilePresenceVisibilityPolicy.FRIENDS_ONLY.name()
                      : presenceVisibilityPolicy)));
      UpdateProfileResponse response = UpdateProfileResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (InvalidRequestException ex) {
      UpdateProfileResponse response =
          UpdateProfileResponse.newBuilder()
              .setSuccess(false)
              .setError(appError("UpdateProfile", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
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
      var data =
          accountService.exportAccountData(
              requirePositiveRequestId(request.getAccountId(), "accountId"));
      ExportAccountResponse response =
          ExportAccountResponse.newBuilder()
              .setAccountJson(JsonMapper.builder().build().writeValueAsString(data.account()))
              .setProfilesJson(JsonMapper.builder().build().writeValueAsString(data.profiles()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (InvalidRequestException ex) {
      ExportAccountResponse response =
          ExportAccountResponse.newBuilder()
              .setError(appError("ExportAccount", "INVALID_ARGUMENT", ex.getMessage()))
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
              requirePositiveRequestId(request.getTenantId(), "tenantId"),
              requirePositiveRequestId(request.getAccountId(), "accountId"));
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
    } catch (InvalidRequestException ex) {
      ExportTenantDataResponse response =
          ExportTenantDataResponse.newBuilder()
              .setError(appError("ExportTenantData", "INVALID_ARGUMENT", ex.getMessage()))
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
      accountService.deleteAccount(requirePositiveRequestId(request.getAccountId(), "accountId"));
      DeleteAccountResponse response = DeleteAccountResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (InvalidRequestException ex) {
      DeleteAccountResponse response =
          DeleteAccountResponse.newBuilder()
              .setSuccess(false)
              .setError(appError("DeleteAccount", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
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
              requirePositiveRequestId(request.getTenantId(), "tenantId"),
              requirePositiveRequestId(request.getAccountId(), "accountId"),
              request.getProvider(),
              request.getExternalId()));
      var response =
          net.firedevops.firemud.account.v1.LinkExternalAccountResponse.newBuilder()
              .setSuccess(true)
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (InvalidRequestException ex) {
      var response =
          net.firedevops.firemud.account.v1.LinkExternalAccountResponse.newBuilder()
              .setSuccess(false)
              .setError(appError("LinkExternalAccount", "INVALID_ARGUMENT", ex.getMessage()))
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
      accountService.requestEmailVerification(
          requirePositiveRequestId(request.getAccountId(), "accountId"));
      var response =
          net.firedevops.firemud.account.v1.RequestEmailVerificationResponse.newBuilder()
              .setSuccess(true)
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (InvalidRequestException ex) {
      var response =
          net.firedevops.firemud.account.v1.RequestEmailVerificationResponse.newBuilder()
              .setSuccess(false)
              .setError(appError("RequestEmailVerification", "INVALID_ARGUMENT", ex.getMessage()))
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

  private long requirePositiveRequestId(String value, String fieldName) {
    try {
      return RequestIdValidation.requirePositiveLong(value, fieldName);
    } catch (IllegalArgumentException ex) {
      throw new InvalidRequestException(ex.getMessage(), ex);
    }
  }

  private static final class InvalidRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private InvalidRequestException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
