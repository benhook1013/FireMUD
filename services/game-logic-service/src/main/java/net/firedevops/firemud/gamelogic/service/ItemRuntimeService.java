package net.firedevops.firemud.gamelogic.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsRequest;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsResponse;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentRequest;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerRequest;
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.RemoveEquipmentRequest;
import net.firedevops.firemud.entitymanagement.v1.RemoveEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomGroundInventoryItem;
import net.firedevops.firemud.entitymanagement.v1.TakeItemFromContainerRequest;
import net.firedevops.firemud.entitymanagement.v1.TakeItemFromContainerResponse;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemRequest;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemResponse;
import net.firedevops.firemud.gamelogic.v1.DropCarriedItemRequest;
import net.firedevops.firemud.gamelogic.v1.PickupVisibleRoomItemRequest;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "gRPC stubs are thread-safe")
public class ItemRuntimeService {
  private static final Logger LOG = LoggerFactory.getLogger(ItemRuntimeService.class);

  private final EntityManagementServiceGrpc.EntityManagementServiceBlockingStub entityStub;
  private final MeterRegistry meterRegistry;

  public QueryInventoryResponse queryInventory(QueryInventoryRequest request) {
    try {
      return entityStub.queryInventory(request);
    } catch (StatusRuntimeException ex) {
      return QueryInventoryResponse.newBuilder()
          .setError(error("QueryInventory", "INVENTORY_UNAVAILABLE", ex))
          .build();
    }
  }

  public ListRoomGroundInventoryResponse listRoomGroundInventory(
      ListRoomGroundInventoryRequest request) {
    try {
      return entityStub.listRoomGroundInventory(request);
    } catch (StatusRuntimeException ex) {
      return ListRoomGroundInventoryResponse.newBuilder()
          .setError(error("ListRoomGroundInventory", "INVENTORY_UNAVAILABLE", ex))
          .build();
    }
  }

  public PickupItemFromRoomResponse pickupItemFromRoom(PickupItemFromRoomRequest request) {
    try {
      return entityStub.pickupItemFromRoom(request);
    } catch (StatusRuntimeException ex) {
      return PickupItemFromRoomResponse.newBuilder()
          .setError(error("PickupItemFromRoom", "INVENTORY_UNAVAILABLE", ex))
          .build();
    }
  }

  public DropItemToRoomResponse dropItemToRoom(DropItemToRoomRequest request) {
    try {
      return entityStub.dropItemToRoom(request);
    } catch (StatusRuntimeException ex) {
      return DropItemToRoomResponse.newBuilder()
          .setError(error("DropItemToRoom", "INVENTORY_UNAVAILABLE", ex))
          .build();
    }
  }

  public PickupItemFromRoomResponse pickupVisibleRoomItem(PickupVisibleRoomItemRequest request) {
    if (request.getQuantity() <= 0) {
      return pickupError("INVALID_ARGUMENT", "GET quantity must be positive");
    }
    String reference = request.getItemReference();
    if (!StringUtils.hasText(reference)) {
      return pickupError("INVALID_ARGUMENT", "GET command requires an item");
    }
    ListRoomGroundInventoryResponse roomItems =
        listRoomGroundInventory(
            ListRoomGroundInventoryRequest.newBuilder()
                .setTenantId(request.getTenantId())
                .setGameInstanceId(request.getGameInstanceId())
                .setRoomInstanceId(request.getRoomInstanceId())
                .setSessionAttestation(request.getSessionAttestation())
                .build());
    if (roomItems.hasError()) {
      return PickupItemFromRoomResponse.newBuilder().setError(roomItems.getError()).build();
    }
    RoomGroundInventoryItem item =
        roomItems.getItemsList().stream()
            .filter(candidate -> matchesReference(candidate, reference))
            .findFirst()
            .orElse(null);
    if (item == null) {
      return pickupError("INVALID_ARGUMENT", "No room item matches \"" + reference + "\"");
    }
    if (request.getQuantity() > 1 && explicitInstanceReference(item, reference)) {
      return pickupError("INVALID_ARGUMENT", "Explicit item refs require quantity 1 for GET");
    }
    PickupItemFromRoomRequest.Builder entityRequest =
        PickupItemFromRoomRequest.newBuilder()
            .setTenantId(request.getTenantId())
            .setCharacterId(request.getCharacterId())
            .setGameInstanceId(request.getGameInstanceId())
            .setRoomInstanceId(request.getRoomInstanceId())
            .setItemId(item.getItemId())
            .setQuantity(request.getQuantity())
            .setSessionAttestation(request.getSessionAttestation());
    if (explicitInstanceReference(item, reference)) {
      entityRequest.setItemInstanceId(item.getItemInstanceId());
    }
    if (StringUtils.hasText(resolveContainerInstanceId(item))) {
      entityRequest.setContainerInstanceId(resolveContainerInstanceId(item));
    }
    if (stackSelection(item, reference)) {
      entityRequest.setStackFamilyKey(item.getVisibleRef());
    }
    if (StringUtils.hasText(request.getEffectId())) {
      entityRequest.setEffectId(request.getEffectId());
    }
    return pickupItemFromRoom(entityRequest.build());
  }

