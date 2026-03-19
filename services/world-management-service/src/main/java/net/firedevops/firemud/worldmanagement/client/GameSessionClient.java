package net.firedevops.firemud.worldmanagement.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.v1.GameSessionServiceGrpc;
import net.firedevops.firemud.gamesession.v1.PingRequest;
import net.firedevops.firemud.gamesession.v1.PingResponse;
import org.springframework.stereotype.Component;

/** gRPC client for communicating with the Game Session Service. */
@Component
public class GameSessionClient
    extends AbstractReloadingBlockingGrpcClient<
        GameSessionServiceGrpc.GameSessionServiceBlockingStub> {

  public GameSessionClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory) {
    super(endpoints, tlsProps, channelFactory, GameSessionClient.class);
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
    return GameSessionServiceGrpc.newBlockingStub(channel).withCompression("gzip");
  }

  /** Simple ping to verify connectivity. */
  public PingResponse ping() {
    return stub().ping(PingRequest.newBuilder().build());
  }
}
