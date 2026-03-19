package net.firedevops.firemud.gamesession.client;

import jakarta.annotation.PostConstruct;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PingRequest;
import net.firedevops.firemud.entitymanagement.v1.PingResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** gRPC client for the Entity Management Service. */
@Component
@ConditionalOnProperty(
    name = "game-session.dev-isolated",
    havingValue = "false",
    matchIfMissing = false)
public final class EntityManagementClient
    extends AbstractBlockingGrpcClient<
        EntityManagementServiceGrpc.EntityManagementServiceBlockingStub> {

  public EntityManagementClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory) {
    super(endpoints, tlsProps, channelFactory);
  }

  @PostConstruct
  void init() throws SSLException {
    initClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getEntityManagementService();
  }

  @Override
  protected String defaultTarget() {
    return "entity-management-service:6565";
  }

  @Override
  protected EntityManagementServiceGrpc.EntityManagementServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return EntityManagementServiceGrpc.newBlockingStub(channel).withCompression("gzip");
  }

  /** Simple ping to verify connectivity. */
  public PingResponse ping() {
    return stub().ping(PingRequest.newBuilder().build());
  }
}
