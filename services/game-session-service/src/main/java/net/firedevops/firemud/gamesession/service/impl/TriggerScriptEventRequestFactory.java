package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.service.ScriptPinTupleCoherence;

final class TriggerScriptEventRequestFactory {
  private TriggerScriptEventRequestFactory() {}

  static TriggerScriptEventRequest.Builder builder(
      CommonFields commonFields, RoutingBundle routingBundle) {
    ScriptPinTupleCoherence.requireCoherent(
        commonFields.scriptPatchVersion(),
        commonFields.scriptPinEpoch() == 0L ? null : commonFields.scriptPinEpoch(),
        commonFields.scriptPinControlPlaneRequestId());
    PlayableStateScope playableStateScope =
        requirePlayableStateScope(commonFields.playableStateScope());
    TriggerScriptEventRequest.Builder builder =
        TriggerScriptEventRequest.newBuilder()
            .setTenantId(commonFields.tenantId())
            .setGameInstanceId(commonFields.gameInstanceId())
            .setRegionId(commonFields.regionId())
            .setRegionEpoch(commonFields.regionEpoch())
            .setEntityId(commonFields.entityId())
            .setEventType(commonFields.eventType())
            .setEventSchemaVersion(commonFields.eventSchemaVersion())
            .setScriptPatchVersion(commonFields.scriptPatchVersion())
            .setScriptPinEpoch(commonFields.scriptPinEpoch())
            .setScriptPinControlPlaneRequestId(
                commonFields.scriptPinControlPlaneRequestId() == null
                    ? ""
                    : commonFields.scriptPinControlPlaneRequestId())
            .setScriptEventId(commonFields.scriptEventId())
            .setIsDryRun(commonFields.isDryRun())
            .setTriggerMode(commonFields.triggerMode())
            .setPlayableStateScope(playableStateScope)
            .setReadSnapshotToken(commonFields.readSnapshotToken())
            .setPayloadJson(commonFields.payloadJson());
    if (routingBundle != null) {
      builder
          .setWorldSlug(routingBundle.worldSlug())
          .setRealmSlug(routingBundle.realmSlug())
          .setPointerVersion(routingBundle.pointerVersion());
    }
    return builder;
  }

  static PlayableStateScope requirePlayableStateScope(PlayableStateScope playableStateScope) {
    if (playableStateScope == null) {
      throw invalidPlayableStateScope();
    }
    return switch (playableStateScope) {
      case PLAYABLE_STATE_SCOPE_SHARED, PLAYABLE_STATE_SCOPE_ISOLATED -> playableStateScope;
      default -> throw invalidPlayableStateScope();
    };
  }

  static PlayableStateScope requirePlayableStateScope(String playableStateScope) {
    if (playableStateScope == null || playableStateScope.isBlank()) {
      throw invalidPlayableStateScope();
    }
    return switch (playableStateScope) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default -> throw invalidPlayableStateScope();
    };
  }

  private static IllegalArgumentException invalidPlayableStateScope() {
    return new IllegalArgumentException("playableStateScope must be explicitly SHARED or ISOLATED");
  }

  record CommonFields(
      String tenantId,
      String gameInstanceId,
      String regionId,
      long regionEpoch,
      String entityId,
      String eventType,
      String eventSchemaVersion,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String scriptPinControlPlaneRequestId,
      String scriptEventId,
      boolean isDryRun,
      TriggerMode triggerMode,
      PlayableStateScope playableStateScope,
      String readSnapshotToken,
      String payloadJson) {}

  record RoutingBundle(String worldSlug, String realmSlug, String pointerVersion) {}
}
