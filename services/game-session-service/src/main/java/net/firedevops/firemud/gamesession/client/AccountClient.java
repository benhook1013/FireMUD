package net.firedevops.firemud.gamesession.client;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.EnsurePublicProductionPlayerMembershipRequest;
import net.firedevops.firemud.account.v1.EnsurePublicProductionPlayerMembershipResponse;
import net.firedevops.firemud.account.v1.GetProfileRequest;
import net.firedevops.firemud.account.v1.GetProfileResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.account.v1.PingRequest;
import net.firedevops.firemud.account.v1.PingResponse;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.service.AccountPresenceVisibilityPolicy;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** gRPC client for the Account Service login endpoint. */
@Component
public final class AccountClient
    extends AbstractBlockingGrpcClient<AccountServiceGrpc.AccountServiceBlockingStub> {
  private static final long READINESS_DEADLINE_SECONDS = 2L;
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final Logger logger = LoggingUtil.getLogger(AccountClient.class);
  private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

  private final DevIsolatedProperties devIsolatedProperties;

  public AccountClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      DevIsolatedProperties devIsolatedProperties,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer);
    this.devIsolatedProperties = devIsolatedProperties;
  }

  @PostConstruct
  void init() throws Exception {
    if (devIsolatedProperties.isDevIsolated()) {
      logger.info("Dev-isolated mode enabled; skipping AccountService channel initialization");
      return;
    }
    initClient();
  }

  /** Authenticates a player via the Account Service. */
  public AuthenticateResponse authenticate(
      String tenantId, String username, String password, String otp) {
    if (devIsolatedProperties.isDevIsolated() || stub() == null) {
      return AuthenticateResponse.newBuilder().setAuthToken("dev-isolated").build();
    }
    AuthenticateRequest request =
        AuthenticateRequest.newBuilder()
            .setTenantId(tenantId)
            .setUsername(username)
            .setPassword(password)
            .setOtp(otp == null ? "" : otp)
            .build();
    try {
      return callStub().authenticate(request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Account Service unavailable; rebuilding channel and retrying authenticate", ex);
        try {
          initClient();
          return callStub().authenticate(request);
        } catch (Exception retryEx) {
          logger.warn("Failed to retry Account Service authenticate after channel reload", retryEx);
        }
      } else {
        logger.warn("Failed to call Account Service authenticate endpoint", ex);
      }
    } catch (Exception ex) {
      logger.warn("Failed to call Account Service authenticate endpoint", ex);
    }
    return AuthenticateResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode(AuthenticationErrorCodes.UPSTREAM_FAILURE)
                .setMessage("Authentication service unavailable"))
        .build();
  }

  public AuthenticateResponse authenticateForReadiness(
      String tenantId, String username, String password, String otp) {
    if (devIsolatedProperties.isDevIsolated() || stub() == null) {
      return AuthenticateResponse.newBuilder().setAuthToken("dev-isolated").build();
    }
    AuthenticateRequest request =
        AuthenticateRequest.newBuilder()
            .setTenantId(tenantId)
            .setUsername(username)
            .setPassword(password)
            .setOtp(otp == null ? "" : otp)
            .build();
    return stub()
        .withDeadlineAfter(READINESS_DEADLINE_SECONDS, TimeUnit.SECONDS)
        .authenticate(request);
  }

  public PingResponse ping() {
    return callStub().ping(PingRequest.getDefaultInstance());
  }

  public GetTenantMembershipForRuntimeResponse getTenantMembershipForRuntime(
      String accountId, String tenantId, String requestId) {
    if (devIsolatedProperties.isDevIsolated() || stub() == null) {
      return GetTenantMembershipForRuntimeResponse.newBuilder()
          .setAccountId(accountId)
          .setTenantId(tenantId)
          .setGameplayAdmissionAllowed(true)
          .setMembershipVersion(1L)
          .setEvaluatedAt(java.time.Instant.now().toString())
          .build();
    }
    GetTenantMembershipForRuntimeRequest request =
        GetTenantMembershipForRuntimeRequest.newBuilder()
            .setAccountId(accountId)
            .setTenantId(tenantId)
            .setRequestId(requestId == null ? "" : requestId)
            .build();
    try {
      return callStub().getTenantMembershipForRuntime(request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Account Service unavailable; rebuilding channel and retrying runtime membership", ex);
        try {
          initClient();
          return callStub().getTenantMembershipForRuntime(request);
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
    return GetTenantMembershipForRuntimeResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("MEMBERSHIP_AUTH_UNAVAILABLE")
                .setMessage("Membership authority unavailable"))
        .build();
  }

  public GetTenantEntitlementsForRuntimeResponse getTenantEntitlementsForRuntime(
      String tenantId, String requestId) {
    if (devIsolatedProperties.isDevIsolated() || stub() == null) {
      return GetTenantEntitlementsForRuntimeResponse.newBuilder()
          .setTenantId(tenantId)
          .setGameplayAvailable(true)
          .setEntitlementVersion(1L)
          .setTenantBillingSequence(1L)
          .setEvaluatedAt(java.time.Instant.now().toString())
          .build();
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
    return GetTenantEntitlementsForRuntimeResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("ENTITLEMENT_UNAVAILABLE")
                .setMessage("Entitlement authority unavailable"))
        .build();
  }

  public EnsurePublicProductionPlayerMembershipResponse ensurePublicProductionPlayerMembership(
      String accountId, String tenantId, String realmSlug, String requestId) {
    if (devIsolatedProperties.isDevIsolated() || stub() == null) {
      return EnsurePublicProductionPlayerMembershipResponse.newBuilder()
          .setAccountId(accountId)
          .setTenantId(tenantId)
          .setRealmSlug(realmSlug)
          .setGameplayAdmissionAllowed(true)
          .setMembershipVersion(1L)
          .setCreated(true)
          .setEvaluatedAt(java.time.Instant.now().toString())
          .build();
    }
    EnsurePublicProductionPlayerMembershipRequest request =
        EnsurePublicProductionPlayerMembershipRequest.newBuilder()
            .setAccountId(accountId)
            .setTenantId(tenantId)
            .setRealmSlug(realmSlug)
            .setRequestId(requestId == null ? "" : requestId)
            .build();
    try {
      return callStub().ensurePublicProductionPlayerMembership(request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Account Service unavailable; rebuilding channel and retrying public-production membership",
            ex);
        try {
          initClient();
          return callStub().ensurePublicProductionPlayerMembership(request);
        } catch (Exception retryEx) {
          logger.warn(
              "Failed to retry Account Service public-production membership after channel reload",
              retryEx);
        }
      } else {
        logger.warn("Failed to call Account Service public-production membership endpoint", ex);
      }
    } catch (Exception ex) {
      logger.warn("Failed to call Account Service public-production membership endpoint", ex);
    }
    return EnsurePublicProductionPlayerMembershipResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("MEMBERSHIP_AUTH_UNAVAILABLE")
                .setMessage("Membership authority unavailable"))
        .build();
  }

  public AccountPresenceVisibilityPolicy getPresenceVisibilityPolicy(
      long tenantId, long accountId) {
    if (devIsolatedProperties.isDevIsolated() || stub() == null) {
      return AccountPresenceVisibilityPolicy.FRIENDS_ONLY;
    }
    GetProfileRequest request =
        GetProfileRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setAccountId(Long.toString(accountId))
            .build();
    try {
      GetProfileResponse response = callStub().getProfile(request);
      if (response.hasError() || response.getProfileJson().isBlank()) {
        return AccountPresenceVisibilityPolicy.FRIENDS_ONLY;
      }
      JsonNode node = JSON_MAPPER.readTree(response.getProfileJson());
      String value = node.path("presenceVisibilityPolicy").asText("");
      return value.isBlank()
          ? AccountPresenceVisibilityPolicy.FRIENDS_ONLY
          : AccountPresenceVisibilityPolicy.valueOf(value);
    } catch (Exception ex) {
      logger.warn(
          "Failed to resolve account presence visibility policy tenantId={} accountId={}",
          tenantId,
          accountId,
          ex);
      return AccountPresenceVisibilityPolicy.FRIENDS_ONLY;
    }
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
