package net.firedevops.firemud.entitymanagement.client;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.security.JwtClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Ordinary credential authentication used only by explicitly enabled local demo seeding. */
@Component
@ConditionalOnProperty(
    prefix = "firemud.smoke.seed-demo-runtime",
    name = "enabled",
    havingValue = "true")
@Lazy
public final class DemoAccountAuthenticationClient
    extends AbstractReloadingBlockingGrpcClient<AccountServiceGrpc.AccountServiceBlockingStub> {
  private static final Logger logger =
      LoggerFactory.getLogger(DemoAccountAuthenticationClient.class);
  private static final int MAX_AUTHENTICATION_ATTEMPTS = 3;
  private static final long RETRY_DELAY_MILLIS = 250;
  private final String namespace;
  private final String email;
  private final String password;

  public DemoAccountAuthenticationClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channelFactory,
      @Value("${firemud.grpc.workload-namespace:}") String namespace,
      @Value("${firemud.smoke.seed-demo-account.email:demo@example.com}") String email,
      @Value("${firemud.smoke.seed-demo-account.password:swordfish}") String password) {
    super(
        endpoints,
        EntityManagementSourceClientValidation.requireEntityManagementMtls(
            tls, "Demo Account authentication"),
        channelFactory,
        DemoAccountAuthenticationClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException(
          "Demo authentication requires the configured workload namespace");
    }
    this.namespace = namespace;
    this.email = email;
    this.password = password;
  }

  @PostConstruct
  public void init() throws IOException {
    initOnce("Demo Account client is closed", "Demo Account client is not initialized");
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
  protected AccountServiceGrpc.AccountServiceBlockingStub buildStub(ManagedChannel channel) {
    return AccountServiceGrpc.newBlockingStub(channel)
        .withInterceptors(
            new GrpcServerPeerIdentityClientInterceptor(
                "spiffe://firemud/ns/" + namespace + "/sa/account-service"));
  }

  public UUID authenticateDemoOwner() {
    var request = AuthenticateRequest.newBuilder().setEmail(email).setPassword(password).build();
    for (int attempt = 1; ; attempt++) {
      if (Thread.currentThread().isInterrupted()) {
        throw new IllegalStateException("Account demo authentication interrupted");
      }
      try {
        var response =
            requireInitializedStub("Demo Account client is not initialized")
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .authenticate(request);
        if (response.hasError() || response.getAuthToken().isBlank()) {
          throw new IllegalStateException("Account demo authentication is unavailable");
        }
        return UUID.fromString(
            JwtClaims.requireCanonicalAccountUuid(response.getAccountId(), "accountId"));
      } catch (StatusRuntimeException ex) {
        Status.Code code = ex.getStatus().getCode();
        if (attempt >= MAX_AUTHENTICATION_ATTEMPTS
            || (code != Status.Code.UNAVAILABLE && code != Status.Code.DEADLINE_EXCEEDED)) {
          throw ex;
        }
        if (Thread.currentThread().isInterrupted()) {
          throw new IllegalStateException("Account demo authentication interrupted", ex);
        }
        logger.warn(
            "Local demo Account authentication transport retry after attempt {}/{} status={}",
            attempt,
            MAX_AUTHENTICATION_ATTEMPTS,
            code);
        try {
          Thread.sleep(RETRY_DELAY_MILLIS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Account demo authentication interrupted", interrupted);
        }
      }
    }
  }

  @Override
  @PreDestroy
  public void close() throws IOException {
    super.close();
  }
}
