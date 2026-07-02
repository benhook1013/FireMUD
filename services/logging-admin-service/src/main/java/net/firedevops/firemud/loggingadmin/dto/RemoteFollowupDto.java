package net.firedevops.firemud.loggingadmin.dto;

import java.time.Instant;

public record RemoteFollowupDto(
    String followupId,
    long tenantId,
    long originGameInstanceId,
    String originRegionId,
    long originRegionEpoch,
    long targetGameInstanceId,
    String targetRegionId,
    long targetRegionEpoch,
    Long dueTickId,
    String effectKey,
    String targetEntityId,
    String status,
    String claimedTickBatchId,
    String payloadJson,
    String failureCode,
    String failureMessage,
    Instant createdAt,
    Instant updatedAt,
    Long claimOrdinal,
    String scriptPatchVersion,
    String pluginId,
    String pluginVersionId,
    ScriptPatchPublicationLinkDto publication,
    String playableStateScope,
    String worldSlug,
    String realmSlug,
    Long pointerVersion,
    String commandId,
    String automationDispatchId,
    String automationWorkItemId,
    String scriptId,
    String payloadKind,
    String requestedCommand,
    String targetCommandId,
    String targetCommandExecutionOutcome,
    String targetCommandGameplayResult,
    PluginPublicationLinkDto pluginPublication,
    boolean requiresSoloTick,
    String originSourceKind,
    String originSourceState,
    Long originSourceOrdinal,
    Long originSourceDueTickId,
    Long originSourceDueAtMs,
    Long originDeadlineRegionEpoch,
    Long originDeadlineTickId,
    String lateResultPolicy,
    String eventType,
    String eventSchemaVersion,
    String scriptEventId,
    String triggerMode,
    String readSnapshotToken,
    String eventPayloadJson,
    String claimTargetAggregate,
    String currentOriginRuntimeRegionId,
    Long currentOriginRuntimeRegionEpoch,
    String currentTargetRuntimeRegionId,
    Long currentTargetRuntimeRegionEpoch,
    String queueSourceKind,
    String queueSourceState,
    Long queueSourceOrdinal,
    Long queueSourceDueTickId,
    Long queueSourceDueAtMs,
    Long currentOriginRuntimeGameInstanceId,
    Long currentTargetRuntimeGameInstanceId,
    String currentOriginRuntimePlayableStateScope,
    String currentOriginRuntimeWorldSlug,
    String currentOriginRuntimeRealmSlug,
    Long currentOriginRuntimePointerVersion,
    String currentTargetRuntimePlayableStateScope,
    String currentTargetRuntimeWorldSlug,
    String currentTargetRuntimeRealmSlug,
    Long currentTargetRuntimePointerVersion,
    boolean originRoutingBundleStale,
    boolean targetRoutingBundleStale) {
  public static Builder builder() {
    return new Builder();
  }

  public record ScriptPatchPublicationLinkDto(
      String scriptPatchVersion,
      Long versionId,
      Long baseVersionId,
      String publicationState,
      Instant lastChangedAt,
      String lookupErrorCode,
      String lookupErrorMessage) {
    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {
      private String scriptPatchVersion;
      private Long versionId;
      private Long baseVersionId;
      private String publicationState;
      private Instant lastChangedAt;
      private String lookupErrorCode;
      private String lookupErrorMessage;

      public Builder scriptPatchVersion(String scriptPatchVersion) {
        this.scriptPatchVersion = scriptPatchVersion;
        return this;
      }

      public Builder versionId(Long versionId) {
        this.versionId = versionId;
        return this;
      }

      public Builder baseVersionId(Long baseVersionId) {
        this.baseVersionId = baseVersionId;
        return this;
      }

      public Builder publicationState(String publicationState) {
        this.publicationState = publicationState;
        return this;
      }

      public Builder lastChangedAt(Instant lastChangedAt) {
        this.lastChangedAt = lastChangedAt;
        return this;
      }

      public Builder lookupErrorCode(String lookupErrorCode) {
        this.lookupErrorCode = lookupErrorCode;
        return this;
      }

      public Builder lookupErrorMessage(String lookupErrorMessage) {
        this.lookupErrorMessage = lookupErrorMessage;
        return this;
      }

      public ScriptPatchPublicationLinkDto build() {
        return new ScriptPatchPublicationLinkDto(
            scriptPatchVersion,
            versionId,
            baseVersionId,
            publicationState,
            lastChangedAt,
            lookupErrorCode,
            lookupErrorMessage);
      }
    }
  }

  public record PluginPublicationLinkDto(
      String pluginVersionId,
      Long publicationId,
      String publicationState,
      String statusReason,
      Instant lastChangedAt,
      String lookupErrorCode,
      String lookupErrorMessage) {
    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {
      private String pluginVersionId;
      private Long publicationId;
      private String publicationState;
      private String statusReason;
      private Instant lastChangedAt;
      private String lookupErrorCode;
      private String lookupErrorMessage;

      public Builder pluginVersionId(String pluginVersionId) {
        this.pluginVersionId = pluginVersionId;
        return this;
      }

      public Builder publicationId(Long publicationId) {
        this.publicationId = publicationId;
        return this;
      }

      public Builder publicationState(String publicationState) {
        this.publicationState = publicationState;
        return this;
      }

      public Builder statusReason(String statusReason) {
        this.statusReason = statusReason;
        return this;
      }

      public Builder lastChangedAt(Instant lastChangedAt) {
        this.lastChangedAt = lastChangedAt;
        return this;
      }

      public Builder lookupErrorCode(String lookupErrorCode) {
        this.lookupErrorCode = lookupErrorCode;
        return this;
      }

      public Builder lookupErrorMessage(String lookupErrorMessage) {
        this.lookupErrorMessage = lookupErrorMessage;
        return this;
      }

      public PluginPublicationLinkDto build() {
        return new PluginPublicationLinkDto(
            pluginVersionId,
            publicationId,
            publicationState,
            statusReason,
            lastChangedAt,
            lookupErrorCode,
            lookupErrorMessage);
      }
    }
  }

  public static final class Builder {
    private String followupId;
    private long tenantId;
    private long originGameInstanceId;
    private String originRegionId;
    private long originRegionEpoch;
    private long targetGameInstanceId;
    private String targetRegionId;
    private long targetRegionEpoch;
    private Long dueTickId;
    private String effectKey;
    private String targetEntityId;
    private String status;
    private String claimedTickBatchId;
    private String payloadJson;
    private String failureCode;
    private String failureMessage;
    private Instant createdAt;
    private Instant updatedAt;
    private Long claimOrdinal;
    private String scriptPatchVersion;
    private String pluginId;
    private String pluginVersionId;
    private ScriptPatchPublicationLinkDto publication;
    private String playableStateScope;
    private String worldSlug;
    private String realmSlug;
    private Long pointerVersion;
    private String commandId;
    private String automationDispatchId;
    private String automationWorkItemId;
    private String scriptId;
    private String payloadKind;
    private String requestedCommand;
    private String targetCommandId;
    private String targetCommandExecutionOutcome;
    private String targetCommandGameplayResult;
    private PluginPublicationLinkDto pluginPublication;
    private boolean requiresSoloTick;
    private String originSourceKind;
    private String originSourceState;
    private Long originSourceOrdinal;
    private Long originSourceDueTickId;
    private Long originSourceDueAtMs;
    private Long originDeadlineRegionEpoch;
    private Long originDeadlineTickId;
    private String lateResultPolicy;
    private String eventType;
    private String eventSchemaVersion;
    private String scriptEventId;
    private String triggerMode;
    private String readSnapshotToken;
    private String eventPayloadJson;
    private String claimTargetAggregate;
    private String currentOriginRuntimeRegionId;
    private Long currentOriginRuntimeRegionEpoch;
    private String currentTargetRuntimeRegionId;
    private Long currentTargetRuntimeRegionEpoch;
    private String queueSourceKind;
    private String queueSourceState;
    private Long queueSourceOrdinal;
    private Long queueSourceDueTickId;
    private Long queueSourceDueAtMs;
    private Long currentOriginRuntimeGameInstanceId;
    private Long currentTargetRuntimeGameInstanceId;
    private String currentOriginRuntimePlayableStateScope;
    private String currentOriginRuntimeWorldSlug;
    private String currentOriginRuntimeRealmSlug;
    private Long currentOriginRuntimePointerVersion;
    private String currentTargetRuntimePlayableStateScope;
    private String currentTargetRuntimeWorldSlug;
    private String currentTargetRuntimeRealmSlug;
    private Long currentTargetRuntimePointerVersion;
    private boolean originRoutingBundleStale;
    private boolean targetRoutingBundleStale;

    public Builder followupId(String followupId) {
      this.followupId = followupId;
      return this;
    }

    public Builder tenantId(long tenantId) {
      this.tenantId = tenantId;
      return this;
    }

    public Builder originGameInstanceId(long originGameInstanceId) {
      this.originGameInstanceId = originGameInstanceId;
      return this;
    }

    public Builder originRegionId(String originRegionId) {
      this.originRegionId = originRegionId;
      return this;
    }

    public Builder originRegionEpoch(long originRegionEpoch) {
      this.originRegionEpoch = originRegionEpoch;
      return this;
    }

    public Builder targetGameInstanceId(long targetGameInstanceId) {
      this.targetGameInstanceId = targetGameInstanceId;
      return this;
    }

    public Builder targetRegionId(String targetRegionId) {
      this.targetRegionId = targetRegionId;
      return this;
    }

    public Builder targetRegionEpoch(long targetRegionEpoch) {
      this.targetRegionEpoch = targetRegionEpoch;
      return this;
    }

    public Builder dueTickId(Long dueTickId) {
      this.dueTickId = dueTickId;
      return this;
    }

    public Builder effectKey(String effectKey) {
      this.effectKey = effectKey;
      return this;
    }

    public Builder targetEntityId(String targetEntityId) {
      this.targetEntityId = targetEntityId;
      return this;
    }

    public Builder status(String status) {
      this.status = status;
      return this;
    }

    public Builder claimedTickBatchId(String claimedTickBatchId) {
      this.claimedTickBatchId = claimedTickBatchId;
      return this;
    }

    public Builder payloadJson(String payloadJson) {
      this.payloadJson = payloadJson;
      return this;
    }

    public Builder failureCode(String failureCode) {
      this.failureCode = failureCode;
      return this;
    }

    public Builder failureMessage(String failureMessage) {
      this.failureMessage = failureMessage;
      return this;
    }

    public Builder createdAt(Instant createdAt) {
      this.createdAt = createdAt;
      return this;
    }

    public Builder updatedAt(Instant updatedAt) {
      this.updatedAt = updatedAt;
      return this;
    }

    public Builder claimOrdinal(Long claimOrdinal) {
      this.claimOrdinal = claimOrdinal;
      return this;
    }

    public Builder scriptPatchVersion(String scriptPatchVersion) {
      this.scriptPatchVersion = scriptPatchVersion;
      return this;
    }

    public Builder pluginId(String pluginId) {
      this.pluginId = pluginId;
      return this;
    }

    public Builder pluginVersionId(String pluginVersionId) {
      this.pluginVersionId = pluginVersionId;
      return this;
    }

    public Builder publication(ScriptPatchPublicationLinkDto publication) {
      this.publication = publication;
      return this;
    }

    public Builder playableStateScope(String playableStateScope) {
      this.playableStateScope = playableStateScope;
      return this;
    }

    public Builder worldSlug(String worldSlug) {
      this.worldSlug = worldSlug;
      return this;
    }

    public Builder realmSlug(String realmSlug) {
      this.realmSlug = realmSlug;
      return this;
    }

    public Builder pointerVersion(Long pointerVersion) {
      this.pointerVersion = pointerVersion;
      return this;
    }

    public Builder commandId(String commandId) {
      this.commandId = commandId;
      return this;
    }

    public Builder automationDispatchId(String automationDispatchId) {
      this.automationDispatchId = automationDispatchId;
      return this;
    }

    public Builder automationWorkItemId(String automationWorkItemId) {
      this.automationWorkItemId = automationWorkItemId;
      return this;
    }

    public Builder scriptId(String scriptId) {
      this.scriptId = scriptId;
      return this;
    }

    public Builder payloadKind(String payloadKind) {
      this.payloadKind = payloadKind;
      return this;
    }

    public Builder requestedCommand(String requestedCommand) {
      this.requestedCommand = requestedCommand;
      return this;
    }

    public Builder targetCommandId(String targetCommandId) {
      this.targetCommandId = targetCommandId;
      return this;
    }

    public Builder targetCommandExecutionOutcome(String targetCommandExecutionOutcome) {
      this.targetCommandExecutionOutcome = targetCommandExecutionOutcome;
      return this;
    }

    public Builder targetCommandGameplayResult(String targetCommandGameplayResult) {
      this.targetCommandGameplayResult = targetCommandGameplayResult;
      return this;
    }

    public Builder pluginPublication(PluginPublicationLinkDto pluginPublication) {
      this.pluginPublication = pluginPublication;
      return this;
    }

    public Builder requiresSoloTick(boolean requiresSoloTick) {
      this.requiresSoloTick = requiresSoloTick;
      return this;
    }

    public Builder originSourceKind(String originSourceKind) {
      this.originSourceKind = originSourceKind;
      return this;
    }

    public Builder originSourceState(String originSourceState) {
      this.originSourceState = originSourceState;
      return this;
    }

    public Builder originSourceOrdinal(Long originSourceOrdinal) {
      this.originSourceOrdinal = originSourceOrdinal;
      return this;
    }

    public Builder originSourceDueTickId(Long originSourceDueTickId) {
      this.originSourceDueTickId = originSourceDueTickId;
      return this;
    }

    public Builder originSourceDueAtMs(Long originSourceDueAtMs) {
      this.originSourceDueAtMs = originSourceDueAtMs;
      return this;
    }

    public Builder originDeadlineRegionEpoch(Long originDeadlineRegionEpoch) {
      this.originDeadlineRegionEpoch = originDeadlineRegionEpoch;
      return this;
    }

    public Builder originDeadlineTickId(Long originDeadlineTickId) {
      this.originDeadlineTickId = originDeadlineTickId;
      return this;
    }

    public Builder lateResultPolicy(String lateResultPolicy) {
      this.lateResultPolicy = lateResultPolicy;
      return this;
    }

    public Builder eventType(String eventType) {
      this.eventType = eventType;
      return this;
    }

    public Builder eventSchemaVersion(String eventSchemaVersion) {
      this.eventSchemaVersion = eventSchemaVersion;
      return this;
    }

    public Builder scriptEventId(String scriptEventId) {
      this.scriptEventId = scriptEventId;
      return this;
    }

    public Builder triggerMode(String triggerMode) {
      this.triggerMode = triggerMode;
      return this;
    }

    public Builder readSnapshotToken(String readSnapshotToken) {
      this.readSnapshotToken = readSnapshotToken;
      return this;
    }

    public Builder eventPayloadJson(String eventPayloadJson) {
      this.eventPayloadJson = eventPayloadJson;
      return this;
    }

    public Builder claimTargetAggregate(String claimTargetAggregate) {
      this.claimTargetAggregate = claimTargetAggregate;
      return this;
    }

    public Builder currentOriginRuntimeRegionId(String currentOriginRuntimeRegionId) {
      this.currentOriginRuntimeRegionId = currentOriginRuntimeRegionId;
      return this;
    }

    public Builder currentOriginRuntimeRegionEpoch(Long currentOriginRuntimeRegionEpoch) {
      this.currentOriginRuntimeRegionEpoch = currentOriginRuntimeRegionEpoch;
      return this;
    }

    public Builder currentTargetRuntimeRegionId(String currentTargetRuntimeRegionId) {
      this.currentTargetRuntimeRegionId = currentTargetRuntimeRegionId;
      return this;
    }

    public Builder currentTargetRuntimeRegionEpoch(Long currentTargetRuntimeRegionEpoch) {
      this.currentTargetRuntimeRegionEpoch = currentTargetRuntimeRegionEpoch;
      return this;
    }

    public Builder queueSourceKind(String queueSourceKind) {
      this.queueSourceKind = queueSourceKind;
      return this;
    }

    public Builder queueSourceState(String queueSourceState) {
      this.queueSourceState = queueSourceState;
      return this;
    }

    public Builder queueSourceOrdinal(Long queueSourceOrdinal) {
      this.queueSourceOrdinal = queueSourceOrdinal;
      return this;
    }

    public Builder queueSourceDueTickId(Long queueSourceDueTickId) {
      this.queueSourceDueTickId = queueSourceDueTickId;
      return this;
    }

    public Builder queueSourceDueAtMs(Long queueSourceDueAtMs) {
      this.queueSourceDueAtMs = queueSourceDueAtMs;
      return this;
    }

    public Builder currentOriginRuntimeGameInstanceId(Long currentOriginRuntimeGameInstanceId) {
      this.currentOriginRuntimeGameInstanceId = currentOriginRuntimeGameInstanceId;
      return this;
    }

    public Builder currentTargetRuntimeGameInstanceId(Long currentTargetRuntimeGameInstanceId) {
      this.currentTargetRuntimeGameInstanceId = currentTargetRuntimeGameInstanceId;
      return this;
    }

    public Builder currentOriginRuntimePlayableStateScope(
        String currentOriginRuntimePlayableStateScope) {
      this.currentOriginRuntimePlayableStateScope = currentOriginRuntimePlayableStateScope;
      return this;
    }

    public Builder currentOriginRuntimeWorldSlug(String currentOriginRuntimeWorldSlug) {
      this.currentOriginRuntimeWorldSlug = currentOriginRuntimeWorldSlug;
      return this;
    }

    public Builder currentOriginRuntimeRealmSlug(String currentOriginRuntimeRealmSlug) {
      this.currentOriginRuntimeRealmSlug = currentOriginRuntimeRealmSlug;
      return this;
    }

    public Builder currentOriginRuntimePointerVersion(Long currentOriginRuntimePointerVersion) {
      this.currentOriginRuntimePointerVersion = currentOriginRuntimePointerVersion;
      return this;
    }

    public Builder currentTargetRuntimePlayableStateScope(
        String currentTargetRuntimePlayableStateScope) {
      this.currentTargetRuntimePlayableStateScope = currentTargetRuntimePlayableStateScope;
      return this;
    }

    public Builder currentTargetRuntimeWorldSlug(String currentTargetRuntimeWorldSlug) {
      this.currentTargetRuntimeWorldSlug = currentTargetRuntimeWorldSlug;
      return this;
    }

    public Builder currentTargetRuntimeRealmSlug(String currentTargetRuntimeRealmSlug) {
      this.currentTargetRuntimeRealmSlug = currentTargetRuntimeRealmSlug;
      return this;
    }

    public Builder currentTargetRuntimePointerVersion(Long currentTargetRuntimePointerVersion) {
      this.currentTargetRuntimePointerVersion = currentTargetRuntimePointerVersion;
      return this;
    }

    public Builder originRoutingBundleStale(boolean originRoutingBundleStale) {
      this.originRoutingBundleStale = originRoutingBundleStale;
      return this;
    }

    public Builder targetRoutingBundleStale(boolean targetRoutingBundleStale) {
      this.targetRoutingBundleStale = targetRoutingBundleStale;
      return this;
    }

    public RemoteFollowupDto build() {
      return new RemoteFollowupDto(
          followupId,
          tenantId,
          originGameInstanceId,
          originRegionId,
          originRegionEpoch,
          targetGameInstanceId,
          targetRegionId,
          targetRegionEpoch,
          dueTickId,
          effectKey,
          targetEntityId,
          status,
          claimedTickBatchId,
          payloadJson,
          failureCode,
          failureMessage,
          createdAt,
          updatedAt,
          claimOrdinal,
          scriptPatchVersion,
          pluginId,
          pluginVersionId,
          publication,
          playableStateScope,
          worldSlug,
          realmSlug,
          pointerVersion,
          commandId,
          automationDispatchId,
          automationWorkItemId,
          scriptId,
          payloadKind,
          requestedCommand,
          targetCommandId,
          targetCommandExecutionOutcome,
          targetCommandGameplayResult,
          pluginPublication,
          requiresSoloTick,
          originSourceKind,
          originSourceState,
          originSourceOrdinal,
          originSourceDueTickId,
          originSourceDueAtMs,
          originDeadlineRegionEpoch,
          originDeadlineTickId,
          lateResultPolicy,
          eventType,
          eventSchemaVersion,
          scriptEventId,
          triggerMode,
          readSnapshotToken,
          eventPayloadJson,
          claimTargetAggregate,
          currentOriginRuntimeRegionId,
          currentOriginRuntimeRegionEpoch,
          currentTargetRuntimeRegionId,
          currentTargetRuntimeRegionEpoch,
          queueSourceKind,
          queueSourceState,
          queueSourceOrdinal,
          queueSourceDueTickId,
          queueSourceDueAtMs,
          currentOriginRuntimeGameInstanceId,
          currentTargetRuntimeGameInstanceId,
          currentOriginRuntimePlayableStateScope,
          currentOriginRuntimeWorldSlug,
          currentOriginRuntimeRealmSlug,
          currentOriginRuntimePointerVersion,
          currentTargetRuntimePlayableStateScope,
          currentTargetRuntimeWorldSlug,
          currentTargetRuntimeRealmSlug,
          currentTargetRuntimePointerVersion,
          originRoutingBundleStale,
          targetRoutingBundleStale);
    }
  }
}
