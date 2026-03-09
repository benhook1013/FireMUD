package net.firedevops.firemud.automationscripting.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "scripts")
public class ScriptDefinition {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(name = "version", nullable = false, length = 20)
  private String scriptVersion;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String definition;

  @Version
  @Column(name = "row_version")
  private int rowVersion;
}
