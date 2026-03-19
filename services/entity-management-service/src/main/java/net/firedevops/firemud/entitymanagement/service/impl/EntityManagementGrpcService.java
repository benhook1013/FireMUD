package net.firedevops.firemud.entitymanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.stream.Collectors;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.entitymanagement.dto.CharacterDto;
import net.firedevops.firemud.entitymanagement.dto.RoomEntityDto;
import net.firedevops.firemud.entitymanagement.service.CharacterService;
import net.firedevops.firemud.entitymanagement.service.InventoryService;
import net.firedevops.firemud.entitymanagement.service.PingService;
import net.firedevops.firemud.entitymanagement.service.RoomEntityService;
import net.firedevops.firemud.entitymanagement.v1.Character;
import net.firedevops.firemud.entitymanagement.v1.CreateCharacterRequest;
import net.firedevops.firemud.entitymanagement.v1.CreateCharacterResponse;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountRequest;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.entitymanagement.v1.PingRequest;
import net.firedevops.firemud.entitymanagement.v1.PingResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomEntity;
import net.firedevops.firemud.entitymanagement.v1.UpdateEntityRequest;
import net.firedevops.firemud.entitymanagement.v1.UpdateEntityResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.grpc.server.service.GrpcService;

/** Simple gRPC service exposing the Ping RPC. */
@GrpcService
public class EntityManagementGrpcService
    extends EntityManagementServiceGrpc.EntityManagementServiceImplBase {
  private final PingService pingService;
  private final CharacterService characterService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected InventoryService is not exposed externally")
  private final InventoryService inventoryService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry is thread-safe and stored as injected")
  private final MeterRegistry meterRegistry;

  private final RoomEntityService roomEntityService;

  public EntityManagementGrpcService(
      PingService pingService,
      CharacterService characterService,
      InventoryService inventoryService,
      RoomEntityService roomEntityService,
      MeterRegistry meterRegistry) {
    this.pingService = pingService;
    this.characterService = characterService;
    this.inventoryService = inventoryService;
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  @Override
  @Timed(value = "entityGrpc.listCharactersByAccount")
  public void listCharactersByAccount(
      ListCharactersByAccountRequest request,
      StreamObserver<ListCharactersByAccountResponse> responseObserver) {
    try {
      long accountId = Long.parseLong(request.getAccountId());
      var characters =
          characterService.listForAccount(accountId, Pageable.unpaged()).stream()
              .map(this::toProto)
              .collect(Collectors.toList());
      ListCharactersByAccountResponse response =
          ListCharactersByAccountResponse.newBuilder().addAllCharacters(characters).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
      ListCharactersByAccountResponse response =
          ListCharactersByAccountResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ListCharactersByAccountResponse response =
          ListCharactersByAccountResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  @Override
  @Timed(value = "entityGrpc.createCharacter")
  public void createCharacter(
      CreateCharacterRequest request, StreamObserver<CreateCharacterResponse> responseObserver) {
    try {
      long tenantId = Long.parseLong(request.getTenantId());
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
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
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  @Override
  @Timed(value = "entityGrpc.queryInventory")
  public void queryInventory(
      QueryInventoryRequest request, StreamObserver<QueryInventoryResponse> responseObserver) {
    try {
      long characterId = Long.parseLong(request.getEntityId());
      var entries = inventoryService.listInventory(characterId, Pageable.unpaged()).getContent();
      var itemIds = entries.stream().map(e -> String.valueOf(e.itemId())).toList();
      QueryInventoryResponse response =
          QueryInventoryResponse.newBuilder().addAllItemIds(itemIds).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (NumberFormatException ex) {
      QueryInventoryResponse response =
          QueryInventoryResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  @Override
  @Timed(value = "entityGrpc.listRoomEntities")
  public void listRoomEntities(
      ListRoomEntitiesRequest request, StreamObserver<ListRoomEntitiesResponse> responseObserver) {
    try {
      var entities =
          roomEntityService.listEntities(resolveTenantId(request), resolveRoomId(request));
      var builder = ListRoomEntitiesResponse.newBuilder();
      entities.stream().map(this::toProto).forEach(builder::addEntities);
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  private String resolveTenantId(ListRoomEntitiesRequest request) {
    if (request.hasRoomInstance() && !request.getRoomInstance().getTenantId().isBlank()) {
      return request.getRoomInstance().getTenantId();
    }
    return request.getTenantId();
  }

  @SuppressWarnings("deprecation")
  private String resolveRoomId(ListRoomEntitiesRequest request) {
    if (request.hasRoomInstance() && !request.getRoomInstance().getRoomInstanceId().isBlank()) {
      return request.getRoomInstance().getRoomInstanceId();
    }
    return request.getRoomId();
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
