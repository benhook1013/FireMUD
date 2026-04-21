package net.firedevops.firemud.gamesession.client;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
import net.firedevops.firemud.gamelogic.v1.CommunicationTargetKind;
import net.firedevops.firemud.gamelogic.v1.CommunicationType;
import net.firedevops.firemud.gamelogic.v1.GameLogicServiceGrpc;
import net.firedevops.firemud.gamelogic.v1.LookRequest;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamelogic.v1.MoveRequest;
import net.firedevops.firemud.gamelogic.v1.MoveResult;
import net.firedevops.firemud.gamelogic.v1.PingRequest;
import net.firedevops.firemud.gamelogic.v1.PingResponse;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationRequest;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationResponse;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import org.springframework.stereotype.Component;

@Component
public class GameLogicClient
    extends AbstractBlockingGrpcClient<GameLogicServiceGrpc.GameLogicServiceBlockingStub> {
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final long READINESS_DEADLINE_SECONDS = 2L;

  private final GameplaySessionAttestationService gameplaySessionAttestationService;

  public GameLogicClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties grpcClientProperties,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer,
      GameplaySessionAttestationService gameplaySessionAttestationService) {
    super(endpoints, grpcClientProperties, channelFactory, stubCustomizer);
    this.gameplaySessionAttestationService = gameplaySessionAttestationService;
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
    return applyStubCustomizer(
        GameLogicServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  public LookResult resolveLook(SessionContext context, String roomId, String localeTag) {
    String tenantId = Long.toString(context.tenantId());
    String sessionId = Long.toString(context.sessionId());
    String characterId = Long.toString(context.characterId());
    String gameInstanceId = Long.toString(context.gameInstanceId());
    LookRequest request =
        LookRequest.newBuilder()
            .setTenantId(tenantId)
            .setSessionId(sessionId)
            .setCharacterId(characterId)
            .setPreferredLocale(localeTag == null ? "" : localeTag)
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(tenantId)
                    .setGameInstanceId(gameInstanceId)
                    .setRoomInstanceId(roomId)
                    .build())
            .build();
    request =
        request.toBuilder()
            .setSessionAttestation(
                gameplaySessionAttestationService.issueGameplaySessionAttestation(
                    tenantId,
                    sessionId,
                    Long.toString(context.accountId()),
                    characterId,
                    gameInstanceId,
                    roomId))
            .build();
    return callStub().resolveLook(request);
  }

  public LookResult resolveLookForReadiness(
      String tenantId, String sessionId, String characterId, String gameInstanceId, String roomId) {
    LookRequest request =
        LookRequest.newBuilder()
            .setTenantId(tenantId)
            .setSessionId(sessionId)
            .setCharacterId(characterId)
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(tenantId)
                    .setGameInstanceId(gameInstanceId == null ? "" : gameInstanceId)
                    .setRoomInstanceId(roomId)
                    .build())
            .build();
    return stub()
        .withDeadlineAfter(READINESS_DEADLINE_SECONDS, TimeUnit.SECONDS)
        .resolveLook(request);
  }

  public SendCommunicationResponse sendCommunication(
      SessionContext context,
      String speakerName,
      String roomId,
      CommunicationType type,
      String text,
      String targetCharacterId,
      String targetCharacterName) {
    String tenantId = Long.toString(context.tenantId());
    String sessionId = Long.toString(context.sessionId());
    String characterId = Long.toString(context.characterId());
    String accountId = Long.toString(context.accountId());
    String gameInstanceId = Long.toString(context.gameInstanceId());
    SendCommunicationRequest request =
        SendCommunicationRequest.newBuilder()
            .setTenantId(tenantId)
            .setSessionId(sessionId)
            .setCharacterId(characterId)
            .setAccountId(accountId)
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(tenantId)
                    .setGameInstanceId(gameInstanceId)
                    .setRoomInstanceId(roomId)
                    .build())
            .setType(type)
            .setText(text)
            .setTargetKind(targetKindFor(type))
            .setTargetCharacterId(targetCharacterId == null ? "" : targetCharacterId)
            .setTargetCharacterName(targetCharacterName == null ? "" : targetCharacterName)
            .setGameInstanceId(gameInstanceId)
            .setSpeakerName(speakerName == null ? "" : speakerName)
            .setSessionAttestation(
                gameplaySessionAttestationService.issueGameplaySessionAttestation(
                    tenantId, sessionId, accountId, characterId, gameInstanceId, roomId))
            .build();
    return callStub().sendCommunication(request);
  }

  public MoveResult resolveMove(
      SessionContext context, String roomId, String direction, String localeTag) {
    String tenantId = Long.toString(context.tenantId());
    String sessionId = Long.toString(context.sessionId());
    String characterId = Long.toString(context.characterId());
    String gameInstanceId = Long.toString(context.gameInstanceId());
    MoveRequest request =
        MoveRequest.newBuilder()
            .setTenantId(tenantId)
            .setSessionId(sessionId)
            .setCharacterId(characterId)
            .setPreferredLocale(localeTag == null ? "" : localeTag)
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(tenantId)
                    .setGameInstanceId(gameInstanceId)
                    .setRoomInstanceId(roomId)
                    .build())
            .setDirection(direction)
            .setSessionAttestation(
                gameplaySessionAttestationService.issueGameplaySessionAttestation(
                    tenantId,
                    sessionId,
                    Long.toString(context.accountId()),
                    characterId,
                    gameInstanceId,
                    roomId))
            .build();
    return callStub().resolveMove(request);
  }

  public PingResponse ping() {
    return callStub().ping(PingRequest.getDefaultInstance());
  }

  private GameLogicServiceGrpc.GameLogicServiceBlockingStub callStub() {
    return stub().withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
  }

  private CommunicationTargetKind targetKindFor(CommunicationType type) {
    return switch (type) {
      case SAY -> CommunicationTargetKind.COMMUNICATION_TARGET_KIND_ROOM;
      case WHISPER -> CommunicationTargetKind.COMMUNICATION_TARGET_KIND_DIRECT_CHARACTER_IN_ROOM;
      case TELL -> CommunicationTargetKind.COMMUNICATION_TARGET_KIND_DIRECT_CHARACTER;
      default -> CommunicationTargetKind.COMMUNICATION_TARGET_KIND_UNSPECIFIED;
    };
  }
}
