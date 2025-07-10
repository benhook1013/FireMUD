package net.firedevops.firemud.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "faction_standing")
public class FactionStanding {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Long playerId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "faction_id", nullable = false)
  private Faction faction;

  @Column(nullable = false)
  private int reputation = 0;
}
