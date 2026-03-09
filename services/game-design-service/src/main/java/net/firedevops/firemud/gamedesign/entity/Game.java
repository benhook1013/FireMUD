package net.firedevops.firemud.gamedesign.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "game")
public class Game {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 36)
  private String tenantId;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(length = 255)
  private String description;
}
