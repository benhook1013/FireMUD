package net.firedevops.firemud.gamedesign.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Data;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.PublishType;

@Data
@Entity
@Table(name = "publish_attempt")
public class PublishAttempt {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 36)
  private String tenantId;

  @Column(length = 64, nullable = false, unique = true)
  private String publishWorkflowId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private PublishType publishType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private PublishAttemptStatus status = PublishAttemptStatus.PENDING;

  @Column private Long versionId;

  @Column(nullable = false)
  private int versionNumber;

  @Column(length = 100)
  private String scriptPatchVersion;

  @Column(length = 64)
  private String failureCode;

  @Column(length = 512)
  private String failureMessage;

  @Column(nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();

  @Column private LocalDateTime completedAt;
}
