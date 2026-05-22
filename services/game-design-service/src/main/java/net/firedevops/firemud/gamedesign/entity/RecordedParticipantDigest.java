package net.firedevops.firemud.gamedesign.entity;

import java.time.LocalDateTime;
import lombok.Data;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import net.firedevops.firemud.gamedesign.model.PublishType;

@Data
public class RecordedParticipantDigest {
  private Long id;
  private String tenantId;
  private PublishType publishType;
  private PublishParticipantKey participantKey;
  private String scopeValue;
  private String appliedCommitId;
  private String contentDigest;
  private Integer digestSchemaVersion;
  private String recordedFromPublishWorkflowId;
  private LocalDateTime recordedAt = LocalDateTime.now();
  private String lastVerifiedPublishWorkflowId;
  private LocalDateTime lastVerifiedAt = LocalDateTime.now();
}
