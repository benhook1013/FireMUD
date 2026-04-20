package net.firedevops.firemud.gamedesign.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@Entity
@Table(name = "version_template_remap_entry")
public class VersionTemplateRemapEntry {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "remap_set_pk", nullable = false)
  private VersionTemplateRemapSet remapSet;

  @Column(nullable = false, length = 64)
  private String mappingDomain;

  @Column(nullable = false, length = 64)
  private String mappingType;

  @Column(nullable = false, length = 128)
  private String sourceTemplateKey;

  @Column(nullable = false, length = 128)
  private String targetTemplateKey;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
  }
}
