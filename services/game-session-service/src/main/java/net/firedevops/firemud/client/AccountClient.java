package net.firedevops.firemud.client;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.config.DevIsolatedProperties;
import net.firedevops.firemud.config.GrpcClientProperties;
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
  private final GrpcClientProperties tlsProps;
  private final DevIsolatedProperties devIsolatedProperties;

  private ManagedChannel channel;
  private AccountServiceGrpc.AccountServiceBlockingStub stub;

  public AccountClient(
      ServiceEndpointsProperties endpoints,
      GrpcClientProperties tlsProps,
      DevIsolatedProperties devIsolatedProperties) {
    this.endpoints = endpoints;
    this.tlsProps = tlsProps;
    this.devIsolatedProperties = devIsolatedProperties;
  }

  @PostConstruct
  void init() throws Exception {
    if (devIsolatedProperties.isDevIsolated()) {
      logger.info("Dev-isolated mode enabled; skipping AccountService channel initialization");
      return;
    }
    String target = endpoints.getAccountService();
    if (target == null || target.isEmpty()) {
      target = "account-service:6565";
    }
    String[] parts = target.split(":");
    String host = parts[0];
    int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 6565;
    if (tlsProps.isPlaintext()) {
      channel =
          ManagedChannelBuilder.forAddress(host, port)
              .keepAliveTime(30, TimeUnit.SECONDS)
              .keepAliveTimeout(5, TimeUnit.SECONDS)
              .keepAliveWithoutCalls(true)
              .usePlaintext()
              .build();
    } else {
      var sslContext =
          GrpcSslContexts.forClient()
              .trustManager(new File(tlsProps.getCaCert()))
              .keyManager(new File(tlsProps.getCertChain()), new File(tlsProps.getPrivateKey()))
              .build();
      channel =
          NettyChannelBuilder.forAddress(host, port)
              .sslContext(sslContext)
              .keepAliveTime(30, TimeUnit.SECONDS)
              .keepAliveTimeout(5, TimeUnit.SECONDS)
              .keepAliveWithoutCalls(true)
              .build();
    }
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
    } catch (Exception ex) {
      logger.warn("Failed to call Account Service authenticate endpoint", ex);
      return AuthenticateResponse.newBuilder()
          .setError(
              ErrorDetail.newBuilder()
                  .setCode(AuthenticationErrorCodes.UPSTREAM_FAILURE)
                  .setMessage("Authentication service unavailable"))
          .build();
    }
  }

  @Override
  public void close() {
    if (channel != null) {
      channel.shutdown();
    }
  }
}
