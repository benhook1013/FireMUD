package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "item_transfer_audits")
public class ItemTransferAudit {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Long itemId;

  private Long itemInstanceId;

  @Column(nullable = false)
  private int quantity;

  @Column(length = 128)
  private String stackFamilyKey;

  @Column(nullable = false, length = 32)
  private String verb;

  private Long actorCharacterId;

  @Column(length = 128)
  private String sessionId;

  @Column(length = 128)
  private String effectId;

  @Column(nullable = false, length = 512)
  private String correlationKey;

  @Column(nullable = false, length = 32)
  private String sourceHolderKind;

  private Long sourceCharacterId;

  @Column(length = 64)
  private String sourceEquipmentSlot;

  @Column(length = 255)
  private String sourceGameInstanceId;

  @Column(length = 255)
  private String sourceRoomInstanceId;

  private Long sourceContainerInstanceId;

  @Column(nullable = false, length = 32)
  private String destinationHolderKind;

  private Long destinationCharacterId;

  @Column(length = 64)
  private String destinationEquipmentSlot;

  @Column(length = 255)
  private String destinationGameInstanceId;

  @Column(length = 255)
  private String destinationRoomInstanceId;

  private Long destinationContainerInstanceId;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();
}
