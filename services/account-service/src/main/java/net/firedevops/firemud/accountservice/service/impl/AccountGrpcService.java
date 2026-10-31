package net.firedevops.firemud.accountservice.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
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
import net.firedevops.firemud.accountservice.AccountUuidText;
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
import net.firedevops.firemud.common.EmailCanonicalization;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.common.security.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataAccessException;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.TransactionException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@GrpcService
public class AccountGrpcService extends AccountServiceGrpc.AccountServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(AccountGrpcService.class);
  private static final int MAX_ACCOUNT_IDS_PER_REQUEST = 100;
  private static final String AUTHORITY_UNAVAILABLE_MESSAGE =
      "Account authority unavailable; retry later";
  private static final String ACCOUNT_CREATION_INTERNAL_ERROR_MESSAGE = "Account creation failed";
  private final PingService pingService;
  private final AccountService accountService;
  private final MeterRegistry meterRegistry;
  private final String workloadNamespace;
  private final boolean localDemoAuthenticationEnabled;
  private final String localDemoEmail;

  public AccountGrpcService(PingService pingService, AccountService accountService) {
    this(pingService, accountService, null, null);
  }

  public AccountGrpcService(
      PingService pingService, AccountService accountService, MeterRegistry meterRegistry) {
    this(pingService, accountService, meterRegistry, null);
  }

  public AccountGrpcService(
      PingService pingService,
      AccountService accountService,
      MeterRegistry meterRegistry,
      String workloadNamespace) {
    this(pingService, accountService, meterRegistry, workloadNamespace, null, false, "");
  }

  @Autowired
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected services and registry remain internal collaborators.")
  public AccountGrpcService(
      PingService pingService,
      AccountService accountService,
      MeterRegistry meterRegistry,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace,
      Environment environment,
      @Value("${firemud.account.local-demo-authentication.enabled:false}")
          boolean localDemoAuthenticationEnabled,
      @Value("${firemud.account.local-demo-authentication.email:}") String localDemoEmail) {
    this.pingService = pingService;
    this.accountService = accountService;
    this.meterRegistry = meterRegistry;
    this.workloadNamespace = workloadNamespace;
    this.localDemoAuthenticationEnabled =
        localDemoAuthenticationEnabled
            && environment != null
            && environment.acceptsProfiles(Profiles.of("local-fixture"))
            && !environment.acceptsProfiles(Profiles.of("prod"));
    this.localDemoEmail =
        this.localDemoAuthenticationEnabled ? EmailCanonicalization.normalize(localDemoEmail) : "";
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
      var context = request.getPlayerContext();
      long tenantId = requirePositiveRequestId(request.getTenantId(), "tenantId");
      UUID realmId = requireCanonicalRealmId(request.getRealmId());
      long gameInstanceId = requirePositiveRequestId(request.getGameInstanceId(), "gameInstanceId");
      if (requirePositiveRequestId(context.getTenantId(), "tenantId") != tenantId
          || !requireCanonicalRealmId(context.getRealmId()).equals(realmId)
          || requirePositiveRequestId(context.getGameInstanceId(), "gameInstanceId")
              != gameInstanceId
          || !context.getPlayableStateNamespaceId().equals(request.getPlayableStateNamespaceId())
          || !context.getPlayableStateScope().equals(request.getPlayableStateScope())) {
        throw new InvalidRequestException("Player context and selected realm disagree", null);
      }
      DirectTextCallerContext caller = directTextCaller(context);
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
          .setConnectScopeExpiresAt(scope.connectScopeExpiresAt())
          .setEvaluatedAt(scope.evaluatedAt());
    } catch (AdminAuthorizationException ex) {
      response.setError(
          appError("IssueDirectTextConnectScope", "PERMISSION_DENIED", ex.getMessage()));
    } catch (AuthenticationException ex) {
      response.setError(appError("IssueDirectTextConnectScope", ex.getCode(), ex.getMessage()));
    } catch (InvalidRequestException | IllegalArgumentException ex) {
      response.setError(
          appError("IssueDirectTextConnectScope", "INVALID_ARGUMENT", ex.getMessage()));
    } catch (RuntimeException ignored) {
      response.setError(
          appError(
              "IssueDirectTextConnectScope", "AUTH_UNAVAILABLE", AUTHORITY_UNAVAILABLE_MESSAGE));
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
      String requestId = requireText(request.getRequestId(), "requestId");
      if (requestId.length() > JoinPublicProductionRequest.MAX_REQUEST_ID_LENGTH) {
        throw new InvalidRequestException("JOIN requestId exceeds the maximum length", null);
      }
      if (!requestId.equals(request.getPlayerContext().getRequestId())) {
        throw new InvalidRequestException("Player context and JOIN request ID disagree", null);
      }
      DirectTextCallerContext caller = directTextCaller(request.getPlayerContext());
      var result =
          accountService.joinPublicProductionFromGameSession(
              caller,
              new JoinPublicProductionRequest(
                  requireText(request.getConnectScopeId(), "connectScopeId"), requestId));
      if (!request.getPlayerContext().getAccountId().equals(result.accountId())
          || result.tenantId() != caller.tenantId()) {
        throw new IllegalStateException("Account JOIN result does not match the validated caller");
      }
      response
          .setSuccess(result.success())
          .setOutcomeCode(result.outcomeCode())
          .setAccountId(result.accountId())
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
    } catch (RuntimeException ignored) {
      response.setError(
          appError(
              "JoinPublicProductionMembership", "AUTH_UNAVAILABLE", AUTHORITY_UNAVAILABLE_MESSAGE));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  private void requireGameSessionPeer() {
    requireWorkloadPeer(
        "Verified Game Session workload identity is required", "game-session-service");
  }

  private void requireCredentialPeer(AuthenticateRequest request) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer != null
        && peer.isService("game-session-service")
        && peer.isInNamespace(workloadNamespace)) {
      requireGameSessionPeer();
      return;
    }
    requireWorkloadPeer(
        "Verified Game Session or local-fixture Entity Management workload identity is required",
        "entity-management-service");
    if (!localDemoAuthenticationEnabled) {
      throw new AdminAuthorizationException("Local demo authentication is unavailable");
    }
    try {
      if (localDemoEmail.equals(EmailCanonicalization.normalize(request.getEmail()))) {
        return;
      }
    } catch (IllegalArgumentException ignored) {
      // Invalid fixture identity does not authorize credential verification.
    }
    throw new AdminAuthorizationException("Configured local demo account identity is required");
  }

  private void requireRuntimeEntitlementsPeer() {
    requireWorkloadPeer(
        "Verified Game Session or World Management workload identity is required",
        "game-session-service",
        "world-management-service");
  }

  private void requireSocialGroupsPeer() {
    requireWorkloadPeer(
        "Verified Social Groups workload identity is required", "social-groups-service");
  }

  private void requireWorkloadPeer(String denialMessage, String... allowedServices) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer != null && workloadNamespace != null && !workloadNamespace.isBlank()) {
      for (String allowedService : allowedServices) {
        String expectedPeerUri =
            "spiffe://firemud/ns/" + workloadNamespace + "/sa/" + allowedService;
        if (peer.uri().equals(expectedPeerUri)) {
          return;
        }
      }
    }
    throw new AdminAuthorizationException(denialMessage);
  }

  private void requireCallerAccountSubject(UUID accountUuid) {
    if (SessionContext.isInternalService()) {
      throw new AdminAuthorizationException("Authenticated account subject is required");
    }
    if (!accountUuid.toString().equals(SessionContext.getAccountId())) {
      throw new AdminAuthorizationException("Profile access is restricted to the caller account");
    }
  }

  private static UUID requireCanonicalAccountUuid(String value) {
    UUID accountUuid = AccountUuidText.parseOrNull(value);
    if (accountUuid == null) {
      throw new InvalidRequestException("accountId must be a canonical non-nil UUID", null);
    }
    return accountUuid;
  }

  private DirectTextCallerContext directTextCaller(
      net.firedevops.firemud.shared.v1.PlayerExecutionContext context) {
    UUID accountUuid = requireCanonicalAccountUuid(context.getAccountId());
    long tenantId = requirePositiveRequestId(context.getTenantId(), "tenantId");
    UUID realmId = requireCanonicalRealmId(context.getRealmId());
    String namespaceId =
        requireCanonicalPlayableStateNamespaceId(context.getPlayableStateNamespaceId());
    String scope = requireText(context.getPlayableStateScope(), "playableStateScope");
    long gameInstanceId = requirePositiveRequestId(context.getGameInstanceId(), "gameInstanceId");
    String sessionId = requireText(context.getSessionId(), "sessionId");
    String requestId = requireText(context.getRequestId(), "requestId");
    long accountStorageId = accountService.resolveAccountStorageId(accountUuid);
    if (accountStorageId <= 0L) {
      throw new IllegalStateException("Account UUID did not resolve to a persisted owner");
    }
    return new DirectTextCallerContext(
        accountStorageId,
        tenantId,
        realmId,
        namespaceId,
        scope,
        gameInstanceId,
        sessionId,
        requestId);
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

  private String requireCanonicalPlayableStateNamespaceId(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("playableStateNamespaceId is required");
    }
    try {
      UUID namespaceId = UUID.fromString(value);
      if (!namespaceId.toString().equals(value)) {
        throw new IllegalArgumentException("playableStateNamespaceId must be a canonical UUID");
      }
      return value;
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("playableStateNamespaceId must be a canonical UUID", ex);
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
          CreateAccountResponse.newBuilder().setAccountId(account.id()).build();
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
    } catch (IllegalStateException ex) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription(ACCOUNT_CREATION_INTERNAL_ERROR_MESSAGE)
              .asRuntimeException());
    }
  }

  @Override
  @Timed(value = "accountGrpc.authenticate")
  public void authenticate(
      AuthenticateRequest request, StreamObserver<AuthenticateResponse> responseObserver) {
    try {
      requireCredentialPeer(request);
      net.firedevops.firemud.accountservice.dto.AuthenticationResult result =
          accountService.authenticateForGameplay(request.getEmail(), request.getPassword());
      AuthenticateResponse response =
          AuthenticateResponse.newBuilder()
              .setAuthToken(result.authToken())
              .setAccountId(result.accountId())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      responseObserver.onNext(
          AuthenticateResponse.newBuilder()
              .setError(appError("Authenticate", "PERMISSION_DENIED", ex.getMessage()))
              .build());
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
    } catch (IllegalStateException ex) {
      AuthenticateResponse response =
          AuthenticateResponse.newBuilder()
              .setError(appError("Authenticate", "AUTH_UNAVAILABLE", AUTHORITY_UNAVAILABLE_MESSAGE))
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
      accountService.requestEmailLoginOtp(requireEmail(request.getEmail()));
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
              .setAccountId(result.accountId())
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
    } catch (AuthenticationException ex) {
      responseObserver.onNext(
          AuthenticateResponse.newBuilder()
              .setError(
                  appError(
                      "VerifyEmailLoginOtp",
                      "AUTH_UNAVAILABLE".equals(ex.getCode())
                          ? "AUTH_UNAVAILABLE"
                          : AuthenticationErrorCodes.INVALID_CREDENTIALS,
                      "AUTH_UNAVAILABLE".equals(ex.getCode())
                          ? AUTHORITY_UNAVAILABLE_MESSAGE
                          : "Invalid credentials"))
              .build());
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          AuthenticateResponse.newBuilder()
              .setError(
                  appError(
                      "VerifyEmailLoginOtp",
                      AuthenticationErrorCodes.INVALID_CREDENTIALS,
                      "Invalid credentials"))
              .build());
    } catch (IllegalStateException ex) {
      responseObserver.onNext(
          AuthenticateResponse.newBuilder()
              .setError(
                  appError(
                      "VerifyEmailLoginOtp", "AUTH_UNAVAILABLE", AUTHORITY_UNAVAILABLE_MESSAGE))
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
      requireGameSessionPeer();
      UUID accountUuid = requireCanonicalAccountUuid(request.getAccountId());
      long tenantId = requirePositiveRequestId(request.getTenantId(), "tenantId");
      long accountStorageId = accountService.resolveAccountStorageId(accountUuid);
      var dto =
          accountService.getTenantMembershipForRuntime(
              accountStorageId, tenantId, request.getRequestId());
      if (dto == null
          || !Long.valueOf(accountStorageId).equals(dto.accountId())
          || !Long.valueOf(tenantId).equals(dto.tenantId())) {
        throw new IllegalStateException(
            "Account membership authority returned an inconsistent identity");
      }
      GetTenantMembershipForRuntimeResponse response =
          GetTenantMembershipForRuntimeResponse.newBuilder()
              .setAccountId(accountUuid.toString())
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
    } catch (AdminAuthorizationException ex) {
      responseObserver.onNext(
          GetTenantMembershipForRuntimeResponse.newBuilder()
              .setError(
                  appError("GetTenantMembershipForRuntime", "PERMISSION_DENIED", ex.getMessage()))
              .build());
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
    } catch (RuntimeException ex) {
      responseObserver.onNext(
          GetTenantMembershipForRuntimeResponse.newBuilder()
              .setError(
                  appError(
                      "GetTenantMembershipForRuntime",
                      "AUTH_UNAVAILABLE",
                      AUTHORITY_UNAVAILABLE_MESSAGE))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.getRealmAccessGrantForRuntime")
  public void getRealmAccessGrantForRuntime(
      GetRealmAccessGrantForRuntimeRequest request,
      StreamObserver<GetRealmAccessGrantForRuntimeResponse> responseObserver) {
    try {
      requireGameSessionPeer();
      UUID accountUuid = requireCanonicalAccountUuid(request.getAccountId());
      long tenantId = requirePositiveRequestId(request.getTenantId(), "tenantId");
      String worldSlug = requireText(request.getWorldSlug(), "worldSlug");
      String realmSlug = requireText(request.getRealmSlug(), "realmSlug");
      long accountStorageId = accountService.resolveAccountStorageId(accountUuid);
      var dto =
          accountService.getRealmAccessGrantForRuntime(
              accountStorageId,
              tenantId,
              worldSlug,
              realmSlug,
              requireText(request.getRequestId(), "requestId"));
      if (dto.accountId() != accountStorageId
          || dto.tenantId() != tenantId
          || !worldSlug.equals(dto.worldSlug())
          || !realmSlug.equals(dto.realmSlug())
          || (dto.granted() && dto.grantVersion() <= 0L)
          || dto.evaluatedAt() == null
          || dto.evaluatedAt().isBlank()
          || Instant.parse(dto.evaluatedAt()).isAfter(Instant.now().plusSeconds(5))) {
        throw new IllegalStateException("Account returned mismatched realm-grant authority");
      }
      GetRealmAccessGrantForRuntimeResponse response =
          GetRealmAccessGrantForRuntimeResponse.newBuilder()
              .setAccountId(accountUuid.toString())
              .setTenantId(Long.toString(tenantId))
              .setWorldSlug(worldSlug)
              .setRealmSlug(realmSlug)
              .setGranted(dto.granted())
              .setGrantVersion(dto.grantVersion())
              .setEvaluatedAt(dto.evaluatedAt())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      responseObserver.onNext(
          GetRealmAccessGrantForRuntimeResponse.newBuilder()
              .setError(
                  appError("GetRealmAccessGrantForRuntime", "PERMISSION_DENIED", ex.getMessage()))
              .build());
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
    } catch (RuntimeException ex) {
      responseObserver.onNext(
          GetRealmAccessGrantForRuntimeResponse.newBuilder()
              .setError(
                  appError(
                      "GetRealmAccessGrantForRuntime",
                      "AUTH_UNAVAILABLE",
                      AUTHORITY_UNAVAILABLE_MESSAGE))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.getTenantEntitlementsForRuntime")
  public void getTenantEntitlementsForRuntime(
      GetTenantEntitlementsForRuntimeRequest request,
      StreamObserver<GetTenantEntitlementsForRuntimeResponse> responseObserver) {
    try {
      requireRuntimeEntitlementsPeer();
      long tenantId = requirePositiveRequestId(request.getTenantId(), "tenantId");
      String requestId = requireText(request.getRequestId(), "requestId");
      var dto = accountService.getTenantEntitlementsForRuntime(tenantId, requestId);
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
    } catch (AdminAuthorizationException ex) {
      GetTenantEntitlementsForRuntimeResponse response =
          GetTenantEntitlementsForRuntimeResponse.newBuilder()
              .setError(
                  appError("GetTenantEntitlementsForRuntime", "PERMISSION_DENIED", ex.getMessage()))
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
      requireSocialGroupsPeer();
      long tenantId = requirePositiveRequestId(request.getTenantId(), "tenantId");
      UUID accountUuid = requireCanonicalAccountUuid(request.getAccountId());
      requireCallerAccountSubject(accountUuid);
      long accountId = accountService.resolveAccountStorageId(accountUuid);
      var dto = accountService.getProfile(tenantId, accountId);
      if (dto == null
          || !accountUuid.toString().equals(dto.accountId())
          || !Long.valueOf(tenantId).equals(dto.tenantId())) {
        throw new IllegalStateException("Profile authority returned a different owner");
      }
      GetProfileResponse response =
          GetProfileResponse.newBuilder()
              .setProfileJson(JsonMapper.builder().build().writeValueAsString(dto))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      responseObserver.onNext(
          GetProfileResponse.newBuilder()
              .setError(appError("GetProfile", "PERMISSION_DENIED", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (AuthenticationException ex) {
      responseObserver.onNext(
          GetProfileResponse.newBuilder()
              .setError(appError("GetProfile", ex.getCode(), ex.getMessage()))
              .build());
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
    } catch (RuntimeException ignored) {
      responseObserver.onNext(
          GetProfileResponse.newBuilder()
              .setError(appError("GetProfile", "AUTH_UNAVAILABLE", AUTHORITY_UNAVAILABLE_MESSAGE))
              .build());
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
      requireSocialGroupsPeer();
      long tenantId = requirePositiveRequestId(request.getTenantId(), "tenantId");
      if (request.getAccountIdsCount() > MAX_ACCOUNT_IDS_PER_REQUEST) {
        throw new InvalidRequestException(
            "accountIds must contain at most " + MAX_ACCOUNT_IDS_PER_REQUEST + " entries", null);
      }
      java.util.List<UUID> accountUuids =
          request.getAccountIdsList().stream()
              .map(AccountGrpcService::requireCanonicalAccountUuid)
              .distinct()
              .toList();
      java.util.Map<UUID, Long> resolvedStorageIdsByAccountUuid =
          accountService.resolveAccountStorageIds(accountUuids);
      if (resolvedStorageIdsByAccountUuid == null) {
        throw new IllegalStateException("Account UUID batch resolution returned no result");
      }
      java.util.Map<Long, UUID> accountUuidsByStorageId = new java.util.HashMap<>();
      for (java.util.Map.Entry<UUID, Long> resolution :
          resolvedStorageIdsByAccountUuid.entrySet()) {
        UUID accountUuid = resolution.getKey();
        Long storageId = resolution.getValue();
        if (accountUuid == null || !accountUuids.contains(accountUuid)) {
          throw new IllegalStateException("Account UUID batch resolution returned an invalid row");
        }
        if (storageId == null || storageId <= 0L) {
          continue;
        }
        UUID previousAccountUuid = accountUuidsByStorageId.putIfAbsent(storageId, accountUuid);
        if (previousAccountUuid != null && !previousAccountUuid.equals(accountUuid)) {
          throw new IllegalStateException("Account UUID resolution returned an ambiguous row");
        }
      }
      java.util.Map<UUID, Long> storageIdsByAccountUuid = new java.util.LinkedHashMap<>();
      for (UUID accountUuid : accountUuids) {
        Long storageId = resolvedStorageIdsByAccountUuid.get(accountUuid);
        if (storageId != null && storageId > 0L) {
          storageIdsByAccountUuid.put(accountUuid, storageId);
        }
      }
      java.util.List<Long> storageIds = java.util.List.copyOf(storageIdsByAccountUuid.values());
      java.util.Map<Long, ProfilePresenceVisibilityPolicy> policies =
          storageIds.isEmpty()
              ? java.util.Map.of()
              : accountService.listPresenceVisibilityPolicies(tenantId, storageIds);
      for (java.util.Map.Entry<Long, ProfilePresenceVisibilityPolicy> policy :
          policies.entrySet()) {
        if (!accountUuidsByStorageId.containsKey(policy.getKey()) || policy.getValue() == null) {
          throw new IllegalStateException(
              "Presence visibility policy read returned an unexpected Account row");
        }
      }
      var builder =
          net.firedevops.firemud.account.v1.ListPresenceVisibilityPoliciesResponse.newBuilder();
      policies.forEach(
          (storageId, policy) ->
              builder.addPolicies(
                  net.firedevops.firemud.account.v1.PresenceVisibilityPolicyEntry.newBuilder()
                      .setAccountId(accountUuidsByStorageId.get(storageId).toString())
                      .setPolicy(policy.name())
                      .build()));
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      responseObserver.onNext(
          net.firedevops.firemud.account.v1.ListPresenceVisibilityPoliciesResponse.newBuilder()
              .setError(
                  appError("ListPresenceVisibilityPolicies", "PERMISSION_DENIED", ex.getMessage()))
              .build());
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
              .setError(
                  appError(
                      "ListPresenceVisibilityPolicies",
                      "INTERNAL",
                      "Presence visibility policy lookup failed"))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.updateProfile")
  public void updateProfile(
      UpdateProfileRequest request, StreamObserver<UpdateProfileResponse> responseObserver) {
    try {
      requireSocialGroupsPeer();
      long tenantId = requirePositiveRequestId(request.getTenantId(), "tenantId");
      UUID accountUuid = requireCanonicalAccountUuid(request.getAccountId());
      requireCallerAccountSubject(accountUuid);
      try {
        accountService.resolveAccountStorageId(accountUuid);
      } catch (IllegalArgumentException ex) {
        responseObserver.onNext(
            UpdateProfileResponse.newBuilder()
                .setSuccess(false)
                .setError(appError("UpdateProfile", "NOT_FOUND", ex.getMessage()))
                .build());
        responseObserver.onCompleted();
        return;
      }
      JsonNode node;
      ProfilePresenceVisibilityPolicy policy;
      try {
        node = JsonMapper.builder().build().readTree(request.getProfileJson());
        if (node == null || !node.isObject()) {
          throw new IllegalArgumentException("Profile update must be an object");
        }
        JsonNode policyNode = node.get("presenceVisibilityPolicy");
        if (policyNode == null || !policyNode.isTextual()) {
          throw new IllegalArgumentException("presenceVisibilityPolicy must be provided");
        }
        policy = ProfilePresenceVisibilityPolicy.valueOf(policyNode.textValue());
        policy.requireSelectableByAccountHolder();
      } catch (JacksonException | IllegalArgumentException ex) {
        throw new InvalidRequestException("profileJson must contain a valid profile update", ex);
      }
      String displayName = node.path("displayName").asText(null);
      String bio = node.path("bio").asText(null);
      var dto =
          accountService.updateProfile(
              new net.firedevops.firemud.accountservice.dto.UpdateProfileRequest(
                  tenantId, accountUuid.toString(), displayName, bio, policy));
      if (dto == null
          || !accountUuid.toString().equals(dto.accountId())
          || !Long.valueOf(tenantId).equals(dto.tenantId())) {
        throw new IllegalStateException("Profile authority returned a different owner");
      }
      UpdateProfileResponse response = UpdateProfileResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      UpdateProfileResponse response =
          UpdateProfileResponse.newBuilder()
              .setSuccess(false)
              .setError(appError("UpdateProfile", "PERMISSION_DENIED", ex.getMessage()))
              .build();
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
    } catch (AuthenticationException ex) {
      UpdateProfileResponse response =
          UpdateProfileResponse.newBuilder()
              .setSuccess(false)
              .setError(appError("UpdateProfile", ex.getCode(), ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      UpdateProfileResponse response =
          UpdateProfileResponse.newBuilder()
              .setSuccess(false)
              .setError(appError("UpdateProfile", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (RuntimeException ignored) {
      responseObserver.onNext(
          UpdateProfileResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  appError("UpdateProfile", "AUTH_UNAVAILABLE", AUTHORITY_UNAVAILABLE_MESSAGE))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "accountGrpc.exportAccount")
  public void exportAccount(
      ExportAccountRequest request, StreamObserver<ExportAccountResponse> responseObserver) {
    responseObserver.onNext(
        ExportAccountResponse.newBuilder()
            .setError(
                appError(
                    "ExportAccount",
                    "FAILED_PRECONDITION",
                    "No authorized internal caller is configured"))
            .build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "accountGrpc.exportTenantData")
  public void exportTenantData(
      ExportTenantDataRequest request, StreamObserver<ExportTenantDataResponse> responseObserver) {
    responseObserver.onNext(
        ExportTenantDataResponse.newBuilder()
            .setError(
                appError(
                    "ExportTenantData",
                    "FAILED_PRECONDITION",
                    "No authorized internal caller is configured"))
            .build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "accountGrpc.deleteAccount")
  public void deleteAccount(
      DeleteAccountRequest request, StreamObserver<DeleteAccountResponse> responseObserver) {
    try {
      AdminRoleGuard.requireAdminRole();
      accountService.deleteAccount(
          resolveAccountStorageIdForRpc(requireCanonicalAccountUuid(request.getAccountId())));
      DeleteAccountResponse response = DeleteAccountResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AccountUuidReadbackUnavailableException ex) {
      DeleteAccountResponse response =
          DeleteAccountResponse.newBuilder()
              .setSuccess(false)
              .setError(accountUuidReadbackUnavailableError("DeleteAccount"))
              .build();
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
    } catch (
        net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository
                .SourceEvidenceUnavailableException
            unavailable) {
      var response =
          net.firedevops.firemud.account.v1.CompletePasswordResetResponse.newBuilder()
              .setSuccess(false)
              .setError(sourceEvidenceUnavailableError("CompletePasswordReset"))
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
    responseObserver.onNext(
        net.firedevops.firemud.account.v1.LinkExternalAccountResponse.newBuilder()
            .setSuccess(false)
            .setError(
                appError(
                    "LinkExternalAccount",
                    "FAILED_PRECONDITION",
                    "No authorized internal caller is configured"))
            .build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "accountGrpc.requestEmailVerification")
  public void requestEmailVerification(
      net.firedevops.firemud.account.v1.RequestEmailVerificationRequest request,
      StreamObserver<net.firedevops.firemud.account.v1.RequestEmailVerificationResponse>
          responseObserver) {
    responseObserver.onNext(
        net.firedevops.firemud.account.v1.RequestEmailVerificationResponse.newBuilder()
            .setSuccess(false)
            .setError(
                appError(
                    "RequestEmailVerification",
                    "FAILED_PRECONDITION",
                    "No authorized internal caller is configured"))
            .build());
    responseObserver.onCompleted();
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
    } catch (
        net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository
                .SourceEvidenceUnavailableException
            unavailable) {
      var response =
          net.firedevops.firemud.account.v1.VerifyEmailResponse.newBuilder()
              .setSuccess(false)
              .setError(sourceEvidenceUnavailableError("VerifyEmail"))
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

  private net.firedevops.firemud.shared.v1.ErrorDetail sourceEvidenceUnavailableError(
      String operation) {
    return appError(operation, "AUTH_UNAVAILABLE", "Account authority is unavailable");
  }

  private net.firedevops.firemud.shared.v1.ErrorDetail accountUuidReadbackUnavailableError(
      String operation) {
    return appError(operation, "AUTH_UNAVAILABLE", AUTHORITY_UNAVAILABLE_MESSAGE);
  }

  private long resolveAccountStorageIdForRpc(UUID accountUuid) {
    try {
      return accountService.resolveAccountStorageId(accountUuid);
    } catch (IllegalStateException | DataAccessException | TransactionException ex) {
      logger.warn(
          "Account UUID readback unavailable; exceptionClass={}", ex.getClass().getSimpleName());
      throw new AccountUuidReadbackUnavailableException(ex);
    }
  }

  private static final class AccountUuidReadbackUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private AccountUuidReadbackUnavailableException(Throwable cause) {
      super(cause);
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

  private String requireEmail(String value) {
    try {
      return EmailCanonicalization.normalize(value);
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
