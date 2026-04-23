package net.firedevops.firemud.gamedesign.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;

@Getter
@Setter
@Entity
@Table(
    name = "published_plugin_versions",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_published_plugin_versions_identity",
          columnNames = {"tenant_id", "plugin_id", "plugin_version_id"})
    })
public class PublishedPluginVersion {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tenant_id", nullable = false, length = 64)
  private String tenantId;

  @Column(name = "plugin_id", nullable = false, length = 128)
  private String pluginId;

  @Column(name = "plugin_version_id", nullable = false, length = 128)
  private String pluginVersionId;

  @Column(name = "base_version_id", nullable = false)
  private Long baseVersionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "publication_state", nullable = false, length = 32)
  private VersionLifecycleState publicationState;

  @Column(name = "ability_schema_digest", nullable = false, length = 256)
  private String abilitySchemaDigest;

  @Column(name = "bundle_digest", nullable = false, length = 256)
  private String bundleDigest;

  @Column(name = "manifest_schema_version", nullable = false)
  private Integer manifestSchemaVersion;

  @Column(name = "distribution_manifest_hash", length = 256)
  private String distributionManifestHash;

  @Column(name = "distribution_manifest_path", length = 512)
  private String distributionManifestPath;

  @Column(name = "signer_key_id", nullable = false, length = 128)
  private String signerKeyId;

  @Column(name = "signer_revoked", nullable = false)
  private boolean signerRevoked;

  @Column(name = "component_policy_decision", nullable = false, length = 32)
  private String componentPolicyDecision;

  @Column(name = "notes", length = 2000)
  private String notes;

  @Column(name = "last_changed_at", nullable = false)
  private LocalDateTime lastChangedAt;
}
