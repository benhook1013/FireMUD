package net.firedevops.firemud.gamesession.client;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
import net.firedevops.firemud.entitymanagement.v1.Character;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.FindCharacterByNameRequest;
import net.firedevops.firemud.entitymanagement.v1.FindCharacterByNameResponse;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountRequest;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.entitymanagement.v1.PingRequest;
import net.firedevops.firemud.entitymanagement.v1.PingResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.ValidateEntityUpgradeMappingsRequest;
import net.firedevops.firemud.entitymanagement.v1.ValidateEntityUpgradeMappingsResponse;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
import net.firedevops.firemud.gamesession.service.GameplayRuntimeRoomIds;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** gRPC client for the Entity Management Service. */
@Component
public final class EntityManagementClient
    extends AbstractBlockingGrpcClient<
        EntityManagementServiceGrpc.EntityManagementServiceBlockingStub> {
  private static final Logger logger = LoggerFactory.getLogger(EntityManagementClient.class);
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final long FIND_CHARACTER_DEADLINE_MILLIS = 500L;
  private final GameplaySessionAttestationService gameplaySessionAttestationService;

  public EntityManagementClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer,
      GameplaySessionAttestationService gameplaySessionAttestationService) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer);
    this.gameplaySessionAttestationService = gameplaySessionAttestationService;
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
    return applyStubCustomizer(
        EntityManagementServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  /** Simple ping to verify connectivity. */
  public PingResponse ping() {
    return callStub().ping(PingRequest.newBuilder().build());
  }

  public Optional<Character> findCharacterByName(
      SessionContext context, PlayableStateScope playableStateScope, String name) {
    String tenantId = Long.toString(context.tenantId());
    String gameInstanceId = Long.toString(context.gameInstanceId());
    FindCharacterByNameRequest request =
        FindCharacterByNameRequest.newBuilder()
            .setTenantId(tenantId)
            .setGameInstanceId(gameInstanceId)
            .setPlayableStateScope(playableStateScope)
            .setName(name)
            .setSessionAttestation(
                sessionAttestation(context, gameInstanceId, context.roomInstanceId()))
            .build();
    try {
      FindCharacterByNameResponse response =
          stub()
              .withDeadlineAfter(FIND_CHARACTER_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
              .findCharacterByName(request);
      if (response.hasError() || !response.hasCharacter()) {
        return Optional.empty();
      }
      return Optional.of(response.getCharacter());
    } catch (StatusRuntimeException ex) {
      logger.debug(
          "Entity Management character lookup failed tenantId={} name={}", tenantId, name, ex);
      return Optional.empty();
    } catch (Exception ex) {
      logger.debug(
          "Entity Management character lookup failed unexpectedly tenantId={} name={}",
          tenantId,
          name,
          ex);
      return Optional.empty();
    }
  }

  public ValidateEntityUpgradeMappingsResponse validateEntityUpgradeMappings(
      long tenantId, long sourceGameInstanceId, long targetVersionId, String remapSetId) {
    ValidateEntityUpgradeMappingsRequest.Builder request =
        ValidateEntityUpgradeMappingsRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setSourceGameInstanceId(Long.toString(sourceGameInstanceId))
            .setTargetVersionId(Long.toString(targetVersionId));
    if (StringUtils.hasText(remapSetId)) {
      request.setRemapSetId(remapSetId);
    }
    return callStub().validateEntityUpgradeMappings(request.build());
  }

  public ListCharactersByAccountResponse listCharactersByAccount(
      String tenantId,
      String accountId,
      String gameInstanceId,
      PlayableStateScope playableStateScope) {
    ListCharactersByAccountRequest request =
        ListCharactersByAccountRequest.newBuilder()
            .setTenantId(tenantId)
            .setAccountId(accountId)
            .setGameInstanceId(gameInstanceId)
            .setPlayableStateScope(playableStateScope)
            .build();
    try {
      return callStub().listCharactersByAccount(request);
    } catch (StatusRuntimeException ex) {
      logger.warn(
          "Failed to call Entity Management list-characters endpoint tenantId={} accountId={}",
          tenantId,
          accountId,
          ex);
    } catch (Exception ex) {
      logger.warn(
          "Failed to call Entity Management list-characters endpoint tenantId={} accountId={}",
          tenantId,
          accountId,
          ex);
    }
    return ListCharactersByAccountResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("CHARACTER_LIST_UNAVAILABLE")
                .setMessage("Character list unavailable"))
        .build();
  }

  public ListRoomEntitiesResponse listRoomEntities(SessionContext context, String roomInstanceId) {
    String canonicalRoomId =
        GameplayRuntimeRoomIds.requireCanonical(roomInstanceId, "roomInstanceId");
    String tenantId = Long.toString(context.tenantId());
    String gameInstanceId = Long.toString(context.gameInstanceId());
    ListRoomEntitiesRequest request =
        ListRoomEntitiesRequest.newBuilder()
            .setTenantId(tenantId)
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(tenantId)
                    .setGameInstanceId(gameInstanceId)
                    .setRoomInstanceId(canonicalRoomId)
                    .build())
            .setSessionAttestation(sessionAttestation(context, gameInstanceId, canonicalRoomId))
            .build();
    try {
      return callStub().listRoomEntities(request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Entity Management Service unavailable; rebuilding channel and retrying room entity query",
            ex);
        try {
          initClient();
          return callStub().listRoomEntities(request);
        } catch (Exception retryEx) {
          logger.warn(
              "Failed to retry Entity Management room entity query after channel reload", retryEx);
        }
      } else {
        logger.warn("Failed to call Entity Management room entity query endpoint", ex);
      }
    } catch (Exception ex) {
      logger.warn("Failed to call Entity Management room entity query endpoint", ex);
    }
    return ListRoomEntitiesResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("ROOM_ENTITIES_UNAVAILABLE")
                .setMessage("Room entity service unavailable"))
        .build();
  }

  private EntityManagementServiceGrpc.EntityManagementServiceBlockingStub callStub() {
    return stub().withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
  }

  private String sessionAttestation(
      SessionContext context, String gameInstanceId, String roomInstanceId) {
    GameplayAdmissionPointerSnapshots.AdmittedRoutingBundle routingBundle =
        GameplayAdmissionPointerSnapshots.requireAdmittedRoutingBundle(
            context, "Entity Management request");
    return gameplaySessionAttestationService.issueGameplaySessionAttestation(
        Long.toString(context.tenantId()),
        Long.toString(context.sessionId()),
        Long.toString(context.accountId()),
        Long.toString(context.characterId()),
        gameInstanceId,
        roomInstanceId,
        routingBundle.worldSlug(),
        routingBundle.realmSlug(),
        routingBundle.pointerVersion(),
        context.playableStateScope());
  }
}
