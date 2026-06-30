package net.firedevops.firemud.gamesession.service.impl;

import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;

final class TriggerScriptEventRequestFactory {
  private TriggerScriptEventRequestFactory() {}

  static TriggerScriptEventRequest.Builder builder(
      CommonFields commonFields, RoutingBundle routingBundle) {
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
            .setScriptEventId(commonFields.scriptEventId())
            .setTriggerMode(commonFields.triggerMode())
            .setPlayableStateScope(commonFields.playableStateScope())
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

  record CommonFields(
      String tenantId,
      String gameInstanceId,
      String regionId,
      long regionEpoch,
      String entityId,
      String eventType,
      String eventSchemaVersion,
      String scriptPatchVersion,
      String scriptEventId,
      TriggerMode triggerMode,
      PlayableStateScope playableStateScope,
      String readSnapshotToken,
      String payloadJson) {}

  record RoutingBundle(String worldSlug, String realmSlug, String pointerVersion) {}
}
