package net.firedevops.firemud.automationscripting.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "factions")
public class Faction {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false, length = 100)
  private String name;

  private String description;

  @Version
  @Column(name = "row_version")
  private int version;
}
