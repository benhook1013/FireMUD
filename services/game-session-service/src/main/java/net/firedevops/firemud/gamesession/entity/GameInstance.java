package net.firedevops.firemud.gamesession.entity;

import jakarta.persistence.*;
import java.time.Instant;
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

  @Column(name = "runtime_version", nullable = false, length = 100)
  private String runtimeVersion;

  @Column(name = "script_patch_version", length = 100)
  private String scriptPatchVersion;

  @Column(name = "script_patch_pinned_at")
  private Instant scriptPatchPinnedAt;

  @Column(name = "script_patch_pinned_by", length = 200)
  private String scriptPatchPinnedBy;

  @Column(name = "script_patch_pinned_reason", length = 500)
  private String scriptPatchPinnedReason;

  @Column(nullable = false)
  private Long ownerAccountId;

  @Column(nullable = false, length = 20)
  private String status;
}
