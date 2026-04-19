package net.firedevops.firemud.accountservice.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.v1.Character;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountRequest;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.stereotype.Component;

/** Blocking client for first-party bootstrap character discovery. */
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

  public List<Character> listCharactersByAccount(
      long tenantId, long accountId, long gameInstanceId, PlayableStateScope playableStateScope) {
    ListCharactersByAccountResponse response =
        stub()
            .listCharactersByAccount(
                ListCharactersByAccountRequest.newBuilder()
                    .setTenantId(Long.toString(tenantId))
                    .setAccountId(Long.toString(accountId))
                    .setGameInstanceId(Long.toString(gameInstanceId))
                    .setPlayableStateScope(playableStateScope)
                    .build());
    if (response.hasError()) {
      throw new IllegalStateException(
          "Character discovery failed: " + response.getError().getCode());
    }
    return response.getCharactersList();
  }
}
