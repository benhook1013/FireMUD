package net.firedevops.firemud.loggingadmin.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.GrpcClientAuth;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamesession.v1.GameSessionServiceGrpc;
import net.firedevops.firemud.gamesession.v1.PingRequest;
import net.firedevops.firemud.gamesession.v1.PingResponse;
import net.firedevops.firemud.gamesession.v1.StopSessionRequest;
import net.firedevops.firemud.gamesession.v1.StopSessionResponse;
import org.springframework.stereotype.Component;

/** gRPC client for communicating with the Game Session Service. */
@Component
public class GameSessionClient
    extends AbstractReloadingBlockingGrpcClient<
        GameSessionServiceGrpc.GameSessionServiceBlockingStub> {
  private final JwtUtil jwtUtil;

  public GameSessionClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      JwtUtil jwtUtil,
      GrpcChannelFactory channelFactory) {
    super(endpoints, tlsProps, channelFactory, GameSessionClient.class);
    this.jwtUtil = jwtUtil;
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
    return GrpcClientAuth.attach(
        GameSessionServiceGrpc.newBlockingStub(channel).withCompression("gzip"), jwtUtil);
  }

  /** Simple ping to verify connectivity. */
  public PingResponse ping() {
    return stub().ping(PingRequest.newBuilder().build());
  }

  /** Stop a running session by ID. */
  public StopSessionResponse stopSession(long sessionId) {
    StopSessionRequest request =
        StopSessionRequest.newBuilder().setSessionId(Long.toString(sessionId)).build();
    return stub().stopSession(request);
  }
}
