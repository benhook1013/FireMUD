package net.firedevops.firemud.worldmanagement.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.v1.CleanupRuntimeInstanceRequest;
import net.firedevops.firemud.entitymanagement.v1.CleanupRuntimeInstanceResponse;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import org.springframework.stereotype.Component;

@Component
public class EntityManagementClient
    extends AbstractReloadingBlockingGrpcClient<
        EntityManagementServiceGrpc.EntityManagementServiceBlockingStub> {
  public EntityManagementClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer, EntityManagementClient.class);
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
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
    return applyStubCustomizer(
        EntityManagementServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  public CleanupRuntimeInstanceResponse cleanupRuntimeInstance(
      long tenantId, long gameInstanceId, String terminationRequestId) {
    return stub()
        .cleanupRuntimeInstance(
            CleanupRuntimeInstanceRequest.newBuilder()
                .setTenantId(Long.toString(tenantId))
                .setGameInstanceId(Long.toString(gameInstanceId))
                .setTerminationRequestId(terminationRequestId)
                .build());
  }
}
