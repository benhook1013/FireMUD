package net.firedevops.firemud.entitymanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.stream.Collectors;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.dto.CharacterDto;
import net.firedevops.firemud.entitymanagement.dto.CharacterEquipmentEntryDto;
import net.firedevops.firemud.entitymanagement.dto.ContainerContentEntryDto;
import net.firedevops.firemud.entitymanagement.dto.RoomEntityDto;
import net.firedevops.firemud.entitymanagement.service.CharacterService;
import net.firedevops.firemud.entitymanagement.service.ContainerService;
import net.firedevops.firemud.entitymanagement.service.EquipmentService;
import net.firedevops.firemud.entitymanagement.service.InventoryService;
import net.firedevops.firemud.entitymanagement.service.PingService;
import net.firedevops.firemud.entitymanagement.service.RoomEntityService;
import net.firedevops.firemud.entitymanagement.v1.Character;
import net.firedevops.firemud.entitymanagement.v1.ContainerItem;
import net.firedevops.firemud.entitymanagement.v1.CreateCharacterRequest;
import net.firedevops.firemud.entitymanagement.v1.CreateCharacterResponse;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.EquipmentItem;
import net.firedevops.firemud.entitymanagement.v1.FindCharacterByNameRequest;
import net.firedevops.firemud.entitymanagement.v1.FindCharacterByNameResponse;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountRequest;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsRequest;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsResponse;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentRequest;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.PingRequest;
import net.firedevops.firemud.entitymanagement.v1.PingResponse;
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerRequest;
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerResponse;
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
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemRequest;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.grpc.server.service.GrpcService;

