package net.firedevops.firemud.gamedesign.entity;

import java.time.LocalDateTime;
import lombok.Data;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;

@Data
public class PublishAttemptParticipantDigest {
  private Long id;
  private Long publishAttemptId;
  private PublishParticipantKey participantKey;
  private String scopeValue;
  private String appliedCommitId;
  private String contentDigest;

  private Integer digestSchemaVersion;
  private String errorCode;
  private String errorMessage;
  private LocalDateTime observedAt = LocalDateTime.now();
}
