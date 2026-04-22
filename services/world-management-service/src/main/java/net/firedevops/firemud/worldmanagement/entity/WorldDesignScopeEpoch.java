package net.firedevops.firemud.worldmanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@Entity
@Table(name = "world_design_scope_epoch")
public class WorldDesignScopeEpoch {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Long versionId;

  @Column(nullable = false, length = 50)
  private String scopeType;

  @Column(nullable = false, length = 100)
  private String scopeId;

  @Column(nullable = false)
  private Long draftScopeRevisionEpoch = 0L;

  @Column(nullable = false)
  private LocalDateTime updatedAt = LocalDateTime.now();
}
