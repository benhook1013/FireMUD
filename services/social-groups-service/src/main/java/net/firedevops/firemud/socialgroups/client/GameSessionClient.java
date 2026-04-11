package net.firedevops.firemud.socialgroups.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.v1.GameSessionServiceGrpc;
import net.firedevops.firemud.gamesession.v1.QueryAccountPresenceRequest;
import net.firedevops.firemud.gamesession.v1.QueryAccountPresenceResponse;
import org.springframework.stereotype.Component;

@Component
public class GameSessionClient
    extends AbstractReloadingBlockingGrpcClient<
        GameSessionServiceGrpc.GameSessionServiceBlockingStub> {
  public GameSessionClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer, GameSessionClient.class);
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameSessionService();
  }

  @Override
  protected String defaultTarget() {
    return "game-session-service:6565";
  }

  @Override
  protected GameSessionServiceGrpc.GameSessionServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        GameSessionServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  public QueryAccountPresenceResponse queryAccountPresence(
      long tenantId, long viewerAccountId, List<Long> accountIds) {
    QueryAccountPresenceRequest.Builder request =
        QueryAccountPresenceRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setViewerAccountId(Long.toString(viewerAccountId));
    for (Long accountId : accountIds) {
      if (accountId != null) {
        request.addAccountIds(Long.toString(accountId));
      }
    }
    return stub().queryAccountPresence(request.build());
  }
}
