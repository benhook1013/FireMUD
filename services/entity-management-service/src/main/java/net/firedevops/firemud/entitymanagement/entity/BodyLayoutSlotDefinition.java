package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

@Data
@Entity
@Table(name = "body_layout_slot_definitions")
public class BodyLayoutSlotDefinition {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Long versionId = 1L;

  @Column(nullable = false, length = 64)
  private String bodyLayoutKey;

  @Column(nullable = false, length = 64)
  private String slotKey;

  @Version private int version;
}
