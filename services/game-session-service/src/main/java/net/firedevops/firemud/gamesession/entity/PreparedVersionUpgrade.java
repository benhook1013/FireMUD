package net.firedevops.firemud.gamesession.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class PreparedVersionUpgrade {
  private Long id;
  private String preparationId;
  private String controlPlaneRequestId;
  private Long tenantId;
  private Long sourceGameInstanceId;
  private Long sourceVersionId;
  private Long targetVersionId;
  private String targetLaunchDescriptorId;
  private String remapSetId;
  private String result;
  private String reasonsJson;
  private String checkedParticipantsJson;
  private String participantResultsJson;
  private Instant checkedAt;
  private Long executedTargetGameInstanceId;
  private Long executedPointerVersion;
  private Instant executedAt;
  private String executionControlPlaneRequestId;
}
