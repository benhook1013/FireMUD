package net.firedevops.firemud.gamesession.service;

import java.util.List;

public interface RemoteFollowupDrainService {
  ClaimOutcome claimDueFollowups(
      long tenantId,
      long targetGameInstanceId,
      String targetRegionId,
      long dueTickIdInclusive,
      String tickBatchId,
      int limit);

  int releaseClaimedFollowups(String tickBatchId, String failureCode, String failureMessage);

  record ClaimOutcome(List<String> followupIds, int claimedCount) {
    public ClaimOutcome {
      followupIds = List.copyOf(followupIds);
    }
  }
}
