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
@Table(name = "launch_descriptor")
public class LaunchDescriptor {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64, unique = true)
  private String launchDescriptorId;

  @Column(nullable = false, length = 36)
  private String tenantId;

  @Column(nullable = false)
  private Long gameTemplateId;

  @Column(nullable = false, length = 64)
  private String controlPlaneRequestId;

  @Column(nullable = false, length = 128)
  private String requestHash;

  @Column(nullable = false)
  private Long versionId;

  @Column(length = 100)
  private String scriptPatchVersion;

  @Lob
  @Column(nullable = false)
  private String runtimeFlagsJson;

  @Column(nullable = false, length = 128)
  private String generationConfigRevision;

  @Column(nullable = false)
  private Long versionStateEpoch;

  @Column(nullable = false)
  private Long releaseBundleId;

  @Column(nullable = false, length = 128)
  private String publishedReleaseBundleRef;

  @Column(nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();
}
