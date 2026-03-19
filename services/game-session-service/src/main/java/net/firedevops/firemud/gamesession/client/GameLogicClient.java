package net.firedevops.firemud.gamesession.client;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.ManagedChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamelogic.v1.BroadcastSayRequest;
import net.firedevops.firemud.gamelogic.v1.BroadcastSayResponse;
import net.firedevops.firemud.gamelogic.v1.ChatAlias;
import net.firedevops.firemud.gamelogic.v1.GameLogicServiceGrpc;
import net.firedevops.firemud.gamelogic.v1.LookRequest;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamelogic.v1.PingRequest;
import net.firedevops.firemud.gamelogic.v1.PingResponse;
import net.firedevops.firemud.gamesession.config.GrpcClientProperties;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected configuration is stored internally")
public class GameLogicClient implements AutoCloseable {
  private final ServiceEndpointsProperties endpoints;
  private final GrpcClientProperties grpcClientProperties;
  private final GrpcChannelFactory channelFactory;

  private ManagedChannel channel;
  private GameLogicServiceGrpc.GameLogicServiceBlockingStub stub;

  @PostConstruct
  void init() throws Exception {
    String target = endpoints.getGameLogicService();
    if (target == null || target.isBlank()) {
      target = "game-logic-service:6565";
    }
    channel = channelFactory.buildChannel(target, 6565, grpcClientProperties, true);
    stub = GameLogicServiceGrpc.newBlockingStub(channel).withCompression("gzip");
  }

  public LookResult resolveLook(String tenantId, String sessionId, String playerId, String roomId) {
    LookRequest request =
        LookRequest.newBuilder()
            .setTenantId(tenantId)
            .setSessionId(sessionId)
            .setPlayerId(playerId)
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(tenantId)
                    .setRoomInstanceId(roomId)
                    .build())
            .build();
    return stub.resolveLook(request);
  }

  public BroadcastSayResponse broadcastSay(
      String tenantId,
      String sessionId,
      String playerId,
      String roomId,
      String aliasToken,
      String text) {
    BroadcastSayRequest request =
        BroadcastSayRequest.newBuilder()
            .setTenantId(tenantId)
            .setSessionId(sessionId)
            .setPlayerId(playerId)
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(tenantId)
                    .setRoomInstanceId(roomId)
                    .build())
            .setAlias(mapAlias(aliasToken))
            .setText(text)
            .build();
    return stub.broadcastSay(request);
  }

  public PingResponse ping() {
    return stub.ping(PingRequest.getDefaultInstance());
  }

  private ChatAlias mapAlias(String token) {
    if (token == null || token.isBlank()) {
      return ChatAlias.SAY;
    }
    return switch (token.toUpperCase(Locale.ROOT)) {
      case "YELL" -> ChatAlias.YELL;
      case "WHISPER" -> ChatAlias.WHISPER;
      default -> ChatAlias.SAY;
    };
  }

  @PreDestroy
  public void close() {
    if (channel != null) {
      channel.shutdown();
    }
  }
}