  public DropItemToRoomResponse dropCarriedItem(DropCarriedItemRequest request) {
    if (request.getQuantity() <= 0) {
      return dropError("INVALID_ARGUMENT", "DROP quantity must be positive");
    }
    String reference = request.getItemReference();
    if (!StringUtils.hasText(reference)) {
      return dropError("INVALID_ARGUMENT", "DROP command requires an item");
    }
    QueryInventoryResponse inventory =
        queryInventory(
            QueryInventoryRequest.newBuilder()
                .setTenantId(request.getTenantId())
                .setCharacterId(request.getCharacterId())
                .setSessionAttestation(request.getSessionAttestation())
                .build());
    if (inventory.hasError()) {
      return DropItemToRoomResponse.newBuilder().setError(inventory.getError()).build();
    }
    InventoryItem item =
        inventory.getItemsList().stream()
            .filter(candidate -> matchesReference(candidate, reference))
            .findFirst()
            .orElse(null);
    if (item == null) {
      return dropError("INVALID_ARGUMENT", "No carried item matches \"" + reference + "\"");
    }
    if (request.getQuantity() > 1 && explicitInstanceReference(item, reference)) {
      return dropError("INVALID_ARGUMENT", "Explicit item refs require quantity 1 for DROP");
    }
    DropItemToRoomRequest.Builder entityRequest =
        DropItemToRoomRequest.newBuilder()
            .setTenantId(request.getTenantId())
            .setCharacterId(request.getCharacterId())
            .setGameInstanceId(request.getGameInstanceId())
            .setRoomInstanceId(request.getRoomInstanceId())
            .setItemId(item.getItemId())
            .setQuantity(request.getQuantity())
            .setSessionAttestation(request.getSessionAttestation());
    if (explicitInstanceReference(item, reference)) {
      entityRequest.setItemInstanceId(item.getItemInstanceId());
    }
    if (StringUtils.hasText(resolveContainerInstanceId(item))) {
      entityRequest.setContainerInstanceId(resolveContainerInstanceId(item));
    }
    if (stackSelection(item, reference)) {
      entityRequest.setStackFamilyKey(item.getVisibleRef());
    }
    if (StringUtils.hasText(request.getEffectId())) {
      entityRequest.setEffectId(request.getEffectId());
    }
    return dropItemToRoom(entityRequest.build());
  }

  public ListEquipmentResponse listEquipment(ListEquipmentRequest request) {
    try {
      return entityStub.listEquipment(request);
    } catch (StatusRuntimeException ex) {
      return ListEquipmentResponse.newBuilder()
          .setError(error("ListEquipment", "EQUIPMENT_UNAVAILABLE", ex))
          .build();
    }
  }

  public WearEquipmentItemResponse wearEquipment(WearEquipmentItemRequest request) {
    try {
      return entityStub.wearEquipment(request);
    } catch (StatusRuntimeException ex) {
      return WearEquipmentItemResponse.newBuilder()
          .setError(error("WearEquipment", "EQUIPMENT_UNAVAILABLE", ex))
          .build();
    }
  }

  public RemoveEquipmentResponse removeEquipment(RemoveEquipmentRequest request) {
    try {
      return entityStub.removeEquipment(request);
    } catch (StatusRuntimeException ex) {
      return RemoveEquipmentResponse.newBuilder()
          .setError(error("RemoveEquipment", "EQUIPMENT_UNAVAILABLE", ex))
          .build();
    }
  }

