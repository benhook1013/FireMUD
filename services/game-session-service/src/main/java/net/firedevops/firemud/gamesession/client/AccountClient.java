package net.firedevops.firemud.gamesession.client;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.PingRequest;
import net.firedevops.firemud.account.v1.PingResponse;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

/** gRPC client for the Account Service login endpoint. */
@Component
public final class AccountClient
    extends AbstractBlockingGrpcClient<AccountServiceGrpc.AccountServiceBlockingStub> {
  private static final long READINESS_DEADLINE_SECONDS = 2L;
  private static final Logger logger = LoggingUtil.getLogger(AccountClient.class);

  private final DevIsolatedProperties devIsolatedProperties;

  public AccountClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      DevIsolatedProperties devIsolatedProperties,
      GrpcChannelFactory channelFactory) {
    super(endpoints, tlsProps, channelFactory);
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
      return stub().authenticate(request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Account Service unavailable; rebuilding channel and retrying authenticate", ex);
        try {
          initClient();
          return stub().authenticate(request);
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
    return stub().ping(PingRequest.getDefaultInstance());
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
    return AccountServiceGrpc.newBlockingStub(channel).withCompression("gzip");
  }
}