/** Simple gRPC service exposing the Ping RPC. */
@GrpcService(interceptors = AuthTokenInterceptor.class)
public class EntityManagementGrpcService
    extends EntityManagementServiceGrpc.EntityManagementServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(EntityManagementGrpcService.class);
  private final PingService pingService;
  private final CharacterService characterService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected EquipmentService is not exposed externally")
  private final EquipmentService equipmentService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected InventoryService is not exposed externally")
  private final InventoryService inventoryService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected ContainerService is not exposed externally")
  private final ContainerService containerService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry is thread-safe and stored as injected")
  private final MeterRegistry meterRegistry;

  private final RoomEntityService roomEntityService;

  public EntityManagementGrpcService(
      PingService pingService,
      CharacterService characterService,
      EquipmentService equipmentService,
      InventoryService inventoryService,
      ContainerService containerService,
      RoomEntityService roomEntityService,
      MeterRegistry meterRegistry) {
    this.pingService = pingService;
    this.characterService = characterService;
    this.equipmentService = equipmentService;
    this.inventoryService = inventoryService;
    this.containerService = containerService;
    this.roomEntityService = roomEntityService;
    this.meterRegistry = meterRegistry;
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
  @Timed(value = "entityGrpc.listCharactersByAccount")
  public void listCharactersByAccount(
      ListCharactersByAccountRequest request,
      StreamObserver<ListCharactersByAccountResponse> responseObserver) {
    try {
      long tenantId = Long.parseLong(request.getTenantId());
      SessionContext.requireTenantAccess(tenantId);
      long accountId = Long.parseLong(request.getAccountId());
      var characters =
          characterService.listForTenantAndAccount(tenantId, accountId, Pageable.unpaged()).stream()
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
      long tenantId = Long.parseLong(request.getTenantId());
      SessionContext.requireTenantAccess(tenantId);
      FindCharacterByNameResponse.Builder builder = FindCharacterByNameResponse.newBuilder();
      characterService
          .findByTenantAndName(tenantId, request.getName())
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
      SessionContext.requireTenantAccess(tenantId);
      long accountId = Long.parseLong(request.getAccountId());
      CharacterDto dto =
          new CharacterDto(
              null, tenantId, accountId, request.getName(), 1, 0, 10, 10, 10, 10, 100, 50);
      CharacterDto created = characterService.create(dto);
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
      long entityId = Long.parseLong(request.getEntityId());
      boolean result = characterService.updateEntity(entityId);
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
      long tenantId = Long.parseLong(request.getTenantId());
      SessionContext.requireTenantAccess(tenantId);
      long characterId = Long.parseLong(request.getCharacterId());
      var entries =
          inventoryService.listInventory(tenantId, characterId, Pageable.unpaged()).getContent();
      var items = entries.stream().map(this::toProto).toList();
      QueryInventoryResponse response =
          QueryInventoryResponse.newBuilder().addAllItems(items).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
      QueryInventoryResponse response =
          QueryInventoryResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "QueryInventory", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
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
  @Timed(value = "entityGrpc.listEquipment")
  public void listEquipment(
      ListEquipmentRequest request, StreamObserver<ListEquipmentResponse> responseObserver) {
    try {
      long tenantId = Long.parseLong(request.getTenantId());
      SessionContext.requireTenantAccess(tenantId);
      long characterId = Long.parseLong(request.getCharacterId());
      var items = equipmentService.listEquipment(tenantId, characterId, Pageable.unpaged());
      ListEquipmentResponse response =
          ListEquipmentResponse.newBuilder()
              .addAllItems(items.stream().map(this::toProto).toList())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
      ListEquipmentResponse response =
          ListEquipmentResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "ListEquipment", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
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
      long tenantId = Long.parseLong(request.getTenantId());
      SessionContext.requireTenantAccess(tenantId);
      long characterId = Long.parseLong(request.getCharacterId());
      long itemId = Long.parseLong(request.getItemId());
      Long itemInstanceId =
          request.getItemInstanceId().isBlank()
              ? null
              : Long.parseLong(request.getItemInstanceId());
      CharacterEquipmentEntryDto dto =
          equipmentService.wearItem(tenantId, characterId, itemId, itemInstanceId);
      WearEquipmentItemResponse response =
          WearEquipmentItemResponse.newBuilder().setEquipmentItem(toProto(dto)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
      WearEquipmentItemResponse response =
          WearEquipmentItemResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "WearEquipment", "INVALID_ARGUMENT", ex.getMessage()))
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
      long tenantId = Long.parseLong(request.getTenantId());
      SessionContext.requireTenantAccess(tenantId);
      long characterId = Long.parseLong(request.getCharacterId());
      CharacterEquipmentEntryDto dto =
          equipmentService.removeWornItem(tenantId, characterId, request.getSlot());
      RemoveEquipmentResponse response =
          RemoveEquipmentResponse.newBuilder().setEquipmentItem(toProto(dto)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
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
      long tenantId = Long.parseLong(request.getTenantId());
      SessionContext.requireTenantAccess(tenantId);
      long characterId = Long.parseLong(request.getCharacterId());
      long containerInstanceId = Long.parseLong(request.getContainerInstanceId());
      var items =
          containerService.listContainerContents(
              tenantId, characterId, containerInstanceId, Pageable.unpaged());
      ListContainerContentsResponse response =
          ListContainerContentsResponse.newBuilder()
              .addAllItems(items.stream().map(this::toProto).toList())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
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
      long tenantId = Long.parseLong(request.getTenantId());
      SessionContext.requireTenantAccess(tenantId);
      long characterId = Long.parseLong(request.getCharacterId());
      long containerInstanceId = Long.parseLong(request.getContainerInstanceId());
      long itemId = Long.parseLong(request.getItemId());
      var dto =
          containerService.putItemIntoContainer(
              tenantId, characterId, containerInstanceId, itemId, request.getQuantity());
      PutItemIntoContainerResponse response =
          PutItemIntoContainerResponse.newBuilder().setContainerItem(toProto(dto)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
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
      long tenantId = Long.parseLong(request.getTenantId());
      SessionContext.requireTenantAccess(tenantId);
      long characterId = Long.parseLong(request.getCharacterId());
      long containerInstanceId = Long.parseLong(request.getContainerInstanceId());
      long itemId = Long.parseLong(request.getItemId());
      var dto =
          containerService.takeItemFromContainer(
              tenantId, characterId, containerInstanceId, itemId, request.getQuantity());
      TakeItemFromContainerResponse response =
          TakeItemFromContainerResponse.newBuilder().setInventoryItem(toProto(dto)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
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
  @Timed(value = "entityGrpc.pickupItemFromRoom")
  public void pickupItemFromRoom(
      PickupItemFromRoomRequest request,
      StreamObserver<PickupItemFromRoomResponse> responseObserver) {
    try {
      long tenantId = Long.parseLong(request.getTenantId());
      SessionContext.requireTenantAccess(tenantId);
      long characterId = Long.parseLong(request.getCharacterId());
      long itemId = Long.parseLong(request.getItemId());
      int quantity = request.getQuantity();
      var dto =
          inventoryService.pickupItemFromRoom(
              tenantId,
              characterId,
              request.getGameInstanceId(),
              request.getRoomInstanceId(),
              itemId,
              request.getItemInstanceId().isBlank()
                  ? null
                  : Long.parseLong(request.getItemInstanceId()),
              request.getContainerInstanceId(),
              quantity);
      PickupItemFromRoomResponse response =
          PickupItemFromRoomResponse.newBuilder().setInventoryItem(toProto(dto)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
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
      long tenantId = Long.parseLong(request.getTenantId());
      SessionContext.requireTenantAccess(tenantId);
      long characterId = Long.parseLong(request.getCharacterId());
      long itemId = Long.parseLong(request.getItemId());
      int quantity = request.getQuantity();
      var dto =
          inventoryService.dropItemToRoom(
              tenantId,
              characterId,
              request.getGameInstanceId(),
              request.getRoomInstanceId(),
              itemId,
              request.getItemInstanceId().isBlank()
                  ? null
                  : Long.parseLong(request.getItemInstanceId()),
              request.getContainerInstanceId(),
              quantity);
      DropItemToRoomResponse response =
          DropItemToRoomResponse.newBuilder().setRoomGroundItem(toProto(dto)).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
      DropItemToRoomResponse response =
          DropItemToRoomResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "DropItemToRoom", "INVALID_ARGUMENT", ex.getMessage()))
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
      requireTenantAccessWhenPresent(Long.parseLong(resolveTenantId(request)));
      var entities =
          roomEntityService.listEntities(
              resolveTenantId(request), resolveGameInstanceId(request), resolveRoomId(request));
      var builder = ListRoomEntitiesResponse.newBuilder();
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
    } catch (Exception ex) {
      ListRoomEntitiesResponse response =
          ListRoomEntitiesResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "ListRoomEntities", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
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

  private void requireTenantAccessWhenPresent(Long tenantId) {
    if (SessionContext.getAccountId() == null
        && SessionContext.getGlobalRoles().isEmpty()
        && SessionContext.getScopedRolesMap().isEmpty()) {
      return;
    }
    SessionContext.requireTenantAccess(tenantId);
  }

  private Character toProto(CharacterDto dto) {
    return Character.newBuilder()
        .setId(String.valueOf(dto.id()))
        .setTenantId(String.valueOf(dto.tenantId()))
        .setAccountId(String.valueOf(dto.accountId()))
        .setName(dto.name())
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
        .build();
  }
}
