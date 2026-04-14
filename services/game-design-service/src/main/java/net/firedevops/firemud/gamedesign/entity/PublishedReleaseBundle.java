package net.firedevops.firemud.gamedesign.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@Entity
@Table(name = "published_release_bundle")
public class PublishedReleaseBundle {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 36)
  private String tenantId;

  @Column(nullable = false)
  private Long versionId;

  @Column(nullable = false)
  private int versionNumber;

  @Column(nullable = false, length = 16)
  private String attestationSchemaVersion;

  @Column(nullable = false, length = 64)
  private String publishWorkflowId;

  @Column(nullable = false, length = 128)
  private String manifestHash;

  @Lob
  @Column(nullable = false)
  private String requiredManifestAssetKeysJson;

  @Lob
  @Column(nullable = false)
  private String participantDigestsJson = "[]";

  @Column(nullable = false)
  private boolean scriptOnly;

  @Column(length = 100)
  private String scriptPatchVersion;

  @Column(nullable = false)
  private LocalDateTime publishedAt = LocalDateTime.now();
}
