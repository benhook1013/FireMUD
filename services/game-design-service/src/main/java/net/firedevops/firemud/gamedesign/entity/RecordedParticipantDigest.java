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
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import net.firedevops.firemud.gamedesign.model.PublishType;

@Data
@Entity
@Table(name = "publish_recorded_participant_digest")
public class RecordedParticipantDigest {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 36)
  private String tenantId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private PublishType publishType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 64)
  private PublishParticipantKey participantKey;

  @Column(nullable = false, length = 128)
  private String scopeValue;

  @Column(nullable = false, length = 128)
  private String appliedCommitId;

  @Column(nullable = false, length = 128)
  private String contentDigest;

  @Column(nullable = false)
  private Integer digestSchemaVersion;

  @Column(nullable = false, length = 64)
  private String recordedFromPublishWorkflowId;

  @Column(nullable = false)
  private LocalDateTime recordedAt = LocalDateTime.now();

  @Column(nullable = false, length = 64)
  private String lastVerifiedPublishWorkflowId;

  @Column(nullable = false)
  private LocalDateTime lastVerifiedAt = LocalDateTime.now();
}
