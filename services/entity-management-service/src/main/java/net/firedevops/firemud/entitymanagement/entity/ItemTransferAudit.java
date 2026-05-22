package net.firedevops.firemud.entitymanagement.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class ItemTransferAudit {
  private Long id;
  private Long tenantId;
  private Long itemId;

  private Long itemInstanceId;
  private int quantity;
  private String stackFamilyKey;
  private String verb;

  private Long actorCharacterId;
  private String sessionId;
  private String effectId;
  private String correlationKey;
  private String sourceHolderKind;

  private Long sourceCharacterId;
  private String sourceEquipmentSlot;
  private String sourceGameInstanceId;
  private String sourceRoomInstanceId;

  private Long sourceContainerInstanceId;
  private String destinationHolderKind;

  private Long destinationCharacterId;
  private String destinationEquipmentSlot;
  private String destinationGameInstanceId;
  private String destinationRoomInstanceId;

  private Long destinationContainerInstanceId;
  private Instant createdAt = Instant.now();
}
