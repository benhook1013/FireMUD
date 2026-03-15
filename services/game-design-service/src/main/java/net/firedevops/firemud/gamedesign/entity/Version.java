package net.firedevops.firemud.gamedesign.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@Entity
@Table(name = "version")
public class Version {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 36)
  private String tenantId;

  @Column(nullable = false)
  private int versionNumber;

  @Column(name = "script_patch_version", length = 100)
  private String scriptPatchVersion;

  @Column(name = "base_version_id")
  private Long baseVersionId;

  @Column(name = "is_script_only")
  private boolean scriptOnly;

  @Column(length = 255)
  private String notes;

  @Column(nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();
}
