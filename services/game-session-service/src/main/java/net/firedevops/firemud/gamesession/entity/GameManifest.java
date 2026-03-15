package net.firedevops.firemud.gamesession.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "game_manifest")
public class GameManifest {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 100)
  private String versionId;

  @Column(length = 500)
  private String description;
}
