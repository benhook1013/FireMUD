package net.firedevops.firemud.accountservice.client;

import io.grpc.ClientInterceptors;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Account-only authenticated read of a Game Design owner-approved legacy association. */
@Component
@ConditionalOnProperty(
    prefix = "firemud.account-tenant-migration",
    name = "enabled",
    havingValue = "true")
public final class GameDesignTenantIdentityClient
    extends AbstractReloadingBlockingGrpcClient<
        TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub> {
  private final GrpcServerPeerIdentityClientInterceptor serverPeerIdentityInterceptor;

  public GameDesignTenantIdentityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    super(
        endpoints,
        requireAccountMtls(tlsProps),
        channelFactory,
        GameDesignTenantIdentityClient.class);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Account workload namespace must be one DNS label");
    }
    this.serverPeerIdentityInterceptor =
        new GrpcServerPeerIdentityClientInterceptor(gameDesignServerPeerUri(workloadNamespace));
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameDesignService();
  }

  @Override
  protected String defaultTarget() {
    return "game-design-service:6565";
  }

  @Override
  protected TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return TenantIdentityServiceGrpc.newBlockingStub(
            ClientInterceptors.intercept(channel, serverPeerIdentityInterceptor))
        .withCompression("gzip");
  }

  private static String gameDesignServerPeerUri(String namespace) {
    return "spiffe://firemud/ns/" + namespace + "/sa/game-design-service";
  }

  private static CommonGrpcClientProperties requireAccountMtls(
      CommonGrpcClientProperties tlsProps) {
    if (tlsProps == null) {
      throw new IllegalArgumentException("Account gRPC TLS configuration is required");
    }
    if (tlsProps.isPlaintext()) {
      throw new IllegalArgumentException(
          "Retained tenant identity reads require Account workload mTLS");
    }
    if (!hasText(tlsProps.getCertChain())
        || !hasText(tlsProps.getPrivateKey())
        || !hasText(tlsProps.getCaCert())) {
      throw new IllegalArgumentException(
          "Retained tenant identity reads require Account certificate, key, and CA files");
    }
    if (tlsProps.getCertChain().trim().startsWith("classpath:")
        || tlsProps.getPrivateKey().trim().startsWith("classpath:")
        || tlsProps.getCaCert().trim().startsWith("classpath:")) {
      throw new IllegalArgumentException(
          "Retained tenant identity reads require file-backed Account workload mTLS");
    }
    return tlsProps;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  public ResolveLegacyAccountTenantAssociationResponse resolveApprovedAssociation(long tenantId) {
    if (tenantId <= 0) {
      throw new IllegalArgumentException("positive legacy Account tenant key is required");
    }
    return stub()
        .withDeadlineAfter(5, TimeUnit.SECONDS)
        .resolveLegacyAccountTenantAssociation(
            ResolveLegacyAccountTenantAssociationRequest.newBuilder()
                .setLegacyAccountTenantId(tenantId)
                .build());
  }
}
