package net.firedevops.firemud.accountservice.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.springframework.stereotype.Component;

/** Account-only authenticated read of a Game Design owner-approved legacy association. */
@Component
public class GameDesignTenantIdentityClient
    extends AbstractReloadingBlockingGrpcClient<
        TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub> {
  public GameDesignTenantIdentityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(
        endpoints, tlsProps, channelFactory, stubCustomizer, GameDesignTenantIdentityClient.class);
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
    return applyStubCustomizer(
        TenantIdentityServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
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
