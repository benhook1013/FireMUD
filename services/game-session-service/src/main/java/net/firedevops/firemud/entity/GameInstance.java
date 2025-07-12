package net.firedevops.firemud.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "game_instances")
public class GameInstance {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false, length = 100)
  private String versionId;

  @Column(name = "script_patch_version", length = 100)
  private String scriptPatchVersion;

  @Column(nullable = false)
  private Long ownerAccountId;

  @Column(nullable = false, length = 20)
  private String status;
}
