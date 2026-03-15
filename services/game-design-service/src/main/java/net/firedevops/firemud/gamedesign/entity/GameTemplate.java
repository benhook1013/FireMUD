package net.firedevops.firemud.gamedesign.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@Entity
@Table(name = "game_templates")
public class GameTemplate {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 36)
  private String tenantId;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(length = 255)
  private String description;

  @Lob
  @Column(nullable = false)
  private String config;

  @Column(nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();
}
