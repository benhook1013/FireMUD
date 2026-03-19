package net.firedevops.firemud.gamesession.client;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

/** gRPC client for the Account Service login endpoint. */
@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected configuration is stored internally")
public final class AccountClient implements AutoCloseable {
  private static final Logger logger = LoggingUtil.getLogger(AccountClient.class);

  private final ServiceEndpointsProperties endpoints;
  private final CommonGrpcClientProperties tlsProps;
  private final DevIsolatedProperties devIsolatedProperties;
  private final GrpcChannelFactory channelFactory;

  private ManagedChannel channel;
  private AccountServiceGrpc.AccountServiceBlockingStub stub;
  private String target;

  public AccountClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      DevIsolatedProperties devIsolatedProperties,
      GrpcChannelFactory channelFactory) {
    this.endpoints = endpoints;
    this.tlsProps = tlsProps;
    this.devIsolatedProperties = devIsolatedProperties;
    this.channelFactory = channelFactory;
  }

  @PostConstruct
  void init() throws Exception {
    if (devIsolatedProperties.isDevIsolated()) {
      logger.info("Dev-isolated mode enabled; skipping AccountService channel initialization");
      return;
    }
    target = endpoints.getAccountService();
    if (target == null || target.isEmpty()) {
      target = "account-service:6565";
    }
    reloadChannel();
  }

  private synchronized void reloadChannel() throws Exception {
    if (devIsolatedProperties.isDevIsolated()) {
      return;
    }
    ManagedChannel newChannel = channelFactory.buildChannel(target, 6565, tlsProps, true);
    if (channel != null) {
      channel.shutdown();
    }
    channel = newChannel;
    stub = AccountServiceGrpc.newBlockingStub(channel).withCompression("gzip");
  }

  /** Authenticates a player via the Account Service. */
  public AuthenticateResponse authenticate(
      String tenantId, String username, String password, String otp) {
    if (devIsolatedProperties.isDevIsolated() || stub == null) {
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
      return stub.authenticate(request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Account Service unavailable; rebuilding channel and retrying authenticate", ex);
        try {
          reloadChannel();
          return stub.authenticate(request);
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

  @Override
  public void close() {
    if (channel != null) {
      channel.shutdown();
    }
  }
}
