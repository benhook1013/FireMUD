package net.firedevops.firemud.entitymanagement.service.impl;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.stream.Collectors;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.GameplaySessionAttestationClaims;
import net.firedevops.firemud.common.security.GameplaySessionAttestationException;
import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.dto.ActorConditionStateDto;
import net.firedevops.firemud.entitymanagement.dto.ActorResourceStateDto;
import net.firedevops.firemud.entitymanagement.dto.CharacterDto;
import net.firedevops.firemud.entitymanagement.dto.CharacterEquipmentEntryDto;
import net.firedevops.firemud.entitymanagement.dto.ContainerContentEntryDto;
import net.firedevops.firemud.entitymanagement.dto.RoomEntityDto;
import net.firedevops.firemud.entitymanagement.service.ActorConditionMutationService;
import net.firedevops.firemud.entitymanagement.service.ActorStateService;
import net.firedevops.firemud.entitymanagement.service.CharacterService;
import net.firedevops.firemud.entitymanagement.service.ContainerService;
import net.firedevops.firemud.entitymanagement.service.EntityDraftDesignDigestService;
import net.firedevops.firemud.entitymanagement.service.EntityTemplateReferenceService;
import net.firedevops.firemud.entitymanagement.service.EntityUpgradeValidationService;
import net.firedevops.firemud.entitymanagement.service.EquipmentService;
import net.firedevops.firemud.entitymanagement.service.EquipmentSlotIncompatibleException;
import net.firedevops.firemud.entitymanagement.service.InventoryService;
import net.firedevops.firemud.entitymanagement.service.PingService;
import net.firedevops.firemud.entitymanagement.service.RoomEntityService;
import net.firedevops.firemud.entitymanagement.service.RuntimeInstanceCleanupService;
import net.firedevops.firemud.entitymanagement.v1.ActorConditionState;
import net.firedevops.firemud.entitymanagement.v1.ActorResourceValue;
import net.firedevops.firemud.entitymanagement.v1.ApplyActorConditionRequest;
import net.firedevops.firemud.entitymanagement.v1.ApplyActorConditionResponse;
import net.firedevops.firemud.entitymanagement.v1.Character;
import net.firedevops.firemud.entitymanagement.v1.CleanupRuntimeInstanceRequest;
import net.firedevops.firemud.entitymanagement.v1.CleanupRuntimeInstanceResponse;
import net.firedevops.firemud.entitymanagement.v1.ContainerItem;
import net.firedevops.firemud.entitymanagement.v1.CreateCharacterRequest;
import net.firedevops.firemud.entitymanagement.v1.CreateCharacterResponse;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.EntityTemplateReferenceType;
import net.firedevops.firemud.entitymanagement.v1.EquipmentItem;
import net.firedevops.firemud.entitymanagement.v1.FindCharacterByNameRequest;
import net.firedevops.firemud.entitymanagement.v1.FindCharacterByNameResponse;
import net.firedevops.firemud.entitymanagement.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.entitymanagement.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountRequest;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsRequest;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsResponse;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentRequest;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.PingRequest;
import net.firedevops.firemud.entitymanagement.v1.PingResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerRequest;
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryActorStateRequest;
import net.firedevops.firemud.entitymanagement.v1.QueryActorStateResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.RemoveEquipmentRequest;
import net.firedevops.firemud.entitymanagement.v1.RemoveEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomEntity;
import net.firedevops.firemud.entitymanagement.v1.RoomGroundInventoryItem;
import net.firedevops.firemud.entitymanagement.v1.TakeItemFromContainerRequest;
import net.firedevops.firemud.entitymanagement.v1.TakeItemFromContainerResponse;
import net.firedevops.firemud.entitymanagement.v1.UpdateEntityRequest;
import net.firedevops.firemud.entitymanagement.v1.UpdateEntityResponse;
import net.firedevops.firemud.entitymanagement.v1.UpgradeValidationResult;
import net.firedevops.firemud.entitymanagement.v1.ValidateEntityTemplateReferenceRequest;
import net.firedevops.firemud.entitymanagement.v1.ValidateEntityTemplateReferenceResponse;
import net.firedevops.firemud.entitymanagement.v1.ValidateEntityUpgradeMappingsRequest;
import net.firedevops.firemud.entitymanagement.v1.ValidateEntityUpgradeMappingsResponse;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemRequest;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/** Simple gRPC service exposing the Ping RPC. */
@GrpcService
public class EntityManagementGrpcService
    extends EntityManagementServiceGrpc.EntityManagementServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(EntityManagementGrpcService.class);
  private final PingService pingService;
  private final CharacterService characterService;
  private final ActorStateService actorStateService;
  private final ActorConditionMutationService actorConditionMutationService;
  private final EntityDraftDesignDigestService entityDraftDesignDigestService;

  private final EquipmentService equipmentService;

  private final InventoryService inventoryService;

  private final ContainerService containerService;

  private final MeterRegistry meterRegistry;

  private final GameplaySessionAttestationService gameplaySessionAttestationService;

  private final RoomEntityService roomEntityService;
  private final RuntimeInstanceCleanupService runtimeInstanceCleanupService;
  private final EntityMutationEffectReplayService entityMutationEffectReplayService;
  private final EntityUpgradeValidationService entityUpgradeValidationService;
  private final EntityTemplateReferenceService entityTemplateReferenceService;

  EntityManagementGrpcService(
      PingService pingService,
      CharacterService characterService,
      ActorStateService actorStateService,
      ActorConditionMutationService actorConditionMutationService,
      EntityDraftDesignDigestService entityDraftDesignDigestService,
      EquipmentService equipmentService,
      InventoryService inventoryService,
      ContainerService containerService,
      RoomEntityService roomEntityService,
      RuntimeInstanceCleanupService runtimeInstanceCleanupService,
      EntityMutationEffectReplayService entityMutationEffectReplayService,
      EntityUpgradeValidationService entityUpgradeValidationService,
      EntityTemplateReferenceService entityTemplateReferenceService,
      GameplaySessionAttestationService gameplaySessionAttestationService,
      MeterRegistry meterRegistry) {
    this.pingService = pingService;
    this.characterService = characterService;
    this.actorStateService = actorStateService;
    this.actorConditionMutationService = actorConditionMutationService;
    this.entityDraftDesignDigestService = entityDraftDesignDigestService;
    this.equipmentService = equipmentService;
    this.inventoryService = inventoryService;
    this.containerService = containerService;
    this.roomEntityService = roomEntityService;
    this.runtimeInstanceCleanupService = runtimeInstanceCleanupService;
    this.entityMutationEffectReplayService = entityMutationEffectReplayService;
    this.entityUpgradeValidationService = entityUpgradeValidationService;
    this.entityTemplateReferenceService = entityTemplateReferenceService;
    this.gameplaySessionAttestationService = gameplaySessionAttestationService;
    this.meterRegistry = meterRegistry;
  }

  public EntityManagementGrpcService(
      PingService pingService,
      CharacterService characterService,
      EntityDraftDesignDigestService entityDraftDesignDigestService,
      EquipmentService equipmentService,
      InventoryService inventoryService,
      ContainerService containerService,
      RoomEntityService roomEntityService,
      EntityMutationEffectReplayService entityMutationEffectReplayService,
      EntityUpgradeValidationService entityUpgradeValidationService,
      GameplaySessionAttestationService gameplaySessionAttestationService,
      MeterRegistry meterRegistry) {
    this(
        pingService,
        characterService,
        unsupportedActorStateService(),
        unsupportedActorConditionMutationService(),
        entityDraftDesignDigestService,
        equipmentService,
        inventoryService,
        containerService,
        roomEntityService,
        entityMutationEffectReplayService,
        entityUpgradeValidationService,
        gameplaySessionAttestationService,
        meterRegistry);
  }

  @Autowired
  public EntityManagementGrpcService(
      PingService pingService,
      CharacterService characterService,
      ActorStateService actorStateService,
      EntityDraftDesignDigestService entityDraftDesignDigestService,
      EquipmentService equipmentService,
      InventoryService inventoryService,
      ContainerService containerService,
      RoomEntityService roomEntityService,
      EntityMutationEffectReplayService entityMutationEffectReplayService,
      EntityUpgradeValidationService entityUpgradeValidationService,
      GameplaySessionAttestationService gameplaySessionAttestationService,
      MeterRegistry meterRegistry) {
    this(
        pingService,
        characterService,
        actorStateService,
        unsupportedActorConditionMutationService(),
        entityDraftDesignDigestService,
        equipmentService,
        inventoryService,
        containerService,
        roomEntityService,
        entityMutationEffectReplayService,
        entityUpgradeValidationService,
        gameplaySessionAttestationService,
        meterRegistry);
  }

  public EntityManagementGrpcService(
      PingService pingService,
      CharacterService characterService,
      ActorStateService actorStateService,
      ActorConditionMutationService actorConditionMutationService,
      EntityDraftDesignDigestService entityDraftDesignDigestService,
      EquipmentService equipmentService,
      InventoryService inventoryService,
      ContainerService containerService,
      RoomEntityService roomEntityService,
      EntityMutationEffectReplayService entityMutationEffectReplayService,
      EntityUpgradeValidationService entityUpgradeValidationService,
      EntityTemplateReferenceService entityTemplateReferenceService,
      GameplaySessionAttestationService gameplaySessionAttestationService,
      MeterRegistry meterRegistry) {
    this(
        pingService,
        characterService,
        actorStateService,
        actorConditionMutationService,
        entityDraftDesignDigestService,
        equipmentService,
        inventoryService,
        containerService,
        roomEntityService,
        (tenantId, gameInstanceId, terminationRequestId) ->
            new net.firedevops.firemud.entitymanagement.dto.RuntimeInstanceCleanupResultDto(
                0L, 0L, 0L, 0L),
        entityMutationEffectReplayService,
        entityUpgradeValidationService,
        entityTemplateReferenceService,
        gameplaySessionAttestationService,
        meterRegistry);
  }

  public EntityManagementGrpcService(
      PingService pingService,
      CharacterService characterService,
      ActorStateService actorStateService,
      ActorConditionMutationService actorConditionMutationService,
      EntityDraftDesignDigestService entityDraftDesignDigestService,
      EquipmentService equipmentService,
      InventoryService inventoryService,
      ContainerService containerService,
      RoomEntityService roomEntityService,
      EntityMutationEffectReplayService entityMutationEffectReplayService,
      EntityUpgradeValidationService entityUpgradeValidationService,
      GameplaySessionAttestationService gameplaySessionAttestationService,
      MeterRegistry meterRegistry) {
    this(
        pingService,
        characterService,
        actorStateService,
        actorConditionMutationService,
        entityDraftDesignDigestService,
        equipmentService,
        inventoryService,
        containerService,
        roomEntityService,
        entityMutationEffectReplayService,
        entityUpgradeValidationService,
        (tenantId, versionId, templateType, templateId) -> false,
        gameplaySessionAttestationService,
        meterRegistry);
  }

  @Override
  @Timed(value = "entityGrpc.getDraftDesignDigest")
  public void getDraftDesignDigest(
      GetDraftDesignDigestRequest request,
      StreamObserver<GetDraftDesignDigestResponse> responseObserver) {
    try {
      if (request.getScopeCase() != GetDraftDesignDigestRequest.ScopeCase.VERSION_ID) {
        responseObserver.onNext(
            GetDraftDesignDigestResponse.newBuilder()
                .setError(
                    GrpcAppErrors.error(
                        meterRegistry,
                        logger,
                        "GetDraftDesignDigest",
                        "UNSUPPORTED_SCOPE",
                        "entity management supports version_id scope only"))
                .build());
        responseObserver.onCompleted();
        return;
      }
      var digest =
          entityDraftDesignDigestService.getDraftDesignDigest(
              request.getTenantId(), request.getVersionId());
      responseObserver.onNext(
          GetDraftDesignDigestResponse.newBuilder()
              .setTenantId(digest.tenantId())
              .setScopeValue(digest.scopeValue())
              .setAppliedCommitId(digest.appliedCommitId())
              .setContentDigest(digest.contentDigest())
              .setDigestSchemaVersion(digest.digestSchemaVersion())
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          GetDraftDesignDigestResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "GetDraftDesignDigest",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onNext(
          GetDraftDesignDigestResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "GetDraftDesignDigest", ex))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.validateEntityTemplateReference")
  public void validateEntityTemplateReference(
      ValidateEntityTemplateReferenceRequest request,
      StreamObserver<ValidateEntityTemplateReferenceResponse> responseObserver) {
    try {
      if (request.getTemplateType()
          == EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_UNSPECIFIED) {
        throw new IllegalArgumentException("template_type is required");
      }
      boolean exists =
          entityTemplateReferenceService.exists(
              request.getTenantId(),
              request.getVersionId(),
              templateTypeName(request.getTemplateType()),
              request.getTemplateId());
      responseObserver.onNext(
          ValidateEntityTemplateReferenceResponse.newBuilder().setExists(exists).build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          ValidateEntityTemplateReferenceResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ValidateEntityTemplateReference",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onNext(
          ValidateEntityTemplateReferenceResponse.newBuilder()
              .setError(
                  GrpcAppErrors.internal(
                      meterRegistry, logger, "ValidateEntityTemplateReference", ex))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.ping")
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    try {
      String msg = pingService.ping();
      PingResponse response = PingResponse.newBuilder().setMessage(msg).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      PingResponse response =
          PingResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "Ping", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      PingResponse response =
          PingResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "Ping", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.validateEntityUpgradeMappings")
  public void validateEntityUpgradeMappings(
      ValidateEntityUpgradeMappingsRequest request,
      StreamObserver<ValidateEntityUpgradeMappingsResponse> responseObserver) {
    try {
      var validation =
          entityUpgradeValidationService.validateEntityUpgradeMappings(
              Long.parseLong(request.getTenantId()),
              Long.parseLong(request.getSourceGameInstanceId()),
              Long.parseLong(request.getTargetVersionId()),
              request.getRemapSetId().isBlank() ? null : request.getRemapSetId());
      ValidateEntityUpgradeMappingsResponse.Builder builder =
          ValidateEntityUpgradeMappingsResponse.newBuilder()
              .addAllStateClassesChecked(validation.stateClassesChecked())
              .addAllCheckedFamilies(validation.checkedFamilies())
              .setHasS2Rows(validation.hasS2Rows())
              .setResult(toUpgradeValidationResult(validation.result()))
              .setRemapSetRequired(validation.remapSetRequired())
              .addAllReasons(validation.reasons());
      if (validation.remapSetId() != null) {
        builder.setRemapSetId(validation.remapSetId());
      }
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
      responseObserver.onNext(
          ValidateEntityUpgradeMappingsResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ValidateEntityUpgradeMappings",
                      "INVALID_ARGUMENT",
                      "invalid id"))
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          ValidateEntityUpgradeMappingsResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ValidateEntityUpgradeMappings",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onNext(
          ValidateEntityUpgradeMappingsResponse.newBuilder()
              .setError(
                  GrpcAppErrors.internal(
                      meterRegistry, logger, "ValidateEntityUpgradeMappings", ex))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.listCharactersByAccount")
  public void listCharactersByAccount(
      ListCharactersByAccountRequest request,
      StreamObserver<ListCharactersByAccountResponse> responseObserver) {
    try {
      long tenantId = Long.parseLong(request.getTenantId());
      requireTenantAccessWhenPresent(tenantId);
      long accountId = Long.parseLong(request.getAccountId());
      var characters =
          characterService
              .listForGameplayScope(
                  tenantId,
                  accountId,
                  request.getGameInstanceId(),
                  requirePlayableStateScope(request.getPlayableStateScope()),
                  Pageable.unpaged())
              .stream()
              .map(this::toProto)
              .collect(Collectors.toList());
      ListCharactersByAccountResponse response =
          ListCharactersByAccountResponse.newBuilder().addAllCharacters(characters).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
      ListCharactersByAccountResponse response =
          ListCharactersByAccountResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ListCharactersByAccount",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ListCharactersByAccountResponse response =
          ListCharactersByAccountResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ListCharactersByAccount",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      ListCharactersByAccountResponse response =
          ListCharactersByAccountResponse.newBuilder()
              .setError(appError("ListCharactersByAccount", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      ListCharactersByAccountResponse response =
          ListCharactersByAccountResponse.newBuilder()
              .setError(
                  GrpcAppErrors.internal(meterRegistry, logger, "ListCharactersByAccount", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.findCharacterByName")
  public void findCharacterByName(
      FindCharacterByNameRequest request,
      StreamObserver<FindCharacterByNameResponse> responseObserver) {
    try {
      requireGameplayOrProbeAttestation(
          request.getSessionAttestation(),
          request.getTenantId(),
          request.getGameInstanceId(),
          null);
      long tenantId = Long.parseLong(request.getTenantId());
      requireTenantAccessWhenPresent(tenantId);
      FindCharacterByNameResponse.Builder builder = FindCharacterByNameResponse.newBuilder();
      characterService
          .findByGameplayScopeAndName(
              tenantId,
              request.getGameInstanceId(),
              requirePlayableStateScope(request.getPlayableStateScope()),
              request.getName())
          .map(this::toProto)
          .ifPresent(builder::setCharacter);
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
      FindCharacterByNameResponse response =
          FindCharacterByNameResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "FindCharacterByName",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (GameplaySessionAttestationException ex) {
      FindCharacterByNameResponse response =
          FindCharacterByNameResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "FindCharacterByName", ex.getCode(), ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      FindCharacterByNameResponse response =
          FindCharacterByNameResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "FindCharacterByName",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      FindCharacterByNameResponse response =
          FindCharacterByNameResponse.newBuilder()
              .setError(appError("FindCharacterByName", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      FindCharacterByNameResponse response =
          FindCharacterByNameResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "FindCharacterByName", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.createCharacter")
  public void createCharacter(
      CreateCharacterRequest request, StreamObserver<CreateCharacterResponse> responseObserver) {
    try {
      long tenantId = Long.parseLong(request.getTenantId());
      requireTenantAccessWhenPresent(tenantId);
      long accountId = Long.parseLong(request.getAccountId());
      CharacterDto created =
          characterService.create(
              tenantId,
              accountId,
              request.getName(),
              request.getGameInstanceId(),
              requirePlayableStateScope(request.getPlayableStateScope()));
      CreateCharacterResponse response =
          CreateCharacterResponse.newBuilder().setCharacterId(String.valueOf(created.id())).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
      CreateCharacterResponse response =
          CreateCharacterResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "CreateCharacter",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      CreateCharacterResponse response =
          CreateCharacterResponse.newBuilder().setError(appError("CreateCharacter", ex)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      CreateCharacterResponse response =
          CreateCharacterResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "CreateCharacter", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.updateEntity")
  public void updateEntity(
      UpdateEntityRequest request, StreamObserver<UpdateEntityResponse> responseObserver) {
    try {
      long tenantId = Long.parseLong(request.getTenantId());
      requireTenantAccessWhenPresent(tenantId);
      long entityId = Long.parseLong(request.getEntityId());
      boolean result =
          characterService.updateEntity(
              tenantId,
              entityId,
              request.getGameInstanceId(),
              requirePlayableStateScope(request.getPlayableStateScope()));
      UpdateEntityResponse response = UpdateEntityResponse.newBuilder().setSuccess(result).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
      UpdateEntityResponse response =
          UpdateEntityResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "UpdateEntity", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      UpdateEntityResponse response =
          UpdateEntityResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "UpdateEntity", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      UpdateEntityResponse response =
          UpdateEntityResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "UpdateEntity", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.queryInventory")
  public void queryInventory(
      QueryInventoryRequest request, StreamObserver<QueryInventoryResponse> responseObserver) {
    try {
      GameplaySessionAttestationClaims claims =
          requireGameplaySessionAttestation(
              request.getSessionAttestation(),
              request.getTenantId(),
              request.getCharacterId(),
              request.getGameInstanceId(),
              null,
              request.getPlayableStateScope());
      GameplayActorScope actorScope =
          requireGameplayActorScope(request.getTenantId(), request.getCharacterId());
      var entries =
          inventoryService
              .listInventory(
                  actorScope.tenantId(),
                  actorScope.characterId(),
                  resolveGameplayTargetGameInstanceId(request.getGameInstanceId(), claims),
                  resolvePlayableStateScope(request.getPlayableStateScope(), claims),
                  Pageable.unpaged())
              .getContent();
      var items = entries.stream().map(this::toProto).toList();
      QueryInventoryResponse response =
          QueryInventoryResponse.newBuilder().addAllItems(items).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (GameplaySessionAttestationException ex) {
      QueryInventoryResponse response =
          QueryInventoryResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "QueryInventory", ex.getCode(), ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      QueryInventoryResponse response =
          QueryInventoryResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "QueryInventory", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      QueryInventoryResponse response =
          QueryInventoryResponse.newBuilder().setError(appError("QueryInventory", ex)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      QueryInventoryResponse response =
          QueryInventoryResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "QueryInventory", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.queryActorState")
  public void queryActorState(
      QueryActorStateRequest request, StreamObserver<QueryActorStateResponse> responseObserver) {
    try {
      GameplaySessionAttestationClaims claims =
          requireGameplaySessionAttestation(
              request.getSessionAttestation(),
              request.getTenantId(),
              request.getCharacterId(),
              request.getGameInstanceId(),
              null,
              request.getPlayableStateScope());
      GameplayActorScope actorScope =
          requireGameplayActorScope(request.getTenantId(), request.getCharacterId());
      var actorState =
          actorStateService.queryActorState(
              actorScope.tenantId(),
              actorScope.characterId(),
              resolveGameplayTargetGameInstanceId(request.getGameInstanceId(), claims),
              resolvePlayableStateScope(request.getPlayableStateScope(), claims));
      QueryActorStateResponse response =
          QueryActorStateResponse.newBuilder()
              .setTenantId(String.valueOf(actorState.tenantId()))
              .setGameInstanceId(String.valueOf(actorState.gameInstanceId()))
              .setCharacterId(String.valueOf(actorState.characterId()))
              .addAllResources(actorState.resources().stream().map(this::toProto).toList())
              .addAllActiveConditions(
                  actorState.activeConditions().stream().map(this::toProto).toList())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (GameplaySessionAttestationException ex) {
      QueryActorStateResponse response =
          QueryActorStateResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "QueryActorState", ex.getCode(), ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      QueryActorStateResponse response =
          QueryActorStateResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "QueryActorState",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      QueryActorStateResponse response =
          QueryActorStateResponse.newBuilder().setError(appError("QueryActorState", ex)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      QueryActorStateResponse response =
          QueryActorStateResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "QueryActorState", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.applyActorCondition")
  public void applyActorCondition(
      ApplyActorConditionRequest request,
      StreamObserver<ApplyActorConditionResponse> responseObserver) {
    try {
      GameplaySessionAttestationClaims claims =
          requireGameplaySessionAttestation(
              request.getSessionAttestation(),
              request.getTenantId(),
              request.getCharacterId(),
              request.getGameInstanceId(),
              null,
              request.getPlayableStateScope());
      GameplayActorScope actorScope =
          requireGameplayActorScope(request.getTenantId(), request.getCharacterId());
      String conditionKey = requireText(request.getConditionKey(), "conditionKey");
      String sourceType = requireText(request.getSourceType(), "sourceType");
      Instant expiresAt = parseOptionalInstant(request.getExpiresAt());
      ApplyActorConditionResponse response =
          entityMutationEffectReplayService.execute(
              actorScope.tenantId(),
              request.getSourceId(),
              "ApplyActorCondition",
              () -> {
                ActorConditionStateDto activeCondition =
                    actorConditionMutationService.applyCondition(
                        actorScope.tenantId(),
                        actorScope.characterId(),
                        resolveGameplayTargetGameInstanceId(request.getGameInstanceId(), claims),
                        resolvePlayableStateScope(request.getPlayableStateScope(), claims),
                        conditionKey,
                        request.getStackCount(),
                        sourceType,
                        request.getSourceId(),
                        expiresAt,
                        request.getEffectPayloadJson());
                return ApplyActorConditionResponse.newBuilder()
                    .setActiveCondition(toProto(activeCondition))
                    .build();
              },
              ApplyActorConditionResponse::parseFrom);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (GameplaySessionAttestationException ex) {
      responseObserver.onNext(
          ApplyActorConditionResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "ApplyActorCondition", ex.getCode(), ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          ApplyActorConditionResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ApplyActorCondition",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      responseObserver.onNext(
          ApplyActorConditionResponse.newBuilder()
              .setError(appError("ApplyActorCondition", ex))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onNext(
          ApplyActorConditionResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "ApplyActorCondition", ex))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.listEquipment")
  public void listEquipment(
      ListEquipmentRequest request, StreamObserver<ListEquipmentResponse> responseObserver) {
    try {
      requireGameplaySessionAttestation(
          request.getSessionAttestation(),
          request.getTenantId(),
          request.getCharacterId(),
          request.getGameInstanceId(),
          null,
          request.getPlayableStateScope());
      GameplayActorScope actorScope =
          requireGameplayActorScope(request.getTenantId(), request.getCharacterId());
      var items =
          equipmentService.listEquipment(
              actorScope.tenantId(),
              actorScope.characterId(),
              request.getGameInstanceId(),
              resolvePlayableStateScope(request.getPlayableStateScope(), null),
              Pageable.unpaged());
      ListEquipmentResponse response =
          ListEquipmentResponse.newBuilder()
              .addAllItems(items.stream().map(this::toProto).toList())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (GameplaySessionAttestationException ex) {
      ListEquipmentResponse response =
          ListEquipmentResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "ListEquipment", ex.getCode(), ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ListEquipmentResponse response =
          ListEquipmentResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "ListEquipment", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      ListEquipmentResponse response =
          ListEquipmentResponse.newBuilder().setError(appError("ListEquipment", ex)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      ListEquipmentResponse response =
          ListEquipmentResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "ListEquipment", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.wearEquipment")
  public void wearEquipment(
      WearEquipmentItemRequest request,
      StreamObserver<WearEquipmentItemResponse> responseObserver) {
    try {
      GameplaySessionAttestationClaims claims =
          requireGameplaySessionAttestation(
              request.getSessionAttestation(),
              request.getTenantId(),
              request.getCharacterId(),
              request.getGameInstanceId(),
              null,
              request.getPlayableStateScope());
      GameplayActorScope actorScope =
          requireGameplayActorScope(request.getTenantId(), request.getCharacterId());
      long itemId = RequestIdValidation.requirePositiveLong(request.getItemId(), "itemId");
      Long itemInstanceId =
          RequestIdValidation.parseOptionalPositiveLong(
              request.getItemInstanceId(), "itemInstanceId");
      WearEquipmentItemResponse response =
          entityMutationEffectReplayService.execute(
              actorScope.tenantId(),
              request.getEffectId(),
              "WearEquipment",
              () -> {
                CharacterEquipmentEntryDto dto =
                    equipmentService.wearItem(
                        actorScope.tenantId(),
                        actorScope.characterId(),
                        resolveGameplayTargetGameInstanceId(request.getGameInstanceId(), claims),
                        resolvePlayableStateScope(request.getPlayableStateScope(), claims),
                        itemId,
                        itemInstanceId,
                        blankToNull(request.getEffectId()),
                        blankToNull(claims.sessionId()));
                return WearEquipmentItemResponse.newBuilder()
                    .setEquipmentItem(toProto(dto))
                    .build();
              },
              WearEquipmentItemResponse::parseFrom);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (GameplaySessionAttestationException ex) {
      WearEquipmentItemResponse response =
          WearEquipmentItemResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "WearEquipment", ex.getCode(), ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (EquipmentSlotIncompatibleException ex) {
      WearEquipmentItemResponse response =
          WearEquipmentItemResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "WearEquipment",
                      EquipmentSlotIncompatibleException.CODE,
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      WearEquipmentItemResponse response =
          WearEquipmentItemResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "WearEquipment", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      WearEquipmentItemResponse response =
          WearEquipmentItemResponse.newBuilder().setError(appError("WearEquipment", ex)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      WearEquipmentItemResponse response =
          WearEquipmentItemResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "WearEquipment", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.removeEquipment")
  public void removeEquipment(
      RemoveEquipmentRequest request, StreamObserver<RemoveEquipmentResponse> responseObserver) {
    try {
      GameplaySessionAttestationClaims claims =
          requireGameplaySessionAttestation(
              request.getSessionAttestation(),
              request.getTenantId(),
              request.getCharacterId(),
              request.getGameInstanceId(),
              null,
              request.getPlayableStateScope());
      GameplayActorScope actorScope =
          requireGameplayActorScope(request.getTenantId(), request.getCharacterId());
      String slot = requireText(request.getSlot(), "slot");
      RemoveEquipmentResponse response =
          entityMutationEffectReplayService.execute(
              actorScope.tenantId(),
              request.getEffectId(),
              "RemoveEquipment",
              () -> {
                CharacterEquipmentEntryDto dto =
                    equipmentService.removeWornItem(
                        actorScope.tenantId(),
                        actorScope.characterId(),
                        resolveGameplayTargetGameInstanceId(request.getGameInstanceId(), claims),
                        resolvePlayableStateScope(request.getPlayableStateScope(), claims),
                        slot,
                        blankToNull(request.getEffectId()),
                        blankToNull(claims.sessionId()));
                return RemoveEquipmentResponse.newBuilder().setEquipmentItem(toProto(dto)).build();
              },
              RemoveEquipmentResponse::parseFrom);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (GameplaySessionAttestationException ex) {
      RemoveEquipmentResponse response =
          RemoveEquipmentResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "RemoveEquipment", ex.getCode(), ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      RemoveEquipmentResponse response =
          RemoveEquipmentResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "RemoveEquipment",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      RemoveEquipmentResponse response =
          RemoveEquipmentResponse.newBuilder().setError(appError("RemoveEquipment", ex)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      RemoveEquipmentResponse response =
          RemoveEquipmentResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "RemoveEquipment", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.listContainerContents")
  public void listContainerContents(
      ListContainerContentsRequest request,
      StreamObserver<ListContainerContentsResponse> responseObserver) {
    try {
      GameplaySessionAttestationClaims claims =
          requireGameplaySessionAttestation(
              request.getSessionAttestation(),
              request.getTenantId(),
              request.getCharacterId(),
              request.getGameInstanceId(),
              null,
              request.getPlayableStateScope());
      GameplayActorScope actorScope =
          requireGameplayActorScope(request.getTenantId(), request.getCharacterId());
      long containerInstanceId =
          RequestIdValidation.requirePositiveLong(
              request.getContainerInstanceId(), "containerInstanceId");
      var items =
          containerService.listContainerContents(
              actorScope.tenantId(),
              actorScope.characterId(),
              containerInstanceId,
              resolveGameplayTargetGameInstanceId(request.getGameInstanceId(), claims),
              resolvePlayableStateScope(request.getPlayableStateScope(), claims),
              blankToNull(claims.roomInstanceId()),
              Pageable.unpaged());
      ListContainerContentsResponse response =
          ListContainerContentsResponse.newBuilder()
              .addAllItems(items.stream().map(this::toProto).toList())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (GameplaySessionAttestationException ex) {
      ListContainerContentsResponse response =
          ListContainerContentsResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ListContainerContents",
                      ex.getCode(),
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ListContainerContentsResponse response =
          ListContainerContentsResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ListContainerContents",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      ListContainerContentsResponse response =
          ListContainerContentsResponse.newBuilder()
              .setError(appError("ListContainerContents", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      ListContainerContentsResponse response =
          ListContainerContentsResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "ListContainerContents", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.putItemIntoContainer")
  public void putItemIntoContainer(
      PutItemIntoContainerRequest request,
      StreamObserver<PutItemIntoContainerResponse> responseObserver) {
    try {
      GameplaySessionAttestationClaims claims =
          requireGameplaySessionAttestation(
              request.getSessionAttestation(),
              request.getTenantId(),
              request.getCharacterId(),
              request.getGameInstanceId(),
              null,
              request.getPlayableStateScope());
      GameplayActorScope actorScope =
          requireGameplayActorScope(request.getTenantId(), request.getCharacterId());
      long containerInstanceId =
          RequestIdValidation.requirePositiveLong(
              request.getContainerInstanceId(), "containerInstanceId");
      long itemId = RequestIdValidation.requirePositiveLong(request.getItemId(), "itemId");
      Long itemInstanceId =
          RequestIdValidation.parseOptionalPositiveLong(
              request.getItemInstanceId(), "itemInstanceId");
      int quantity = requirePositiveQuantity(request.getQuantity());
      PutItemIntoContainerResponse response =
          entityMutationEffectReplayService.execute(
              actorScope.tenantId(),
              request.getEffectId(),
              "PutItemIntoContainer",
              () -> {
                var dto =
                    containerService.putItemIntoContainer(
                        actorScope.tenantId(),
                        actorScope.characterId(),
                        containerInstanceId,
                        resolveGameplayTargetGameInstanceId(request.getGameInstanceId(), claims),
                        resolvePlayableStateScope(request.getPlayableStateScope(), claims),
                        blankToNull(claims.roomInstanceId()),
                        itemId,
                        itemInstanceId,
                        blankToNull(request.getStackFamilyKey()),
                        quantity,
                        blankToNull(request.getEffectId()),
                        blankToNull(claims.sessionId()));
                return PutItemIntoContainerResponse.newBuilder()
                    .setContainerItem(toProto(dto))
                    .build();
              },
              PutItemIntoContainerResponse::parseFrom);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (GameplaySessionAttestationException ex) {
      PutItemIntoContainerResponse response =
          PutItemIntoContainerResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "PutItemIntoContainer", ex.getCode(), ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      PutItemIntoContainerResponse response =
          PutItemIntoContainerResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "PutItemIntoContainer",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      PutItemIntoContainerResponse response =
          PutItemIntoContainerResponse.newBuilder()
              .setError(appError("PutItemIntoContainer", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      PutItemIntoContainerResponse response =
          PutItemIntoContainerResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "PutItemIntoContainer", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.takeItemFromContainer")
  public void takeItemFromContainer(
      TakeItemFromContainerRequest request,
      StreamObserver<TakeItemFromContainerResponse> responseObserver) {
    try {
      GameplaySessionAttestationClaims claims =
          requireGameplaySessionAttestation(
              request.getSessionAttestation(),
              request.getTenantId(),
              request.getCharacterId(),
              request.getGameInstanceId(),
              null,
              request.getPlayableStateScope());
      GameplayActorScope actorScope =
          requireGameplayActorScope(request.getTenantId(), request.getCharacterId());
      long containerInstanceId =
          RequestIdValidation.requirePositiveLong(
              request.getContainerInstanceId(), "containerInstanceId");
      long itemId = RequestIdValidation.requirePositiveLong(request.getItemId(), "itemId");
      Long itemInstanceId =
          RequestIdValidation.parseOptionalPositiveLong(
              request.getItemInstanceId(), "itemInstanceId");
      int quantity = requirePositiveQuantity(request.getQuantity());
      TakeItemFromContainerResponse response =
          entityMutationEffectReplayService.execute(
              actorScope.tenantId(),
              request.getEffectId(),
              "TakeItemFromContainer",
              () -> {
                var dto =
                    containerService.takeItemFromContainer(
                        actorScope.tenantId(),
                        actorScope.characterId(),
                        containerInstanceId,
                        resolveGameplayTargetGameInstanceId(request.getGameInstanceId(), claims),
                        resolvePlayableStateScope(request.getPlayableStateScope(), claims),
                        blankToNull(claims.roomInstanceId()),
                        itemId,
                        itemInstanceId,
                        blankToNull(request.getStackFamilyKey()),
                        quantity,
                        blankToNull(request.getEffectId()),
                        blankToNull(claims.sessionId()));
                return TakeItemFromContainerResponse.newBuilder()
                    .setInventoryItem(toProto(dto))
                    .build();
              },
              TakeItemFromContainerResponse::parseFrom);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (GameplaySessionAttestationException ex) {
      TakeItemFromContainerResponse response =
          TakeItemFromContainerResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "TakeItemFromContainer",
                      ex.getCode(),
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      TakeItemFromContainerResponse response =
          TakeItemFromContainerResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "TakeItemFromContainer",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      TakeItemFromContainerResponse response =
          TakeItemFromContainerResponse.newBuilder()
              .setError(appError("TakeItemFromContainer", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      TakeItemFromContainerResponse response =
          TakeItemFromContainerResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "TakeItemFromContainer", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.listRoomGroundInventory")
  public void listRoomGroundInventory(
      ListRoomGroundInventoryRequest request,
      StreamObserver<ListRoomGroundInventoryResponse> responseObserver) {
    try {
      requireGameplayOrProbeAttestation(
          request.getSessionAttestation(),
          request.getTenantId(),
          request.getGameInstanceId(),
          request.getRoomInstanceId());
      long tenantId = RequestIdValidation.requirePositiveLong(request.getTenantId(), "tenantId");
      requireTenantAccessWhenPresent(tenantId);
      var items =
          inventoryService.listRoomGroundItems(
              tenantId,
              request.getGameInstanceId(),
              request.getRoomInstanceId(),
              Pageable.unpaged());
      ListRoomGroundInventoryResponse response =
          ListRoomGroundInventoryResponse.newBuilder()
              .addAllItems(items.stream().map(this::toProto).toList())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (GameplaySessionAttestationException ex) {
      ListRoomGroundInventoryResponse response =
          ListRoomGroundInventoryResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ListRoomGroundInventory",
                      ex.getCode(),
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ListRoomGroundInventoryResponse response =
          ListRoomGroundInventoryResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ListRoomGroundInventory",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      ListRoomGroundInventoryResponse response =
          ListRoomGroundInventoryResponse.newBuilder()
              .setError(appError("ListRoomGroundInventory", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      ListRoomGroundInventoryResponse response =
          ListRoomGroundInventoryResponse.newBuilder()
              .setError(
                  GrpcAppErrors.internal(meterRegistry, logger, "ListRoomGroundInventory", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.pickupItemFromRoom")
  public void pickupItemFromRoom(
      PickupItemFromRoomRequest request,
      StreamObserver<PickupItemFromRoomResponse> responseObserver) {
    try {
      GameplaySessionAttestationClaims claims =
          requireGameplaySessionAttestation(
              request.getSessionAttestation(),
              request.getTenantId(),
              request.getCharacterId(),
              request.getGameInstanceId(),
              request.getRoomInstanceId(),
              request.getPlayableStateScope());
      GameplayActorScope actorScope =
          requireGameplayActorScope(request.getTenantId(), request.getCharacterId());
      long itemId = RequestIdValidation.requirePositiveLong(request.getItemId(), "itemId");
      Long itemInstanceId =
          RequestIdValidation.parseOptionalPositiveLong(
              request.getItemInstanceId(), "itemInstanceId");
      int quantity = requirePositiveQuantity(request.getQuantity());
      PickupItemFromRoomResponse response =
          entityMutationEffectReplayService.execute(
              actorScope.tenantId(),
              request.getEffectId(),
              "PickupItemFromRoom",
              () -> {
                var dto =
                    inventoryService.pickupItemFromRoom(
                        actorScope.tenantId(),
                        actorScope.characterId(),
                        request.getGameInstanceId(),
                        resolvePlayableStateScope(request.getPlayableStateScope(), claims),
                        request.getRoomInstanceId(),
                        itemId,
                        itemInstanceId,
                        request.getContainerInstanceId(),
                        blankToNull(request.getStackFamilyKey()),
                        quantity,
                        blankToNull(request.getEffectId()),
                        blankToNull(claims.sessionId()));
                return PickupItemFromRoomResponse.newBuilder()
                    .setInventoryItem(toProto(dto))
                    .build();
              },
              PickupItemFromRoomResponse::parseFrom);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (GameplaySessionAttestationException ex) {
      PickupItemFromRoomResponse response =
          PickupItemFromRoomResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "PickupItemFromRoom", ex.getCode(), ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      PickupItemFromRoomResponse response =
          PickupItemFromRoomResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "PickupItemFromRoom",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      PickupItemFromRoomResponse response =
          PickupItemFromRoomResponse.newBuilder()
              .setError(appError("PickupItemFromRoom", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      PickupItemFromRoomResponse response =
          PickupItemFromRoomResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "PickupItemFromRoom", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.dropItemToRoom")
  public void dropItemToRoom(
      DropItemToRoomRequest request, StreamObserver<DropItemToRoomResponse> responseObserver) {
    try {
      GameplaySessionAttestationClaims claims =
          requireGameplaySessionAttestation(
              request.getSessionAttestation(),
              request.getTenantId(),
              request.getCharacterId(),
              request.getGameInstanceId(),
              request.getRoomInstanceId(),
              request.getPlayableStateScope());
      GameplayActorScope actorScope =
          requireGameplayActorScope(request.getTenantId(), request.getCharacterId());
      long itemId = RequestIdValidation.requirePositiveLong(request.getItemId(), "itemId");
      Long itemInstanceId =
          RequestIdValidation.parseOptionalPositiveLong(
              request.getItemInstanceId(), "itemInstanceId");
      int quantity = requirePositiveQuantity(request.getQuantity());
      DropItemToRoomResponse response =
          entityMutationEffectReplayService.execute(
              actorScope.tenantId(),
              request.getEffectId(),
              "DropItemToRoom",
              () -> {
                var dto =
                    inventoryService.dropItemToRoom(
                        actorScope.tenantId(),
                        actorScope.characterId(),
                        request.getGameInstanceId(),
                        resolvePlayableStateScope(request.getPlayableStateScope(), claims),
                        request.getRoomInstanceId(),
                        itemId,
                        itemInstanceId,
                        request.getContainerInstanceId(),
                        blankToNull(request.getStackFamilyKey()),
                        quantity,
                        blankToNull(request.getEffectId()),
                        blankToNull(claims.sessionId()));
                return DropItemToRoomResponse.newBuilder().setRoomGroundItem(toProto(dto)).build();
              },
              DropItemToRoomResponse::parseFrom);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (GameplaySessionAttestationException ex) {
      DropItemToRoomResponse response =
          DropItemToRoomResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "DropItemToRoom", ex.getCode(), ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      DropItemToRoomResponse response =
          DropItemToRoomResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "DropItemToRoom", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      DropItemToRoomResponse response =
          DropItemToRoomResponse.newBuilder().setError(appError("DropItemToRoom", ex)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      DropItemToRoomResponse response =
          DropItemToRoomResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "DropItemToRoom", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.listRoomEntities")
  public void listRoomEntities(
      ListRoomEntitiesRequest request, StreamObserver<ListRoomEntitiesResponse> responseObserver) {
    try {
      requireGameplayOrProbeAttestation(
          request.getSessionAttestation(),
          request.getRoomInstance().getTenantId(),
          request.getRoomInstance().getGameInstanceId(),
          request.getRoomInstance().getRoomInstanceId());
      requireTenantAccessWhenPresent(Long.parseLong(resolveTenantId(request)));
      var entities =
          roomEntityService.listEntities(
              resolveTenantId(request), resolveGameInstanceId(request), resolveRoomId(request));
      String tenantId = resolveTenantId(request);
      String gameInstanceId = resolveGameInstanceId(request);
      String roomInstanceId = resolveRoomId(request);
      var builder =
          ListRoomEntitiesResponse.newBuilder()
              .setTenantId(tenantId)
              .setGameInstanceId(gameInstanceId)
              .setRoomInstanceId(roomInstanceId)
              .setEntitySnapshotId(readFence(tenantId, gameInstanceId, roomInstanceId));
      entities.stream().map(this::toProto).forEach(builder::addEntities);
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ListRoomEntitiesResponse response =
          ListRoomEntitiesResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ListRoomEntities",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (GameplaySessionAttestationException ex) {
      ListRoomEntitiesResponse response =
          ListRoomEntitiesResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "ListRoomEntities", ex.getCode(), ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (ResponseStatusException ex) {
      ListRoomEntitiesResponse response =
          ListRoomEntitiesResponse.newBuilder().setError(appError("ListRoomEntities", ex)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      ListRoomEntitiesResponse response =
          ListRoomEntitiesResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "ListRoomEntities", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "entityGrpc.cleanupRuntimeInstance")
  public void cleanupRuntimeInstance(
      CleanupRuntimeInstanceRequest request,
      StreamObserver<CleanupRuntimeInstanceResponse> responseObserver) {
    CleanupRuntimeInstanceResponse.Builder builder = CleanupRuntimeInstanceResponse.newBuilder();
    try {
      requireTenantAccessWhenPresent(Long.parseLong(request.getTenantId()));
      var result =
          runtimeInstanceCleanupService.cleanupRuntimeInstance(
              Long.parseLong(request.getTenantId()),
              request.getGameInstanceId(),
              request.getTerminationRequestId());
      builder
          .setDeletedRoomGroundEntries(result.deletedRoomGroundEntries())
          .setDeletedItemStacks(result.deletedItemStacks())
          .setDeletedItemInstances(result.deletedItemInstances())
          .setDeletedContainerInstances(result.deletedContainerInstances());
    } catch (NumberFormatException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "CleanupRuntimeInstance",
              "INVALID_ARGUMENT",
              "tenant_id must be numeric"));
    } catch (IllegalArgumentException ex) {
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "CleanupRuntimeInstance",
              "INVALID_ARGUMENT",
              ex.getMessage()));
    } catch (Exception ex) {
      builder.setError(GrpcAppErrors.internal(meterRegistry, logger, "CleanupRuntimeInstance", ex));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  private String resolveTenantId(ListRoomEntitiesRequest request) {
    if (request.getRoomInstance().getTenantId().isBlank()) {
      return request.getTenantId();
    }
    return request.getRoomInstance().getTenantId();
  }

  private String resolveRoomId(ListRoomEntitiesRequest request) {
    if (request.getRoomInstance().getRoomInstanceId().isBlank()) {
      throw new IllegalArgumentException("room_instance.room_instance_id is required");
    }
    return request.getRoomInstance().getRoomInstanceId();
  }

  private String resolveGameInstanceId(ListRoomEntitiesRequest request) {
    if (request.getRoomInstance().getGameInstanceId().isBlank()) {
      throw new IllegalArgumentException("room_instance.game_instance_id is required");
    }
    return request.getRoomInstance().getGameInstanceId();
  }

  private String readFence(String tenantId, String gameInstanceId, String roomInstanceId) {
    return tenantId + ":" + gameInstanceId + ":" + roomInstanceId;
  }

  private void requireTenantAccessWhenPresent(Long tenantId) {
    if (SessionContext.isInternalService()) {
      return;
    }
    if (!SessionContext.hasAuthenticatedCallerContext()) {
      return;
    }
    SessionContext.requireTenantAccess(tenantId);
  }

  private GameplayActorScope requireGameplayActorScope(
      String tenantIdText, String characterIdText) {
    long tenantId = RequestIdValidation.requirePositiveLong(tenantIdText, "tenantId");
    requireTenantAccessWhenPresent(tenantId);
    return new GameplayActorScope(
        tenantId, RequestIdValidation.requirePositiveLong(characterIdText, "characterId"));
  }

  private net.firedevops.firemud.shared.v1.ErrorDetail appError(
      String operation, ResponseStatusException ex) {
    return GrpcAppErrors.error(meterRegistry, logger, operation, appErrorCode(ex), ex.getReason());
  }

  private String appErrorCode(ResponseStatusException ex) {
    return ex.getStatusCode().value() == 403 ? "PERMISSION_DENIED" : "INVALID_ARGUMENT";
  }

  private record GameplayActorScope(long tenantId, long characterId) {}

  private PlayableStateScope requirePlayableStateScope(PlayableStateScope playableStateScope) {
    if (playableStateScope == null
        || playableStateScope == PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED
        || playableStateScope == PlayableStateScope.UNRECOGNIZED) {
      throw new IllegalArgumentException("playableStateScope must be specified");
    }
    return playableStateScope;
  }

  private PlayableStateScope resolvePlayableStateScope(
      PlayableStateScope requestPlayableStateScope, GameplaySessionAttestationClaims claims) {
    if (requestPlayableStateScope != null
        && requestPlayableStateScope != PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED
        && requestPlayableStateScope != PlayableStateScope.UNRECOGNIZED) {
      return requestPlayableStateScope;
    }
    if (claims == null) {
      throw new IllegalArgumentException("playableStateScope must be specified");
    }
    return switch (blankToNull(claims.playableStateScope())) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      case null -> throw new IllegalArgumentException("playableStateScope must be specified");
      default ->
          throw new IllegalArgumentException(
              "Unsupported attested playableStateScope=" + claims.playableStateScope());
    };
  }

  private static ActorStateService unsupportedActorStateService() {
    return (tenantId, characterId, gameInstanceId, playableStateScope) -> {
      throw new UnsupportedOperationException("actor state service is not configured");
    };
  }

  private static ActorConditionMutationService unsupportedActorConditionMutationService() {
    return new ActorConditionMutationService() {
      @Override
      public ActorConditionStateDto applyCondition(
          long tenantId,
          long characterId,
          String gameInstanceId,
          PlayableStateScope playableStateScope,
          String conditionKey,
          int stackCount,
          String sourceType,
          String sourceId,
          Instant expiresAt,
          String effectPayloadJson) {
        throw new UnsupportedOperationException(
            "actor condition mutation service is not configured");
      }

      @Override
      public int expireConditions(Instant now) {
        throw new UnsupportedOperationException(
            "actor condition mutation service is not configured");
      }
    };
  }

  private Instant parseOptionalInstant(String value) {
    String text = blankToNull(value);
    if (text == null) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("expiresAt must be ISO-8601", ex);
    }
  }

  private int requirePositiveQuantity(int quantity) {
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be positive");
    }
    return quantity;
  }

  private String requireText(String value, String fieldName) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(fieldName + " must be specified");
    }
    return value.trim();
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private String resolveGameplayTargetGameInstanceId(
      String requestGameInstanceId, GameplaySessionAttestationClaims claims) {
    String resolved = blankToNull(requestGameInstanceId);
    if (resolved != null) {
      return resolved;
    }
    String attested = blankToNull(claims.gameInstanceId());
    if (attested != null) {
      return attested;
    }
    throw new IllegalArgumentException("gameInstanceId must be provided");
  }

  private UpgradeValidationResult toUpgradeValidationResult(String result) {
    return switch (result) {
      case "COMPATIBLE" -> UpgradeValidationResult.UPGRADE_VALIDATION_RESULT_COMPATIBLE;
      case "REQUIRES_MAPPING" -> UpgradeValidationResult.UPGRADE_VALIDATION_RESULT_REQUIRES_MAPPING;
      case "INCOMPATIBLE" -> UpgradeValidationResult.UPGRADE_VALIDATION_RESULT_INCOMPATIBLE;
      case "UNAVAILABLE" -> UpgradeValidationResult.UPGRADE_VALIDATION_RESULT_UNAVAILABLE;
      default -> UpgradeValidationResult.UPGRADE_VALIDATION_RESULT_UNSPECIFIED;
    };
  }

  private Character toProto(CharacterDto dto) {
    return Character.newBuilder()
        .setId(String.valueOf(dto.id()))
        .setTenantId(String.valueOf(dto.tenantId()))
        .setAccountId(String.valueOf(dto.accountId()))
        .setName(dto.name())
        .setPlayableStateScope(dto.playableStateScope())
        .setLevel(dto.level())
        .setExperience(dto.experience())
        .setStrength(dto.strength())
        .setAgility(dto.agility())
        .setIntelligence(dto.intelligence())
        .setStamina(dto.stamina())
        .setHealth(dto.health())
        .setMana(dto.mana())
        .build();
  }

  private ActorResourceValue toProto(ActorResourceStateDto dto) {
    ActorResourceValue.Builder builder =
        ActorResourceValue.newBuilder()
            .setStatKey(dto.statKey())
            .setCurrentValue(dto.currentValue())
            .setPrimitiveKind(dto.primitiveKind())
            .setSourceType(dto.sourceType() == null ? "" : dto.sourceType())
            .setSourceId(dto.sourceId() == null ? "" : dto.sourceId());
    if (dto.maxValue() != null) {
      builder.setMaxValue(dto.maxValue());
    }
    if (dto.baseValue() != null) {
      builder.setBaseValue(dto.baseValue());
    }
    return builder.build();
  }

  private ActorConditionState toProto(ActorConditionStateDto dto) {
    return ActorConditionState.newBuilder()
        .setConditionKey(dto.conditionKey())
        .setStackCount(dto.stackCount())
        .setSourceType(dto.sourceType() == null ? "" : dto.sourceType())
        .setSourceId(dto.sourceId() == null ? "" : dto.sourceId())
        .setStartedAt(dto.startedAt() == null ? "" : dto.startedAt().toString())
        .setExpiresAt(dto.expiresAt() == null ? "" : dto.expiresAt().toString())
        .setEffectPayloadJson(dto.effectPayloadJson() == null ? "" : dto.effectPayloadJson())
        .build();
  }

  private InventoryItem toProto(net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto dto) {
    InventoryItem.Builder builder =
        InventoryItem.newBuilder()
            .setItemId(String.valueOf(dto.itemId()))
            .setItemName(dto.itemName())
            .setItemDescription(dto.itemDescription() == null ? "" : dto.itemDescription())
            .setQuantity(dto.quantity());
    if (dto.itemInstanceId() != null) {
      builder.setItemInstanceId(String.valueOf(dto.itemInstanceId()));
    }
    if (dto.containerInstanceId() != null) {
      builder.setContainerInstanceId(String.valueOf(dto.containerInstanceId()));
    }
    if (dto.visibleRef() != null && !dto.visibleRef().isBlank()) {
      builder.setVisibleRef(dto.visibleRef());
    }
    return builder.build();
  }

  private EquipmentItem toProto(CharacterEquipmentEntryDto dto) {
    EquipmentItem.Builder builder =
        EquipmentItem.newBuilder()
            .setTenantId(String.valueOf(dto.tenantId()))
            .setCharacterId(String.valueOf(dto.characterId()))
            .setSlot(dto.slot())
            .setItemId(String.valueOf(dto.itemId()))
            .setItemName(dto.itemName())
            .setItemDescription(dto.itemDescription() == null ? "" : dto.itemDescription());
    if (dto.itemInstanceId() != null) {
      builder.setItemInstanceId(String.valueOf(dto.itemInstanceId()));
    }
    if (dto.containerInstanceId() != null) {
      builder.setContainerInstanceId(String.valueOf(dto.containerInstanceId()));
    }
    if (dto.visibleRef() != null && !dto.visibleRef().isBlank()) {
      builder.setVisibleRef(dto.visibleRef());
    }
    return builder.build();
  }

  private ContainerItem toProto(ContainerContentEntryDto dto) {
    return ContainerItem.newBuilder()
        .setTenantId(String.valueOf(dto.tenantId()))
        .setCharacterId(String.valueOf(dto.characterId()))
        .setContainerInstanceId(String.valueOf(dto.containerInstanceId()))
        .setItemId(String.valueOf(dto.itemId()))
        .setItemName(dto.itemName())
        .setItemDescription(dto.itemDescription() == null ? "" : dto.itemDescription())
        .setQuantity(dto.quantity())
        .setItemInstanceId(dto.itemInstanceId() == null ? "" : String.valueOf(dto.itemInstanceId()))
        .setVisibleRef(dto.visibleRef() == null ? "" : dto.visibleRef())
        .build();
  }

  private RoomGroundInventoryItem toProto(
      net.firedevops.firemud.entitymanagement.dto.RoomGroundInventoryEntryDto dto) {
    RoomGroundInventoryItem.Builder builder =
        RoomGroundInventoryItem.newBuilder()
            .setTenantId(String.valueOf(dto.tenantId()))
            .setGameInstanceId(dto.gameInstanceId())
            .setRoomInstanceId(dto.roomInstanceId())
            .setItemId(String.valueOf(dto.itemId()))
            .setItemName(dto.itemName())
            .setItemDescription(dto.itemDescription() == null ? "" : dto.itemDescription())
            .setQuantity(dto.quantity());
    if (dto.itemInstanceId() != null) {
      builder.setItemInstanceId(String.valueOf(dto.itemInstanceId()));
    }
    if (dto.containerInstanceId() != null) {
      builder.setContainerInstanceId(String.valueOf(dto.containerInstanceId()));
    }
    if (dto.visibleRef() != null && !dto.visibleRef().isBlank()) {
      builder.setVisibleRef(dto.visibleRef());
    }
    return builder.build();
  }

  private RoomEntity toProto(RoomEntityDto dto) {
    return RoomEntity.newBuilder()
        .setEntityId(dto.entityId())
        .setDisplayName(dto.displayName())
        .setEntityType(dto.entityType())
        .setRole(dto.role() == null ? "" : dto.role())
        .addAllStateFlags(dto.stateFlags())
        .setVisionPriority(dto.visionPriority())
        .setReloadHint(dto.reloadHint())
        .setVisible(dto.visible())
        .setVisibleRef(dto.visibleRef() == null ? "" : dto.visibleRef())
        .build();
  }

  private GameplaySessionAttestationClaims requireGameplaySessionAttestation(
      String token,
      String tenantId,
      String characterId,
      String gameInstanceId,
      String roomInstanceId,
      PlayableStateScope playableStateScope) {
    GameplaySessionAttestationClaims claims = gameplaySessionAttestationService.requireValid(token);
    gameplaySessionAttestationService.requireAdmittedRoutingBundle(claims);
    gameplaySessionAttestationService.requireGameplaySessionMatch(
        token,
        tenantId,
        null,
        null,
        characterId,
        gameInstanceId,
        roomInstanceId,
        null,
        null,
        null,
        attestedPlayableStateScopeText(playableStateScope));
    requireInternalServiceCaller();
    return claims;
  }

  private String attestedPlayableStateScopeText(PlayableStateScope playableStateScope) {
    if (playableStateScope == null
        || playableStateScope == PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED
        || playableStateScope == PlayableStateScope.UNRECOGNIZED) {
      return null;
    }
    return switch (playableStateScope) {
      case PLAYABLE_STATE_SCOPE_SHARED -> "SHARED";
      case PLAYABLE_STATE_SCOPE_ISOLATED -> "ISOLATED";
      default -> null;
    };
  }

  private void requireGameplayOrProbeAttestation(
      String token, String tenantId, String gameInstanceId, String roomInstanceId) {
    gameplaySessionAttestationService.requireGameplayOrProbeMatch(
        token, tenantId, gameInstanceId, roomInstanceId);
    requireInternalServiceCaller();
  }

  private void requireInternalServiceCaller() {
    if (!SessionContext.isInternalService()) {
      throw new GameplaySessionAttestationException(
          "SESSION_ATTESTATION_INVALID", "Gameplay entity RPCs require internal service identity");
    }
  }

  private String templateTypeName(EntityTemplateReferenceType templateType) {
    return switch (templateType) {
      case ENTITY_TEMPLATE_REFERENCE_TYPE_ITEM -> "ITEM";
      case ENTITY_TEMPLATE_REFERENCE_TYPE_NPC -> "NPC";
      default -> throw new IllegalArgumentException("unsupported template_type");
    };
  }
}
