package net.firedevops.firemud.worldmanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "generation_rule")
public class GenerationRule {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Long versionId = 1L;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(name = "scope_type", length = 64)
  private String scopeType;

  @Column(name = "scope_id", length = 128)
  private String scopeId;

  @Column(length = 255)
  private String value;

  @Version private int version;
}
