package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import org.junit.jupiter.api.Test;

class ScriptEventIngressRequestDigestTest {
  @Test
  void canonicalizesEquivalentPayloadJson() {
    TriggerScriptEventRequest first = request("{\"b\":2, \"a\": 1}");
    TriggerScriptEventRequest second = request("{\"a\":1,\"b\":2}");

    assertThat(ScriptEventIngressRequestDigest.compute(first, "v1", "game-session-service"))
        .isEqualTo(ScriptEventIngressRequestDigest.compute(second, "v1", "game-session-service"));
  }

  @Test
  void bindsOwnerRequestEvidenceSoChangedIdCannotReplayTheClaim() {
    TriggerScriptEventRequest original = request("{\"a\":1}");
    TriggerScriptEventRequest changedOwner =
        original.toBuilder().setScriptPinControlPlaneRequestId("pin-request-2").build();

    assertThat(ScriptEventIngressRequestDigest.compute(original, "v1", "game-session-service"))
        .isNotEqualTo(
            ScriptEventIngressRequestDigest.compute(changedOwner, "v1", "game-session-service"));
  }

  private static TriggerScriptEventRequest request(String payload) {
    return TriggerScriptEventRequest.newBuilder()
        .setTenantId("1")
        .setGameInstanceId("game-1")
        .setRegionId("region-1")
        .setRegionEpoch(7L)
        .setEntityId("entity-1")
        .setEventType("onCommand")
        .setScriptPatchVersion("patch-1")
        .setScriptPinEpoch(1L)
        .setScriptPinControlPlaneRequestId("pin-request-1")
        .setScriptEventId("event-1")
        .setPayloadJson(payload)
        .build();
  }
}
