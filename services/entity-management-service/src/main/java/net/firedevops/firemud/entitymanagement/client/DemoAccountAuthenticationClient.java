package net.firedevops.firemud.entitymanagement.client;

import io.grpc.ManagedChannel;
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
    var response =
        requireInitializedStub("Demo Account client is not initialized")
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .authenticate(
                AuthenticateRequest.newBuilder().setEmail(email).setPassword(password).build());
    if (response.hasError() || response.getAuthToken().isBlank()) {
      throw new IllegalStateException("Account demo authentication is unavailable");
    }
    return UUID.fromString(
        JwtClaims.requireCanonicalAccountUuid(response.getAccountId(), "accountId"));
  }

  @Override
  @PreDestroy
  public void close() throws IOException {
    super.close();
  }
}
