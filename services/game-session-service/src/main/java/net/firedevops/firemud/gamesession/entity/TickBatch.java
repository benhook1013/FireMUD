package net.firedevops.firemud.gamesession.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class TickBatch {
  private Long id;
  private String tickBatchId;
  private Long tenantId;
  private Long gameInstanceId;
  private String regionId;
  private long regionEpoch;
  private String executorFence;
  private String batchSource;
  private String status;
  private boolean requiresSoloTick;
  private int commandCount;
  private int expectedEffectCount;
  private String selectedWorkManifestDigest;
  private String selectedWorkManifestJson;
  private Instant stagedAt;
  private Instant completedAt;
  private String failureCode;
  private String failureMessage;
}
