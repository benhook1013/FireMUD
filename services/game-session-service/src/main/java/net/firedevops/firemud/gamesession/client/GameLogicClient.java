package net.firedevops.firemud.gamesession.client;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamelogic.v1.BroadcastSayRequest;
import net.firedevops.firemud.gamelogic.v1.BroadcastSayResponse;
import net.firedevops.firemud.gamelogic.v1.ChatAlias;
import net.firedevops.firemud.gamelogic.v1.GameLogicServiceGrpc;
import net.firedevops.firemud.gamelogic.v1.LookRequest;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamelogic.v1.MoveRequest;
import net.firedevops.firemud.gamelogic.v1.MoveResult;
import net.firedevops.firemud.gamelogic.v1.PingRequest;
import net.firedevops.firemud.gamelogic.v1.PingResponse;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import org.springframework.stereotype.Component;

@Component
public class GameLogicClient
    extends AbstractBlockingGrpcClient<GameLogicServiceGrpc.GameLogicServiceBlockingStub> {
  private static final long READINESS_DEADLINE_SECONDS = 2L;

  public GameLogicClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties grpcClientProperties,
      GrpcChannelFactory channelFactory) {
    super(endpoints, grpcClientProperties, channelFactory);
  }

  @PostConstruct
  void init() throws Exception {
    initClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameLogicService();
  }

  @Override
  protected String defaultTarget() {
    return "game-logic-service:6565";
  }

  @Override
  protected GameLogicServiceGrpc.GameLogicServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return GameLogicServiceGrpc.newBlockingStub(channel).withCompression("gzip");
  }

  public LookResult resolveLook(
      String tenantId, String sessionId, String characterId, String roomId) {
    LookRequest request =
        LookRequest.newBuilder()
            .setTenantId(tenantId)
            .setSessionId(sessionId)
            .setCharacterId(characterId)
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(tenantId)
                    .setRoomInstanceId(roomId)
                    .build())
            .build();
    return stub().resolveLook(request);
  }

  public LookResult resolveLookForReadiness(
      String tenantId, String sessionId, String characterId, String roomId) {
    LookRequest request =
        LookRequest.newBuilder()
            .setTenantId(tenantId)
            .setSessionId(sessionId)
            .setCharacterId(characterId)
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(tenantId)
                    .setRoomInstanceId(roomId)
                    .build())
            .build();
    return stub()
        .withDeadlineAfter(READINESS_DEADLINE_SECONDS, TimeUnit.SECONDS)
        .resolveLook(request);
  }

  public BroadcastSayResponse broadcastSay(
      String tenantId,
      String sessionId,
      String characterId,
      String roomId,
      String aliasToken,
      String text) {
    BroadcastSayRequest request =
        BroadcastSayRequest.newBuilder()
            .setTenantId(tenantId)
            .setSessionId(sessionId)
            .setCharacterId(characterId)
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(tenantId)
                    .setRoomInstanceId(roomId)
                    .build())
            .setAlias(mapAlias(aliasToken))
            .setText(text)
            .build();
    return stub().broadcastSay(request);
  }

  public MoveResult resolveMove(
      String tenantId, String sessionId, String characterId, String roomId, String direction) {
    MoveRequest request =
        MoveRequest.newBuilder()
            .setTenantId(tenantId)
            .setSessionId(sessionId)
            .setCharacterId(characterId)
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(tenantId)
                    .setRoomInstanceId(roomId)
                    .build())
            .setDirection(direction)
            .build();
    return stub().resolveMove(request);
  }

  public PingResponse ping() {
    return stub().ping(PingRequest.getDefaultInstance());
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
}
