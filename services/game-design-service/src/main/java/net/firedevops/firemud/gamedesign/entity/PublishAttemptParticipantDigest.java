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

@Data
@Entity
@Table(name = "publish_attempt_participant_digest")
public class PublishAttemptParticipantDigest {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long publishAttemptId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 64)
  private PublishParticipantKey participantKey;

  @Column(nullable = false, length = 128)
  private String scopeValue;

  @Column(length = 128)
  private String appliedCommitId;

  @Column(length = 128)
  private String contentDigest;

  @Column private Integer digestSchemaVersion;

  @Column(length = 64)
  private String errorCode;

  @Column(length = 512)
  private String errorMessage;

  @Column(nullable = false)
  private LocalDateTime observedAt = LocalDateTime.now();
}
