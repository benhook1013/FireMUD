package net.firedevops.firemud.gamedesign.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import net.firedevops.firemud.gamedesign.model.TemplateRemapSetStatus;

@Data
@Entity
@Table(name = "version_template_remap_set")
public class VersionTemplateRemapSet {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64, unique = true)
  private String remapSetId;

  @Column(nullable = false, length = 36)
  private String tenantId;

  @Column(nullable = false)
  private Long sourceVersionId;

  @Column(nullable = false)
  private Long targetVersionId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private TemplateRemapSetStatus status = TemplateRemapSetStatus.DRAFT;

  @Column(nullable = false, length = 500)
  private String createdReason;

  @Column(length = 500)
  private String approvalReason;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  private LocalDateTime approvedAt;

  @Column(nullable = false)
  private LocalDateTime updatedAt;

  @OneToMany(mappedBy = "remapSet", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<VersionTemplateRemapEntry> remapEntries = new ArrayList<>();

  public void addRemapEntry(VersionTemplateRemapEntry remapEntry) {
    remapEntries.add(remapEntry);
    remapEntry.setRemapSet(this);
  }

  @PrePersist
  void prePersist() {
    LocalDateTime now = LocalDateTime.now();
    if (createdAt == null) {
      createdAt = now;
    }
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
