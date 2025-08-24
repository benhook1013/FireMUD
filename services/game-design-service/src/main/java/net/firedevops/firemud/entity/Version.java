package net.firedevops.firemud.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Entity
@Table(name = "version")
public class Version {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 36)
  private String tenantId;

  @Getter(onMethod_ = @SuppressFBWarnings("EI_EXPOSE_REP"))
  @Setter(onMethod_ = @SuppressFBWarnings("EI_EXPOSE_REP2"))
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "tenant_id", nullable = false)
  private Game game;

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
