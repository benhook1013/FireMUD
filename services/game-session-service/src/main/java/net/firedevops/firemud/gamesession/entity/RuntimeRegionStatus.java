package net.firedevops.firemud.gamesession.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class RuntimeRegionStatus {
  private Long id;
  private Long tenantId;
  private Long gameInstanceId;
  private String regionId;
  private long regionEpoch;
  private String executorFence;
  private String ownerService;
  private String ownerInstanceId;
  private boolean paused;
  private String lastCommittedTickBatchId;
  private long lastCommittedTickId;
  private Instant updatedAt;
}
