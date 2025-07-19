package net.firedevops.firemud.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@Entity
@Table(name = "game_assets")
public class GameAsset {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 36)
  private String tenantId;

  @Column(nullable = false, length = 255)
  private String fileName;

  @Column(nullable = false, length = 100)
  private String contentType;

  @Lob
  @Column(nullable = false)
  private byte[] data;

  @Column(nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();
}
