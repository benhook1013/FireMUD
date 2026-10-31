package net.firedevops.firemud.gamesession.client;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
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
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** gRPC client for the Account Service login endpoint. */
@Component
public final class AccountClient
    extends AbstractBlockingGrpcClient<AccountServiceGrpc.AccountServiceBlockingStub> {
  private static final long READINESS_DEADLINE_SECONDS = 2L;
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final Logger logger = LoggingUtil.getLogger(AccountClient.class);
  private final Clock clock;

  @Autowired
  public AccountClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    this(endpoints, tlsProps, channelFactory, stubCustomizer, Clock.systemUTC());
  }

  AccountClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer,
      Clock clock) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer);
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @PostConstruct
  void init() throws Exception {
    initClient();
  }

  /** Authenticates a player via the Account Service. */
  public AuthenticateResponse authenticate(String email, String password) {
    if (stub() == null) {
      return authenticationUnavailable();
    }
    AuthenticateRequest request =
        AuthenticateRequest.newBuilder().setEmail(email).setPassword(password).build();
    try {
      return callStub().authenticate(request);
    } catch (StatusRuntimeException ex) {
      Status.Code statusCode = ex.getStatus().getCode();
      if (statusCode == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Account Service authentication transport failed with UNAVAILABLE; "
                + "not retrying credential-consuming authentication without an idempotency identity",
            ex);
        try {
          initClient();
        } catch (Exception reloadEx) {
          logger.warn(
              "Failed to reload Account Service channel after authentication failure", reloadEx);
        }
        return authenticationUnavailable();
      }
      if (statusCode == Status.Code.DEADLINE_EXCEEDED) {
        logger.warn(
            "Account Service authentication deadline exceeded; not retrying "
                + "credential-consuming authentication without an idempotency identity",
            ex);
        return authenticationUnavailable();
      }
      logger.warn("Account Service authentication returned a terminal gRPC status", ex);
      return authenticationError(statusCode.name(), "Authentication request failed");
    } catch (Exception ex) {
      logger.warn("Account Service authenticate failed before a response completed", ex);
      return authenticationUnavailable();
    }
  }

  private AuthenticateResponse authenticationUnavailable() {
    return authenticationError(
        AuthenticationErrorCodes.UNAVAILABLE, "Authentication service unavailable");
  }

  private AuthenticateResponse authenticationError(String code, String message) {
    return AuthenticateResponse.newBuilder()
        .setError(ErrorDetail.newBuilder().setCode(code).setMessage(message))
        .build();
  }

  /** Requests a neutral email-login challenge from the Account Service. */
  public RequestEmailLoginOtpResponse requestEmailLoginOtp(String email) {
    if (stub() == null) {
      return emailLoginOtpUnavailable();
    }
    RequestEmailLoginOtpRequest request =
        RequestEmailLoginOtpRequest.newBuilder().setEmail(email).build();
    try {
      return callStub().requestEmailLoginOtp(request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Account Service unavailable; rebuilding channel and retrying email login challenge",
            ex);
        try {
          initClient();
          return callStub().requestEmailLoginOtp(request);
        } catch (Exception retryEx) {
          logger.warn(
              "Failed to retry Account Service email login challenge after channel reload",
              retryEx);
        }
      } else {
        logger.warn("Failed to call Account Service email login challenge endpoint", ex);
      }
    } catch (Exception ex) {
      logger.warn("Failed to call Account Service email login challenge endpoint", ex);
    }
    return emailLoginOtpUnavailable();
  }

  public AuthenticateResponse authenticateForReadiness(String email, String password) {
    if (stub() == null) {
      return AuthenticateResponse.newBuilder()
          .setError(
              ErrorDetail.newBuilder()
                  .setCode(AuthenticationErrorCodes.UNAVAILABLE)
                  .setMessage("Authentication service unavailable"))
          .build();
    }
    AuthenticateRequest request =
        AuthenticateRequest.newBuilder().setEmail(email).setPassword(password).build();
    return stub()
        .withDeadlineAfter(READINESS_DEADLINE_SECONDS, TimeUnit.SECONDS)
        .authenticate(request);
  }

  private RequestEmailLoginOtpResponse emailLoginOtpUnavailable() {
    return RequestEmailLoginOtpResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode(AuthenticationErrorCodes.UNAVAILABLE)
                .setMessage("Authentication service unavailable"))
        .build();
  }

  public PingResponse ping() {
    return callStub().ping(PingRequest.getDefaultInstance());
  }

  /**
   * Requests one Account-owned scope for the exact server-resolved REALMS target. The unsigned
   * context supplies scope data only; Account authenticates the workload and caller separately. The
   * consuming store must validate the returned opaque ID and expiry before retaining them.
   */
  public IssueDirectTextConnectScopeResponse issueDirectTextConnectScope(
      PlayerExecutionContext playerContext, DirectTextConnectScopeTarget target) {
    if (!hasDirectTextCallerShape(playerContext)
        || target == null
        || !playerContext.getTenantId().equals(target.tenantId())
        || !playerContext.getRealmId().equals(target.realmId())
        || !playerContext.getPlayableStateNamespaceId().equals(target.playableStateNamespaceId())
        || !playerContext.getPlayableStateScope().equals(target.playableStateScope())
        || !playerContext.getGameInstanceId().equals(target.gameInstanceId())) {
      return scopeIssueError("INVALID_ARGUMENT", "Direct-text scope context did not match target");
    }
    if (stub() == null) {
      return scopeIssueError("AUTH_UNAVAILABLE", "Account authority unavailable");
    }
    IssueDirectTextConnectScopeRequest request =
        IssueDirectTextConnectScopeRequest.newBuilder()
            .setPlayerContext(playerContext)
            .setTenantId(target.tenantId())
            .setWorldSlug(target.worldSlug())
            .setRealmSlug(target.realmSlug())
            .setRealmId(target.realmId())
            .setPlayableStateNamespaceId(target.playableStateNamespaceId())
            .setPlayableStateScope(target.playableStateScope())
            .setGameInstanceId(target.gameInstanceId())
            .setCatalogRevision(target.catalogRevision())
            .setPointerVersion(target.pointerVersion())
            .build();
    try {
      return callStub().issueDirectTextConnectScope(request);
    } catch (Exception ex) {
      // Issuance has no retained idempotency identity: never automatically issue again.
      logger.warn("Account direct-text scope issuance did not complete", ex);
      return scopeIssueError("AUTH_UNAVAILABLE", "Account authority unavailable");
    }
  }

  /** Applies explicit JOIN, retaining one immutable request across at most one transport retry. */
  public JoinPublicProductionMembershipResponse joinPublicProductionMembership(
      PlayerExecutionContext playerContext,
      String connectScopeId,
      String requestId,
      Instant connectScopeExpiresAt) {
    if (!hasDirectTextCallerShape(playerContext)
        || !isBoundedText(connectScopeId, 2048)
        || !isBoundedText(requestId, 128)
        || !playerContext.getRequestId().equals(requestId)) {
      return joinError("INVALID_ARGUMENT", "Direct-text JOIN request was incomplete");
    }
    if (!hasUsableConnectScope(connectScopeExpiresAt)) {
      return joinError("CONNECT_SCOPE_INVALID", "Direct-text JOIN scope is missing or expired");
    }
    if (stub() == null) {
      return joinUnavailable();
    }
    JoinPublicProductionMembershipRequest request =
        JoinPublicProductionMembershipRequest.newBuilder()
            .setPlayerContext(playerContext)
            .setConnectScopeId(connectScopeId)
            .setRequestId(requestId)
            .build();
    try {
      AccountServiceGrpc.AccountServiceBlockingStub initialStub = callStub();
      if (!hasUsableConnectScope(connectScopeExpiresAt)) {
        return joinError("CONNECT_SCOPE_INVALID", "Direct-text JOIN scope is missing or expired");
      }
      return correlateJoin(initialStub.joinPublicProductionMembership(request), request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE
          || ex.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
        // Expiry after possible dispatch is uncertainty, never evidence of no commit or rearm.
        if (!hasUsableConnectScope(connectScopeExpiresAt)) {
          return joinUnavailable();
        }
        logger.warn("Account direct-text JOIN response unavailable; retrying exact request", ex);
        try {
          initClient();
          AccountServiceGrpc.AccountServiceBlockingStub retryStub = callStub();
          if (!hasUsableConnectScope(connectScopeExpiresAt)) {
            return joinUnavailable();
          }
          return correlateJoin(retryStub.joinPublicProductionMembership(request), request);
        } catch (Exception retryEx) {
          logger.warn("Account direct-text JOIN retry did not complete", retryEx);
        }
        return joinUnavailable();
      }
      // This is a command-level failure, not an Account terminal operation outcome.
      logger.warn("Account direct-text JOIN transport failed", ex);
      return joinError("JOIN_FAILED", "Account JOIN request failed");
    } catch (Exception ex) {
      logger.warn("Account direct-text JOIN did not complete", ex);
      return joinUnavailable();
    }
  }

  private JoinPublicProductionMembershipResponse correlateJoin(
      JoinPublicProductionMembershipResponse response,
      JoinPublicProductionMembershipRequest request) {
    if (response == null) {
      return joinUnavailable();
    }
    if (response.getSuccess()
        && (!request.getPlayerContext().getAccountId().equals(response.getAccountId())
            || !request.getPlayerContext().getTenantId().equals(response.getTenantId()))) {
      return joinUnavailable();
    }
    return response;
  }

  private boolean hasUsableConnectScope(Instant expiresAt) {
    return expiresAt != null && expiresAt.isAfter(clock.instant());
  }

  private boolean hasDirectTextCallerShape(PlayerExecutionContext context) {
    if (context == null) {
      return false;
    }
    try {
      JwtClaims.requireCanonicalAccountUuid(context.getAccountId(), "accountId");
      RequestIdValidation.requirePositiveLong(context.getTenantId(), "tenantId");
      DirectTextConnectScopeTarget.requireUuid(context.getRealmId(), "realmId");
      DirectTextConnectScopeTarget.requireUuid(
          context.getPlayableStateNamespaceId(), "playableStateNamespaceId");
      DirectTextConnectScopeTarget.requireScope(context.getPlayableStateScope());
      RequestIdValidation.requirePositiveLong(context.getGameInstanceId(), "gameInstanceId");
      return isBoundedText(context.getSessionId(), 128)
          && isBoundedText(context.getRequestId(), 128);
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private boolean isBoundedText(String value, int maxLength) {
    return value != null && !value.isBlank() && value.length() <= maxLength;
  }

  private IssueDirectTextConnectScopeResponse scopeIssueError(String code, String message) {
    return IssueDirectTextConnectScopeResponse.newBuilder()
        .setError(ErrorDetail.newBuilder().setCode(code).setMessage(message))
        .build();
  }

  private JoinPublicProductionMembershipResponse joinUnavailable() {
    return joinError("AUTH_UNAVAILABLE", "Account authority unavailable");
  }

  private JoinPublicProductionMembershipResponse joinError(String code, String message) {
    return JoinPublicProductionMembershipResponse.newBuilder()
        .setOutcomeCode(code)
        .setError(ErrorDetail.newBuilder().setCode(code).setMessage(message))
        .build();
  }

  public GetTenantMembershipForRuntimeResponse getTenantMembershipForRuntime(
      String accountId, String tenantId, String requestId) {
    if (stub() == null) {
      return membershipAuthorityUnavailable();
    }
    try {
      JwtClaims.requireCanonicalAccountUuid(accountId, "accountId");
    } catch (IllegalArgumentException ex) {
      return membershipAuthorityUnavailable();
    }
    GetTenantMembershipForRuntimeRequest request =
        GetTenantMembershipForRuntimeRequest.newBuilder()
            .setAccountId(accountId)
            .setTenantId(tenantId)
            .setRequestId(requestId == null ? "" : requestId)
            .build();
    try {
      return correlateMembership(callStub().getTenantMembershipForRuntime(request), request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Account Service unavailable; rebuilding channel and retrying runtime membership", ex);
        try {
          initClient();
          return correlateMembership(callStub().getTenantMembershipForRuntime(request), request);
        } catch (Exception retryEx) {
          logger.warn(
              "Failed to retry Account Service runtime membership after channel reload", retryEx);
        }
      } else {
        logger.warn("Failed to call Account Service runtime membership endpoint", ex);
      }
    } catch (Exception ex) {
      logger.warn("Failed to call Account Service runtime membership endpoint", ex);
    }
    return membershipAuthorityUnavailable();
  }

  private GetTenantMembershipForRuntimeResponse membershipAuthorityUnavailable() {
    return GetTenantMembershipForRuntimeResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode(AuthenticationErrorCodes.UNAVAILABLE)
                .setMessage("Membership authority unavailable"))
        .build();
  }

  public GetTenantEntitlementsForRuntimeResponse getTenantEntitlementsForRuntime(
      String tenantId, String requestId) {
    if (stub() == null) {
      return entitlementAuthorityUnavailable();
    }
    GetTenantEntitlementsForRuntimeRequest request =
        GetTenantEntitlementsForRuntimeRequest.newBuilder()
            .setTenantId(tenantId)
            .setRequestId(requestId == null ? "" : requestId)
            .build();
    try {
      return callStub().getTenantEntitlementsForRuntime(request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Account Service unavailable; rebuilding channel and retrying runtime entitlements",
            ex);
        try {
          initClient();
          return callStub().getTenantEntitlementsForRuntime(request);
        } catch (Exception retryEx) {
          logger.warn(
              "Failed to retry Account Service runtime entitlements after channel reload", retryEx);
        }
      } else {
        logger.warn("Failed to call Account Service runtime entitlements endpoint", ex);
      }
    } catch (Exception ex) {
      logger.warn("Failed to call Account Service runtime entitlements endpoint", ex);
    }
    return entitlementAuthorityUnavailable();
  }

  public GetRealmAccessGrantForRuntimeResponse getRealmAccessGrantForRuntime(
      String accountId, String tenantId, String worldSlug, String realmSlug, String requestId) {
    if (stub() == null) {
      return realmAccessGrantUnavailable();
    }
    try {
      JwtClaims.requireCanonicalAccountUuid(accountId, "accountId");
    } catch (IllegalArgumentException ex) {
      return realmAccessGrantUnavailable();
    }
    GetRealmAccessGrantForRuntimeRequest request =
        GetRealmAccessGrantForRuntimeRequest.newBuilder()
            .setAccountId(accountId)
            .setTenantId(tenantId)
            .setWorldSlug(worldSlug)
            .setRealmSlug(realmSlug)
            .setRequestId(requestId == null ? "" : requestId)
            .build();
    try {
      return correlateRealmGrant(callStub().getRealmAccessGrantForRuntime(request), request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Account Service unavailable; rebuilding channel and retrying realm access grant read",
            ex);
        try {
          initClient();
          return correlateRealmGrant(callStub().getRealmAccessGrantForRuntime(request), request);
        } catch (Exception retryEx) {
          logger.warn(
              "Failed to retry Account Service realm access grant read after channel reload",
              retryEx);
        }
      } else {
        logger.warn("Failed to call Account Service realm access grant endpoint", ex);
      }
    } catch (Exception ex) {
      logger.warn("Failed to call Account Service realm access grant endpoint", ex);
    }
    return realmAccessGrantUnavailable();
  }

  private GetTenantMembershipForRuntimeResponse correlateMembership(
      GetTenantMembershipForRuntimeResponse response,
      GetTenantMembershipForRuntimeRequest request) {
    if (response != null && response.hasError()) {
      return response;
    }
    if (response == null
        || !request.getAccountId().equals(response.getAccountId())
        || !request.getTenantId().equals(response.getTenantId())) {
      return membershipAuthorityUnavailable();
    }
    return response;
  }

  private GetRealmAccessGrantForRuntimeResponse correlateRealmGrant(
      GetRealmAccessGrantForRuntimeResponse response,
      GetRealmAccessGrantForRuntimeRequest request) {
    if (response != null && response.hasError()) {
      return response;
    }
    if (response == null
        || !request.getAccountId().equals(response.getAccountId())
        || !request.getTenantId().equals(response.getTenantId())
        || !request.getWorldSlug().equals(response.getWorldSlug())
        || !request.getRealmSlug().equals(response.getRealmSlug())) {
      return realmAccessGrantUnavailable();
    }
    return response;
  }

  private GetRealmAccessGrantForRuntimeResponse realmAccessGrantUnavailable() {
    return GetRealmAccessGrantForRuntimeResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode(AuthenticationErrorCodes.UNAVAILABLE)
                .setMessage("Realm grant authority unavailable"))
        .build();
  }

  private GetTenantEntitlementsForRuntimeResponse entitlementAuthorityUnavailable() {
    return GetTenantEntitlementsForRuntimeResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("ENTITLEMENT_UNAVAILABLE")
                .setMessage("Entitlement authority unavailable"))
        .build();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getAccountService();
  }

  @Override
  protected String defaultTarget() {
    return "account-service:6565";
  }

  @Override
  protected AccountServiceGrpc.AccountServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(AccountServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  private AccountServiceGrpc.AccountServiceBlockingStub callStub() {
    return stub().withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
  }
}
