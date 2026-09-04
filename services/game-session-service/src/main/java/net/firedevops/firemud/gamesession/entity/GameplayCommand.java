package net.firedevops.firemud.gamesession.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class GameplayCommand {
  private Long id;
  private String commandId;
  private Long tenantId;
  private Long gameInstanceId;
  private Long sessionId;
  private Long accountId;
  private Long characterId;
  private String commandName;
  private String commandText;
  private String sanitizedCommandText;
  private boolean requiresSoloTick;
  private String executionOutcome;
  private String gameplayResult;
  private Instant acceptedAt;
  private Instant stagedAt;
  private Instant completedAt;
  private Instant lastAttemptAt;
  private int attemptCount;
  private Long enqueueSeq;
  private String failureCode;
  private String failureMessage;
  private String sourceType = "PLAYER";
  private String automationDispatchId;
  private String automationWorkItemId;
  private String scriptId;
  private String executionHook;
  private String scriptPatchVersion;
  private Long scriptPinEpoch;
  /** Exact pin request identity captured with the admitted tuple. */
  private String scriptPinControlPlaneRequestId;
  private String sourceScriptPatchVersion;
  private Long sourceScriptPinEpoch;
  private String sourceScriptPinControlPlaneRequestId;
  private String targetScriptPatchVersion;
  private Long targetScriptPinEpoch;
  private String targetScriptPinControlPlaneRequestId;
  private String pluginId;
  private String pluginVersionId;
  private String playableStateScope;
  private String worldSlug;
  private String realmSlug;
  private Long pointerVersion;
  private String originSourceKind;
  private String originSourceState;
  private Long originSourceOrdinal;
  private Long originSourceDueTickId;
  private Long originSourceDueAtMs;
  private String queueSourceKind;
  private String queueSourceState;
  private Long queueSourceOrdinal;
  private Long queueSourceDueTickId;
  private Long queueSourceDueAtMs;
  private String targetEntityId;
  private String remoteCoordinatorId;
  private String remoteFollowupId;
  private String regionId;
  private Long regionEpoch;
  private Long dueTickId;
  private Long admittedReleaseBundleId;
  private Long admittedVersionId;
  private String declaredEffectsJson;
}
