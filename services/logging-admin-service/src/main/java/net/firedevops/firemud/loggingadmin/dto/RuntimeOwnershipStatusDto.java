package net.firedevops.firemud.loggingadmin.dto;

import java.time.Instant;

public record RuntimeOwnershipStatusDto(
    long tenantId,
    long gameInstanceId,
    long regionEpoch,
    String executorFence,
    String ownerService,
    String ownerInstanceId,
    boolean paused,
    String lastCommittedTickBatchId,
    Instant updatedAt,
    long lastCommittedTickId,
    String regionId,
    long pendingGameplayCommandCount,
    long dueRemoteFollowupCount,
    long oldestDueRemoteFollowupTickId,
    long remoteFollowupDrainLagMs) {}