  public ListContainerContentsResponse listContainerContents(ListContainerContentsRequest request) {
    try {
      return entityStub.listContainerContents(request);
    } catch (StatusRuntimeException ex) {
      return ListContainerContentsResponse.newBuilder()
          .setError(error("ListContainerContents", "CONTAINER_UNAVAILABLE", ex))
          .build();
    }
  }

  public PutItemIntoContainerResponse putItemIntoContainer(PutItemIntoContainerRequest request) {
    try {
      return entityStub.putItemIntoContainer(request);
    } catch (StatusRuntimeException ex) {
      return PutItemIntoContainerResponse.newBuilder()
          .setError(error("PutItemIntoContainer", "CONTAINER_UNAVAILABLE", ex))
          .build();
    }
  }

  public TakeItemFromContainerResponse takeItemFromContainer(TakeItemFromContainerRequest request) {
    try {
      return entityStub.takeItemFromContainer(request);
    } catch (StatusRuntimeException ex) {
      return TakeItemFromContainerResponse.newBuilder()
          .setError(error("TakeItemFromContainer", "CONTAINER_UNAVAILABLE", ex))
          .build();
    }
  }

  private ErrorDetail error(String operation, String code, StatusRuntimeException ex) {
    String description = ex.getStatus().getDescription();
    String message =
        description == null || description.isBlank()
            ? "Entity Management unavailable"
            : description;
    return GrpcAppErrors.error(meterRegistry, LOG, operation, code, message);
  }

  private PickupItemFromRoomResponse pickupError(String code, String message) {
    return PickupItemFromRoomResponse.newBuilder()
        .setError(GrpcAppErrors.error(meterRegistry, LOG, "PickupVisibleRoomItem", code, message))
        .build();
  }

  private DropItemToRoomResponse dropError(String code, String message) {
    return DropItemToRoomResponse.newBuilder()
        .setError(GrpcAppErrors.error(meterRegistry, LOG, "DropCarriedItem", code, message))
        .build();
  }

  private boolean matchesReference(InventoryItem item, String reference) {
    return matchesReference(item.getItemId(), reference)
        || matchesReference(item.getItemName(), reference)
        || matchesReference(item.getItemInstanceId(), reference)
        || matchesReference(item.getVisibleRef(), reference)
        || matchesReference(resolveContainerInstanceId(item), reference);
  }

  private boolean matchesReference(RoomGroundInventoryItem item, String reference) {
    return matchesReference(item.getItemId(), reference)
        || matchesReference(item.getItemName(), reference)
        || matchesReference(item.getItemInstanceId(), reference)
        || matchesReference(item.getVisibleRef(), reference)
        || matchesReference(resolveContainerInstanceId(item), reference);
  }

  private boolean matchesReference(String candidate, String reference) {
    return StringUtils.hasText(candidate)
        && StringUtils.hasText(reference)
        && candidate.equalsIgnoreCase(reference);
  }

  private boolean explicitInstanceReference(InventoryItem item, String reference) {
    return StringUtils.hasText(item.getItemInstanceId())
        && (matchesReference(item.getItemInstanceId(), reference)
            || (!stackSelection(item, reference)
                && (matchesReference(item.getVisibleRef(), reference)
                    || matchesReference(resolveContainerInstanceId(item), reference))));
  }

  private boolean explicitInstanceReference(RoomGroundInventoryItem item, String reference) {
    return StringUtils.hasText(item.getItemInstanceId())
        && (matchesReference(item.getItemInstanceId(), reference)
            || (!stackSelection(item, reference)
                && (matchesReference(item.getVisibleRef(), reference)
                    || matchesReference(resolveContainerInstanceId(item), reference))));
  }

  private boolean stackSelection(InventoryItem item, String reference) {
    return !StringUtils.hasText(item.getItemInstanceId())
        && matchesReference(item.getVisibleRef(), reference);
  }

  private boolean stackSelection(RoomGroundInventoryItem item, String reference) {
    return !StringUtils.hasText(item.getItemInstanceId())
        && matchesReference(item.getVisibleRef(), reference);
  }

  private String resolveContainerInstanceId(InventoryItem item) {
    return StringUtils.hasText(item.getContainerInstanceId())
        ? item.getContainerInstanceId()
        : item.getItemId();
  }

  private String resolveContainerInstanceId(RoomGroundInventoryItem item) {
    return StringUtils.hasText(item.getContainerInstanceId())
        ? item.getContainerInstanceId()
        : item.getItemId();
  }
}
